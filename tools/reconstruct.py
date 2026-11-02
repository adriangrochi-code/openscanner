#!/usr/bin/env python3
"""
Reconstrucción fotogramétrica de un escaneo de Tisma Scanner (carpeta scan_* o .zip).

Pasos:
  1. HDR: fusiona cada ráfaga −2/0/+2 EV con Mertens (sin necesitar curva de respuesta).
  2. Realce para superficies lisas/opacas: CLAHE sólo en las imágenes usadas para
     *detectar* puntos (las originales se usan para color/textura).
  3. COLMAP: una cámara por objetivo físico, con los intrínsecos de fábrica del móvil como
     punto de partida si existen; SIFT denso con DSP-SIFT y forma afín (mejor en poca textura).
  4. Escala métrica: marcadores ArUco del tapete (precisión ~0.1 %) o, si no hay, alineando
     la trayectoria SfM con la de la INS (Umeyama; precisión ~5-10 %).
  5. Densificado/malla opcional con COLMAP (CUDA) o OpenMVS.

Requisitos: python3, numpy, opencv-contrib-python, y `colmap` en el PATH
(opcional: OpenMVS para malla texturizada).

Ejemplo:
    python3 reconstruct.py scan_20261102_101500.zip --work out/ --mat tapete.json --dense openmvs
"""
import argparse
import csv
import json
import shutil
import subprocess
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

import numpy as np

try:
    import cv2
except ImportError:  # pragma: no cover
    sys.exit("Falta OpenCV: pip install opencv-contrib-python numpy")


# --------------------------------------------------------------------------- carga

def load_session(path: Path, work: Path) -> Path:
    if path.suffix == ".zip":
        with zipfile.ZipFile(path) as z:
            z.extractall(work / "raw")
        dirs = [d for d in (work / "raw").iterdir() if d.is_dir()]
        return dirs[0]
    return path


def read_frames(scan: Path):
    with open(scan / "frames.csv") as f:
        return list(csv.DictReader(f))


def read_meta(scan: Path):
    """(camera_id, t_ns) -> metadatos de captura."""
    out = {}
    p = scan / "capture_meta.jsonl"
    if p.exists():
        for line in p.read_text().splitlines():
            if line.strip():
                m = json.loads(line)
                out[(m["camera_id"], int(m["t_ns"]))] = m
    return out


def meta_for(meta, cam, t, tol_ns=2_000_000):
    m = meta.get((cam, t))
    if m is not None:
        return m
    best = min((k for k in meta if k[0] == cam), key=lambda k: abs(k[1] - t), default=None)
    return meta[best] if best and abs(best[1] - t) <= tol_ns else {}


# --------------------------------------------------------------------------- HDR + realce

def group_brackets(frames, meta, max_gap_ns=600_000_000):
    """Agrupa fotos consecutivas de la misma cámara y disparo con EV distintos."""
    by_cam = defaultdict(list)
    for fr in frames:
        by_cam[fr["camera_id"]].append(fr)
    groups = []
    for cam, lst in by_cam.items():
        lst.sort(key=lambda r: int(r["t_ns"]))
        cur = []
        for fr in lst:
            ev = meta_for(meta, cam, int(fr["t_ns"])).get("bracket_ev", 0)
            fr["_ev"] = ev
            if cur and (fr["trigger"] != cur[-1]["trigger"]
                        or int(fr["t_ns"]) - int(cur[-1]["t_ns"]) > max_gap_ns
                        or ev in [c["_ev"] for c in cur]):
                groups.append(cur); cur = []
            cur.append(fr)
        if cur:
            groups.append(cur)
    return groups


def fuse(paths):
    imgs = [cv2.imread(str(p)) for p in paths]
    if len(imgs) == 1:
        return imgs[0]
    # Alinear por si hubo micro-movimiento entre exposiciones.
    cv2.createAlignMTB().process(imgs, imgs)
    fused = cv2.createMergeMertens().process(imgs)
    return np.clip(fused * 255, 0, 255).astype(np.uint8)


def enhance_for_features(img, clip=3.0):
    """CLAHE en luminancia: saca la microtextura de superficies mates y uniformes."""
    lab = cv2.cvtColor(img, cv2.COLOR_BGR2LAB)
    lab[..., 0] = cv2.createCLAHE(clipLimit=clip, tileGridSize=(16, 16)).apply(lab[..., 0])
    return cv2.cvtColor(lab, cv2.COLOR_LAB2BGR)


def prepare_images(scan, work, frames, meta, clahe):
    color_dir, feat_dir = work / "images", work / "images_feat"
    records = []  # (nombre relativo, frame representativo)
    for g in group_brackets(frames, meta):
        ref = min(g, key=lambda r: abs(r["_ev"]))  # la de 0 EV como referencia de pose
        cam = ref["camera_id"]
        name = f"{cam}/{ref['t_ns']}.jpg"
        out = color_dir / name
        out.parent.mkdir(parents=True, exist_ok=True)
        img = fuse([scan / r["file"] for r in g])
        cv2.imwrite(str(out), img, [cv2.IMWRITE_JPEG_QUALITY, 97])
        f = feat_dir / name
        f.parent.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(f), enhance_for_features(img) if clahe else img, [cv2.IMWRITE_JPEG_QUALITY, 97])
        records.append((name, ref))
    print(f"[hdr] {len(frames)} fotos -> {len(records)} imágenes ({sum(len(g) > 1 for g in group_brackets(frames, meta))} fusiones HDR)")
    return records


# --------------------------------------------------------------------------- COLMAP

def colmap(*args):
    cmd = ["colmap", *map(str, args)]
    print("[colmap]", " ".join(cmd))
    subprocess.run(cmd, check=True)


def camera_params(session, cam_id, width, height):
    """OPENCV: fx,fy,cx,cy,k1,k2,p1,p2 escalados del active array al tamaño de la imagen."""
    cam = next((c for c in session["cameras"] if c["id"] == cam_id), None)
    if not cam or "intrinsics_fx_fy_cx_cy_s" not in cam or "active_array" not in cam:
        return None
    fx, fy, cx, cy, _ = cam["intrinsics_fx_fy_cx_cy_s"]
    if fx <= 0:
        return None
    l, t, r, b = cam["active_array"]
    sx, sy = width / (r - l), height / (b - t)
    k1, k2, _k3, p1, p2 = cam.get("distortion_k1_k2_k3_p1_p2", [0, 0, 0, 0, 0])
    return [fx * sx, fy * sy, cx * sx, cy * sy, k1, k2, p1, p2]


def run_sfm(work, session, records, matcher):
    db = work / "database.db"
    if db.exists():
        db.unlink()
    by_cam = defaultdict(list)
    for name, fr in records:
        by_cam[fr["camera_id"]].append((name, fr))
    for cam, items in by_cam.items():
        lst = work / f"list_{cam}.txt"
        lst.write_text("\n".join(n for n, _ in items) + "\n")
        w, h = int(items[0][1]["width"]), int(items[0][1]["height"])
        params = camera_params(session, cam, w, h)
        args = ["feature_extractor", "--database_path", db, "--image_path", work / "images_feat",
                "--image_list_path", lst, "--ImageReader.single_camera", 1,
                "--ImageReader.camera_model", "OPENCV",
                "--SiftExtraction.max_num_features", 16384,
                "--SiftExtraction.estimate_affine_shape", 1,
                "--SiftExtraction.domain_size_pooling", 1]
        if params:
            args += ["--ImageReader.camera_params", ",".join(f"{p:.6f}" for p in params)]
            print(f"[sfm] cámara {cam}: intrínsecos de fábrica {params[:4]}")
        colmap(*args)
    if matcher == "sequential":
        colmap("sequential_matcher", "--database_path", db, "--SiftMatching.guided_matching", 1)
    else:
        colmap("exhaustive_matcher", "--database_path", db, "--SiftMatching.guided_matching", 1)
    sparse = work / "sparse"
    sparse.mkdir(exist_ok=True)
    colmap("mapper", "--database_path", db, "--image_path", work / "images_feat", "--output_path", sparse)
    models = sorted(sparse.iterdir(), key=lambda p: -sum(1 for _ in p.iterdir()))
    return models[0]


# --------------------------------------------------------------------------- modelo TXT

def qvec2rot(q):
    w, x, y, z = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)],
        [2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)],
        [2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)]])


def read_model_txt(d: Path):
    cams, imgs = {}, {}
    for line in (d / "cameras.txt").read_text().splitlines():
        if line and not line.startswith("#"):
            p = line.split()
            cams[int(p[0])] = (p[1], int(p[2]), int(p[3]), np.array(list(map(float, p[4:]))))
    lines = [l for l in (d / "images.txt").read_text().splitlines() if l and not l.startswith("#")]
    for l in lines[0::2]:
        p = l.split()
        q, t = np.array(list(map(float, p[1:5]))), np.array(list(map(float, p[5:8])))
        imgs[p[9]] = dict(R=qvec2rot(q), t=t, cam=int(p[8]))
    return cams, imgs


def scale_model_txt(src: Path, dst: Path, s: float):
    dst.mkdir(parents=True, exist_ok=True)
    shutil.copy(src / "cameras.txt", dst / "cameras.txt")
    out, k = [], 0
    for l in (src / "images.txt").read_text().splitlines():
        if l.startswith("#") or not l:
            out.append(l); continue
        if k % 2 == 0:
            p = l.split()
            p[5:8] = [repr(float(v) * s) for v in p[5:8]]
            l = " ".join(p)
        out.append(l); k += 1
    (dst / "images.txt").write_text("\n".join(out) + "\n")
    out = []
    for l in (src / "points3D.txt").read_text().splitlines():
        if l and not l.startswith("#"):
            p = l.split()
            p[1:4] = [repr(float(v) * s) for v in p[1:4]]
            l = " ".join(p)
        out.append(l)
    (dst / "points3D.txt").write_text("\n".join(out) + "\n")


# --------------------------------------------------------------------------- escala

def undistort_pts(pts, cam):
    model, w, h, prm = cam
    if model == "OPENCV":
        fx, fy, cx, cy, k1, k2, p1, p2 = prm
        K = np.array([[fx, 0, cx], [0, fy, cy], [0, 0, 1]])
        D = np.array([k1, k2, p1, p2])
    elif model in ("PINHOLE",):
        fx, fy, cx, cy = prm; K = np.array([[fx, 0, cx], [0, fy, cy], [0, 0, 1]]); D = np.zeros(4)
    else:  # SIMPLE_RADIAL / RADIAL / SIMPLE_PINHOLE
        f, cx, cy = prm[:3]; K = np.array([[f, 0, cx], [0, f, cy], [0, 0, 1]])
        D = np.array([prm[3] if len(prm) > 3 else 0, prm[4] if len(prm) > 4 else 0, 0, 0])
    return cv2.undistortPoints(pts.reshape(-1, 1, 2).astype(np.float64), K, D).reshape(-1, 2)


def triangulate(obs):
    """DLT con observaciones normalizadas [(R, t, xy_normalizado)]."""
    A = []
    for R, t, (x, y) in obs:
        P = np.hstack([R, t[:, None]])
        A += [x * P[2] - P[0], y * P[2] - P[1]]
    _, _, vt = np.linalg.svd(np.array(A))
    X = vt[-1]
    return X[:3] / X[3]


def scale_from_aruco(work, cams, imgs, mat_json):
    mat = json.loads(Path(mat_json).read_text())
    marker_mm = mat["marker_mm"]
    det = cv2.aruco.ArucoDetector(cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50),
                                  cv2.aruco.DetectorParameters())
    obs = defaultdict(list)  # (id, esquina) -> [(R,t,xy)]
    for name, im in imgs.items():
        img = cv2.imread(str(work / "images" / name), cv2.IMREAD_GRAYSCALE)
        if img is None:
            continue
        corners, ids, _ = det.detectMarkers(img)
        if ids is None:
            continue
        for c, i in zip(corners, ids.flatten()):
            c = c.reshape(4, 2)
            cv2.cornerSubPix(img, c, (5, 5), (-1, -1), (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 40, 0.01))
            nc = undistort_pts(c, cams[im["cam"]])
            for k in range(4):
                obs[(int(i), k)].append((im["R"], im["t"], nc[k]))
    pts = {k: triangulate(v) for k, v in obs.items() if len(v) >= 3}
    ratios = []
    for mid in {k[0] for k in pts}:
        for a, b in [(0, 1), (1, 2), (2, 3), (3, 0)]:
            if (mid, a) in pts and (mid, b) in pts:
                ratios.append(marker_mm / 1000.0 / np.linalg.norm(pts[(mid, a)] - pts[(mid, b)]))
    if len(ratios) < 4:
        return None, None
    r = np.array(ratios)
    med = np.median(r)
    r = r[np.abs(r - med) < 0.05 * med]
    return float(np.median(r)), float(np.std(r) / np.median(r))


def umeyama_scale(src, dst):
    """Escala de la similitud que mejor lleva src -> dst (Umeyama 1991)."""
    mu_s, mu_d = src.mean(0), dst.mean(0)
    xs, xd = src - mu_s, dst - mu_d
    cov = xd.T @ xs / len(src)
    U, S, Vt = np.linalg.svd(cov)
    D = np.eye(3)
    if np.linalg.det(U) * np.linalg.det(Vt) < 0:
        D[2, 2] = -1
    var_s = (xs ** 2).sum() / len(src)
    return float(np.trace(np.diag(S) @ D) / var_s)


def scale_from_ins(imgs, records):
    ref = {name: fr for name, fr in records}
    src, dst = [], []
    for name, im in imgs.items():
        fr = ref.get(name)
        if fr and fr.get("px"):
            src.append(-im["R"].T @ im["t"])  # centro de cámara SfM
            dst.append([float(fr["px"]), float(fr["py"]), float(fr["pz"])])
    if len(src) < 5:
        return None
    src, dst = np.array(src), np.array(dst)
    if np.ptp(dst, axis=0).max() < 0.05:
        return None  # la INS apenas se movió: escala no fiable
    return umeyama_scale(src, dst)


# --------------------------------------------------------------------------- denso

def dense(work, model_dir, mode):
    if mode == "none":
        return
    und = work / "undistorted"
    colmap("image_undistorter", "--image_path", work / "images", "--input_path", model_dir,
           "--output_path", und, "--output_type", "COLMAP")
    if mode == "colmap":
        colmap("patch_match_stereo", "--workspace_path", und,
               "--PatchMatchStereo.geom_consistency", 1)
        colmap("stereo_fusion", "--workspace_path", und, "--output_path", work / "fused.ply")
        colmap("poisson_mesher", "--input_path", work / "fused.ply", "--output_path", work / "mesh.ply")
    elif mode == "openmvs":
        def mvs(*a):
            print("[openmvs]", " ".join(map(str, a)))
            subprocess.run(list(map(str, a)), check=True, cwd=und)
        mvs("InterfaceCOLMAP", "-i", ".", "-o", "scene.mvs", "--image-folder", "images")
        mvs("DensifyPointCloud", "scene.mvs", "--resolution-level", 0)
        mvs("ReconstructMesh", "scene_dense.mvs")
        mvs("RefineMesh", "scene_dense_mesh.mvs", "--resolution-level", 0)
        mvs("TextureMesh", "scene_dense_mesh_refine.mvs", "--export-type", "obj")
    print(f"[dense] resultado en {und if mode == 'openmvs' else work}")


# --------------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("scan", type=Path, help="carpeta scan_* o .zip exportado por la app")
    ap.add_argument("--work", type=Path, default=Path("tisma_work"))
    ap.add_argument("--mat", help="JSON del tapete (make_scan_mat.py) para escala ArUco")
    ap.add_argument("--no-clahe", action="store_true", help="desactiva el realce de microtextura")
    ap.add_argument("--matcher", choices=["exhaustive", "sequential"], default="exhaustive")
    ap.add_argument("--dense", choices=["none", "colmap", "openmvs"], default="none")
    ap.add_argument("--prepare-only", action="store_true", help="sólo HDR + realce, sin COLMAP")
    a = ap.parse_args()

    a.work.mkdir(parents=True, exist_ok=True)
    scan = load_session(a.scan, a.work)
    session = json.loads((scan / "session.json").read_text())
    frames, meta = read_frames(scan), read_meta(scan)
    print(f"[load] {scan.name}: {len(frames)} fotos, {session['device']['manufacturer']} {session['device']['model']}")
    records = prepare_images(scan, a.work, frames, meta, clahe=not a.no_clahe)
    if a.prepare_only:
        return
    if not shutil.which("colmap"):
        sys.exit("No se encuentra `colmap` en el PATH (https://colmap.github.io/install.html)")

    model = run_sfm(a.work, session, records, a.matcher)
    txt = a.work / "sparse_txt"
    txt.mkdir(exist_ok=True)
    colmap("model_converter", "--input_path", model, "--output_path", txt, "--output_type", "TXT")
    cams, imgs = read_model_txt(txt)
    print(f"[sfm] {len(imgs)}/{len(records)} imágenes registradas")

    s, src = None, None
    if a.mat:
        s, rel = scale_from_aruco(a.work, cams, imgs, a.mat)
        if s:
            src = f"ArUco (dispersión {rel * 100:.2f} %)"
    if s is None:
        s = scale_from_ins(imgs, records)
        if s:
            src = "trayectoria INS (aprox. 5-10 %)"
    if s:
        print(f"[scale] factor {s:.6f} m/unidad desde {src}")
        metric = a.work / "sparse_metric_txt"
        scale_model_txt(txt, metric, s)
        out = a.work / "sparse_metric"
        out.mkdir(exist_ok=True)
        colmap("model_converter", "--input_path", metric, "--output_path", out, "--output_type", "BIN")
        model = out
    else:
        print("[scale] sin escala métrica (usa --mat con el tapete ArUco)")
    dense(a.work, model, a.dense)


if __name__ == "__main__":
    main()
