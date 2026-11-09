#!/usr/bin/env python3
"""A synthetic scan in the format of Tisma Scanner, for tests of the whole pipeline (tisma_scan, Tisma Cloud).

A box of 40 × 30 × 20 mm with a random texture on each face, on the printed mat of make_scan_mat.py (the chessboard
with ArUco markers, A5 by default; --mat none for a plain textured square), photographed by a pinhole camera from
rings around it (by default 3 heights × 24 views), with the poses a still phone would give: session.json (one camera
with its intrinsics), frames.csv (position in m and orientation of the phone, which looks along its -Z) and
capture_meta.jsonl. Not a photo: no blur, no noise, no lighting; enough for COLMAP to register the views and for the
dense cloud and the mesh.

    python3 make_synthetic_scan.py out.zip [--views 24] [--size 800x600] [--mat a5|a4|...|none]

The poses in frames.csv are exact, so the INS scale is too: in reconstruct.py's log, the INS line's "% vs la
elegida" is the error of the scale taken from the mat.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import zipfile
from pathlib import Path

import sys

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

BOX_MM = (40.0, 30.0, 20.0)
MAT_M = 0.20


def texture(rng: np.random.Generator, size: int = 512) -> np.ndarray:
    """Random spots at several scales: many distinct points for the matching, as the spray of a real scan."""
    img = np.zeros((size, size), np.float32)
    for cells in (8, 32, 128):
        noise = rng.random((cells, cells)).astype(np.float32)
        img += cv2.resize(noise, (size, size), interpolation=cv2.INTER_CUBIC)
    img = (img - img.min()) / (img.max() - img.min())
    colour = np.stack([img, np.roll(img, size // 3, 0), np.roll(img, size // 3, 1)], -1)
    return (colour * 255).astype(np.uint8)


def quads(mat_mm: tuple[float, float] = (MAT_M * 1000, MAT_M * 1000)) -> list[tuple[np.ndarray, ...]]:
    """The planes to draw: (origin, u axis, v axis, outward normal), in m, Z up, the box centred on the mat. The mat's
    v axis runs towards -Y, so its image is seen from above as printed (not mirrored)."""
    w, d, h = (v / 1000 for v in BOX_MM)
    x0, y0 = -w / 2, -d / 2
    mw, mh = (v / 1000 for v in mat_mm)
    mat = (np.array([-mw / 2, mh / 2, 0]), np.array([mw, 0, 0]), np.array([0, -mh, 0]), np.array([0, 0, 1.0]))
    faces = [
        (np.array([x0, y0, h]), np.array([w, 0, 0]), np.array([0, d, 0]), np.array([0, 0, 1.0])),      # top
        (np.array([x0, y0, 0]), np.array([w, 0, 0]), np.array([0, 0, h]), np.array([0, -1.0, 0])),     # front
        (np.array([x0, -y0, 0]), np.array([w, 0, 0]), np.array([0, 0, h]), np.array([0, 1.0, 0])),     # back
        (np.array([x0, y0, 0]), np.array([0, d, 0]), np.array([0, 0, h]), np.array([-1.0, 0, 0])),     # left
        (np.array([-x0, y0, 0]), np.array([0, d, 0]), np.array([0, 0, h]), np.array([1.0, 0, 0])),     # right
    ]
    return [mat] + faces


def look_at(centre: np.ndarray, target: np.ndarray) -> np.ndarray:
    """World-from-camera rotation (columns: the camera's x right, y down, z forward in the world)."""
    z = target - centre
    z /= np.linalg.norm(z)
    x = np.cross(z, np.array([0, 0, 1.0]))
    x /= np.linalg.norm(x)
    y = np.cross(z, x)
    return np.stack([x, y, z], 1)


def quaternion(r: np.ndarray) -> tuple[float, float, float, float]:
    """(w, x, y, z) of a rotation matrix."""
    w = np.sqrt(max(0.0, 1 + r[0, 0] + r[1, 1] + r[2, 2])) / 2
    if w > 1e-6:
        return (w, (r[2, 1] - r[1, 2]) / (4 * w), (r[0, 2] - r[2, 0]) / (4 * w), (r[1, 0] - r[0, 1]) / (4 * w))
    i = int(np.argmax(np.diag(r)))
    j, k = (i + 1) % 3, (i + 2) % 3
    s = np.sqrt(max(0.0, 1 + r[i, i] - r[j, j] - r[k, k])) * 2
    q = [0.0, 0.0, 0.0, 0.0]
    q[i + 1] = s / 4
    q[j + 1] = (r[j, i] + r[i, j]) / s
    q[k + 1] = (r[k, i] + r[i, k]) / s
    q[0] = (r[k, j] - r[j, k]) / s
    return tuple(q)


def render(k: np.ndarray, r_wc: np.ndarray, centre: np.ndarray, size: tuple[int, int], textures,
           planes_mm=None) -> np.ndarray:
    width, height = size
    r_cw = r_wc.T
    t = -r_cw @ centre
    img = np.full((height, width, 3), 40, np.uint8)
    planes = []
    for (origin, u, v, normal), tex in zip(planes_mm or quads(), textures):
        # Only the faces that look at the camera, drawn from the farthest.
        if normal @ (centre - (origin + (u + v) / 2)) <= 0:
            continue
        planes.append((np.linalg.norm(centre - (origin + (u + v) / 2)), origin, u, v, tex))
    for _, origin, u, v, tex in sorted(planes, key=lambda p: -p[0]):
        ny, nx = tex.shape[:2]
        corners = [origin, origin + u, origin + u + v, origin + v]
        if any((r_cw @ c + t)[2] < 0.01 for c in corners):
            continue
        # Texture pixel (a, b) -> world origin + a/nx u + b/ny v -> image.
        h = k @ np.stack([r_cw @ u / nx, r_cw @ v / ny, r_cw @ origin + t], 1)
        warped = cv2.warpPerspective(tex, h, (width, height), flags=cv2.INTER_AREA)
        mask = cv2.warpPerspective(np.full((ny, nx), 255, np.uint8), h, (width, height), flags=cv2.INTER_NEAREST)
        img[mask > 0] = warped[mask > 0]
    return img


def make(out: Path, views: int = 24, size: tuple[int, int] = (800, 600), seed: int = 0, mat: str = "a5") -> Path:
    rng = np.random.default_rng(seed)
    width, height = size
    f = 0.9 * width
    k = np.array([[f, 0, width / 2], [0, f, height / 2], [0, 0, 1.0]])
    planes = quads()
    textures = [texture(rng) for _ in planes]
    if mat != "none":
        import make_scan_mat  # same folder
        canvas, _ = make_scan_mat.make_mat(mat, dpi=200)
        planes = quads(make_scan_mat.PAPERS_MM[mat])
        textures[0] = cv2.cvtColor(canvas, cv2.COLOR_GRAY2BGR)
    target = np.array([0, 0, BOX_MM[2] / 2000])
    rows, meta, files = [], [], {}
    t_ns = 1_000_000_000
    n = 0
    for elevation in (30, 50, 70):
        for i in range(views):
            az = np.radians(360 * i / views + elevation)
            el = np.radians(elevation)
            dist = 0.16
            centre = target + dist * np.array([np.cos(el) * np.cos(az), np.cos(el) * np.sin(az), np.sin(el)])
            r_wc = look_at(centre, target)
            img = render(k, r_wc, centre, size, textures, planes)
            name = f"images/0/{t_ns}.jpg"
            ok, jpg = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 95])
            assert ok
            files[name] = jpg.tobytes()
            # The phone: X = the camera's right, Y = its up (-y), Z = out of the screen (-z: the camera looks along -Z).
            r_wd = np.stack([r_wc[:, 0], -r_wc[:, 1], -r_wc[:, 2]], 1)
            qw, qx, qy, qz = quaternion(r_wd)
            rows.append([n, n + 1, "FAR", "0", t_ns, name, width, height, *(f"{c:.6f}" for c in centre),
                         f"{qw:.6f}", f"{qx:.6f}", f"{qy:.6f}", f"{qz:.6f}", 1])
            meta.append({"camera_id": "0", "t_ns": t_ns, "trigger": n + 1, "bracket_ev": 0})
            t_ns += 500_000_000
            n += 1
    session = {"device": {"manufacturer": "TEST", "model": "synthetic"},
               "cameras": [{"id": "0", "intrinsics_fx_fy_cx_cy_s": [f, f, width / 2, height / 2, 0],
                            "active_array": [0, 0, width, height], "distortion_k1_k2_k3_p1_p2": [0, 0, 0, 0, 0],
                            "focus_distance_calibration": "approximate"}]}
    frames = io.StringIO()
    w = csv.writer(frames)
    w.writerow(["frame", "trigger", "pass", "camera_id", "t_ns", "file", "width", "height", "px", "py", "pz",
                "qw", "qx", "qy", "qz", "stationary"])
    w.writerows(rows)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("scan_synthetic/session.json", json.dumps(session))
        z.writestr("scan_synthetic/frames.csv", frames.getvalue())
        z.writestr("scan_synthetic/capture_meta.jsonl", "\n".join(json.dumps(m) for m in meta) + "\n")
        for name, data in files.items():
            z.writestr(f"scan_synthetic/{name}", data)
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out", type=Path)
    ap.add_argument("--views", type=int, default=24, help="views per ring (3 rings)")
    ap.add_argument("--size", default="800x600")
    ap.add_argument("--mat", default="a5", help="paper of make_scan_mat.py's mat under the box, or none")
    a = ap.parse_args()
    w, h = (int(v) for v in a.size.split("x"))
    print(make(a.out, a.views, (w, h), mat=a.mat))


if __name__ == "__main__":
    main()
