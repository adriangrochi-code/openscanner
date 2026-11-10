"""Pruebas de postprocess.py sin COLMAP: una caja medida (nube de puntos) sobre la mesa -> STL cerrado y en mm."""
import sys
import tempfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import postprocess as pp  # noqa: E402


def box_on_table(w=0.040, d=0.030, h=0.020, step=0.0006, seed=0, gap=None):
    """Puntos de las caras visibles de una caja de w × d × h m (sin la de abajo) y de la mesa alrededor. Con
    gap(t), a lo largo de cada pared (t de 0 a 1) falta la parte de abajo hasta esa altura (m), como cerca del
    tapete en la prueba completa de CI."""
    pts = []
    xs, ys, zs = np.arange(0, w, step), np.arange(0, d, step), np.arange(0, h, step)
    X, Y = np.meshgrid(xs, ys); pts.append(np.c_[X.ravel(), Y.ravel(), np.full(X.size, h)])
    for y in (0, d):
        X, Z = np.meshgrid(xs, zs); pts.append(np.c_[X.ravel(), np.full(X.size, y), Z.ravel()])
    for x in (0, w):
        Y, Z = np.meshgrid(ys, zs); pts.append(np.c_[np.full(Y.size, x), Y.ravel(), Z.ravel()])
    if gap is not None:
        walls = np.vstack(pts[1:])
        t = np.where(np.isin(walls[:, 1], (0, d)), walls[:, 0] / w, walls[:, 1] / d)
        pts = [pts[0], walls[walls[:, 2] >= gap(t)]]
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


def test_walls_missing_near_the_mat_go_down_to_the_floor():
    """Si cerca del tapete faltan varios mm de pared (en CI el borde de la base subía hasta 13 mm), la base se
    baja en vertical hasta Z = 0: la pieza queda cerrada, con su volumen y sin angostarse abajo."""
    import open3d as o3d
    gap = lambda t: 0.002 + 0.011 * (0.5 + 0.5 * np.sin(2 * np.pi * 3 * t))   # entre 2 y 13 mm, ondulado
    with tempfile.TemporaryDirectory() as d:
        inp = Path(d) / "denso.ply"
        o3d.io.write_point_cloud(str(inp), o3d.geometry.PointCloud(o3d.utility.Vector3dVector(box_on_table(gap=gap))))
        r = pp.postprocess(inp, Path(d) / "pieza", up=np.array([0, 0, 1.0]), max_triangles=8000)
        assert r["watertight"], r
        assert abs(r["volume_mm3"] / 24000 - 1) < 0.07, r["volume_mm3"]
        V = np.asarray(o3d.io.read_triangle_mesh(str(Path(d) / "pieza.ply")).vertices)
        low = V[V[:, 2] < 0.5]
        assert np.ptp(low[:, 0]) > 38 and np.ptp(low[:, 1]) > 28, (np.ptp(low[:, 0]), np.ptp(low[:, 1]))


def flared_tube(bottom=20.0, top=24.0, z0=1.0, z1=20.0, k=4):
    """Paredes de un tronco de pirámide cuadrado, abierto arriba y abajo (mm): la base, de `bottom` de lado a z0
    (sin llegar al suelo), y la boca, más ancha, de `top` a z1. Caras hacia fuera."""
    def ring(side, z):
        h = side / 2; c = [(-h, -h), (h, -h), (h, h), (-h, h)]
        return [(c[i][0] + (c[(i + 1) % 4][0] - c[i][0]) * j / k, c[i][1] + (c[(i + 1) % 4][1] - c[i][1]) * j / k, z)
                for i in range(4) for j in range(k)]
    V = np.array(ring(bottom, z0) + ring(top, z1)); n = 4 * k
    T = [t for i in range(n) for t in ([i, (i + 1) % n, n + (i + 1) % n], [i, n + (i + 1) % n, n + i])]
    return V, np.array(T)


def test_a_wide_open_top_is_not_taken_as_the_base():
    """La base es el borde de abajo aunque haya uno más ancho arriba (una boca abierta, más ancha que la base):
    sólo ése baja al suelo. Antes se tomaba el más ancho y la boca bajaba hasta Z = 0, con un volumen sin sentido."""
    import open3d as o3d
    V, T = flared_tube()
    mesh = o3d.geometry.TriangleMesh(o3d.utility.Vector3dVector(V), o3d.utility.Vector3iVector(T))
    assert pp.cap_holes(mesh, max_perimeter=4 * 24, flatten_lowest=True) == 2
    closed, vol = pp.closed_volume(mesh)
    expected = 20 * 20 * 1 + 19 / 3 * (400 + 576 + 480)   # el pie hasta el suelo + el tronco
    assert closed and abs(vol / expected - 1) < 0.01, (closed, vol, expected)
    assert np.asarray(mesh.vertices)[:, 2].min() == 0


def test_simple_loops_split_a_pinched_border():
    assert pp.simple_loops([1, 2, 3, 0, 4, 5, 0, 6]) == [[0, 4, 5], [1, 2, 3, 0, 6]]
    assert pp.simple_loops([0, 1, 2, 0, 3, 4]) == [[0, 1, 2], [0, 3, 4]]


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
    test_walls_missing_near_the_mat_go_down_to_the_floor()
    test_simple_loops_split_a_pinched_border()
    test_a_wide_open_top_is_not_taken_as_the_base()
    print("ok")
