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


if __name__ == "__main__":
    for fn in [v for k, v in dict(globals()).items() if k.startswith("test_")]:
        fn()
        print("ok", fn.__name__)
