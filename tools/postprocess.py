#!/usr/bin/env python3
"""
Postprocesado de Tisma Scanner: nube densa -> pieza limpia, cerrada y en milímetros.

Pasos (cada uno se puede ajustar o desactivar):
  1. Limpieza de puntos sueltos (estadística + por radio): restos de emparejamientos fallidos.
  2. Quita la mesa/tapete: detecta el plano de apoyo (RANSAC) y elimina el plano y lo de debajo.
  3. Se queda con la pieza: agrupa (DBSCAN) y conserva el grupo mayor (fuera restos de fondo).
  4. Base plana: el contorno inferior (lo que la cámara no ve, sobre la mesa) se lleva a Z = 0 y
     se tapa plano, en vez de una "burbuja" inventada por la reconstrucción.
  5. Malla cerrada (Poisson con pantalla) y recorte de zonas con poca densidad (inventadas).
  6. Reparación, suavizado Taubin suave (conserva el volumen) y simplificación.
  7. Exporta en mm, con la pieza apoyada en Z = 0 y centrada: STL para imprimir + PLY.
  8. Informe: medidas, volumen, ruido de la nube y desviación malla↔puntos medidos.

Uso:
    python3 postprocess.py nube_densa.ply --out pieza --units m
    (--units m si el modelo está en metros: con escala ArUco/enfoque/INS de reconstruct.py)
Requisitos: pip install open3d numpy  (en Linux sin pantalla: apt install libegl1 libgl1)
"""
import argparse
import json
from pathlib import Path

import numpy as np

try:
    import open3d as o3d
except ImportError:  # pragma: no cover
    raise SystemExit("Falta Open3D: pip install open3d (y en Linux sin pantalla: apt install libegl1 libgl1)")

UNITS_TO_MM = {"m": 1000.0, "mm": 1.0, "cm": 10.0, "unit": 1.0}


def log(msg):
    print(f"[post] {msg}")


def spacing_of(pcd):
    d = np.asarray(pcd.compute_nearest_neighbor_distance())
    return float(np.median(d)) if len(d) else 1.0


def support_plane(pcd, thr, up=None, max_planes=4):
    """
    Plano de apoyo (mesa/tapete). Se buscan varios planos y se elige con cuidado, porque una
    pieza plana tiene caras grandes que también son planos:
      * con gravedad (`up`, de la INS): el plano MÁS BAJO casi perpendicular a la gravedad;
      * sin ella: el de mayor extensión (la mesa sobresale de la pieza; una cara suya, no).
    Devuelve (normal hacia arriba, d, índices) o None.
    """
    P_all = np.asarray(pcd.points)
    rest = pcd
    rest_idx = np.arange(len(P_all))
    cands = []
    for _ in range(max_planes):
        if len(rest.points) < 200:
            break
        model, inl = rest.segment_plane(distance_threshold=thr, ransac_n=3, num_iterations=3000)
        if len(inl) < 0.03 * len(P_all):
            break
        n = np.array(model[:3]); d = model[3]
        k = np.linalg.norm(n); n, d = n / k, d / k
        idx = rest_idx[inl]
        Q = P_all[idx]
        # Extensión: área del rectángulo que cubre los puntos del plano (en su propio plano).
        u = np.cross(n, [1, 0, 0] if abs(n[0]) < 0.9 else [0, 1, 0]); u /= np.linalg.norm(u)
        v = np.cross(n, u)
        area = np.ptp(Q @ u) * np.ptp(Q @ v)
        cands.append((n, d, idx, area))
        keep = np.setdiff1d(np.arange(len(rest.points)), inl)
        rest = rest.select_by_index(keep); rest_idx = rest_idx[keep]
    if not cands:
        return None
    if up is not None:
        up = np.asarray(up, float) / np.linalg.norm(up)
        horiz = [c for c in cands if abs(c[0] @ up) > 0.9]
        if horiz:
            # Altura de cada plano a lo largo de la gravedad: el más bajo es la mesa.
            best = min(horiz, key=lambda c: np.median(P_all[c[2]] @ up))
            n, d, idx, _ = best
            if n @ up < 0:
                n, d = -n, -d
            return n, d, idx
    n, d, idx, _ = max(cands, key=lambda c: c[3])
    side = P_all @ n + d
    if np.sum(side > thr) < np.sum(side < -thr):   # "arriba" = lado donde está la pieza
        n, d = -n, -d
    return n, d, idx


def frame_from_plane(n, origin):
    """Rotación que lleva la normal del plano a +Z (pieza "de pie" sobre Z = 0)."""
    z = n / np.linalg.norm(n)
    x = np.cross([0, 1, 0], z) if abs(z[1]) < 0.9 else np.cross([1, 0, 0], z)
    x /= np.linalg.norm(x)
    y = np.cross(z, x)
    R = np.stack([x, y, z])          # filas: ejes nuevos en coordenadas antiguas
    T = np.eye(4); T[:3, :3] = R; T[:3, 3] = -R @ origin
    return T


def orient_normals(pcd, spacing, cameras=None):
    """
    Normales hacia fuera. Las nubes de COLMAP/OpenMVS ya las traen orientadas hacia las cámaras
    y se respetan. Si no hay, se estiman y se orienta CADA normal por separado: hacia la cámara
    más cercana (si se conocen sus posiciones) o, si no, en sentido contrario al centro de la
    pieza. (La orientación "consistente" global falla en aristas vivas de 90° y Poisson genera
    entonces burbujas y autointersecciones.)
    """
    if pcd.has_normals():
        return
    pcd.estimate_normals(o3d.geometry.KDTreeSearchParamHybrid(radius=spacing * 4, max_nn=30))
    P = np.asarray(pcd.points); N = np.asarray(pcd.normals)
    if cameras is not None and len(cameras):
        C = np.asarray(cameras)
        tree = o3d.geometry.KDTreeFlann(o3d.geometry.PointCloud(o3d.utility.Vector3dVector(C)))
        ref = np.array([C[tree.search_knn_vector_3d(p, 1)[1][0]] for p in P])
        flip = np.sum((ref - P) * N, axis=1) < 0
    else:
        flip = np.sum((P - P.mean(0)) * N, axis=1) < 0
    N[flip] *= -1
    pcd.normals = o3d.utility.Vector3dVector(N)


def boundary_loops(tris):
    """Bucles de aristas de borde (aristas que pertenecen a un solo triángulo), como listas de vértices."""
    E = np.vstack([tris[:, [0, 1]], tris[:, [1, 2]], tris[:, [2, 0]]])
    key = np.sort(E, axis=1)
    _, inv, cnt = np.unique(key, axis=0, return_inverse=True, return_counts=True)
    border = E[cnt[inv.ravel()] == 1]          # con su orientación original
    nxt = {}
    for a, b in border:
        nxt.setdefault(a, []).append(b)
    loops, used = [], set()
    for start in list(nxt):
        if start in used:
            continue
        loop, v = [], start
        while v in nxt and nxt[v] and v not in used:
            used.add(v); loop.append(v)
            v = nxt[v].pop()
        if len(loop) >= 3:
            loops.append(loop)
    return loops


def cap_holes(mesh, max_perimeter, flatten_lowest=False):
    """
    Cierra cada agujero con un abanico desde su centro (la orientación sigue al borde).
    Robusto para la base y agujeros pequeños; los muy grandes (> max_perimeter) se dejan,
    porque cerrarlos sería inventar superficie.
    """
    V = np.asarray(mesh.vertices).copy(); T = np.asarray(mesh.triangles).copy()
    new_v, new_t, closed = [], [], 0
    loops = boundary_loops(T)
    if flatten_lowest and loops:
        # El borde más bajo y largo es el contorno de la base (lo que no vio la cámara, sobre la
        # mesa): se baja a Z = 0 para que la pieza apoye y se tapa plano.
        base = min(loops, key=lambda l: V[l, 2].mean() - 1e-3 * len(l))
        V[base, 2] = 0.0
    for loop in loops:
        P = V[loop]
        if np.sum(np.linalg.norm(P - np.roll(P, -1, 0), axis=1)) > max_perimeter:
            continue
        c = len(V) + len(new_v)
        new_v.append(P.mean(0))
        for a, b in zip(loop, loop[1:] + loop[:1]):
            new_t.append([b, a, c])             # sentido inverso al del borde: cara hacia fuera
        closed += 1
    if new_v:
        mesh.vertices = o3d.utility.Vector3dVector(np.vstack([V, new_v]))
        mesh.triangles = o3d.utility.Vector3iVector(np.vstack([T, new_t]))
    return closed


def closed_volume(mesh):
    """(cerrada, volumen mm³): cerrada = sin aristas de borde y cada arista en 2 caras."""
    T = np.asarray(mesh.triangles); V = np.asarray(mesh.vertices)
    key = np.sort(np.vstack([T[:, [0, 1]], T[:, [1, 2]], T[:, [2, 0]]]), axis=1)
    _, cnt = np.unique(key, axis=0, return_counts=True)
    closed = bool(np.all(cnt == 2))
    vol = float(abs(np.sum(np.einsum("ij,ij->i", V[T[:, 0]], np.cross(V[T[:, 1]], V[T[:, 2]]))) / 6)) if closed else None
    return closed, vol


def mesh_quality(mesh, cloud):
    """Distancia de los puntos medidos a la malla final (mm): cuánto se ha 'inventado' o suavizado."""
    scene = o3d.t.geometry.RaycastingScene()
    scene.add_triangles(o3d.t.geometry.TriangleMesh.from_legacy(mesh))
    q = o3d.core.Tensor(np.asarray(cloud.points), dtype=o3d.core.Dtype.Float32)
    d = scene.compute_distance(q).numpy()
    return float(np.mean(d)), float(np.percentile(d, 95))


def cloud_noise(pcd, spacing):
    """Ruido de la nube: residuo de ajuste de plano local (mm), en una muestra de puntos."""
    tree = o3d.geometry.KDTreeFlann(pcd)
    P = np.asarray(pcd.points)
    res = []
    for i in np.random.default_rng(0).choice(len(P), min(2000, len(P)), replace=False):
        _, idx, _ = tree.search_knn_vector_3d(P[i], 20)
        Q = P[idx] - P[idx].mean(0)
        res.append(np.linalg.svd(Q, compute_uv=False)[-1] / np.sqrt(len(idx)))
    return float(np.median(res))


def postprocess(inp: Path, out: Path, units="m", voxel=0.0, flat_base=True, depth=0,
                trim=0.02, smooth=5, max_triangles=500_000, keep_plane=False, up=None, cameras=None):
    """
    up: dirección "arriba" (contra la gravedad) en coordenadas de la nube, p.ej. de la INS.
    cameras: centros de cámara (en unidades de la nube) para orientar normales si faltan.
    """
    out.parent.mkdir(parents=True, exist_ok=True)
    o3d.utility.random.seed(0)          # RANSAC/Poisson reproducibles
    pcd = o3d.io.read_point_cloud(str(inp))
    if len(pcd.points) == 0:
        raise SystemExit(f"Nube vacía: {inp}")
    pcd.scale(UNITS_TO_MM[units], center=(0, 0, 0))
    report = {"input_points": len(pcd.points), "units_in": units}
    log(f"{len(pcd.points)} puntos de entrada")

    sp = spacing_of(pcd)
    if voxel > 0 or sp < 0.02:
        pcd = pcd.voxel_down_sample(voxel or 0.05)
        sp = spacing_of(pcd)
    report["point_spacing_mm"] = sp

    if cameras is not None:
        cameras = np.asarray(cameras) * UNITS_TO_MM[units]

    # 1. Plano de apoyo ANTES de limpiar: la mesa suele tener menos densidad que la pieza y la
    #    limpieza estadística la "adelgaza" hasta que una cara de la pieza parece el plano mayor.
    thr = max(3 * sp, 0.15)
    plane = support_plane(pcd, thr, up=up)
    if plane is not None:
        n, d, inl = plane
        origin = -d * n
        T0 = frame_from_plane(n, origin)
        pcd.transform(T0)
        if cameras is not None:
            cameras = (T0[:3, :3] @ cameras.T).T + T0[:3, 3]
        if not keep_plane:
            P = np.asarray(pcd.points)
            keep = np.where(P[:, 2] > 2 * thr)[0]       # fuera el plano y lo que está debajo
            pcd = pcd.select_by_index(keep)
        log(f"plano de apoyo detectado y quitado ({len(inl)} puntos" + (", elegido con la gravedad de la INS)" if up is not None else ")"))
    else:
        log("no se detectó plano de apoyo (¿pieza sin mesa en el encuadre?)")
    report["support_plane"] = plane is not None
    report["gravity_from_ins"] = up is not None

    # 2. Limpieza de puntos sueltos
    pcd, _ = pcd.remove_statistical_outlier(nb_neighbors=20, std_ratio=2.0)
    pcd, _ = pcd.remove_radius_outlier(nb_points=6, radius=sp * 4)
    log(f"tras limpieza: {len(pcd.points)} puntos (separación {sp:.3f} mm)")

    # 3. La pieza: el grupo mayor
    labels = np.array(pcd.cluster_dbscan(eps=sp * 6, min_points=10))
    if labels.max() >= 0:
        big = np.bincount(labels[labels >= 0]).argmax()
        pcd = pcd.select_by_index(np.where(labels == big)[0])
    log(f"pieza: {len(pcd.points)} puntos")
    if len(pcd.points) < 200:
        raise SystemExit("Muy pocos puntos en la pieza tras limpiar: revisa la nube densa.")
    # (La mesa ya está en Z = 0: no se desplaza la pieza, o perdería la franja quitada con el plano.)
    report["noise_mm"] = cloud_noise(pcd, sp)

    # 4-5. Normales, base plana y Poisson
    orient_normals(pcd, sp, cameras)
    measured = o3d.geometry.PointCloud(pcd)
    extent = np.ptp(np.asarray(pcd.points), axis=0).max()
    depth = depth or int(np.clip(np.ceil(np.log2(extent / sp)) + 1, 7, 11))
    mesh, dens = o3d.geometry.TriangleMesh.create_from_point_cloud_poisson(pcd, depth=depth, linear_fit=True)
    dens = np.asarray(dens)
    closed_base = plane is not None and flat_base
    # Fuera lo que Poisson "inventa" lejos de los datos (baja densidad: burbujas).
    mesh.remove_vertices_by_mask(dens < np.quantile(dens, trim))
    if not closed_base:
        bbox = pcd.get_axis_aligned_bounding_box()
        mesh = mesh.crop(o3d.geometry.AxisAlignedBoundingBox(bbox.min_bound - sp, bbox.max_bound + sp))
    log(f"Poisson profundidad {depth}: {len(mesh.triangles)} triángulos")

    # Seguridad: nada de la malla puede estar lejos de los puntos medidos (restos de Poisson).
    pb = measured.get_axis_aligned_bounding_box()
    lo = pb.min_bound - 3 * sp
    if closed_base:
        lo[2] = -sp
    mesh = mesh.crop(o3d.geometry.AxisAlignedBoundingBox(lo, pb.max_bound + 3 * sp))

    # 6. Reparación, suavizado, simplificación
    mesh.remove_degenerate_triangles(); mesh.remove_duplicated_triangles()
    mesh.remove_duplicated_vertices(); mesh.remove_unreferenced_vertices()
    if not mesh.is_edge_manifold():
        mesh.remove_non_manifold_edges()
    tri_clusters, counts, _ = mesh.cluster_connected_triangles()
    tri_clusters = np.asarray(tri_clusters); counts = np.asarray(counts)
    if len(counts):
        mesh.remove_triangles_by_mask(tri_clusters != counts.argmax()); mesh.remove_unreferenced_vertices()
    # Cerrar los agujeros que deja el recorte (la base y huecos pequeños) para que sea imprimible.
    # (El fill_holes de Open3D creaba vértices a metros de la pieza con bordes no limpios.)
    if closed_base:
        n_capped = cap_holes(mesh, max_perimeter=4 * extent, flatten_lowest=True)
        log(f"base plana en Z = 0 y agujeros cerrados: {n_capped}")
    if smooth > 0:
        mesh = mesh.filter_smooth_taubin(number_of_iterations=smooth)
    if len(mesh.triangles) > max_triangles:
        mesh = mesh.simplify_quadric_decimation(max_triangles)
    mesh.compute_vertex_normals(); mesh.compute_triangle_normals()

    # Alinear la pieza con sus ejes en el plano (rectángulo de área mínima de la huella), lado
    # largo en X, y centrarla en XY sobre Z = 0: así las medidas son las de la pieza.
    import cv2
    V = np.asarray(mesh.vertices)
    (cx, cy), (w, h), ang = cv2.minAreaRect(V[:, :2].astype(np.float32))
    if w < h:
        ang += 90
    Rz = o3d.geometry.get_rotation_matrix_from_xyz((0, 0, -np.radians(ang)))
    T = np.eye(4); T[:3, :3] = Rz; T[:3, 3] = -Rz @ np.array([cx, cy, 0.0])
    if plane is None:
        T[2, 3] -= (Rz @ V.T)[2].min()
    mesh.transform(T); measured.transform(T)

    # 7-8. Exportar e informar
    o3d.io.write_triangle_mesh(str(out.with_suffix(".stl")), mesh)
    o3d.io.write_triangle_mesh(str(out.with_suffix(".ply")), mesh)
    o3d.io.write_point_cloud(str(out.with_name(out.name + "_nube.ply")), measured)
    dims = mesh.get_axis_aligned_bounding_box().get_extent()
    closed, vol = closed_volume(mesh)
    obb = mesh.get_oriented_bounding_box()
    fit_mean, fit_p95 = mesh_quality(mesh, measured)
    report.update({
        "triangles": len(mesh.triangles),
        "watertight": closed,
        "dimensions_mm_xyz": [round(float(v), 3) for v in dims],
        "oriented_box_mm": [round(float(v), 3) for v in sorted(obb.extent, reverse=True)],
        "area_mm2": round(float(mesh.get_surface_area()), 2),
        "volume_mm3": round(vol, 2) if vol else None,
        "mesh_vs_points_mean_mm": round(fit_mean, 4),
        "mesh_vs_points_p95_mm": round(fit_p95, 4),
        "flat_base": bool(flat_base and plane is not None),
        "poisson_depth": depth,
    })
    out.with_name(out.name + "_informe.json").write_text(json.dumps(report, indent=2, ensure_ascii=False))
    log(f"medidas {dims[0]:.2f} × {dims[1]:.2f} × {dims[2]:.2f} mm · "
        f"{'cerrada' if report['watertight'] else 'NO cerrada'}"
        + (f" · volumen {report['volume_mm3'] / 1000:.2f} cm³" if report['volume_mm3'] else ""))
    log(f"ruido de la nube {report['noise_mm']:.3f} mm · malla↔puntos: media {fit_mean:.3f} mm, p95 {fit_p95:.3f} mm")
    log(f"guardado: {out.with_suffix('.stl')} (+ .ply, _nube.ply, _informe.json)")
    return report


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cloud", type=Path, help="nube densa (fused.ply de COLMAP o scene_dense.ply de OpenMVS)")
    ap.add_argument("--out", type=Path, default=Path("pieza"), help="prefijo de salida (pieza.stl, …)")
    ap.add_argument("--units", choices=UNITS_TO_MM, default="m", help="unidades de la nube (m tras reconstruct.py con escala)")
    ap.add_argument("--voxel", type=float, default=0.0, help="submuestreo en mm (0 = automático)")
    ap.add_argument("--no-flat-base", action="store_true", help="no cerrar la base sobre el plano de apoyo")
    ap.add_argument("--keep-plane", action="store_true", help="no quitar la mesa/tapete")
    ap.add_argument("--depth", type=int, default=0, help="profundidad Poisson (0 = automática; más = más detalle)")
    ap.add_argument("--trim", type=float, default=0.02, help="fracción de baja densidad a recortar")
    ap.add_argument("--smooth", type=int, default=5, help="iteraciones de suavizado Taubin (0 = ninguno)")
    ap.add_argument("--max-triangles", type=int, default=500_000)
    ap.add_argument("--up", help="dirección 'arriba' x,y,z en coordenadas de la nube (gravedad de la INS)")
    a = ap.parse_args()
    up = np.array([float(v) for v in a.up.split(",")]) if a.up else None
    postprocess(a.cloud, a.out, a.units, a.voxel, not a.no_flat_base, a.depth, a.trim, a.smooth,
                a.max_triangles, a.keep_plane, up=up)


if __name__ == "__main__":
    main()
