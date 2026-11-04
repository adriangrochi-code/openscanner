"""Pruebas rápidas de reconstruct.py que no necesitan COLMAP:  python3 tools/test_reconstruct.py"""
import sys
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
            (Path(args[args.index("--output_path") + 1]) / "0").mkdir(parents=True, exist_ok=True)

    def run_with(glomap_ok, glomap_present=True):
        calls.clear()

        def fake_run_tool(cmd, cwd=None):
            calls.append(("glomap", cmd[1]))
            if not glomap_ok:
                raise RuntimeError("glomap terminó con código 1")
            (Path(cmd[cmd.index("--output_path") + 1]) / "0").mkdir(parents=True, exist_ok=True)

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



def mat_views(tmp, marker_mm=30.0, units_per_m=7.3, n=10):
    """Photos of a mat like make_scan_mat.py's (A4, 9 markers of marker_mm) from n views with known poses, and the
    model of those views in units of 1/units_per_m m (the SfM's arbitrary scale)."""
    import cv2
    px_mm = 4.0
    W, H = int(210 * px_mm), int(297 * px_mm)
    canvas = np.full((H, W), 255, np.uint8)
    d = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)
    m_px, mid = int(round(marker_mm * px_mm)), 0
    for yi, y in enumerate((12, (297 - marker_mm) / 2, 297 - 12 - marker_mm)):
        for xi, x in enumerate((12, (210 - marker_mm) / 2, 210 - 12 - marker_mm)):
            if xi == 1 and yi == 1:
                continue  # the centre is left for the part, as on the printed mat
            x0, y0 = int(round(x * px_mm)), int(round(y * px_mm))
            canvas[y0:y0 + m_px, x0:x0 + m_px] = cv2.aruco.generateImageMarker(d, mid, m_px)
            mid += 1
    K = np.array([[900.0, 0, 640], [0, 900.0, 480], [0, 0, 1]])
    # Canvas px -> m on the plane Z = 0, centred; rows go towards -Y, so the mat is seen from above, not mirrored.
    S = np.array([[1 / (px_mm * 1000), 0, -0.105], [0, -1 / (px_mm * 1000), 0.1485], [0, 0, 1]])
    (tmp / "images").mkdir()
    imgs = {}
    for k in range(n):
        az, el = 2 * np.pi * k / n, np.radians(65)
        c = 0.42 * np.array([np.cos(el) * np.cos(az), np.cos(el) * np.sin(az), np.sin(el)])
        z = -c / np.linalg.norm(c)
        x = np.cross(z, [0, 0, 1.0]); x /= np.linalg.norm(x)
        R = np.vstack([x, np.cross(z, x), z])  # world -> camera
        t_m = -R @ c
        Hm = K @ np.column_stack([R[:, 0], R[:, 1], t_m]) @ S
        img = cv2.warpPerspective(canvas, Hm, (1280, 960), flags=cv2.INTER_AREA, borderValue=128)
        name = f"0/{k}.jpg"
        (tmp / "images" / "0").mkdir(exist_ok=True)
        cv2.imwrite(str(tmp / "images" / name), img)
        imgs[name] = dict(R=R, t=t_m * units_per_m, cam=1)
    cams = {1: ("PINHOLE", 1280, 960, np.array([900.0, 900.0, 640, 480]))}
    return cams, imgs


def test_standard_mat_scale_without_mat_json():
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        cams, imgs = mat_views(Path(d))
        s, rel = rc.scale_from_aruco(Path(d), cams, imgs, rc.STANDARD_MAT)
        assert abs(s * 7.3 - 1) < 0.01, s * 7.3  # metres per unit within 1 % (+0.34 % measured)
        assert rel < rc.STANDARD_MAT_MAX_SPREAD
        # Chosen without --mat, close to the INS scale (8 % off, as the INS can be).
        got = rc.choose_mat_scale(Path(d), cams, imgs, None, [1 / 7.3 * 1.08])
        assert got and abs(got[0] * 7.3 - 1) < 0.01 and "tapete estándar" in got[1]


def test_a_mat_with_other_markers_is_not_taken_for_the_standard_one():
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        cams, imgs = mat_views(Path(d), marker_mm=40.0)  # read as 30 mm: 25 % off the true scale
        assert rc.choose_mat_scale(Path(d), cams, imgs, None, [1 / 7.3]) is None
        # With its .json (--mat) it is right.
        mat = Path(d) / "tapete.json"
        mat.write_text('{"marker_mm": 40.0}')
        s, _ = rc.choose_mat_scale(Path(d), cams, imgs, str(mat), [1 / 7.3])
        assert abs(s * 7.3 - 1) < 0.01

if __name__ == "__main__":
    for fn in [v for k, v in dict(globals()).items() if k.startswith("test_")]:
        fn()
        print("ok", fn.__name__)
