"""
Puntos y emparejado con IA (ALIKED + LightGlue) para COLMAP, en lugar de SIFT.

Por qué: en piezas lisas/mate SIFT encuentra pocos puntos y muchos emparejamientos falsos;
ALIKED (BSD-3) + LightGlue (Apache-2.0) encuentran más y mejores, y ambos permiten uso comercial.
(SuperPoint original NO: su licencia es sólo para investigación no comercial.)

Cómo encaja: COLMAP crea la base de datos (cámaras e imágenes con sus intrínsecos) y aquí se
sustituyen los puntos de cada imagen y se escriben los pares ya verificados geométricamente
(RANSAC de la matriz fundamental); luego el mapper de COLMAP (o GLOMAP) reconstruye igual.

Motores:
  - "aliked": ALIKED + LightGlue (necesita `pip install torch lightglue`; GPU si hay).
  - "cv-sift": SIFT de OpenCV + ratio test (sin dependencias; para pruebas y de reserva).
"""
import sqlite3
from pathlib import Path

import numpy as np

MAX_ID = 2147483647


def pair_id(a, b):
    a, b = (a, b) if a < b else (b, a)
    return a * MAX_ID + b


class CvSift:
    name = "cv-sift"

    def __init__(self, max_kp=8000):
        import cv2
        self.cv2 = cv2
        self.sift = cv2.SIFT_create(nfeatures=max_kp)
        self.bf = cv2.BFMatcher(cv2.NORM_L2)

    def extract(self, gray):
        kps, desc = self.sift.detectAndCompute(gray, None)
        if desc is None:
            return np.zeros((0, 2), np.float32), None
        return np.array([k.pt for k in kps], np.float32), desc

    def match(self, fa, fb):
        (ka, da), (kb, db) = fa, fb
        if da is None or db is None or len(da) < 2 or len(db) < 2:
            return np.zeros((0, 2), np.uint32)
        out = []
        for m in self.bf.knnMatch(da, db, k=2):
            if len(m) == 2 and m[0].distance < 0.8 * m[1].distance:
                out.append((m[0].queryIdx, m[0].trainIdx))
        return np.array(out, np.uint32).reshape(-1, 2)


class AlikedLightGlue:
    """ALIKED + LightGlue (paquete `lightglue` de cvg, pesos Apache-2.0 / BSD-3)."""
    name = "aliked"

    def __init__(self, max_kp=4096, max_side=1600):
        import torch
        from lightglue import ALIKED, LightGlue
        self.torch = torch
        self.dev = "cuda" if torch.cuda.is_available() else "cpu"
        self.ext = ALIKED(max_num_keypoints=max_kp).eval().to(self.dev)
        self.matcher = LightGlue(features="aliked").eval().to(self.dev)
        self.max_side = max_side

    def extract(self, gray):
        import cv2
        h, w = gray.shape[:2]
        s = min(1.0, self.max_side / max(h, w))
        img = cv2.resize(gray, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA) if s < 1 else gray
        rgb = np.repeat(img[None].astype(np.float32) / 255.0, 3, axis=0)
        with self.torch.inference_mode():
            f = self.ext.extract(self.torch.from_numpy(rgb).to(self.dev))
        kp = f["keypoints"][0].cpu().numpy() / s
        return kp.astype(np.float32), f

    def match(self, fa, fb):
        (_, a), (_, b) = fa, fb
        with self.torch.inference_mode():
            r = self.matcher({"image0": a, "image1": b})
        return r["matches"][0].cpu().numpy().astype(np.uint32).reshape(-1, 2)


def make_backend(name):
    """El motor pedido, o None si no está instalado (el llamador vuelve a SIFT de COLMAP)."""
    try:
        return AlikedLightGlue() if name == "aliked" else CvSift()
    except Exception as e:  # noqa: BLE001 - falta torch/lightglue o no hay pesos
        print(f"[features] {name} no disponible ({type(e).__name__}: {e})")
        return None


def verify(ka, kb, matches, min_inliers=15, thr_px=1.5):
    """RANSAC de la matriz fundamental: sólo los emparejamientos geométricamente coherentes."""
    import cv2
    if len(matches) < min_inliers:
        return None, None
    pa = ka[matches[:, 0]]; pb = kb[matches[:, 1]]
    method = getattr(cv2, "USAC_MAGSAC", cv2.FM_RANSAC)
    F, mask = cv2.findFundamentalMat(pa, pb, method, thr_px, 0.999, 10000)
    if F is None or mask is None or F.shape != (3, 3):
        return None, None
    inl = matches[mask.ravel().astype(bool)]
    return (inl, F) if len(inl) >= min_inliers else (None, None)


def run(db_path: Path, image_dir: Path, pairs, backend, log=print):
    """Sustituye puntos y emparejados de la base de datos COLMAP por los del motor.
    `pairs`: lista de (nombre_a, nombre_b) o None = todos con todos."""
    import cv2
    con = sqlite3.connect(str(db_path))
    ids = dict(con.execute("SELECT name, image_id FROM images"))
    feats = {}
    for name in ids:
        gray = cv2.imread(str(image_dir / name), cv2.IMREAD_GRAYSCALE)
        if gray is None:
            continue
        feats[name] = backend.extract(gray)
    con.execute("DELETE FROM keypoints"); con.execute("DELETE FROM descriptors")
    con.execute("DELETE FROM matches"); con.execute("DELETE FROM two_view_geometries")
    for name, (kp, _) in feats.items():
        # COLMAP mide desde la esquina del píxel: centro del píxel (0,0) = (0.5, 0.5).
        k = (kp + 0.5).astype(np.float32)
        con.execute("INSERT INTO keypoints (image_id, rows, cols, data) VALUES (?, ?, ?, ?)",
                    (ids[name], len(k), 2, k.tobytes()))
        con.execute("INSERT INTO descriptors (image_id, rows, cols, data) VALUES (?, ?, ?, ?)",
                    (ids[name], 0, 128, b""))
    cols = [r[1] for r in con.execute("PRAGMA table_info(two_view_geometries)")]
    names = sorted(feats)
    if pairs is None:
        pairs = [(a, b) for i, a in enumerate(names) for b in names[i + 1:]]
    good = total_inl = 0
    for a, b in pairs:
        if a not in feats or b not in feats:
            continue
        m = backend.match(feats[a], feats[b])
        ia, ib = ids[a], ids[b]
        if ia > ib:                       # COLMAP guarda el par con el id menor primero
            a, b, ia, ib = b, a, ib, ia
            m = m[:, ::-1].copy()
        pid = pair_id(ia, ib)
        con.execute("INSERT INTO matches (pair_id, rows, cols, data) VALUES (?, ?, ?, ?)",
                    (pid, len(m), 2, m.astype(np.uint32).tobytes()))
        inl, F = verify(feats[a][0], feats[b][0], m)
        if inl is None:
            continue
        row = {"pair_id": pid, "rows": len(inl), "cols": 2, "data": inl.astype(np.uint32).tobytes(),
               "config": 3,  # UNCALIBRATED: COLMAP estima la geometría con los intrínsecos al reconstruir
               "F": F.astype(np.float64).tobytes(), "E": np.eye(3).tobytes(), "H": np.eye(3).tobytes(),
               "qvec": np.array([1.0, 0, 0, 0]).tobytes(), "tvec": np.zeros(3).tobytes()}
        use = [c for c in cols if c in row]
        con.execute(f"INSERT INTO two_view_geometries ({', '.join(use)}) VALUES ({', '.join('?' * len(use))})",
                    [row[c] for c in use])
        good += 1; total_inl += len(inl)
    con.commit(); con.close()
    n_kp = int(np.mean([len(f[0]) for f in feats.values()])) if feats else 0
    log(f"[features] {backend.name}: {len(feats)} imágenes, ~{n_kp} puntos/imagen, "
        f"{good}/{len(pairs)} pares verificados, {total_inl // max(good, 1)} coincidencias por par")
    return good
