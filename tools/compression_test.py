#!/usr/bin/env python3
"""
¿Cuánto se puede comprimir sin perder calidad fotogramétrica? Mídelo con TUS fotos.

Para cada nivel de compresión recodifica pares de fotos vecinas de un escaneo y mide:
  * tamaño medio por foto (y el total estimado del escaneo)
  * puntos SIFT detectados
  * emparejamientos válidos (inliers RANSAC) entre las dos fotos del par: es lo que de verdad
    usa la fotogrametría. Si cae poco respecto al original, la compresión es "gratis".

Uso:
    python3 compression_test.py scan_xxx/ --pairs 8
Formatos: JPEG a varias calidades y WebP (siempre); AVIF si tu OpenCV lo soporta.
HEIC no lo escribe OpenCV: es ~similar a AVIF en tamaño y lo soporta el codificador del móvil.
"""
import argparse
import csv
from pathlib import Path

import cv2
import numpy as np

CODECS = [("original", None, None)] + \
    [(f"jpeg q{q}", ".jpg", [cv2.IMWRITE_JPEG_QUALITY, q]) for q in (95, 92, 90, 85, 80, 70)] + \
    [(f"webp q{q}", ".webp", [cv2.IMWRITE_WEBP_QUALITY, q]) for q in (95, 90)]
if hasattr(cv2, "IMWRITE_AVIF_QUALITY"):
    CODECS += [(f"avif q{q}", ".avif", [cv2.IMWRITE_AVIF_QUALITY, q]) for q in (90, 80)]


def recode(img, ext, params):
    ok, buf = cv2.imencode(ext, img, params)
    if not ok:
        return None, 0
    return cv2.imdecode(buf, cv2.IMREAD_COLOR), len(buf)


def inliers(sift, a, b):
    ka, da = sift.detectAndCompute(cv2.cvtColor(a, cv2.COLOR_BGR2GRAY), None)
    kb, db = sift.detectAndCompute(cv2.cvtColor(b, cv2.COLOR_BGR2GRAY), None)
    if da is None or db is None or len(ka) < 8 or len(kb) < 8:
        return len(ka or []), 0
    m = cv2.BFMatcher().knnMatch(da, db, k=2)
    good = [x[0] for x in m if len(x) == 2 and x[0].distance < 0.8 * x[1].distance]
    if len(good) < 8:
        return len(ka), 0
    pa = np.float32([ka[g.queryIdx].pt for g in good])
    pb = np.float32([kb[g.trainIdx].pt for g in good])
    _, mask = cv2.findFundamentalMat(pa, pb, cv2.FM_RANSAC, 1.0, 0.999)
    return len(ka), int(mask.sum()) if mask is not None else 0


def neighbour_pairs(scan: Path, n: int):
    with open(scan / "frames.csv") as f:
        rows = [r for r in csv.DictReader(f) if (scan / r["file"]).exists()]
    by_cam = {}
    for r in rows:
        by_cam.setdefault(r["camera_id"], []).append(r)
    cam = max(by_cam, key=lambda c: len(by_cam[c]))
    lst = sorted(by_cam[cam], key=lambda r: int(r["t_ns"]))
    step = max(1, (len(lst) - 1) // n)
    return [(scan / lst[i]["file"], scan / lst[i + 1]["file"]) for i in range(0, len(lst) - 1, step)][:n], len(rows)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("scan", type=Path)
    ap.add_argument("--pairs", type=int, default=6)
    ap.add_argument("--max-side", type=int, default=0, help="reducir para ir más rápido (0 = original)")
    a = ap.parse_args()

    pairs, total = neighbour_pairs(a.scan, a.pairs)
    if not pairs:
        raise SystemExit("No hay pares de fotos consecutivas en el escaneo.")
    sift = cv2.SIFT_create(nfeatures=16384)
    res = {name: [0, 0, 0, 0] for name, _, _ in CODECS}  # bytes, fotos, kp, inliers
    for pa, pb in pairs:
        ia, ib = cv2.imread(str(pa)), cv2.imread(str(pb))
        if a.max_side:
            s = a.max_side / max(ia.shape[:2])
            ia, ib = (cv2.resize(x, None, fx=s, fy=s, interpolation=cv2.INTER_AREA) for x in (ia, ib))
        for name, ext, params in CODECS:
            if ext is None:
                ra, rb, size = ia, ib, pa.stat().st_size + pb.stat().st_size
            else:
                ra, sa = recode(ia, ext, params)
                rb, sb = recode(ib, ext, params)
                if ra is None or rb is None:
                    continue
                size = sa + sb
            kp, inl = inliers(sift, ra, rb)
            r = res[name]
            r[0] += size; r[1] += 2; r[2] += kp; r[3] += inl

    base = res["original"]
    print(f"{len(pairs)} pares de {a.scan.name}; el escaneo tiene {total} fotos\n")
    print(f"{'formato':<12}{'MB/foto':>9}{'escaneo MB':>12}{'puntos':>9}{'inliers':>9}{'vs orig':>9}")
    for name, (b, n, kp, inl) in res.items():
        if n == 0:
            continue
        mb = b / n / 1e6
        rel = inl / base[3] * 100 if base[3] else 0
        flag = "" if rel >= 95 else ("  ← pierde algo" if rel >= 85 else "  ← pierde detalle")
        print(f"{name:<12}{mb:>9.2f}{mb * total:>12.0f}{kp / (n / 2):>9.0f}{inl / (n / 2):>9.0f}{rel:>8.0f}%{flag}")
    print("\nRegla práctica: el nivel más comprimido con ≥ 95 % de inliers es 'gratis'.")


if __name__ == "__main__":
    main()
