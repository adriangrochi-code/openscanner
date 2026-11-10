"""Pruebas de postprocess.py sin COLMAP: una caja medida (nube de puntos) sobre la mesa -> STL cerrado y en mm."""
import sys
import tempfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import postprocess as pp  # noqa: E402


def box_on_table(w=0.040, d=0.030, h=0.020, step=0.0006, seed=0):
    """Puntos de las caras visibles de una caja de w × d × h m (sin la de abajo) y de la mesa alrededor."""
    pts = []
    xs, ys, zs = np.arange(0, w, step), np.arange(0, d, step), np.arange(0, h, step)
    X, Y = np.meshgrid(xs, ys); pts.append(np.c_[X.ravel(), Y.ravel(), np.full(X.size, h)])
    for y in (0, d):
        X, Z = np.meshgrid(xs, zs); pts.append(np.c_[X.ravel(), np.full(X.size, y), Z.ravel()])
    for x in (0, w):
        Y, Z = np.meshgrid(ys, zs); pts.append(np.c_[np.full(Y.size, x), Y.ravel(), Z.ravel()])
    t = np.arange(-0.03, 0.07, 0.0012); X, Y = np.meshgrid(t, t)
    out = ~((X > -0.001) & (X < w + 0.001) & (Y > -0.001) & (Y < d + 0.001))
    pts.append(np.c_[X[out], Y[out], np.zeros(out.sum())])
    P = np.vstack(pts)
    return P + np.random.default_rng(seed).normal(0, 0.00003, P.shape)


def test_a_simplified_part_stays_closed_with_its_volume():
    """Con más triángulos que max_triangles se simplifica: la pieza tiene que seguir cerrada y con volumen (en CI,
    simplificar después de cerrar la dejaba con 278 aristas abiertas y sin volumen)."""
    import open3d as o3d
    with tempfile.TemporaryDirectory() as d:
        inp = Path(d) / "denso.ply"
        o3d.io.write_point_cloud(str(inp), o3d.geometry.PointCloud(o3d.utility.Vector3dVector(box_on_table())))
        r = pp.postprocess(inp, Path(d) / "pieza", up=np.array([0, 0, 1.0]), max_triangles=8000)
        assert r["triangles"] < 12000, r["triangles"]
        assert r["watertight"], r
        assert abs(r["volume_mm3"] / 24000 - 1) < 0.05, r["volume_mm3"]   # caja de 40 × 30 × 20 mm
        dims = sorted(r["dimensions_mm_xyz"], reverse=True)
        assert all(abs(g / w - 1) < 0.15 for g, w in zip(dims, (40, 30, 20))), dims  # como en scan-e2e


def test_boundary_loops_through_a_pinched_vertex_and_a_flipped_edge():
    """Un borde que pasa dos veces por el mismo vértice, y uno con una arista en sentido cruzado: los dos se cierran."""
    # Dos triángulos que sólo se tocan en el vértice 0 (pellizco): dos bordes de 3 aristas por el mismo vértice.
    pinch = np.array([[0, 1, 2], [0, 3, 4]])
    loops = pp.boundary_loops(pinch)
    assert sorted(len(l) for l in loops) == [3, 3], loops
    # Un cuadrado de dos triángulos con uno dado vuelta: el borde tiene aristas en sentidos cruzados.
    flipped = np.array([[0, 1, 2], [0, 2, 3][::-1]])
    loops = pp.boundary_loops(flipped)
    assert sum(len(l) for l in loops) >= 4, loops


if __name__ == "__main__":
    test_boundary_loops_through_a_pinched_vertex_and_a_flipped_edge()
    test_a_simplified_part_stays_closed_with_its_volume()
    print("ok")
