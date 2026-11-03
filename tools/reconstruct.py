#!/usr/bin/env python3
"""
Reconstrucción fotogramétrica de un escaneo de Tisma Scanner (carpeta scan_* o .zip).

Pasos:
  1. Une ráfagas: HDR (Mertens), apilados de enfoque (todo enfocado + mapa de profundidad) y
     ráfagas anti-movimiento (alineadas y promediadas). Descarta las fotos movidas.
  2. Imágenes: iguala exposición y color entre fotos (textura sin parches). Para DETECTAR
     puntos: quita ruido sólo si la foto lo tiene (estimado) y aplica el filtro de Wallis, que
     en superficies de textura débil da 2-4,6× más emparejamientos que CLAHE.
  3. COLMAP: una cámara por objetivo y distancia de enfoque, con intrínsecos de fábrica.
  4. INS: las fotos cuyo giro contradice al giróscopo (registro erróneo) se quitan del modelo
     antes del denso; la gravedad orienta la pieza y distingue la mesa de una cara plana.
  5. Escala métrica: ArUco > distancia por enfoque > INS (se muestran todas para contrastar).
  6. Densificado (COLMAP con CUDA u OpenMVS) y POSTPROCESADO (postprocess.py): limpieza,
     mesa fuera, pieza aislada, base plana, malla cerrada y STL en mm con informe de calidad.

Requisitos: python3, numpy, opencv-contrib-python, y `colmap` en el PATH
(opcional: OpenMVS para malla texturizada).

Ejemplo:
    python3 reconstruct.py scan_20261102_101500.zip --work out/ --mat tapete.json --dense openmvs
"""
import argparse
import csv
import os
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


def drop_blurry(scan: Path, frames, max_drop: float = 0.3):
    """Descarta las fotos que la app marcó como movidas (quality.csv), con dos salvaguardas:
    - no toca los disparos de varias fotos por cámara (HDR, apilado de foco, ráfaga): se
      fusionan después y quitar una rompe el grupo;
    - nunca quita más de `max_drop` de las fotos: si casi todo sale "movido" (interior, a mano),
      se quitan sólo las peores; borrar todo dejaría sin cobertura.
    """
    p = scan / "quality.csv"
    if not p.exists():
        return frames
    with open(p) as f:
        blur = {(r["camera_id"], r["t_ns"]): float(r.get("motion_blur_px") or 0)
                for r in csv.DictReader(f) if r["blurry"] == "1"}
    per_shot = {}
    for fr in frames:
        key = (fr.get("trigger", ""), fr["camera_id"])
        per_shot[key] = per_shot.get(key, 0) + 1
    # Las fotos que el móvil ya enfocó (deblurred.csv) no se tiran por su arrastre original.
    fixed = set()
    dp = scan / "deblurred.csv"
    if dp.exists():
        with open(dp) as f:
            fixed = {r["file"] for r in csv.DictReader(f) if r.get("applied") == "1"}
    candidates = [fr for fr in frames if (fr["camera_id"], fr["t_ns"]) in blur
                  and fr.get("file") not in fixed
                  and per_shot[(fr.get("trigger", ""), fr["camera_id"])] == 1]
    candidates.sort(key=lambda fr: -blur[(fr["camera_id"], fr["t_ns"])])
    drop = {id(fr) for fr in candidates[:int(len(frames) * max_drop)]}
    kept = [fr for fr in frames if id(fr) not in drop]
    print(f"[quality] {len(drop)} fotos movidas descartadas (marcadas {len(blur)}, máx. {int(max_drop * 100)} %)")
    return kept


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
    """
    Agrupa las ráfagas: fotos consecutivas de la misma cámara y disparo que difieren en EV
    (HDR) o en distancia de enfoque (apilado). Cada grupo acaba siendo una sola imagen.
    """
    by_cam = defaultdict(list)
    for fr in frames:
        by_cam[fr["camera_id"]].append(fr)
    groups = []
    for cam, lst in by_cam.items():
        lst.sort(key=lambda r: int(r["t_ns"]))
        cur = []
        for fr in lst:
            m = meta_for(meta, cam, int(fr["t_ns"]))
            ev = m.get("bracket_ev", 0)
            fr["_ev"] = ev
            fr["_focus_target"] = m.get("focus_sweep_target_diopters")
            fr["_focus"] = m.get("focus_distance_diopters", fr["_focus_target"])
            fr["_burst"] = m.get("burst_index")
            key = (ev, fr["_focus_target"], fr["_burst"])
            if cur and (fr["trigger"] != cur[-1]["trigger"]
                        or int(fr["t_ns"]) - int(cur[-1]["t_ns"]) > max_gap_ns
                        or key in [(c["_ev"], c["_focus_target"], c["_burst"]) for c in cur]):
                groups.append(cur); cur = []
            cur.append(fr)
        if cur:
            groups.append(cur)
    return groups


def fuse(paths):
    # Las fotos movidas pueden haberse borrado ya en el móvil.
    imgs = [im for im in (cv2.imread(str(p)) for p in paths) if im is not None]
    if not imgs:
        return None
    if len(imgs) == 1:
        return imgs[0]
    # Alinear por si hubo micro-movimiento entre exposiciones.
    cv2.createAlignMTB().process(imgs, imgs)
    fused = cv2.createMergeMertens().process(imgs)
    return np.clip(fused * 255, 0, 255).astype(np.uint8)


def wallis(L, win=31, target_mean=127.0, target_std=50.0, c=0.95, b=0.9):
    """
    Filtro de Wallis (clásico en fotogrametría): lleva la media y el contraste LOCALES de cada
    zona a unos objetivos comunes. En superficies lisas o en sombra hace visible la microtextura
    y deja comparables fotos con distinta exposición, sin la saturación por bloques del CLAHE.
      c: fuerza del realce de contraste (0-1; la ganancia máxima es 1/(1-c)) · b: brillo (0-1)
    Valores elegidos midiendo emparejamientos válidos en superficies de textura débil:
    c=0.95 y ventana 31 dan 2-4,6× más que CLAHE y lo igualan en textura normal.
    """
    f = L.astype(np.float32)
    m = cv2.blur(f, (win, win))
    sd = np.sqrt(np.maximum(cv2.blur(f * f, (win, win)) - m * m, 0))
    gain = (c * target_std) / (c * sd + (1 - c) * target_std)
    out = (f - m) * gain + b * target_mean + (1 - b) * m
    return np.clip(out, 0, 255).astype(np.uint8)


def estimate_noise(L):
    """Desviación del ruido (niveles de gris), método de Immerkær: robusto a la textura."""
    k = np.array([[1, -2, 1], [-2, 4, -2], [1, -2, 1]], np.float32)
    h, w = L.shape
    conv = np.abs(cv2.filter2D(L.astype(np.float32), -1, k))[1:-1, 1:-1]
    return float(np.sqrt(np.pi / 2) * conv.sum() / (6 * (w - 2) * (h - 2)))


def enhance_for_features(img, mode="wallis", denoise=-1):
    """
    Imagen sólo para DETECTAR puntos (la textura final usa la foto sin realzar):
      1. Quitar ruido de la luminancia ANTES de realzar (si no, el realce lo convierte en
         falsos puntos). denoise = -1: automático (sólo si la foto tiene ruido apreciable, con
         fuerza igual al ruido medido; en fotos limpias filtrar borra textura débil y empeora);
         0 = nunca; > 0 = fuerza fija.
      2. Realce local: Wallis (por defecto) o CLAHE.
    """
    if mode == "none" and not denoise:
        return img
    lab = cv2.cvtColor(img, cv2.COLOR_BGR2LAB)
    L = lab[..., 0]
    h = estimate_noise(L) if denoise < 0 else float(denoise)
    if h >= 2.0 or (denoise > 0):
        L = cv2.fastNlMeansDenoising(L, None, h=h, templateWindowSize=7, searchWindowSize=21)
    if mode == "wallis":
        L = wallis(L)
    elif mode == "clahe":
        L = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(16, 16)).apply(L)
    lab[..., 0] = L
    return cv2.cvtColor(lab, cv2.COLOR_LAB2BGR)


def normalize_colors(paths):
    """
    Exposición y color iguales en todas las fotos (para una textura sin parches): cada canal de
    cada foto se escala para que su media coincida con la mediana del escaneo. Ganancias
    limitadas (0,6-1,6) para no estropear fotos realmente distintas.
    """
    means = {}
    for p in paths:
        im = cv2.imread(str(p))
        if im is not None:
            small = cv2.resize(im, None, fx=0.25, fy=0.25, interpolation=cv2.INTER_AREA)
            means[p] = small.reshape(-1, 3).mean(0)
    if len(means) < 2:
        return 0
    target = np.median(np.array(list(means.values())), axis=0)
    for p, m in means.items():
        gain = np.clip(target / np.maximum(m, 1), 0.6, 1.6)
        if np.all(np.abs(gain - 1) < 0.02):
            continue
        im = cv2.imread(str(p)).astype(np.float32) * gain
        cv2.imwrite(str(p), np.clip(im, 0, 255).astype(np.uint8), [cv2.IMWRITE_JPEG_QUALITY, 97])
    return len(means)


def sharpness(gray):
    return cv2.Laplacian(gray, cv2.CV_32F).var() / (gray.var() + 1.0)


def align_ecc(ref_gray, gray, coarse=1000):
    """
    Homografía que lleva la foto a la referencia (para warpPerspective con WARP_INVERSE_MAP).
    Primero en pequeño (rápido, robusto) y luego refinada a resolución completa: la
    superresolución necesita ~0,1 px de precisión, y alinear sólo en pequeño deja ~0,5 px.
    """
    h, w = ref_gray.shape
    s = min(1.0, coarse / max(h, w))
    crit = (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 80, 1e-5)
    warp = np.eye(3, dtype=np.float32)
    _, warp = cv2.findTransformECC(cv2.resize(ref_gray, None, fx=s, fy=s).astype(np.float32),
                                   cv2.resize(gray, None, fx=s, fy=s).astype(np.float32),
                                   warp, cv2.MOTION_HOMOGRAPHY, crit, None, 5)
    S = np.diag([s, s, 1]).astype(np.float32)
    H = (np.linalg.inv(S) @ warp @ S).astype(np.float32)
    if s < 1.0:
        try:
            _, H = cv2.findTransformECC(ref_gray.astype(np.float32), gray.astype(np.float32), H,
                                        cv2.MOTION_HOMOGRAPHY, (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 30, 1e-6), None, 3)
        except cv2.error:
            pass
    return H


def shift_and_add(imgs, Hs, scale=2):
    """
    Superresolución multi-imagen ("shift-and-add"): el temblor de la mano desplaza cada foto de
    la ráfaga fracciones de píxel; una vez alineadas con precisión, sus píxeles caen en puntos
    distintos de una rejilla `scale` veces más fina. Se reparten (bilineal) y se normalizan.
    imgs[0] es la referencia (H = identidad). Huecos: la referencia ampliada. Al final, un
    realce suave compensa el reparto bilineal.
    """
    ref = imgs[0]
    h, w = ref.shape[:2]
    H2, W2 = h * scale, w * scale
    acc = np.zeros((H2 * W2, 3), np.float64)
    wsum = np.zeros(H2 * W2, np.float64)
    ys, xs = np.mgrid[0:h, 0:w]
    pts = np.stack([xs.ravel(), ys.ravel(), np.ones(h * w)]).astype(np.float64)
    for im, H in zip(imgs, Hs):
        # Píxel de esta foto -> coordenadas de la referencia -> rejilla fina (centros de píxel).
        q = np.linalg.inv(H.astype(np.float64)) @ pts
        X = (q[0] / q[2] + 0.5) * scale - 0.5
        Y = (q[1] / q[2] + 0.5) * scale - 0.5
        x0 = np.floor(X).astype(np.int64); y0 = np.floor(Y).astype(np.int64)
        fx = X - x0; fy = Y - y0
        cols = im.reshape(-1, 3).astype(np.float64)
        for dx, dy, wt in ((0, 0, (1 - fx) * (1 - fy)), (1, 0, fx * (1 - fy)), (0, 1, (1 - fx) * fy), (1, 1, fx * fy)):
            xi = x0 + dx; yi = y0 + dy
            ok = (xi >= 0) & (xi < W2) & (yi >= 0) & (yi < H2) & (wt > 1e-6)
            idx = yi[ok] * W2 + xi[ok]
            wv = wt[ok]
            wsum += np.bincount(idx, weights=wv, minlength=H2 * W2)
            for c in range(3):
                acc[:, c] += np.bincount(idx, weights=wv * cols[ok, c], minlength=H2 * W2)
    up = cv2.resize(ref, (W2, H2), interpolation=cv2.INTER_CUBIC).reshape(-1, 3).astype(np.float64)
    good = wsum > 0.3
    out = up.copy()
    out[good] = acc[good] / wsum[good, None]
    out = out.reshape(H2, W2, 3).astype(np.float32)
    blur = cv2.GaussianBlur(out, (0, 0), 1.0)
    out = cv2.addWeighted(out, 1.6, blur, -0.6, 0)          # deshace el emborronado del reparto
    return np.clip(out, 0, 255).astype(np.uint8)


def burst_merge(paths, superres=2):
    """
    Ráfaga anti-movimiento: alinea las fotos cortas con la más nítida (la referencia conserva su
    geometría completa: la calibración no cambia) y:
      * superres > 1: superresolución "shift-and-add" a `superres`× (aprovecha el temblor);
      * superres <= 1: media (menos ruido, misma resolución).
    Devuelve (imagen, índice de la referencia, escala) o (None, None, 1).
    """
    imgs = [cv2.imread(str(p)) for p in paths]
    idx = [i for i, im in enumerate(imgs) if im is not None]
    if not idx:
        return None, None, 1
    grays = {i: cv2.cvtColor(imgs[i], cv2.COLOR_BGR2GRAY) for i in idx}
    sharp = {i: sharpness(grays[i]) for i in idx}
    ref = max(idx, key=lambda i: sharp[i])
    h, w = grays[ref].shape
    used, Hs = [imgs[ref]], [np.eye(3, dtype=np.float32)]
    for i in idx:
        if i == ref or sharp[i] < 0.6 * sharp[ref]:
            continue  # una foto de la ráfaga salió movida: no ensuciar el resultado
        try:
            Hs.append(align_ecc(grays[ref], grays[i])); used.append(imgs[i])
        except cv2.error:
            continue
    if superres > 1 and len(used) >= 3:
        return shift_and_add(used, Hs, superres), ref, superres
    acc = np.zeros((h, w, 3), np.float32); weight = np.zeros((h, w, 1), np.float32)
    flags = cv2.INTER_LINEAR + cv2.WARP_INVERSE_MAP
    for im, H in zip(used, Hs):
        acc += cv2.warpPerspective(im.astype(np.float32), H, (w, h), flags=flags)
        weight += cv2.warpPerspective(np.ones((h, w), np.float32), H, (w, h), flags=flags)[..., None]
    return np.clip(acc / np.maximum(weight, 1e-6), 0, 255).astype(np.uint8), ref, 1


def focus_stack(group, scan, work):
    """Apilado de enfoque -> imagen todo-enfocada alineada con el plano central (+ mapa de profundidad)."""
    from depth_from_focus import depth_from_focus  # mismo directorio
    shots = [(r["_focus"], cv2.imread(str(scan / r["file"])), r) for r in group]
    shots = [s for s in shots if s[1] is not None and s[0]]
    if len(shots) < 2:
        return (shots[0][1], shots[0][2]) if shots else (None, None)
    shots.sort(key=lambda s: -s[0])
    ref = shots[len(shots) // 2][2]  # el plano central define la pose y la calibración
    # 4096 px cubre 12 MP sin reducir y acota la RAM del servidor (~1 GB por apilado de 9).
    depth, conf, aif = depth_from_focus([s[0] for s in shots], [s[1] for s in shots], max_side=4096)
    if aif.shape[:2] != shots[0][1].shape[:2]:
        aif = cv2.resize(aif, (shots[0][1].shape[1], shots[0][1].shape[0]), interpolation=cv2.INTER_CUBIC)
    ddir = work / "depth" / ref["camera_id"]
    ddir.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(ddir / f"{ref['t_ns']}_mm.png"), np.where(conf > 0.5, depth * 1000, 0).clip(0, 65535).astype(np.uint16))
    return aif, ref


def prepare_images(scan, work, frames, meta, filter_mode="wallis", denoise=-1, color_norm=True, superres=2):
    color_dir, feat_dir = work / "images", work / "images_feat"
    records = []  # (nombre relativo, frame representativo)
    n_hdr = n_stack = n_burst = n_sr = 0
    for g in group_brackets(frames, meta):
        if len({r["_focus_target"] for r in g}) > 1:
            img, ref = focus_stack(g, scan, work)
            n_stack += 1
        elif len(g) > 1 and any(r["_burst"] is not None for r in g):
            img, k, sc = burst_merge([scan / r["file"] for r in g], superres)
            ref = dict(g[k] if k is not None else g[0])
            if sc > 1:
                # La imagen superresuelta es sc× mayor: su "cámara" para COLMAP también.
                ref["width"] = str(int(ref["width"]) * sc); ref["height"] = str(int(ref["height"]) * sc)
                n_sr += 1
            n_burst += 1
        else:
            ref = min(g, key=lambda r: abs(r["_ev"]))  # la de 0 EV como referencia de pose
            img = fuse([scan / r["file"] for r in g])
            n_hdr += len(g) > 1
        if img is None:
            continue
        cam = ref["camera_id"]
        name = f"{cam}/{ref['t_ns']}.jpg"
        out = color_dir / name
        out.parent.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(out), img, [cv2.IMWRITE_JPEG_QUALITY, 97])
        records.append((name, ref))
    # Color/exposición comunes (textura) y, a partir de ahí, imágenes realzadas para los puntos.
    if color_norm:
        normalize_colors([color_dir / n for n, _ in records])
    for name, _ in records:
        img = cv2.imread(str(color_dir / name))
        f = feat_dir / name
        f.parent.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(f), enhance_for_features(img, filter_mode, denoise), [cv2.IMWRITE_JPEG_QUALITY, 97])
    print(f"[hdr] {len(frames)} fotos -> {len(records)} imágenes ({n_hdr} fusiones HDR, "
          f"{n_stack} apilados de enfoque, {n_burst} ráfagas anti-movimiento, {n_sr} con superresolución)")
    return records


# --------------------------------------------------------------------------- COLMAP

# Rutas configurables (en Windows COLMAP/OpenMVS no suelen estar en el PATH): las fija el
# servidor (tisma_server.py) o las variables de entorno TISMA_COLMAP / TISMA_OPENMVS.
COLMAP_EXE = os.environ.get("TISMA_COLMAP", "colmap")
OPENMVS_DIR = os.environ.get("TISMA_OPENMVS", "")


def run_tool(cmd, cwd=None):
    """Ejecuta una herramienta externa volcando su salida en el registro (stdout actual)."""
    cmd = [str(c) for c in cmd]
    print("[run]", " ".join(cmd), flush=True)
    p = subprocess.Popen(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                         errors="replace")
    for line in p.stdout:
        print("   ", line.rstrip(), flush=True)
    if p.wait() != 0:
        raise RuntimeError(f"{Path(cmd[0]).name} terminó con código {p.returncode}")


def colmap(*args):
    run_tool([COLMAP_EXE, *args])


def camera_params(session, cam_id, width, height):
    """
    OPENCV: fx,fy,cx,cy,k1,k2,p1,p2 llevados del array del sensor al tamaño de la imagen.

    Desde Android 9 los intrínsecos se refieren al array *pre-corrección* (la app desactiva la
    corrección de distorsión, así que la salida también). Si la imagen tiene otro aspecto que
    el array, la cámara recorta centrado y escala de forma uniforme.
    """
    cam = next((c for c in session["cameras"] if c["id"] == cam_id), None)
    arr = cam and (cam.get("pre_correction_active_array") or cam.get("active_array"))
    if not cam or "intrinsics_fx_fy_cx_cy_s" not in cam or not arr:
        return None
    fx, fy, cx, cy, _ = cam["intrinsics_fx_fy_cx_cy_s"]
    if fx <= 0:
        return None
    l, t, r, b = arr
    W, H = r - l, b - t
    s = max(width / W, height / H)          # escala uniforme con recorte centrado
    ox, oy = (W * s - width) / 2, (H * s - height) / 2
    k1, k2, _k3, p1, p2 = cam.get("distortion_k1_k2_k3_p1_p2", [0, 0, 0, 0, 0])
    return [fx * s, fy * s, cx * s - ox, cy * s - oy, k1, k2, p1, p2]


def run_sfm(work, session, records, matcher):
    db = work / "database.db"
    if db.exists():
        db.unlink()
    # Una cámara COLMAP por (objetivo, enfoque): al cambiar el enfoque cambian la focal
    # efectiva y la distorsión, así que cada pasada (lejos/cerca) se calibra por separado.
    by_cam = defaultdict(list)
    for name, fr in records:
        f = fr.get("_focus") or 0.0
        # También por tamaño: las imágenes superresueltas son otra "cámara" (2× la focal en px).
        by_cam[(fr["camera_id"], round(f / 0.25) * 0.25, fr["width"])].append((name, fr))
    for (cam, fbucket, _w), items in by_cam.items():
        lst = work / f"list_{cam}_f{fbucket:.2f}_{_w}.txt"
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
            print(f"[sfm] cámara {cam} enfoque {fbucket:.2f} D: intrínsecos de fábrica {params[:4]}")
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
    lines = [l for l in (d / "images.txt").read_text().splitlines() if not l.startswith("#")]
    for l, obs_line in zip(lines[0::2], lines[1::2]):
        if not l.strip():
            continue
        p = l.split()
        q, t = np.array(list(map(float, p[1:5]))), np.array(list(map(float, p[5:8])))
        o = np.array(list(map(float, obs_line.split())) or [0, 0, -1], float).reshape(-1, 3)
        imgs[p[9]] = dict(R=qvec2rot(q), t=t, cam=int(p[8]), obs=o[o[:, 2] >= 0])
    return cams, imgs


def read_points3d(d: Path):
    pts = {}
    p = d / "points3D.txt"
    if p.exists():
        for l in p.read_text().splitlines():
            if l and not l.startswith("#"):
                v = l.split()
                pts[int(v[0])] = np.array(list(map(float, v[1:4])))
    return pts


def scale_from_focus(work, imgs, pts):
    """
    Escala métrica a partir de la distancia medida con el ENFOQUE (barridos de enfoque).
    Para cada punto 3D visto en una foto con mapa de profundidad por enfoque:
    ratio = profundidad por enfoque (m) / profundidad SfM (unidades del modelo). La mediana es
    la escala. Su precisión depende de la calibración del enfoque del móvil (session.json).
    Devuelve (escala, dispersión relativa, nº de puntos) o (None, None, 0).
    """
    ratios = []
    for name, im in imgs.items():
        cam, stem = name.split("/")[0], Path(name).stem
        dpath = work / "depth" / cam / f"{stem}_mm.png"
        if not dpath.exists() or len(im["obs"]) == 0:
            continue
        depth = cv2.imread(str(dpath), cv2.IMREAD_UNCHANGED)
        color = cv2.imread(str(work / "images" / name), cv2.IMREAD_GRAYSCALE)
        if depth is None or color is None:
            continue
        sy, sx = depth.shape[0] / color.shape[0], depth.shape[1] / color.shape[1]
        for x, y, pid in im["obs"]:
            X = pts.get(int(pid))
            if X is None:
                continue
            z = (im["R"] @ X + im["t"])[2]
            yi, xi = int(y * sy), int(x * sx)
            if z <= 0 or not (0 <= yi < depth.shape[0] and 0 <= xi < depth.shape[1]):
                continue
            dmm = float(depth[yi, xi])
            if dmm > 0:
                ratios.append(dmm / 1000.0 / z)
    if len(ratios) < 20:
        return None, None, len(ratios)
    r = np.array(ratios)
    med = np.median(r)
    mad = np.median(np.abs(r - med))
    r = r[np.abs(r - med) < 4 * mad + 1e-12]       # fuera puntos en bordes/sin textura
    med = float(np.median(r))
    return med, float(1.4826 * np.median(np.abs(r - med)) / med / np.sqrt(len(r))), len(r)


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


def scale_from_ins(imgs, records, scan=None):
    """Escala por alineación SfM↔INS. Usa la trayectoria suavizada entre paradas si existe."""
    ref = {name: fr for name, fr in records}
    tt = Ps = None
    if scan is not None:
        from ins_accuracy import read_trajectory, zupt_smooth  # mismo directorio
        tt, Ps = zupt_smooth(read_trajectory(scan))
        if len(tt) == 0:
            tt = None
    src, dst = [], []
    for name, im in imgs.items():
        fr = ref.get(name)
        if fr and fr.get("px"):
            src.append(-im["R"].T @ im["t"])  # centro de cámara SfM
            if tt is not None:
                t = int(fr["t_ns"]) * 1e-9
                dst.append([np.interp(t, tt, Ps[:, k]) for k in range(3)])
            else:
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
        def mvs(tool, *a):
            exe = str(Path(OPENMVS_DIR) / tool) if OPENMVS_DIR else tool
            run_tool([exe, *a], cwd=und)
        mvs("InterfaceCOLMAP", "-i", ".", "-o", "scene.mvs", "--image-folder", "images")
        mvs("DensifyPointCloud", "scene.mvs", "--resolution-level", 0)
        mvs("ReconstructMesh", "scene_dense.mvs")
        mvs("RefineMesh", "scene_dense_mesh.mvs", "--resolution-level", 0)
        mvs("TextureMesh", "scene_dense_mesh_refine.mvs", "--export-type", "obj")
    print(f"[dense] resultado en {und if mode == 'openmvs' else work}")


# --------------------------------------------------------------------------- main


def _utf8_console():
    """Consola de Windows (cp1252) no puede imprimir ↔, ≈, ³…: forzar UTF-8 sin romper nunca."""
    for stream in (sys.stdout, sys.stderr, sys.__stdout__, sys.__stderr__):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass

def main(argv=None):
    _utf8_console()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("scan", type=Path, help="carpeta scan_* o .zip exportado por la app")
    ap.add_argument("--work", type=Path, default=Path("tisma_work"))
    ap.add_argument("--mat", help="JSON del tapete (make_scan_mat.py) para escala ArUco")
    ap.add_argument("--filter", choices=["wallis", "clahe", "none"], default="wallis",
                    help="realce de las imágenes usadas para detectar puntos (wallis = clásico de fotogrametría)")
    ap.add_argument("--no-clahe", action="store_true", help="(compatibilidad) equivale a --filter none")
    ap.add_argument("--denoise", type=float, default=-1, help="quitar ruido antes de realzar: -1 automático (según el ruido medido), 0 no, >0 fuerza fija")
    ap.add_argument("--no-color-norm", action="store_true", help="no igualar exposición/color entre fotos")
    ap.add_argument("--superres", type=int, default=2, help="superresolución de las ráfagas (2 = doble; 1 = sólo promediar)")
    ap.add_argument("--matcher", choices=["exhaustive", "sequential"], default="exhaustive")
    ap.add_argument("--dense", choices=["none", "colmap", "openmvs"], default="none")
    ap.add_argument("--prepare-only", action="store_true", help="sólo HDR + realce, sin COLMAP")
    ap.add_argument("--no-post", action="store_true", help="no postprocesar la nube densa (limpieza, malla, STL)")
    a = ap.parse_args(argv)

    a.work.mkdir(parents=True, exist_ok=True)
    scan = load_session(a.scan, a.work)
    session = json.loads((scan / "session.json").read_text())
    frames, meta = drop_blurry(scan, read_frames(scan)), read_meta(scan)
    qs = scan / "quality_summary.json"
    if qs.exists():
        q = json.loads(qs.read_text())
        print(f"[quality] cobertura {q['coverage'] * 100:.0f} %, listo={q['ready']}")
        for h in q.get("hints", []):
            print(f"[quality]   aviso: {h}")
    print(f"[load] {scan.name}: {len(frames)} fotos, {session['device']['manufacturer']} {session['device']['model']}")
    records = prepare_images(scan, a.work, frames, meta, "none" if a.no_clahe else a.filter,
                             a.denoise, not a.no_color_norm, a.superres)
    if a.prepare_only:
        return
    if not (shutil.which(COLMAP_EXE) or Path(COLMAP_EXE).exists()):
        raise SystemExit(f"No se encuentra COLMAP ({COLMAP_EXE}): https://colmap.github.io/install.html")

    model = run_sfm(a.work, session, records, a.matcher)
    txt = a.work / "sparse_txt"
    txt.mkdir(exist_ok=True)
    colmap("model_converter", "--input_path", model, "--output_path", txt, "--output_type", "TXT")

    # INS -> calidad final: fotos mal registradas (su giro contradice al giróscopo) fuera del
    # modelo antes del denso, y la gravedad para orientar la pieza y elegir bien la mesa.
    from ins_accuracy import gravity_and_outliers
    up, bad, rot_med = gravity_and_outliers(scan, txt)
    if rot_med is not None:
        print(f"[ins] giro SfM vs giróscopo: error mediano {rot_med:.2f}° · {len(bad)} fotos mal registradas")
    if bad:
        lst = a.work / "mal_registradas.txt"
        lst.write_text("\n".join(bad) + "\n")
        clean = a.work / "sparse_clean"
        clean.mkdir(exist_ok=True)
        colmap("image_deleter", "--input_path", model, "--output_path", clean, "--image_names_path", lst)
        colmap("bundle_adjuster", "--input_path", clean, "--output_path", clean)
        model = clean
        colmap("model_converter", "--input_path", model, "--output_path", txt, "--output_type", "TXT")
    cams, imgs = read_model_txt(txt)
    print(f"[sfm] {len(imgs)}/{len(records)} imágenes registradas")

    # Todas las fuentes de escala disponibles (se usa la mejor y se muestran las demás para
    # contrastarlas): tapete ArUco > distancia por enfoque > trayectoria INS.
    sources = []
    if a.mat:
        s_a, rel = scale_from_aruco(a.work, cams, imgs, a.mat)
        if s_a:
            sources.append((s_a, f"ArUco (dispersión {rel * 100:.2f} %)"))
    s_f, rel_f, n_f = scale_from_focus(a.work, imgs, read_points3d(txt))
    if s_f:
        calib = {c["id"]: c.get("focus_distance_calibration", "?") for c in session["cameras"]}
        sources.append((s_f, f"distancia por enfoque ({n_f} puntos, ±{rel_f * 100:.2f} % estadístico; "
                             f"calibración del foco: {', '.join(sorted(set(calib.values())))})"))
    s_i = scale_from_ins(imgs, records, scan)
    if s_i:
        sources.append((s_i, "trayectoria INS suavizada (aprox. 5-10 %; mídelo con ins_accuracy.py)"))
    for k, (sv, desc) in enumerate(sources):
        diff = f"  ({(sv / sources[0][0] - 1) * 100:+.1f} % vs la elegida)" if k else "  ← elegida"
        print(f"[scale] {sv:.6f} m/unidad · {desc}{diff}")
    s, src = sources[0] if sources else (None, None)
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

    # Postprocesado de la nube densa -> pieza limpia, cerrada y en mm (STL para imprimir).
    cloud = {"colmap": a.work / "fused.ply", "openmvs": a.work / "undistorted" / "scene_dense.ply"}.get(a.dense)
    if cloud is not None and cloud.exists() and not a.no_post:
        from postprocess import postprocess
        centers = [(-im["R"].T @ im["t"]) * (s or 1.0) for im in imgs.values()]
        postprocess(cloud, a.work / "pieza", units="m" if s else "unit", up=up, cameras=centers)


if __name__ == "__main__":
    main()
