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
import sys
import json
from pathlib import Path

import numpy as np

try:
    import open3d as o3d
except ImportError as e:  # pragma: no cover
    raise SystemExit(f"No se puede cargar Open3D ({e}). Instálalo con: pip install open3d "
                     "(y en Linux sin pantalla: apt install libegl1 libgl1)")

UNITS_TO_MM = {"m": 1000.0, "mm": 1.0, "cm": 10.0, "unit": 1.0}


def log(msg):
    print(f"[post] {msg}")


def spacing_of(pcd):
    d = np.asarray(pcd.compute_nearest_neighbor_distance())
    return float(np.median(d)) if len(d) else 1.0



def for_person(text: str) -> SystemExit:
    """Como reconstruct.for_person: un final con texto para la persona, que tisma_scan muestra en error_es."""
    e = SystemExit(text)
    e.for_person = True
    return e

def refine_plane(P, model, thr, rounds=5):
    """
    El plano de RANSAC ajustado por mínimos cuadrados a sus puntos, y otra vez con los puntos de ese plano, hasta que
    no cambia. RANSAC de Open3D corre en varios hilos y aun con semilla da planos algo distintos en cada corrida: el
    mismo escaneo daba medidas y volumen distintos (24076-24130 mm³ en la caja de prueba; mucho más en una placa
    delgada). Ajustado así, converge al mismo plano, y más exacto que tres puntos.
    """
    n = np.asarray(model[:3], float); d = float(model[3])
    k = np.linalg.norm(n); n, d = n / k, d / k
    inl = np.where(np.abs(P @ n + d) < thr)[0]
    for _ in range(rounds):
        if len(inl) < 3:
            break
        c = P[inl].mean(0)
        n2 = np.linalg.svd(P[inl] - c, full_matrices=False)[2][-1]
        if n2 @ n < 0:
            n2 = -n2
        d2 = -float(n2 @ c)
        new = np.where(np.abs(P @ n2 + d2) < thr)[0]
        n, d = n2, d2
        if np.array_equal(new, inl):
            break
        inl = new
    return [*n, d], inl


def support_plane(pcd, thr, up=None, max_planes=8, min_share=0.005):
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
        model, inl = refine_plane(np.asarray(rest.points), model, thr)
        # Hasta planos chicos: con la pieza densa y cerca, la mesa puede tener el 1 % de los puntos (antes se paraba
        # en el 3 % y la mesa nunca era candidata; con gravedad, la tapa de una caja pasaba por mesa).
        if len(inl) < max(200, min_share * len(P_all)):
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
        # La mesa tiene la pieza ENCIMA: un plano con la mayoría de los demás puntos debajo (la tapa de la pieza)
        # no es la mesa, aunque sea el más bajo de los encontrados.
        def most_above(c):
            h = P_all @ up
            others = np.setdiff1d(np.arange(len(P_all)), c[2])
            return np.mean(h[others] > np.median(h[c[2]]) + thr) > 0.5 if len(others) else False
        horiz = [c for c in horiz if most_above(c)]
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
    # Se sigue arista por arista (no vértice por vértice): un borde puede pasar dos veces por el mismo vértice
    # (un «pellizco», que deja la simplificación de la malla), y seguirlo por vértices lo dejaba abierto.
    loops, failed = [], []
    for start in list(nxt):
        while nxt.get(start):
            loop, v = [start], nxt[start].pop()
            while v != start and nxt.get(v):
                loop.append(v)
                v = nxt[v].pop()
            if v == start and len(loop) >= 3:
                loops.append(loop)
            elif v != start:
                failed += list(zip(loop, loop[1:] + [v]))  # aristas de un recorrido que no cerró
    # Lo que queda: bordes con aristas en sentidos cruzados (un triángulo dado vuelta). Se siguen sin sentido.
    left = {}
    for a, b in failed + [(a, b) for a, bs in nxt.items() for b in bs]:
        left.setdefault(a, []).append(b); left.setdefault(b, []).append(a)
    for start in list(left):
        while left.get(start):
            loop, prev, v = [start], start, left[start][0]
            left[start].remove(v); left[v].remove(start)
            while v != start and left.get(v):
                loop.append(v)
                prev, v = v, left[v][0]
                left[prev].remove(v); left[v].remove(prev)
            if v == start and len(loop) >= 3:
                loops.append(loop)
    return loops


def simple_loops(loop):
    """Parte un borde que pasa dos veces por un vértice (un pellizco) en bordes simples: un abanico sobre el borde
    entero cubriría dos veces la arista de ese vértice al centro (4 caras en una arista: no queda cerrada)."""
    out, path, at = [], [], {}
    for v in loop:
        if v in at:
            i = at[v]
            sub = path[i:]
            for u in sub[1:]:
                del at[u]
            del path[i + 1:]
            if len(sub) >= 3:
                out.append(sub)
        else:
            at[v] = len(path)
            path.append(v)
    if len(path) >= 3:
        out.append(path)
    return out


def skirt_to_floor(V, loop, new_v, new_t, snap=0.1):
    """
    Extiende el borde `loop` en vertical hasta Z = 0: por cada vértice, uno nuevo debajo (en new_v) y una pared
    de dos triángulos por arista (en new_t), con el sentido del borde. Los vértices que ya están a menos de
    `snap` mm del suelo se bajan a Z = 0 y no llevan pared. Devuelve el borde de abajo, para taparlo.
    """
    floor = []
    for k in loop:                              # uno por paso, aunque el borde pase dos veces por un vértice
        if V[k, 2] <= snap:
            V[k, 2] = 0.0
            floor.append(k)
        else:
            floor.append(len(V) + len(new_v))
            new_v.append([V[k, 0], V[k, 1], 0.0])
    n = len(loop)
    for i in range(n):
        a, b, a2, b2 = loop[i], loop[(i + 1) % n], floor[i], floor[(i + 1) % n]
        # La cara de la malla tiene la arista a→b; la pared la recorre b→a y deja abajo b2→a2, como la tapa.
        if a2 != a:
            new_t.append([b, a, a2])
        if b2 != b:
            new_t.append([b, a2, b2])
    return floor


def cap_holes(mesh, max_perimeter, flatten_lowest=False):
    """
    Cierra cada agujero con un abanico desde su centro (la orientación sigue al borde).
    Robusto para la base y agujeros pequeños; los muy grandes (> max_perimeter) se dejan,
    porque cerrarlos sería inventar superficie.
    """
    V = np.asarray(mesh.vertices).copy(); T = np.asarray(mesh.triangles).copy()
    new_v, new_t, closed = [], [], 0
    loops = boundary_loops(T)
    base = None
    if flatten_lowest and loops:
        # La base es el borde con el contorno más ancho en planta (lo que no vio la cámara, sobre la mesa). Cerca
        # del tapete faltan a veces varios mm de pared (en CI, el borde subía hasta 13 mm por un lado): se baja
        # en vertical hasta Z = 0 con una «falda» y se tapa plano allí, sea cual sea su largo.
        # Sólo bordes que llegan cerca de lo más bajo: una boca abierta arriba, más ancha que la base (un vaso,
        # una bandeja), no es la base y no se baja al suelo.
        def footprint(l):
            P = V[l, :2]
            return np.prod(P.max(0) - P.min(0))
        low = min(V[l, 2].min() for l in loops)
        near = low + 0.25 * (V[:, 2].max() - low)
        base = max((i for i, l in enumerate(loops) if V[l, 2].min() <= near), key=lambda i: footprint(loops[i]))
    for i, loop in enumerate(loops):
        if i == base:
            loop = skirt_to_floor(V, loop, new_v, new_t)
        else:
            P = V[loop]
            if np.sum(np.linalg.norm(P - np.roll(P, -1, 0), axis=1)) > max_perimeter:
                continue
        for sub in simple_loops(loop):
            c = len(V) + len(new_v)
            new_v.append(np.mean([V[k] if k < len(V) else new_v[k - len(V)] for k in sub], axis=0))
            for a, b in zip(sub, sub[1:] + sub[:1]):
                new_t.append([b, a, c])         # sentido inverso al del borde: cara hacia fuera
        closed += 1
    if new_v:
        mesh.vertices = o3d.utility.Vector3dVector(np.vstack([V, new_v]))
        mesh.triangles = o3d.utility.Vector3iVector(np.vstack([T, new_t]))
    return closed


def measured_extent(cloud, flat_base: bool, q: float = 0.05):
    """
    Las medidas de la pieza (mm, ejes ya alineados): de los puntos MEDIDOS, entre los percentiles q y 100 - q (fuera
    el ruido suelto), con la base en Z = 0 si apoya en la mesa. No de la malla: Poisson y el suavizado dejan vértices
    unas décimas más allá de la superficie medida (en aristas vivas) y, sumados a los dos lados, una placa de 2 mm
    medía 2,38 (+19 %).
    """
    P = np.asarray(cloud.points)
    lo = np.percentile(P, q, axis=0); hi = np.percentile(P, 100 - q, axis=0)
    if flat_base:
        lo[2] = 0.0
    return hi - lo


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

    # Nada sobre la mesa: la pieza es más delgada que la franja que se quita con el plano (o la nube es muy rala).
    # Antes seguía y terminaba en un error de NumPy («zero-size array»), sin decir qué pasó.
    if plane is not None and not keep_plane and len(pcd.points) < 200:
        raise for_person(f"No queda pieza sobre la mesa: con esta nube (un punto cada {sp:.2f} mm) se quita la franja "
                         f"de {2 * thr:.1f} mm junto a la mesa, y la pieza parece más delgada que eso. Escanea más cerca "
                         "o con más fotos para una nube más densa.")

    # 2. Limpieza de puntos sueltos
    pcd, _ = pcd.remove_statistical_outlier(nb_neighbors=20, std_ratio=2.0)
    pcd, _ = pcd.remove_radius_outlier(nb_points=6, radius=sp * 4)
    log(f"tras limpieza: {len(pcd.points)} puntos (separación {sp:.3f} mm)")

    # 3. La pieza: el grupo mayor
    labels = np.array(pcd.cluster_dbscan(eps=sp * 6, min_points=10))
    if len(labels) and labels.max() >= 0:
        big = np.bincount(labels[labels >= 0]).argmax()
        pcd = pcd.select_by_index(np.where(labels == big)[0])
    log(f"pieza: {len(pcd.points)} puntos")
    if len(pcd.points) < 200:
        raise for_person("Quedaron muy pocos puntos de la pieza: prueba con más fotos, más cerca, y el tapete de escaneo.")
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
    # Simplificar antes de cerrar: la simplificación abre agujeritos y deja aristas cruzadas en una malla ya
    # cerrada (en la prueba de CI, 1,3 M → 500 k triángulos: 278 aristas abiertas, sin volumen).
    if len(mesh.triangles) > max_triangles:
        mesh = mesh.simplify_quadric_decimation(max_triangles)
        mesh.remove_degenerate_triangles(); mesh.remove_duplicated_triangles()
        mesh.remove_duplicated_vertices(); mesh.remove_unreferenced_vertices()
        if not mesh.is_edge_manifold():
            mesh.remove_non_manifold_edges()
    # Cerrar los agujeros que deja el recorte (la base y huecos pequeños) para que sea imprimible.
    # (El fill_holes de Open3D creaba vértices a metros de la pieza con bordes no limpios.)
    if closed_base:
        n_capped = cap_holes(mesh, max_perimeter=4 * extent, flatten_lowest=True)
        log(f"base plana en Z = 0 y agujeros cerrados: {n_capped}")
    if smooth > 0:
        mesh = mesh.filter_smooth_taubin(number_of_iterations=smooth)
    if closed_base:
        # El suavizado mueve también la base plana: nada de la pieza queda por debajo de la mesa (Z = 0).
        V = np.asarray(mesh.vertices).copy(); V[:, 2] = np.maximum(V[:, 2], 0.0)
        mesh.vertices = o3d.utility.Vector3dVector(V)
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
    dims = measured_extent(measured, flat_base=plane is not None)
    mesh_dims = mesh.get_axis_aligned_bounding_box().get_extent()
    closed, vol = closed_volume(mesh)
    obb = mesh.get_oriented_bounding_box()
    fit_mean, fit_p95 = mesh_quality(mesh, measured)
    report.update({
        "triangles": len(mesh.triangles),
        "watertight": closed,
        "dimensions_mm_xyz": [round(float(v), 3) for v in dims],
        "mesh_dimensions_mm_xyz": [round(float(v), 3) for v in mesh_dims],
        "oriented_box_mm": [round(float(v), 3) for v in sorted(obb.extent, reverse=True)],
        "area_mm2": round(float(mesh.get_surface_area()), 2),
        "volume_mm3": round(vol, 2) if vol else None,
        "mesh_vs_points_mean_mm": round(fit_mean, 4),
        "mesh_vs_points_p95_mm": round(fit_p95, 4),
        "flat_base": bool(flat_base and plane is not None),
        "poisson_depth": depth,
    })
    # NaN/infinito no son JSON (la app y los lectores estrictos rechazan el informe entero): van como null.
    report = {k: (None if isinstance(v, float) and not np.isfinite(v) else v) for k, v in report.items()}
    out.with_name(out.name + "_informe.json").write_text(json.dumps(report, indent=2, ensure_ascii=False,
                                                                    allow_nan=False))
    log(f"medidas {dims[0]:.2f} × {dims[1]:.2f} × {dims[2]:.2f} mm · "
        f"{'cerrada' if report['watertight'] else 'NO cerrada'}"
        + (f" · volumen {report['volume_mm3'] / 1000:.2f} cm³" if report['volume_mm3'] else ""))
    noise = f"{report['noise_mm']:.3f} mm" if report.get("noise_mm") is not None else "sin medir"
    log(f"ruido de la nube {noise} · malla↔puntos: media {fit_mean:.3f} mm, p95 {fit_p95:.3f} mm")
    log(f"guardado: {out.with_suffix('.stl')} (+ .ply, _nube.ply, _informe.json)")
    return report



def _utf8_console():
    """Consola de Windows (cp1252) no puede imprimir ↔, ≈, ³…: forzar UTF-8 sin romper nunca."""
    for stream in (sys.stdout, sys.stderr, sys.__stdout__, sys.__stderr__):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass

def main():
    _utf8_console()
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
