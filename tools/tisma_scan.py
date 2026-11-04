#!/usr/bin/env python3
"""tisma_scan: rebuilds a part from a scan of Tisma Scanner, for the computing nodes of Tisma Cloud.

The node of Tisma Cloud runs it as a separate program for each job (its contract, in Tisma Cloud's
tisma_cloud/node/runners.py, run_scan()):

    tisma_scan <scan.zip> -o <folder> --summary <summary.json> [--threads <n>] [--no-gpu]

- the same pipeline as TismaServer (reconstruct.py: images -> COLMAP -> dense cloud -> postprocess.py);
- on the standard output, one line per change of progress: "progress <0 to 100>";
- in <folder>: pieza.stl (the part in mm), pieza_informe.json and, when made, pieza.ply, textura.zip and log.txt;
- <summary.json>: the measures of the report, or "error" (English), "code" and "error_es" (Spanish) when it fails,
  with an exit code other than 0.

COLMAP and OpenMVS are found as for TismaServer: TISMA_COLMAP and TISMA_OPENMVS, or COLMAP on the PATH.
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import os
import shutil
import sys
import traceback
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

# The stages of reconstruct.py (the tags it prints) and how far along each one starts.
STAGES = (
    ("[hdr]", None, 5),
    ("[sfm]", None, 15),
    ("[run]", "colmap", 25),
    ("[ins]", None, 45),
    ("[scale]", None, 50),
    ("[dense]", None, 55),
    ("[run]", "DensifyPointCloud", 60),
    ("[run]", "ReconstructMesh", 72),
    ("[run]", "RefineMesh", 78),
    ("[run]", "TextureMesh", 84),
    ("[post]", None, 90),
)
# What the app shows of the report of postprocess.py.
SUMMARY_KEYS = ("dimensions_mm_xyz", "oriented_box_mm", "volume_mm3", "area_mm2", "watertight", "triangles",
                "mesh_vs_points_mean_mm", "mesh_vs_points_p95_mm", "noise_mm", "flat_base")
RESULTS = ("pieza.stl", "pieza.ply", "pieza_informe.json")


class Failed(Exception):
    def __init__(self, code: str, error: str, error_es: str) -> None:
        super().__init__(error)
        self.code, self.error_es = code, error_es


def progress_of(line: str) -> int | None:
    """The progress a line of reconstruct.py means, or None."""
    for tag, tool, percent in STAGES:
        if not line.startswith(tag):
            continue
        if tool is None:
            return percent
        words = line.split()
        if len(words) > 1 and Path(words[1]).stem == tool:
            return percent
    return None


class Tee(io.TextIOBase):
    """The output of the pipeline: to log.txt, and its stages as progress lines on the real standard output."""

    def __init__(self, log, out) -> None:
        self.log, self.out = log, out
        self.buffer = ""
        self.done = 0

    def write(self, s: str) -> int:
        self.log.write(s)
        self.buffer += s
        while "\n" in self.buffer:
            line, self.buffer = self.buffer.split("\n", 1)
            p = progress_of(line.strip())
            if p is not None and p > self.done:
                self.done = p
                self.out.write(f"progress {p}\n")
                self.out.flush()
        return len(s)

    def flush(self) -> None:
        if not self.log.closed:
            self.log.flush()


def collect(work: Path, out: Path) -> None:
    for name in RESULTS:
        if (work / name).exists():
            shutil.copy(work / name, out / name)
    und = work / "undistorted"
    texture = [p for p in und.glob("*texture*") if p.suffix.lower() in (".obj", ".mtl", ".png", ".jpg")] \
        if und.exists() else []
    if texture:
        with zipfile.ZipFile(out / "textura.zip", "w", zipfile.ZIP_DEFLATED) as z:
            for p in texture:
                z.write(p, p.name)


def summary_of(out: Path) -> dict:
    try:
        report = json.loads((out / "pieza_informe.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return {k: report[k] for k in SUMMARY_KEYS if k in report} if isinstance(report, dict) else {}


def load_pipeline():
    """reconstruct.py with its modules (numpy, OpenCV, Open3D): a node without them gives a coded error, and what
    is missing goes only to the log of the node (the standard error), not to the person."""
    try:
        import reconstruct
    except (ImportError, SystemExit) as ex:  # reconstruct.py ends with SystemExit when OpenCV is missing
        print(f"tisma_scan: the pipeline cannot start on this node: {ex}", file=sys.stderr)
        raise Failed("scan_setup", "This computing server lacks the programs for 3D scans",
                     "A este servidor de cálculo le faltan programas para escanear") from None
    return reconstruct


def run(scan: Path, out: Path, threads: int, gpu: bool, real_stdout) -> dict:
    reconstruct = load_pipeline()

    if not (shutil.which(reconstruct.COLMAP_EXE) or Path(reconstruct.COLMAP_EXE).exists()):
        raise Failed("no_colmap", "COLMAP is not installed on this computing server",
                     "Este servidor de cálculo no tiene COLMAP instalado")
    if threads > 0:
        # COLMAP, OpenMVS and OpenCV take their threads from here (the cores the plan gives the job).
        for var in ("OMP_NUM_THREADS", "OPENCV_FOR_THREADS_NUM"):
            os.environ[var] = str(threads)
    if not gpu:
        os.environ["CUDA_VISIBLE_DEVICES"] = ""
    out.mkdir(parents=True, exist_ok=True)
    work = out.parent / "tisma_work"
    dense = os.environ.get("TISMA_SCAN_DENSE") or ("openmvs" if reconstruct.OPENMVS_DIR else "colmap")
    argv = [str(scan), "--work", str(work), "--dense", dense]
    with open(out / "log.txt", "w", encoding="utf-8") as log:
        tee = Tee(log, real_stdout)
        try:
            with contextlib.redirect_stdout(tee), contextlib.redirect_stderr(tee):
                reconstruct.main(argv)
        except BaseException as ex:  # noqa: BLE001 (the pipeline ends with SystemExit too)
            traceback.print_exc(file=log)
            if isinstance(ex, KeyboardInterrupt):
                raise
            raise Failed("reconstruction_failed", f"The part could not be rebuilt from these photos: {ex}"[:500],
                         "No se pudo armar la pieza con estas fotos. Probá con más fotos, más textura en la pieza "
                         "o el tapete de escaneo.") from ex
    collect(work, out)
    if not (out / "pieza.stl").exists():
        raise Failed("no_part", "The scan gave no part (no STL)",
                     "El escaneo no dio una pieza: faltan fotos o la pieza no se separó de la mesa.")
    real_stdout.write("progress 100\n")
    real_stdout.flush()
    return summary_of(out)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="tisma_scan", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("scan", type=Path, help="the zip of the scan (Tisma Scanner)")
    ap.add_argument("-o", "--output", type=Path, required=True, help="folder for the part and its report")
    ap.add_argument("--summary", type=Path, required=True, help="JSON summary for Tisma Cloud")
    ap.add_argument("--threads", type=int, default=0, help="processor threads at most (0: all)")
    ap.add_argument("--no-gpu", action="store_true", help="do not use the graphics card")
    a = ap.parse_args(argv)
    a.output.mkdir(parents=True, exist_ok=True)
    try:
        summary = run(a.scan, a.output, a.threads, not a.no_gpu, sys.stdout)
        code = 0
    except Failed as ex:
        summary, code = {"error": str(ex), "code": ex.code, "error_es": ex.error_es}, 1
    a.summary.write_text(json.dumps(summary, ensure_ascii=False), encoding="utf-8")
    return code


if __name__ == "__main__":
    sys.exit(main())
