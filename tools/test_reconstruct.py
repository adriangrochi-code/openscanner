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


if __name__ == "__main__":
    for fn in [v for k, v in dict(globals()).items() if k.startswith("test_")]:
        fn()
        print("ok", fn.__name__)
