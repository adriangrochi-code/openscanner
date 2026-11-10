#!/usr/bin/env python3
"""
Genera un "tapete de escaneo" imprimible para Tisma Scanner.

Por qué: la fotogrametría necesita textura. Una pieza plana/opaca y lisa no tiene puntos
que emparejar, así que se apoya sobre este tapete, que aporta:
  * Ruido aleatorio multiescala (con buena respuesta a SIFT a cualquier distancia) para que
    la cámara se pueda localizar aunque la pieza no tenga textura.
  * Marcadores ArUco de tamaño conocido -> escala métrica exacta (mejor que la INS o el GPS).

Uso:
    python3 make_scan_mat.py --paper a4                 # tapete_a4.png, tapete_a4.pdf y tapete_a4.json
    python3 make_scan_mat.py --all --out-dir tapetes    # todos los tamaños, para publicarlos

Imprime el PDF a «tamaño real» / 100 % (sin «ajustar a la página») y mide con una regla la barra de
100 mm. El PNG lleva sus DPI, pero muchos programas lo escalan al imprimir: el PDF es lo seguro.

El tapete es un tablero de ajedrez (ChArUco) de casillas de 20 mm, igual en todas las hojas: en una hoja
más grande hay más casillas, no más grandes. Cada casilla blanca lleva un marcador ArUco de 15 mm con su
número; las otras, una textura de manchas para que la fotogrametría tenga puntos únicos. Como el marcador es
siempre el mismo, Tisma Cloud saca la escala sin que nadie le diga qué hoja se imprimió
(reconstruct.STANDARD_MAT), aunque la pieza tape parte del tablero.
También se genera <out>.json con las coordenadas (mm) de cada esquina de los marcadores.
"""
import argparse
import sys
import json
from pathlib import Path

import cv2
import numpy as np

# Hojas de impresora común, de la más chica a la más grande (mm, vertical).
PAPERS_MM = {"a6": (105, 148), "a5": (148, 210), "a4": (210, 297), "a3": (297, 420),
             "letter": (216, 279), "legal": (216, 356), "tabloid": (279, 432)}
PAPER_NAMES = {"a6": "A6", "a5": "A5", "a4": "A4", "a3": "A3", "letter": "Carta", "legal": "Oficio (Legal)",
               "tabloid": "Tabloide"}
# El tapete estándar (mismos datos en reconstruct.STANDARD_MAT).
DICTIONARY = "DICT_4X4_250"
SQUARE_MM = 20.0
MARKER_MM = 15.0
MARGIN_MM = 8.0
BAR_STRIP_MM = 9.0   # franja de abajo para la barra de verificación


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

def make_mat(paper, dpi=300, seed=7):
    """El tapete estándar en una hoja: (imagen en gris, datos del .json). Un tablero de ajedrez de casillas de
    SQUARE_MM, tantas como entren, centrado; en cada casilla blanca un marcador de MARKER_MM."""
    pw, ph = PAPERS_MM[paper]
    px_per_mm = dpi / 25.4
    W, H = int(round(pw * px_per_mm)), int(round(ph * px_per_mm))
    canvas = np.full((H, W), 255, np.uint8)
    # The "dark" squares carry the full multiscale texture (the old mat's), not a darkened one: with it at 33 %,
    # COLMAP registered 3 of 36 synthetic views (only repeated squares and look-alike codes to match); with it whole,
    # 36 of 36, and the mat's scale within 0.1-0.2 % of the exact one through COLMAP. It also takes less ink.
    dark = multiscale_noise(H, W, np.random.default_rng(seed))
    dictionary = cv2.aruco.getPredefinedDictionary(getattr(cv2.aruco, DICTIONARY))
    cols = int((pw - 2 * MARGIN_MM) // SQUARE_MM)
    rows = int((ph - 2 * MARGIN_MM - BAR_STRIP_MM) // SQUARE_MM)
    x0 = (pw - cols * SQUARE_MM) / 2
    y0 = (ph - BAR_STRIP_MM - rows * SQUARE_MM) / 2
    px = lambda mm: int(round(mm * px_per_mm))
    m_px, inset = px(MARKER_MM), (SQUARE_MM - MARKER_MM) / 2
    markers, mid = {}, 0
    for r in range(rows):
        for c in range(cols):
            x, y = x0 + c * SQUARE_MM, y0 + r * SQUARE_MM
            if (r + c) % 2 == 0:  # casilla de textura (la «negra» del tablero)
                canvas[px(y):px(y + SQUARE_MM), px(x):px(x + SQUARE_MM)] = \
                    dark[px(y):px(y + SQUARE_MM), px(x):px(x + SQUARE_MM)]
                continue
            if mid >= dictionary.bytesList.shape[0]:
                raise ValueError(f"{paper}: más casillas blancas que marcadores en {DICTIONARY}")
            mx, my = x + inset, y + inset
            canvas[px(my):px(my) + m_px, px(mx):px(mx) + m_px] = cv2.aruco.generateImageMarker(dictionary, mid, m_px)
            # Esquinas en el orden de OpenCV: sup-izq, sup-der, inf-der, inf-izq (mm, plano Z=0)
            markers[mid] = [[mx, my], [mx + MARKER_MM, my], [mx + MARKER_MM, my + MARKER_MM], [mx, my + MARKER_MM]]
            mid += 1

    # Barra de verificación de 100 mm (o de 50 mm donde no cabe) y cómo imprimir.
    bar_mm = 100 if pw - 2 * MARGIN_MM >= 100 else 50
    # Abajo de todo pero dentro del margen (8 mm): a 3 mm del borde, muchas impresoras la cortaban o no la imprimían,
    # y es lo que el tester mide. Hay lugar: el tablero termina al menos 17 mm antes del borde.
    bx, by = px(MARGIN_MM), px(ph - MARGIN_MM)
    cv2.rectangle(canvas, (bx, by - px(1.2)), (bx + px(bar_mm), by), 0, -1)
    text = f"{bar_mm} mm - {PAPER_NAMES[paper]} - imprimir a tamano real / 100 %"
    cv2.putText(canvas, text, (bx, by - px(2)), cv2.FONT_HERSHEY_SIMPLEX, 0.065 * px_per_mm * (0.8 if pw < 150 else 1),
                0, max(1, int(px_per_mm / 6)), cv2.LINE_AA)
    meta = {"dictionary": DICTIONARY, "square_mm": SQUARE_MM, "marker_mm": MARKER_MM, "paper": paper, "dpi": dpi,
            "squares": [cols, rows], "check_bar_mm": bar_mm, "markers_mm": {str(k): v for k, v in markers.items()}}
    return canvas, meta


def save_mat(canvas, meta, base):
    """<base>.png con sus DPI, <base>.pdf al tamaño exacto de la hoja y <base>.json."""
    from PIL import Image
    img = Image.fromarray(canvas)
    dpi = meta["dpi"]
    img.save(f"{base}.png", dpi=(dpi, dpi))
    img.save(f"{base}.pdf", resolution=float(dpi))
    with open(f"{base}.json", "w") as f:
        json.dump(meta, f, indent=2)


def main():
    _utf8_console()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--paper", choices=PAPERS_MM, default="a4")
    ap.add_argument("--all", action="store_true", help="todos los tamaños de hoja")
    ap.add_argument("--dpi", type=int, default=300)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--out-dir", default=".")
    a = ap.parse_args()
    out = Path(a.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    for paper in (PAPERS_MM if a.all else [a.paper]):
        canvas, meta = make_mat(paper, a.dpi, a.seed)
        save_mat(canvas, meta, out / f"tapete_{paper}")
        print(f"tapete_{paper}: {PAPERS_MM[paper][0]} x {PAPERS_MM[paper][1]} mm, {len(meta['markers_mm'])} "
              f"marcadores de {meta['marker_mm']:g} mm")


if __name__ == "__main__":
    main()
