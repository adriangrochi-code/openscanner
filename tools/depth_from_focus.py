#!/usr/bin/env python3
"""
Profundidad por enfoque (depth from focus) a partir de los "Barrido foco" de Tisma Scanner.

Idea: en un barrido el objetivo enfoca de cerca a lejos. Cada píxel está más nítido en la
foto cuyo plano de enfoque coincide con su distancia. Midiendo la nitidez local en cada foto
y buscando el máximo se obtiene un mapa de profundidad y, de paso, una imagen todo-enfocada.

Limitaciones honestas:
  * Necesita textura (igual que la fotogrametría): sobre el tapete o con spray mate.
  * La precisión depende de la profundidad de campo y de la calibración del foco que reporta
    el móvil (session.json -> focus_distance_calibration). "uncalibrated" = sólo orden
    relativo (qué está delante/detrás), no milímetros fiables.
  * Funciona mejor cerca (10-40 cm), donde la profundidad de campo es pequeña.

Uso:
    python3 depth_from_focus.py scan_xxx/ --out dff/
Salida por barrido: depth_mm.png (16 bits), depth_color.jpg, confidence.png, all_in_focus.jpg
"""
import argparse
import sys
import csv
import json
from collections import defaultdict
from pathlib import Path

import cv2
import numpy as np


def load_sweeps(scan: Path):
    meta = {}
    for line in (scan / "capture_meta.jsonl").read_text().splitlines():
        if line.strip():
            m = json.loads(line)
            if "focus_sweep_target_diopters" in m:
                meta[(m["camera_id"], int(m["t_ns"]))] = m
    sweeps = defaultdict(list)
    with open(scan / "frames.csv") as f:
        for fr in csv.DictReader(f):
            key = (fr["camera_id"], int(fr["t_ns"]))
            m = meta.get(key) or next((v for k, v in meta.items()
                                       if k[0] == key[0] and abs(k[1] - key[1]) < 2_000_000), None)
            if m:
                # Distancia real reportada por el objetivo (mejor que la pedida).
                d = m.get("focus_distance_diopters", m["focus_sweep_target_diopters"])
                sweeps[(fr["camera_id"], fr["trigger"])].append((d, scan / fr["file"], m.get("lens_moving", False)))
    return sweeps


def align_to(ref_gray, img, img_gray):
    """Compensa el 'focus breathing' (cambio de aumento al enfocar) con ECC afín."""
    warp = np.eye(2, 3, dtype=np.float32)
    s = 0.25
    small_r = cv2.resize(ref_gray, None, fx=s, fy=s)
    small_i = cv2.resize(img_gray, None, fx=s, fy=s)
    try:
        _, warp = cv2.findTransformECC(small_r, small_i, warp, cv2.MOTION_AFFINE,
                                       (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 100, 1e-5), None, 5)
    except cv2.error:
        return img, img_gray
    warp[:, 2] /= s
    flags = cv2.INTER_LINEAR + cv2.WARP_INVERSE_MAP
    h, w = ref_gray.shape
    return (cv2.warpAffine(img, warp, (w, h), flags=flags, borderMode=cv2.BORDER_REFLECT),
            cv2.warpAffine(img_gray, warp, (w, h), flags=flags, borderMode=cv2.BORDER_REFLECT))


def focus_measure(gray, win):
    """Sum-Modified-Laplacian (Nayar): robusto y rápido."""
    g = gray.astype(np.float32)
    kx = np.array([[0, 0, 0], [-1, 2, -1], [0, 0, 0]], np.float32)
    ml = np.abs(cv2.filter2D(g, -1, kx)) + np.abs(cv2.filter2D(g, -1, kx.T))
    return cv2.boxFilter(ml, -1, (win, win))


def depth_from_focus(stack_diopters, images, win=9, max_side=2000):
    order = np.argsort(stack_diopters)[::-1]  # cerca (más dioptrías) -> lejos
    d = np.asarray(stack_diopters, np.float64)[order]
    imgs = [images[i] for i in order]
    scale = min(1.0, max_side / max(imgs[0].shape[:2]))
    if scale < 1:
        imgs = [cv2.resize(im, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA) for im in imgs]
    ref = len(imgs) // 2
    ref_gray = cv2.cvtColor(imgs[ref], cv2.COLOR_BGR2GRAY).astype(np.float32)
    aligned, fm = [], []
    for im in imgs:
        g = cv2.cvtColor(im, cv2.COLOR_BGR2GRAY).astype(np.float32)
        im, g = align_to(ref_gray, im, g)
        aligned.append(im)
        fm.append(focus_measure(g, win))
    fm = np.stack(fm)                      # (N, H, W)
    idx = np.argmax(fm, axis=0)
    n = len(d)
    # Interpolación parabólica entre vecinos para precisión sub-paso.
    i0 = np.clip(idx, 1, n - 2)
    ym, y0, yp = (np.take_along_axis(fm, (i0 + k)[None], 0)[0] for k in (-1, 0, 1))
    denom = ym - 2 * y0 + yp
    off = np.where(np.abs(denom) > 1e-6, 0.5 * (ym - yp) / denom, 0).clip(-0.5, 0.5)
    di = np.interp(i0 + off, np.arange(n), d)
    di = np.where((idx == 0) | (idx == n - 1), d[idx], di)
    depth_m = 1.0 / np.maximum(di, 1e-3)
    # Confianza: lo marcado que es el pico frente a la media del barrido.
    conf = (fm.max(0) - fm.mean(0)) / (fm.mean(0) + 1e-3)
    # Un pico en el primer o el último plano no es una medida: la nitidez sigue subiendo hacia afuera del barrido
    # (la mesa o el tapete detrás de la pieza) y la profundidad queda recortada al extremo, con confianza alta. Esos
    # puntos entraban en la escala por enfoque y la sesgaban, sin aviso.
    if n >= 3:
        conf = np.where((idx == 0) | (idx == n - 1), 0.0, conf)
    aif = np.take_along_axis(np.stack(aligned), idx[None, ..., None], 0)[0]
    return depth_m, conf, aif



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
    ap.add_argument("scan", type=Path)
    ap.add_argument("--out", type=Path, default=Path("dff"))
    ap.add_argument("--win", type=int, default=9, help="ventana de nitidez (px)")
    ap.add_argument("--min-conf", type=float, default=0.5)
    a = ap.parse_args()

    session = json.loads((a.scan / "session.json").read_text())
    calib = {c["id"]: c.get("focus_distance_calibration", "?") for c in session["cameras"]}
    sweeps = load_sweeps(a.scan)
    if not sweeps:
        raise SystemExit("No hay barridos de foco en este escaneo (botón 'Barrido foco').")
    for (cam, trig), shots in sweeps.items():
        shots = [s for s in shots if not s[2]] or shots  # descarta fotos con el objetivo en movimiento
        # Los planos movidos pueden haberse borrado ya en el móvil.
        loaded = [(s[0], cv2.imread(str(s[1]))) for s in shots]
        loaded = [(d, im) for d, im in loaded if im is not None]
        if len(loaded) < 3:
            continue
        depth, conf, aif = depth_from_focus([d for d, _ in loaded], [im for _, im in loaded], a.win)
        out = a.out / f"cam{cam}_trig{trig}"
        out.mkdir(parents=True, exist_ok=True)
        valid = conf >= a.min_conf
        mm = np.where(valid, depth * 1000, 0).clip(0, 65535).astype(np.uint16)
        cv2.imwrite(str(out / "depth_mm.png"), mm)
        lo, hi = np.percentile(depth[valid], [2, 98]) if valid.any() else (0, 1)
        vis = ((depth - lo) / max(hi - lo, 1e-6)).clip(0, 1)
        color = cv2.applyColorMap((255 - vis * 255).astype(np.uint8), cv2.COLORMAP_TURBO)
        color[~valid] = 0
        cv2.imwrite(str(out / "depth_color.jpg"), color)
        cv2.imwrite(str(out / "confidence.png"), (conf / max(conf.max(), 1e-6) * 255).astype(np.uint8))
        cv2.imwrite(str(out / "all_in_focus.jpg"), aif)
        print(f"{out}: {len(loaded)} planos, profundidad {lo * 100:.1f}-{hi * 100:.1f} cm, "
              f"{valid.mean() * 100:.0f}% píxeles fiables, calibración del foco: {calib.get(cam)}")


if __name__ == "__main__":
    main()
