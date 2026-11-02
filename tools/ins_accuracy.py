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
    lines = [l for l in (model / "images.txt").read_text().splitlines() if l and not l.startswith("#")]
    for l in lines[0::2]:
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
    return t, out


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


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("scan", type=Path, help="carpeta del escaneo (frames.csv, ins_trajectory.csv)")
    ap.add_argument("model", type=Path, help="modelo COLMAP en TXT (mejor el métrico: sparse_metric_txt)")
    ap.add_argument("--not-metric", action="store_true", help="el modelo no está en metros (se estima la escala)")
    a = ap.parse_args()

    ins = read_frames(a.scan)
    sfm = read_sfm(a.model)
    pairs = []
    for name, (Wc, c) in sfm.items():
        cam, stem = name.split("/")[0], Path(name).stem
        key = (cam, int(stem)) if stem.isdigit() else None
        if key in ins:
            p, Wd = ins[key]
            pairs.append((key[1] * 1e-9, Wc, c, Wd, p))
    pairs.sort(key=lambda x: x[0])
    if len(pairs) < 10:
        raise SystemExit(f"Sólo {len(pairs)} fotos con pose SfM e INS: hacen falta ≥ 10.")
    times = np.array([x[0] for x in pairs])
    print(f"{len(pairs)} fotos comparadas, {times[-1] - times[0]:.0f} s de escaneo\n")

    # Calibración cámara↔IMU (rotación) a partir de los giros entre fotos consecutivas.
    A, B, w = [], [], []
    for (_, Wc1, _, Wd1, _), (_, Wc2, _, Wd2, _) in zip(pairs, pairs[1:]):
        ra, rb = rot_log(Wc1.T @ Wc2), rot_log(Wd1.T @ Wd2)
        if np.linalg.norm(ra) > np.radians(3):
            A.append(ra); B.append(rb); w.append(np.linalg.norm(ra))
    if len(A) < 3:
        raise SystemExit("Muy poco giro entre fotos para calibrar cámara↔IMU.")
    Yt = kabsch(np.array(A), np.array(B), np.array(w))     # rv_cámara = Yᵀ rv_dispositivo
    Y = Yt.T
    G = chordal_mean([Wc @ Yt @ Wd.T for _, Wc, _, Wd, _ in pairs])

    rel_rot = [rot_angle((Wc1.T @ Wc2).T @ (Yt @ (Wd1.T @ Wd2) @ Y))
               for (_, Wc1, _, Wd1, _), (_, Wc2, _, Wd2, _) in zip(pairs, pairs[1:])]
    # Residuo de orientación en el mundo SfM; su pendiente en el tiempo es la deriva (sobre todo rumbo).
    resid = np.array([rot_log((G @ Wd @ Y) @ Wc.T) for _, Wc, _, Wd, _ in pairs])
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
