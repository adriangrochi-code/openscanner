#!/usr/bin/env python3
"""
Genera un "tapete de escaneo" imprimible para Tisma Scanner.

Por qué: la fotogrametría necesita textura. Una pieza plana/opaca y lisa no tiene puntos
que emparejar, así que se apoya sobre este tapete, que aporta:
  * Ruido aleatorio multiescala (con buena respuesta a SIFT a cualquier distancia) para que
    la cámara se pueda localizar aunque la pieza no tenga textura.
  * Marcadores ArUco de tamaño conocido -> escala métrica exacta (mejor que la INS o el GPS).

Uso:
    python3 make_scan_mat.py --paper a4 --out tapete_a4.png
Imprime al 100 % (sin "ajustar a página") y mide con una regla la barra de 100 mm para
comprobarlo. Si no mide 100 mm, corrige con --marker-mm o reimprime.
Genera también <out>.json con las coordenadas (mm) de cada esquina de los marcadores.
"""
import argparse
import sys
import json

import cv2
import numpy as np

PAPERS_MM = {"a4": (210, 297), "a3": (297, 420), "letter": (216, 279)}


def multiscale_noise(h, w, rng):
    """Ruido de valor a varias escalas: manchas grandes (vistas de lejos) y finas (de cerca)."""
    img = np.zeros((h, w), np.float32)
    for cell, weight in [(64, 0.35), (24, 0.3), (9, 0.2), (3, 0.15)]:
        small = rng.random((max(2, h // cell), max(2, w // cell))).astype(np.float32)
        img += weight * cv2.resize(small, (w, h), interpolation=cv2.INTER_NEAREST if cell <= 3 else cv2.INTER_CUBIC)
    img = (img - img.min()) / (np.ptp(img) + 1e-9)
    # Contraste alto pero sin saturar, para que la impresora no "empaste".
    return (40 + img * 175).astype(np.uint8)



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
    ap.add_argument("--paper", choices=PAPERS_MM, default="a4")
    ap.add_argument("--dpi", type=int, default=300)
    ap.add_argument("--marker-mm", type=float, default=30.0, help="lado del marcador ArUco (mm)")
    ap.add_argument("--margin-mm", type=float, default=12.0)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--out", default="tapete.png")
    a = ap.parse_args()

    pw, ph = PAPERS_MM[a.paper]
    px_per_mm = a.dpi / 25.4
    W, H = int(round(pw * px_per_mm)), int(round(ph * px_per_mm))
    rng = np.random.default_rng(a.seed)
    canvas = multiscale_noise(H, W, rng)

    dictionary = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)
    m_px = int(round(a.marker_mm * px_per_mm))
    quiet = int(round(7 * px_per_mm))  # borde blanco alrededor del marcador
    # Marcadores en las esquinas y a mitad de los lados: siempre se ven varios.
    xs = [a.margin_mm, (pw - a.marker_mm) / 2, pw - a.margin_mm - a.marker_mm]
    ys = [a.margin_mm, (ph - a.marker_mm) / 2, ph - a.margin_mm - a.marker_mm]
    markers = {}
    mid = 0
    for yi, y in enumerate(ys):
        for xi, x in enumerate(xs):
            if xi == 1 and yi == 1:
                continue  # el centro queda libre para la pieza
            x0, y0 = int(round(x * px_per_mm)), int(round(y * px_per_mm))
            canvas[y0 - quiet:y0 + m_px + quiet, x0 - quiet:x0 + m_px + quiet] = 255
            canvas[y0:y0 + m_px, x0:x0 + m_px] = cv2.aruco.generateImageMarker(dictionary, mid, m_px)
            # Esquinas en el orden de OpenCV: sup-izq, sup-der, inf-der, inf-izq (mm, plano Z=0)
            markers[mid] = [[x, y], [x + a.marker_mm, y], [x + a.marker_mm, y + a.marker_mm], [x, y + a.marker_mm]]
            mid += 1

    # Barra de verificación de 100 mm.
    bx, by = int(a.margin_mm * px_per_mm), int((ph - a.margin_mm / 2) * px_per_mm)
    cv2.rectangle(canvas, (bx, by - 12), (bx + int(100 * px_per_mm), by), 0, -1)
    cv2.putText(canvas, "100 mm - imprimir al 100%  |  Tisma Scanner", (bx, by - 20),
                cv2.FONT_HERSHEY_SIMPLEX, 0.9, 0, 2, cv2.LINE_AA)

    cv2.imwrite(a.out, canvas)
    meta = {"dictionary": "DICT_4X4_50", "marker_mm": a.marker_mm, "paper": a.paper,
            "dpi": a.dpi, "markers_mm": {str(k): v for k, v in markers.items()}}
    with open(a.out.rsplit(".", 1)[0] + ".json", "w") as f:
        json.dump(meta, f, indent=2)
    print(f"{a.out}: {W}x{H}px, {len(markers)} marcadores de {a.marker_mm} mm")


if __name__ == "__main__":
    main()
