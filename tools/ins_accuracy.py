#!/usr/bin/env python3
"""
Precisión REAL de la INS de Tisma Scanner, medida contra la fotogrametría.

La fotogrametría (COLMAP + escala ArUco) da la pose de cada foto con precisión de ~mm y
décimas de grado: es una "verdad" mucho mejor que la INS. Comparando ambas se obtiene el error
real de la INS mientras se mueve el móvil, en tu móvil y con tu forma de escanear:

  * Orientación: error del giro entre fotos consecutivas y deriva acumulada (grados).
  * Posición: error del desplazamiento entre dos fotos según el tiempo que las separa
    (0,5 s, 1 s, 2 s, 5 s, 10 s), y error absoluto tras alinear (ATE).
  * Lo mismo con la trayectoria SUAVIZADA entre paradas (ZUPT hacia atrás) para ver cuánto mejora.

No hace falta conocer la orientación cámara↔IMU: se calibra sola (hand-eye) con los propios datos.
Se ignora el brazo de palanca cámara↔IMU (~1-2 cm en un móvil): por debajo de eso no es fiable.

Uso (tras reconstruct.py con --mat para que el modelo esté en metros):
    python3 ins_accuracy.py out/raw/scan_xxx out/sparse_metric_txt
"""
import argparse
import sys
import csv
from pathlib import Path

import numpy as np


# --------------------------------------------------------------------------- rotaciones

def quat_to_rot(w, x, y, z):
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)],
        [2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)],
        [2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)]])


def rot_angle(R):
    return np.degrees(np.arccos(np.clip((np.trace(R) - 1) / 2, -1, 1)))


def rot_log(R):
    a = np.arccos(np.clip((np.trace(R) - 1) / 2, -1, 1))
    if a < 1e-9:
        return np.zeros(3)
    w = np.array([R[2, 1] - R[1, 2], R[0, 2] - R[2, 0], R[1, 0] - R[0, 1]]) / (2 * np.sin(a))
    return w * a


def kabsch(A, B, w=None):
    """R que minimiza Σ w |A_i - R B_i|²."""
    w = np.ones(len(A)) if w is None else w
    H = (B * w[:, None]).T @ A
    U, _, Vt = np.linalg.svd(H)
    D = np.diag([1, 1, np.sign(np.linalg.det(Vt.T @ U.T))])
    return Vt.T @ D @ U.T


def chordal_mean(Rs):
    U, _, Vt = np.linalg.svd(sum(Rs))
    D = np.diag([1, 1, np.sign(np.linalg.det(U @ Vt))])
    return U @ D @ Vt


def umeyama(src, dst, with_scale):
    mu_s, mu_d = src.mean(0), dst.mean(0)
    xs, xd = src - mu_s, dst - mu_d
    U, S, Vt = np.linalg.svd(xd.T @ xs / len(src))
    D = np.eye(3)
    if np.linalg.det(U) * np.linalg.det(Vt) < 0:
        D[2, 2] = -1
    R = U @ D @ Vt
    s = np.trace(np.diag(S) @ D) / ((xs ** 2).sum() / len(src)) if with_scale else 1.0
    return s, R, mu_d - s * R @ mu_s


# --------------------------------------------------------------------------- datos

def read_frames(scan: Path):
    out = {}
    with open(scan / "frames.csv") as f:
        for r in csv.DictReader(f):
            if r.get("px"):
                out[(r["camera_id"], int(r["t_ns"]))] = (
                    np.array([float(r["px"]), float(r["py"]), float(r["pz"])]),
                    quat_to_rot(float(r["qw"]), float(r["qx"]), float(r["qy"]), float(r["qz"])))
    return out


def read_sfm(model: Path):
    """nombre -> (R_mundo_desde_cámara, centro de cámara)."""
    out = {}
    # Dos líneas por foto; la segunda (puntos 2D) puede estar VACÍA: no filtrar líneas vacías.
    lines = [l for l in (model / "images.txt").read_text().splitlines() if not l.startswith("#")]
    for l in lines[0::2]:
        if not l.strip():
            continue
        p = l.split()
        R_cw = quat_to_rot(*map(float, p[1:5]))
        t = np.array(list(map(float, p[5:8])))
        out[p[9]] = (R_cw.T, -R_cw.T @ t)
    return out


def read_trajectory(scan: Path):
    rows = []
    p = scan / "ins_trajectory.csv"
    if p.exists():
        with open(p) as f:
            for r in csv.DictReader(f):
                rows.append((int(r["t_ns"]), np.array([float(r["px"]), float(r["py"]), float(r["pz"])]),
                             np.array([float(r["vx"]), float(r["vy"]), float(r["vz"])]), r["stationary"] == "1"))
    return rows


def zupt_smooth(traj):
    """
    Corrección hacia atrás entre paradas. Al llegar a una parada, la velocidad debería ser 0:
    la que queda (v_fin) es error acumulado durante el tramo. Suponiendo que creció linealmente,
    el error de posición es v_fin·T·τ²/2 (τ = fracción del tramo) y se resta; los tramos
    siguientes heredan la corrección total del tramo.
    Devuelve (tiempos, posiciones corregidas).
    """
    if not traj:
        return np.array([]), np.zeros((0, 3))
    t = np.array([r[0] for r in traj], float) * 1e-9
    p = np.array([r[1] for r in traj])
    v = np.array([r[2] for r in traj])
    still = np.array([r[3] for r in traj])
    out = p.copy()
    shift = np.zeros(3)
    i, n = 0, len(traj)
    while i < n:
        if still[i]:
            out[i] = p[i] - shift
            i += 1
            continue
        a = i
        while i < n and not still[i]:
            i += 1
        b = i - 1                       # última muestra en movimiento del tramo
        T = max(t[b] - t[a], 1e-6)
        v_end = v[b] if i < n else np.zeros(3)   # sin parada final, no se puede corregir
        tau = (t[a:b + 1] - t[a]) / T
        out[a:b + 1] = p[a:b + 1] - shift - v_end * T * (tau ** 2)[:, None] / 2
        shift = shift + v_end * T / 2
        if i < n:
            # The INS's own zero-velocity update at this stop already moved the position back (its Kalman corrects
            # position through the position-velocity covariance): the trajectory jumps between the last moving sample
            # and the first still one. Only what it left carries over; subtracting the whole error again
            # double-corrected every segment after the first stop. (With no jump, nothing changes.)
            shift = shift + (p[i] - p[b])
    return t, out


# --------------------------------------------------------------------------- uso en el pipeline

def match_pairs(scan: Path, model: Path):
    """(t, Wc, c, Wd, p, nombre) de cada foto con pose SfM y pose INS, ordenadas por tiempo."""
    ins = read_frames(scan)
    pairs = []
    for name, (Wc, c) in read_sfm(model).items():
        cam, stem = name.split("/")[0], Path(name).stem
        key = (cam, int(stem)) if stem.isdigit() else None
        if key in ins:
            p, Wd = ins[key]
            pairs.append((key[1] * 1e-9, Wc, c, Wd, p, name))
    pairs.sort(key=lambda x: x[0])
    return pairs


def hand_eye(pairs, min_turn_deg=3, iters=3):
    """
    Rotación cámara↔dispositivo (Y) y mundo INS→mundo SfM (G) a partir de los giros entre fotos
    consecutivas. Robusta: se estima, se descartan los pares que no encajan (fotos mal
    registradas, que además suelen tener giros enormes y dominarían la estimación) y se repite.
    """
    A, B = [], []
    for a, b in zip(pairs, pairs[1:]):
        ra, rb = rot_log(a[1].T @ b[1]), rot_log(a[3].T @ b[3])
        if np.linalg.norm(ra) > np.radians(min_turn_deg):
            A.append(ra); B.append(rb)
    if len(A) < 3:
        return None, None
    A, B = np.array(A), np.array(B)
    w = np.minimum(np.linalg.norm(A, axis=1), np.radians(30))      # que ningún par pese demasiado
    keep = np.ones(len(A), bool)
    for _ in range(iters):
        Yt = kabsch(A[keep], B[keep], w[keep])
        res = np.linalg.norm(A - (Yt @ B.T).T, axis=1)
        thr = max(3 * np.median(res[keep]), np.radians(1.0))
        new = res < thr
        if new.sum() < 3 or np.array_equal(new, keep):
            break
        keep = new
    # Mundo: media de las fotos coherentes (las incoherentes también estropearían G).
    Gs = [x[1] @ Yt @ x[3].T for x in pairs]
    G = chordal_mean(Gs)
    dev = np.array([rot_angle(Gi.T @ G) for Gi in Gs])
    G = chordal_mean([Gi for Gi, d in zip(Gs, dev) if d < max(3 * np.median(dev), 2.0)])
    return Yt.T, G


def gravity_and_outliers(scan: Path, model: Path, max_err_deg=5.0):
    """
    Para el postprocesado:
      * up: dirección "arriba" (contra la gravedad, de la INS) en coordenadas del modelo SfM;
      * bad: fotos cuyo giro respecto a sus vecinas contradice al giróscopo en más de
        max_err_deg en AMBOS lados (registro erróneo de la fotogrametría).
    Devuelve (up, bad, error_mediano_deg) o (None, [], None) si no hay datos suficientes.
    """
    pairs = match_pairs(scan, model)
    if len(pairs) < 10:
        return None, [], None
    Y, G = hand_eye(pairs)
    if G is None:
        return None, [], None
    Yt = Y.T
    errs = [rot_angle((a[1].T @ b[1]).T @ (Yt @ (a[3].T @ b[3]) @ Y)) for a, b in zip(pairs, pairs[1:])]
    bad = [pairs[i][5] for i in range(1, len(pairs) - 1) if errs[i - 1] > max_err_deg and errs[i] > max_err_deg]
    return G @ np.array([0.0, 0.0, 1.0]), bad, float(np.median(errs))


# --------------------------------------------------------------------------- análisis

BUCKETS = [0.5, 1, 2, 5, 10]


def position_errors(times, P, C, metric):
    s, R, tr = umeyama(P, C, with_scale=not metric)
    Pa = (s * (R @ P.T)).T + tr
    ate = np.sqrt(((Pa - C) ** 2).sum(1).mean())
    rel = {b: [] for b in BUCKETS}
    for i in range(len(times)):
        for j in range(i + 1, len(times)):
            dt = times[j] - times[i]
            b = min(BUCKETS, key=lambda x: abs(np.log(dt / x))) if dt > 0.2 else None
            if b and 0.7 * b <= dt <= 1.4 * b:
                rel[b].append(np.linalg.norm((Pa[j] - Pa[i]) - (C[j] - C[i])))
    return ate, s, {b: np.median(v) for b, v in rel.items() if len(v) >= 3}


def fmt(m):
    return f"{m * 1000:.1f} mm" if m < 0.01 else (f"{m * 100:.1f} cm" if m < 1 else f"{m:.2f} m")



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
    ap.add_argument("scan", type=Path, help="carpeta del escaneo (frames.csv, ins_trajectory.csv)")
    ap.add_argument("model", type=Path, help="modelo COLMAP en TXT (mejor el métrico: sparse_metric_txt)")
    ap.add_argument("--not-metric", action="store_true", help="el modelo no está en metros (se estima la escala)")
    a = ap.parse_args()

    pairs = match_pairs(a.scan, a.model)
    if len(pairs) < 10:
        raise SystemExit(f"Sólo {len(pairs)} fotos con pose SfM e INS: hacen falta ≥ 10.")
    _, bad, _ = gravity_and_outliers(a.scan, a.model)
    if bad:
        print(f"{len(bad)} fotos mal registradas por la fotogrametría (se excluyen): {', '.join(bad[:5])}"
              + (" …" if len(bad) > 5 else ""))
        pairs = [x for x in pairs if x[5] not in set(bad)]
    times = np.array([x[0] for x in pairs])
    print(f"{len(pairs)} fotos comparadas, {times[-1] - times[0]:.0f} s de escaneo\n")

    # Calibración cámara↔IMU (rotación), robusta, a partir de los giros entre fotos consecutivas.
    Y, G = hand_eye(pairs)
    if G is None:
        raise SystemExit("Muy poco giro entre fotos para calibrar cámara↔IMU.")
    Yt = Y.T

    rel_rot = [rot_angle((a_[1].T @ b_[1]).T @ (Yt @ (a_[3].T @ b_[3]) @ Y)) for a_, b_ in zip(pairs, pairs[1:])]
    # Residuo de orientación en el mundo SfM; su pendiente en el tiempo es la deriva (sobre todo rumbo).
    resid = np.array([rot_log((G @ x[3] @ Y) @ x[1].T) for x in pairs])
    slope = np.polyfit(times - times[0], np.degrees(resid), 1)[0]          # °/s por eje
    abs_rot = np.linalg.norm(np.degrees(resid - resid.mean(0)), axis=1)
    print("ORIENTACIÓN")
    print(f"  giro entre fotos consecutivas: error mediano {np.median(rel_rot):.2f}°, p90 {np.percentile(rel_rot, 90):.2f}°")
    print(f"  deriva: {np.linalg.norm(slope) * 60:.2f}°/min (rumbo {abs(slope[2]) * 60:.2f}°/min), "
          f"error máx. tras alinear {abs_rot.max():.2f}°\n")

    C = np.array([x[2] for x in pairs])
    P_raw = np.array([(G @ x[4]) for x in pairs])
    tt, Ps = zupt_smooth(read_trajectory(a.scan))
    variants = [("INS en tiempo real", P_raw)]
    if len(tt):
        P_s = np.array([[np.interp(t, tt, Ps[:, k]) for k in range(3)] for t in times])
        variants.append(("INS suavizada entre paradas", np.array([G @ p for p in P_s])))
    print("POSICIÓN (error del desplazamiento entre dos fotos separadas por…)")
    for name, P in variants:
        ate, s, rel = position_errors(times, P, C, metric=not a.not_metric)
        line = " · ".join(f"{b:g} s → {fmt(e)}" for b, e in rel.items())
        print(f"  {name}: {line}")
        print(f"    error absoluto (ATE) {fmt(ate)}" + (f", escala estimada {s:.3f}" if a.not_metric else ""))
    print("\nReferencia: la fotogrametría con tapete ArUco ronda 0,1-0,5 mm.")


if __name__ == "__main__":
    main()
