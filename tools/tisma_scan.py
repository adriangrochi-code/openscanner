#!/usr/bin/env python3
"""tisma_scan: rebuilds a part from a scan of Tisma Scanner, for the computing nodes of Tisma Cloud.

The node of Tisma Cloud runs it as a separate program for each job (its contract, in Tisma Cloud's
tisma_cloud/node/runners.py, run_scan()):

    tisma_scan <scan.zip> -o <folder> --summary <summary.json> [--threads <n>] [--no-gpu]
    tisma_scan --check      whether this machine can rebuild scans (JSON: ready, problems, warnings; exit 0 or 3)

- the same pipeline as TismaServer (reconstruct.py: images -> COLMAP -> dense cloud -> postprocess.py);
- on the standard output, one line per change of progress: "progress <0 to 100>";
- in <folder>: pieza.stl (the part in mm), pieza_informe.json and, when made, pieza.ply, textura.zip and log.txt;
- <summary.json>: the measures of the report, or "error" (English), "code" and "error_es" (Spanish) when it fails,
  with an exit code other than 0.

COLMAP and OpenMVS are found as for TismaServer: TISMA_COLMAP and TISMA_OPENMVS, or COLMAP on the PATH. The Windows
package of a scan node (the folder tisma-scan) brings them next to tisma_scan.exe, in colmap/ and openmvs/: they are
used when those variables are not set.
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


def colmap_program(colmap: str) -> str:
    """The COLMAP program behind a COLMAP.bat (bin/colmap.exe next to it), with what the .bat sets: its lib/ on the
    PATH and lib/plugins as QT_PLUGIN_PATH. The .bat passes the arguments one by one with %1, which cuts at commas,
    so --ImageReader.camera_params reached COLMAP as one number and it refused the camera. Anything else is returned
    as it is."""
    bat = Path(colmap)
    exe = bat.parent / "bin" / "colmap.exe"
    if bat.suffix.lower() != ".bat" or not exe.is_file():
        return colmap
    if (bat.parent / "lib").is_dir():
        os.environ["PATH"] = str(bat.parent / "lib") + os.pathsep + os.environ.get("PATH", "")
    plugins = bat.parent / "lib" / "plugins"  # COLMAP 3.9.1's COLMAP.bat: QT_PLUGIN_PATH=%SCRIPT_PATH%\lib\plugins
    if plugins.is_dir():
        os.environ["QT_PLUGIN_PATH"] = os.pathsep.join(filter(None, (str(plugins), os.environ.get("QT_PLUGIN_PATH"))))
    return str(exe)


def bundled_programs(folder: Path | None = None) -> dict[str, str]:
    """COLMAP and OpenMVS that came next to this program (colmap/COLMAP.bat or colmap/bin/colmap(.exe), openmvs/ with
    DensifyPointCloud), set as TISMA_COLMAP and TISMA_OPENMVS when they are not set. A COLMAP.bat, found here or set
    by the person, becomes the colmap.exe next to it (colmap_program). Returns what was set."""
    if folder is None:
        folder = Path(sys.executable).resolve().parent if getattr(sys, "frozen", False) else HERE
    found = {}
    colmap = os.environ.get("TISMA_COLMAP")
    if not colmap:
        for name in ("COLMAP.bat", "bin/colmap.exe", "bin/colmap"):
            if (folder / "colmap" / name).is_file():
                colmap = str(folder / "colmap" / name)
                found["TISMA_COLMAP"] = colmap
                break
    program = colmap_program(colmap) if colmap else colmap
    if program != colmap:
        found["TISMA_COLMAP"] = program
    if not os.environ.get("TISMA_OPENMVS"):
        mvs = folder / "openmvs"
        if any((mvs / n).is_file() for n in ("DensifyPointCloud.exe", "DensifyPointCloud")):
            found["TISMA_OPENMVS"] = str(mvs)
    os.environ.update(found)
    return found


def nvidia_card() -> bool:
    """An NVIDIA card with its driver (nvidia-smi comes with the driver, on Windows and Linux)."""
    return shutil.which("nvidia-smi") is not None


def colmap_has_cuda(colmap: str) -> bool:
    """COLMAP says in its help whether it was built with CUDA ("... with CUDA" / "... without CUDA")."""
    import subprocess

    import reconstruct  # loaded by now (load_pipeline); its _own_dlls keeps COLMAP off this .exe's DLLs

    try:
        with reconstruct._own_dlls():
            text = subprocess.run([colmap, "help"], capture_output=True, text=True, errors="replace",
                                  timeout=60).stdout
    except (OSError, subprocess.SubprocessError):
        return False
    return "with CUDA" in text and "without CUDA" not in text


def headless_colmap(reconstruct, gpu: bool = True) -> None:
    """COLMAP on a server: without a screen its Qt needs the offscreen platform, and without CUDA (or with --no-gpu)
    its SIFT must run on the processor (on the GPU it uses OpenGL, which aborts without a context)."""
    if sys.platform.startswith("linux") and not os.environ.get("DISPLAY") and not os.environ.get("WAYLAND_DISPLAY"):
        os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
    if not gpu or not nvidia_card() or not colmap_has_cuda(reconstruct.COLMAP_EXE):
        os.environ["TISMA_COLMAP_NO_GPU"] = "1"


def dense_method(reconstruct) -> str | None:
    """How the dense cloud is made here: TISMA_SCAN_DENSE, else OpenMVS when set, else COLMAP's, which needs CUDA and
    an NVIDIA card.
    None: it cannot be made on this machine (and without it there is no STL)."""
    chosen = os.environ.get("TISMA_SCAN_DENSE")
    if chosen:
        return chosen
    if reconstruct.OPENMVS_DIR:
        return "openmvs"
    return "colmap" if nvidia_card() and colmap_has_cuda(reconstruct.COLMAP_EXE) else None


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
    bundled_programs()
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
    headless_colmap(reconstruct, gpu)
    out.mkdir(parents=True, exist_ok=True)
    work = out.parent / "tisma_work"
    dense = dense_method(reconstruct)
    if dense is None:
        raise Failed("no_dense", "The dense cloud cannot be made on this computing server (COLMAP without CUDA)",
                     "Este servidor de cálculo no puede armar la nube densa: le falta OpenMVS o una placa NVIDIA")
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
                         "No se pudo armar la pieza con estas fotos. Prueba con más fotos, más textura en la pieza "
                         "o el tapete de escaneo.") from ex
    collect(work, out)
    if not (out / "pieza.stl").exists():
        raise Failed("no_part", "The scan gave no part (no STL)",
                     "El escaneo no dio una pieza: faltan fotos o la pieza no se separó de la mesa.")
    real_stdout.write("progress 100\n")
    real_stdout.flush()
    return summary_of(out)


def check() -> dict:
    """Whether this machine can rebuild scans: {"ready", "problems", "warnings"}. The node of Tisma Cloud asks it
    (tisma_scan --check) before it takes any scan, so that a missing piece never fails a person's job."""
    problems: list[str] = []
    warnings: list[str] = []
    reconstruct = None
    try:
        reconstruct = load_pipeline()
    except Failed:
        problems.append("the Python modules of the pipeline (numpy, OpenCV) are missing")
    try:
        import open3d  # noqa: F401  (postprocess.py: the mesh and the STL)
    except ImportError:
        problems.append("Open3D is missing (the mesh and the STL)")
    if reconstruct is not None:
        colmap = reconstruct.COLMAP_EXE
        if not (shutil.which(colmap) or Path(colmap).is_file()):
            problems.append(f"COLMAP is missing ({colmap}; TISMA_COLMAP or the PATH)")
        if reconstruct.OPENMVS_DIR and not Path(reconstruct.OPENMVS_DIR).is_dir():
            problems.append(f"OpenMVS is set but missing ({reconstruct.OPENMVS_DIR})")
        elif not problems and dense_method(reconstruct) is None:
            problems.append("the dense cloud cannot be made: COLMAP has no CUDA or no NVIDIA card here; install "
                            "OpenMVS (TISMA_OPENMVS) or a COLMAP with CUDA and an NVIDIA card")
    if not nvidia_card():
        warnings.append("no NVIDIA card found: COLMAP runs on the processor, much slower")
    return {"ready": not problems, "problems": problems, "warnings": warnings}


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if args[:1] == ["--check"]:
        result = check()
        print(json.dumps(result, ensure_ascii=False))
        return 0 if result["ready"] else 3
    ap = argparse.ArgumentParser(prog="tisma_scan", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("scan", type=Path, help="the zip of the scan (Tisma Scanner)")
    ap.add_argument("-o", "--output", type=Path, required=True, help="folder for the part and its report")
    ap.add_argument("--summary", type=Path, required=True, help="JSON summary for Tisma Cloud")
    ap.add_argument("--threads", type=int, default=0, help="processor threads at most (0: all)")
    ap.add_argument("--no-gpu", action="store_true", help="do not use the graphics card")
    a = ap.parse_args(args)
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
