"""Pruebas rápidas de reconstruct.py que no necesitan COLMAP:  python3 tools/test_reconstruct.py"""
import sys
import tempfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import reconstruct as rc  # noqa: E402


def quat_look(d):
    """Cuaternión (w,x,y,z) que lleva −Z del dispositivo a la dirección d (Z del mundo arriba)."""
    z = -d / np.linalg.norm(d)                       # eje Z del dispositivo en el mundo
    x = np.cross([0, 0, 1], z); x /= np.linalg.norm(x)
    y = np.cross(z, x)
    R = np.column_stack([x, y, z])
    # Conversión robusta (Shepperd): se parte de la mayor componente para no dividir por ~0.
    tr = np.trace(R)
    if tr > 0:
        s = np.sqrt(tr + 1) * 2
        return s / 4, (R[2, 1] - R[1, 2]) / s, (R[0, 2] - R[2, 0]) / s, (R[1, 0] - R[0, 1]) / s
    i = int(np.argmax(np.diag(R))); j, k = (i + 1) % 3, (i + 2) % 3
    s = np.sqrt(1 + R[i, i] - R[j, j] - R[k, k]) * 2
    q = [0.0, 0.0, 0.0]
    q[i] = s / 4; q[j] = (R[j, i] + R[i, j]) / s; q[k] = (R[k, i] + R[i, k]) / s
    return (R[k, j] - R[j, k]) / s, q[0], q[1], q[2]


def orbit_records(n_per_ring=60, rings=(20, 40, 60), dist=0.25):
    recs, t = [], 0
    for el in rings:
        for k in range(n_per_ring):
            az = np.radians(360 * k / n_per_ring); e = np.radians(el)
            c = dist * np.array([np.cos(e) * np.cos(az), np.cos(e) * np.sin(az), np.sin(e)])
            w, x, y, z = quat_look(-c)
            t += 300_000_000
            recs.append((f"0/{t}.jpg", {"t_ns": str(t), "px": c[0], "py": c[1], "pz": c[2], "qw": w, "qx": x, "qy": y, "qz": z}))
    return recs


def test_ins_pairs_keep_neighbours_and_skip_opposite_views():
    recs = orbit_records()
    views = rc.ins_views(recs)
    assert len(views) == len(recs)
    # La dirección de visión apunta a la pieza (el origen).
    name, d, c, _ = views[0]
    assert np.dot(d, -c / np.linalg.norm(c)) > 0.999
    pairs = rc.ins_pairs(views)
    total = len(recs) * (len(recs) - 1) // 2
    names = [r[0] for r in recs]
    idx = {n: i for i, n in enumerate(names)}
    P = {(idx[a], idx[b]) for a, b in pairs}
    # Vecinas en la misma vuelta y entre vueltas a la misma altura de azimut: sí.
    assert (0, 1) in P and (0, 60) in P
    # Vista opuesta (180°): no.
    assert (0, 30) not in P
    print(f"pares INS: {len(pairs)} de {total} ({100 * len(pairs) / total:.0f} %)")
    assert len(pairs) < 0.3 * total


def test_refined_positions_override_ins():
    recs = orbit_records(n_per_ring=8, rings=(30,))
    t = int(recs[2][1]["t_ns"])
    views = rc.ins_views(recs, {t + 1_000_000: np.array([9.0, 9.0, 9.0])})
    assert np.allclose(views[2][2], [9, 9, 9])



def _colmap_like_db(path, names):
    """Base de datos con el esquema de COLMAP 3.x (lo que crea feature_extractor)."""
    import sqlite3
    con = sqlite3.connect(str(path))
    con.executescript("""
        CREATE TABLE images (image_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL UNIQUE, camera_id INTEGER NOT NULL);
        CREATE TABLE keypoints (image_id INTEGER PRIMARY KEY NOT NULL, rows INTEGER NOT NULL, cols INTEGER NOT NULL, data BLOB);
        CREATE TABLE descriptors (image_id INTEGER PRIMARY KEY NOT NULL, rows INTEGER NOT NULL, cols INTEGER NOT NULL, data BLOB);
        CREATE TABLE matches (pair_id INTEGER PRIMARY KEY NOT NULL, rows INTEGER NOT NULL, cols INTEGER NOT NULL, data BLOB);
        CREATE TABLE two_view_geometries (pair_id INTEGER PRIMARY KEY NOT NULL, rows INTEGER NOT NULL, cols INTEGER NOT NULL,
            data BLOB, config INTEGER NOT NULL, F BLOB, E BLOB, H BLOB, qvec BLOB, tvec BLOB);
    """)
    # Ids en orden inverso al alfabético: obliga a comprobar el orden del par (id menor primero).
    for n in reversed(names):
        con.execute("INSERT INTO images (name, camera_id) VALUES (?, 1)", (n,))
    con.commit(); con.close()


def test_learned_matching_writes_verified_pairs():
    import tempfile, sqlite3, cv2
    import learned_matching as lm
    rng = np.random.default_rng(3)
    base = np.full((480, 640), 120, np.uint8)
    for _ in range(900):
        x, y = rng.integers(0, 640), rng.integers(0, 480)
        cv2.circle(base, (int(x), int(y)), int(rng.integers(2, 6)), int(rng.integers(0, 255)), -1)
    with tempfile.TemporaryDirectory() as d:
        d = Path(d); (d / "img" / "0").mkdir(parents=True)
        names = []
        for k in range(4):   # vistas giradas/desplazadas (homografías) de la misma superficie
            H = cv2.getRotationMatrix2D((320, 240), 4 * k, 1.0 + 0.03 * k); H[0, 2] += 12 * k
            cv2.imwrite(str(d / "img" / "0" / f"{k}.png"), cv2.warpAffine(base, H, (640, 480)))
            names.append(f"0/{k}.png")
        _colmap_like_db(d / "db.db", names)
        n = lm.run(d / "db.db", d / "img", None, lm.CvSift(), log=print)
        con = sqlite3.connect(str(d / "db.db"))
        ids = dict(con.execute("SELECT name, image_id FROM images"))
        rows = con.execute("SELECT pair_id, rows, config FROM two_view_geometries").fetchall()
        kp = con.execute("SELECT rows, data FROM keypoints WHERE image_id = ?", (ids["0/0.png"],)).fetchone()
        con.close()
        assert n == 6 and len(rows) == 6, rows
        for pid, cnt, cfg in rows:
            a, b = divmod(pid, lm.MAX_ID)
            assert a < b and cnt >= 50 and cfg == 3
        pts = np.frombuffer(kp[1], np.float32).reshape(-1, 2)
        assert kp[0] > 200 and pts.min() >= 0.5     # coordenadas de COLMAP (centro del píxel + 0,5)


def test_mapper_prefers_glomap_and_falls_back_to_colmap():
    import tempfile
    calls = []

    def fake_colmap(*args):
        calls.append(("colmap", args[0]))
        if args[0] == "mapper":
            m = Path(args[args.index("--output_path") + 1]) / "0"; m.mkdir(parents=True, exist_ok=True)
            (m / "images.bin").write_bytes((10).to_bytes(8, "little"))          # as a real mapper: 10 photos placed

    def run_with(glomap_ok, glomap_present=True):
        calls.clear()

        def fake_run_tool(cmd, cwd=None):
            calls.append(("glomap", cmd[1]))
            if not glomap_ok:
                raise RuntimeError("glomap terminó con código 1")
            m = Path(cmd[cmd.index("--output_path") + 1]) / "0"; m.mkdir(parents=True, exist_ok=True)
            (m / "images.bin").write_bytes((10).to_bytes(8, "little"))

        old = rc.colmap, rc.run_tool, rc.find_glomap
        rc.colmap, rc.run_tool = fake_colmap, fake_run_tool
        rc.find_glomap = (lambda: "glomap") if glomap_present else (lambda: None)
        try:
            with tempfile.TemporaryDirectory() as d:
                recs = [("0/1.jpg", {"camera_id": "0", "width": "4000", "height": "3000", "t_ns": "1"})]
                rc.run_sfm(Path(d), {"cameras": []}, recs, "exhaustive", None, features="sift", mapper="auto")
        finally:
            rc.colmap, rc.run_tool, rc.find_glomap = old
        return [c for c in calls if c[1] == "mapper"]

    assert run_with(True) == [("glomap", "mapper")]
    assert run_with(False) == [("glomap", "mapper"), ("colmap", "mapper")]
    assert run_with(True, glomap_present=False) == [("colmap", "mapper")]



def mat_views(tmp, paper="a4", print_scale=1.0, units_per_m=7.3, n=10, occlude=False):
    """Photos of make_scan_mat.py's mat for `paper`, printed at print_scale (1 = 100 %), from n views with known
    poses, and the model of those views in units of 1/units_per_m m (the SfM's arbitrary scale). occlude: the part
    over the middle, a hand over a corner and every third marker half covered (flat, on the mat)."""
    import cv2
    import make_scan_mat
    canvas, meta = make_scan_mat.make_mat(paper, dpi=300)
    if occlude:
        h, w = canvas.shape[:2]; px = meta["dpi"] / 25.4
        cv2.ellipse(canvas, (w // 2, h // 2), (int(w * 0.3), int(h * 0.25)), 0, 0, 360, 40, -1)
        cv2.rectangle(canvas, (0, 0), (int(w * 0.45), int(h * 0.35)), 90, -1)
        for corners in list(meta["markers_mm"].values())[::3]:
            (x0, y0), (x1, y1) = corners[0], corners[2]
            cv2.rectangle(canvas, (int(x0 * px), int(y0 * px)), (int((x0 + x1) / 2 * px), int(y1 * px)), 60, -1)
    pw, ph = make_scan_mat.PAPERS_MM[paper]
    m_per_px = 25.4 / meta["dpi"] / 1000 * print_scale
    # Closer to a phone photo (COLMAP works at 3200 px wide) than a small test image.
    K = np.array([[1800.0, 0, 1280], [0, 1800.0, 960], [0, 0, 1]])
    # Canvas px -> m on the plane Z = 0, centred; rows go towards -Y, so the mat is seen from above, not mirrored.
    cx, cy = pw / 2000 * print_scale, ph / 2000 * print_scale
    S = np.array([[m_per_px, 0, -cx], [0, -m_per_px, cy], [0, 0, 1]])
    dist = 0.35  # as when scanning: the distance to the part, whatever the sheet (it sees part of a big one)
    imgs = {}
    (tmp / "images" / "0").mkdir(parents=True)
    for k in range(n):
        az, el = 2 * np.pi * k / n, np.radians(65)
        c = dist * np.array([np.cos(el) * np.cos(az), np.cos(el) * np.sin(az), np.sin(el)])
        z = -c / np.linalg.norm(c)
        x = np.cross(z, [0, 0, 1.0]); x /= np.linalg.norm(x)
        R = np.vstack([x, np.cross(z, x), z])  # world -> camera
        t_m = -R @ c
        Hm = K @ np.column_stack([R[:, 0], R[:, 1], t_m]) @ S
        img = cv2.warpPerspective(canvas, Hm, (2560, 1920), flags=cv2.INTER_AREA, borderValue=128)
        name = f"0/{k}.jpg"
        cv2.imwrite(str(tmp / "images" / name), img)
        imgs[name] = dict(R=R, t=t_m * units_per_m, cam=1)
    cams = {1: ("PINHOLE", 2560, 1920, np.array([1800.0, 1800.0, 1280, 960]))}
    return cams, imgs


def test_standard_mat_scale_without_mat_json_on_every_paper():
    import tempfile
    for paper in ("a6", "a4", "tabloid"):  # the same chessboard on the smallest, a common and the largest sheet
        with tempfile.TemporaryDirectory() as d:
            cams, imgs = mat_views(Path(d), paper)
            s, rel = rc.scale_from_aruco(Path(d), cams, imgs, rc.STANDARD_MAT)
            assert abs(s * 7.3 - 1) < 0.002, (paper, s * 7.3)  # within 0.2 % (+0.03 to +0.06 % measured)
            assert rel < rc.STANDARD_MAT_MAX_SPREAD
            # Chosen without --mat, close to the INS scale (8 % off, as the INS can be).
            got = rc.choose_mat_scale(Path(d), cams, imgs, None, [1 / 7.3 * 1.08])
            assert got and abs(got[0] * 7.3 - 1) < 0.01 and "tapete estándar" in got[1]
            # One bad other scale (the INS 30 % off after jerky moves) does not throw the mat away while the focus
            # scale agrees: it is checked against the median of the others, not against each one.
            got = rc.choose_mat_scale(Path(d), cams, imgs, None, [1 / 7.3 * 1.30, 1 / 7.3 * 0.98, 1 / 7.3 * 1.03])
            assert got and abs(got[0] * 7.3 - 1) < 0.01, got


def test_a_mat_partly_hidden_still_gives_the_scale():
    """As when scanning: the part covers the middle of the mat, a hand or a cable a corner, and some markers are half
    covered (a half marker must not be read with a wrong corner). Measured: within 0.05 % (2026-11-10)."""
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        cams, imgs = mat_views(Path(d), "a4", occlude=True)
        s, rel = rc.scale_from_aruco(Path(d), cams, imgs, rc.STANDARD_MAT)
        assert abs(s * 7.3 - 1) < 0.002, s * 7.3
        assert rel < rc.STANDARD_MAT_MAX_SPREAD, rel


def test_a_mat_not_printed_at_100_percent_is_not_trusted():
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        # Printed at 133 %: its 15 mm markers measure 20 mm, the scale would be 25 % off.
        cams, imgs = mat_views(Path(d), "a4", print_scale=4 / 3)
        assert rc.choose_mat_scale(Path(d), cams, imgs, None, [1 / 7.3]) is None
        # Not even when one bad other scale happens to be near it: the median of the others is not.
        assert rc.choose_mat_scale(Path(d), cams, imgs, None, [1 / 7.3, 1 / 7.3 * 0.75, 1 / 7.3 * 1.02]) is None
        # With its .json (--mat), saying 20 mm, it is right.
        mat = Path(d) / "tapete.json"
        mat.write_text('{"dictionary": "DICT_4X4_250", "marker_mm": 20.0}')
        s, _ = rc.choose_mat_scale(Path(d), cams, imgs, str(mat), [1 / 7.3])
        assert abs(s * 7.3 - 1) < 0.01


def test_every_paper_size_has_the_same_chessboard():
    import make_scan_mat as msm
    for paper, (pw, ph) in msm.PAPERS_MM.items():
        canvas, meta = msm.make_mat(paper, dpi=50)
        assert canvas.shape == (round(ph * 50 / 25.4), round(pw * 50 / 25.4))
        # The same chessboard on every sheet: same squares and markers, more of them on a bigger sheet.
        assert (meta["marker_mm"], meta["dictionary"]) == (rc.STANDARD_MAT["marker_mm"], rc.STANDARD_MAT["dictionary"])
        assert meta["square_mm"] == 20.0 and len(meta["markers_mm"]) >= 12, paper

def test_everything_on_the_mat_prints_inside_a_printers_margins():
    """Nothing within 5 mm of an edge, where many printers cannot print (lasers about 4-5 mm, inkjets at the bottom 5 mm
    and more). The 100 mm check bar was 3 mm from the bottom: clipped or gone, and it is what the tester measures.
    The bar also has to measure what it says, inside its strip under the board."""
    import make_scan_mat as msm
    dpi = 200
    for paper, (pw, ph) in msm.PAPERS_MM.items():
        canvas, meta = msm.make_mat(paper, dpi=dpi)
        g = canvas if canvas.ndim == 2 else canvas.min(axis=2)
        ink = np.argwhere(g < 128)
        mm = ink[:, ::-1] * 25.4 / dpi              # (x, y) in mm
        assert mm[:, 0].min() >= 5 and mm[:, 0].max() <= pw - 5, (paper, mm[:, 0].min(), mm[:, 0].max())
        assert mm[:, 1].min() >= 5 and mm[:, 1].max() <= ph - 5, (paper, mm[:, 1].min(), mm[:, 1].max())
        # The bar: the longest dark run on a row under the board.
        board_bottom = max(c[1] for m in meta["markers_mm"].values() for c in m)
        rows = [r for r in range(g.shape[0]) if r * 25.4 / dpi > board_bottom + 3]
        longest = max(max((len(run) for run in np.split(np.arange(g.shape[1]), np.where(g[r] >= 128)[0]) if len(run)),
                          default=0) for r in rows)
        assert abs(longest * 25.4 / dpi - meta["check_bar_mm"]) < 1, (paper, longest * 25.4 / dpi)


def test_load_session_finds_the_scan_folder_and_refuses_a_zip_bomb():
    """La carpeta del escaneo es la de session.json, aunque el zip traiga __MACOSX (Finder) o no tenga carpeta; un
    zip que se descomprime a mucho más de lo que ocupa (bomba zip) se rechaza antes de descomprimir."""
    import zipfile
    with tempfile.TemporaryDirectory() as d:
        d = Path(d)
        cases = {"mac.zip": ["__MACOSX/scan_1/._session.json", "__MACOSX/scan_1/session.json",
                             "scan_1/session.json", "scan_1/frames.csv"],
                 "plano.zip": ["session.json", "frames.csv"]}
        for name, entries in cases.items():
            with zipfile.ZipFile(d / name, "w") as z:
                for e in entries:
                    z.writestr(e, "{}")
            scan = rc.load_session(d / name, d / ("w_" + name))
            assert (scan / "session.json").exists() and (scan / "frames.csv").exists(), (name, scan)
            assert "__MACOSX" not in scan.parts
        with zipfile.ZipFile(d / "nada.zip", "w") as z:
            z.writestr("otra/cosa.txt", "x")
        try:
            rc.load_session(d / "nada.zip", d / "w_nada")
            assert False, "sin session.json"
        except SystemExit as e:
            assert "session.json" in str(e)
        with zipfile.ZipFile(d / "bomba.zip", "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("scan/session.json", "{}")
            z.writestr("scan/ceros.bin", bytes(200 * 1024 ** 2))   # 200 MB de ceros: unos 200 KB comprimidos
        try:
            rc.load_session(d / "bomba.zip", d / "w_bomba")
            assert False, "bomba aceptada"
        except SystemExit as e:
            assert "no es un escaneo" in str(e)
        assert not (d / "w_bomba" / "raw").exists()


def test_ins_scale_uses_only_frames_inside_the_trajectory():
    """Frames before or after the INS trajectory were placed at its first or last point (np.interp clamps), and the
    scale came out too small. Only frames inside the trajectory's time span are used."""
    import tempfile
    true = 0.137                                   # metres per SfM unit
    n = 40
    imgs, records, rows = {}, [], []
    for k in range(n):
        a = 2 * np.pi * k / n
        c_m = np.array([0.3 * np.cos(a), 0.3 * np.sin(a), 0.2])          # camera centre in metres (orbit)
        R = np.eye(3)
        imgs[f"0/{k}.jpg"] = dict(R=R, t=-(c_m / true), cam=1)           # SfM centre = c_m / true
        records.append((f"0/{k}.jpg", {"t_ns": str(k * 10**9), "px": "1", "py": "0", "pz": "0"}))
        if k < 0.6 * n:                                                   # the trajectory ends early
            rows.append(f"{k * 10**9},{c_m[0]},{c_m[1]},{c_m[2]},0,0,0,0")
    with tempfile.TemporaryDirectory() as d:
        (Path(d) / "ins_trajectory.csv").write_text("t_ns,px,py,pz,vx,vy,vz,stationary\n" + "\n".join(rows) + "\n")
        s = rc.scale_from_ins(imgs, records, Path(d))
    assert s is not None and abs(s / true - 1) < 0.01, (s, true)


def test_the_largest_colmap_model_is_the_one_with_most_photos():
    """COLMAP can split a scan into several models (sparse/0, 1, …). The largest was chosen by the number of FILES in
    each folder, the same for all (cameras, images, points3D): a sub-model of 8 photos could be the part. It is the one
    with most registered photos; none at all is a clear error."""
    import struct
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        sparse = Path(d) / "sparse"
        for name, n in (("0", 8), ("1", 31), ("2", 3)):
            m = sparse / name; m.mkdir(parents=True)
            (m / "images.bin").write_bytes(struct.pack("<Q", n) + bytes(16))
            (m / "cameras.bin").write_bytes(bytes(8)); (m / "points3D.bin").write_bytes(bytes(8))
        t = sparse / "3"; t.mkdir()                                   # a text model
        (t / "images.txt").write_text("# comment\n" + "".join(f"{i} 1 0 0 0 0 0 0 1 img{i}.jpg\n\n" for i in range(12)))
        assert rc.largest_model(sparse).name == "1"
        assert rc.registered_images(t) == 12
        empty = Path(d) / "vacío"; empty.mkdir()
        try:
            rc.largest_model(empty)
            assert False, "no model, no error"
        except SystemExit as e:
            assert "fotos" in str(e)


if __name__ == "__main__":
    for fn in [v for k, v in dict(globals()).items() if k.startswith("test_")]:
        fn()
        print("ok", fn.__name__)


def test_scaling_the_model_with_a_photo_without_points():
    """COLMAP writes an EMPTY points line for a photo without points. The scaled copy of the model took every other
    non-empty line as a pose: after that photo the poses stayed unscaled (and points lines were «scaled»), so the
    metric model the dense cloud starts from was broken."""
    with tempfile.TemporaryDirectory() as d:
        src, dst = Path(d) / "src", Path(d) / "dst"
        src.mkdir()
        (src / "cameras.txt").write_text("1 PINHOLE 640 480 500 500 320 240\n")
        (src / "images.txt").write_text(
            "# Image list\n"
            "1 1 0 0 0 0.1 0.2 0.3 1 a.jpg\n10 20 7 30 40 8\n"
            "2 1 0 0 0 1.0 2.0 3.0 1 b.jpg\n\n"
            "3 1 0 0 0 4.0 5.0 6.0 1 c.jpg\n50 60 7\n")
        (src / "points3D.txt").write_text("7 1.0 1.0 1.0 0 0 0 0\n8 2.0 2.0 2.0 0 0 0 0\n")
        rc.scale_model_txt(src, dst, 2.0)
        _, imgs = rc.read_model_txt(dst)
        assert sorted(imgs) == ["a.jpg", "b.jpg", "c.jpg"]
        assert np.allclose(imgs["a.jpg"]["t"], [0.2, 0.4, 0.6])
        assert np.allclose(imgs["b.jpg"]["t"], [2.0, 4.0, 6.0])
        assert np.allclose(imgs["c.jpg"]["t"], [8.0, 10.0, 12.0]), "the photo after one without points"
        assert np.allclose(imgs["c.jpg"]["obs"], [[50, 60, 7]]), "its points untouched"
        assert np.allclose(rc.read_points3d(dst)[8], [4.0, 4.0, 4.0])


def test_an_uncalibrated_focus_is_not_a_scale():
    """Android: with focus_distance_calibration «uncalibrated» the focus distances have no physical units. Their depth
    maps were still taken as a metric scale, above the INS: the part could come out of any size with nothing said."""
    import cv2
    with tempfile.TemporaryDirectory() as d:
        work = Path(d)
        for cam in ("0", "2"):
            (work / "images" / cam).mkdir(parents=True)
            (work / "depth" / cam).mkdir(parents=True)
            cv2.imwrite(str(work / "images" / cam / "1.jpg"), np.full((100, 100), 128, np.uint8))
            cv2.imwrite(str(work / "depth" / cam / "1_mm.png"), np.full((100, 100), 500, np.uint16))
        pts = {i: np.array([0.0, 0.0, 0.25]) for i in range(30)}
        obs = np.array([[50.0, 50.0, i] for i in range(30)])
        imgs = {f"{cam}/1.jpg": dict(R=np.eye(3), t=np.zeros(3), cam=1, obs=obs) for cam in ("0", "2")}
        session = {"cameras": [{"id": "0", "focus_distance_calibration": "uncalibrated"},
                               {"id": "2", "focus_distance_calibration": "approximate"}]}
        usable = rc.metric_focus_cameras(session)
        assert usable == {"2"}
        s, _, n = rc.scale_from_focus(work, imgs, pts, usable)
        assert n == 30 and abs(s - 2.0) < 1e-9, "only camera 2's points"
        assert rc.scale_from_focus(work, {"0/1.jpg": imgs["0/1.jpg"]}, pts, usable)[0] is None
