#!/usr/bin/env python3
"""
The kit for testers of Tisma Scanner: one zip with the APK, the short instructions (docs/PROBAR.md, TESTING.md)
and the printable mat as PDF in every paper size (make_scan_mat.py).

    python3 tools/make_tester_kit.py app/build/outputs/apk/debug/app-debug.apk --version 0.1.0-abc1234 --out dist

Writes dist/TismaScanner-kit-<version>.zip:
    TismaScanner-<version>.apk
    LEEME - PROBAR.md, README - TESTING.md
    tapetes/tapete_<paper>.pdf   (A6 ... Tabloid; print at actual size)
    LICENSE, THIRD_PARTY.md, SOURCE.txt   (AGPL-3.0: where the source of this exact build is; the notices of
                                           the libraries inside the APK, e.g. Bouncy Castle and ONNX Runtime, MIT)
"""
import argparse
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent


SOURCE_URL = "https://github.com/adriangrochi-code/openscanner"  # the same as About.SOURCE_URL in the app


def commit() -> str:
    """The commit this kit is built from (GITHUB_SHA on CI, else git), for SOURCE.txt."""
    import os
    sha = os.environ.get("GITHUB_SHA") or subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True,
                                                         text=True).stdout.strip()
    return sha or "unknown"


def source_text(version: str, sha: str) -> str:
    return (f"Tisma Scanner {version}\n"
            f"Software libre bajo la GNU Affero General Public License 3.0, AGPL-3.0 (LICENSE), sin ninguna garantía.\n"
            f"Free software under the GNU Affero General Public License 3.0, AGPL-3.0 (LICENSE), without any warranty.\n\n"
            f"Código fuente de esta versión / source code of this build:\n"
            f"  {SOURCE_URL}\n  commit {sha}\n  {SOURCE_URL}/archive/{sha}.zip\n\n"
            f"Licencias de las bibliotecas que lleva la app / licences of the libraries in the app: THIRD_PARTY.md\n")


def make_kit(apk: Path, version: str, out: Path) -> Path:
    sys.path.insert(0, str(HERE))
    import make_scan_mat

    out.mkdir(parents=True, exist_ok=True)
    kit = out / f"TismaScanner-kit-{version}.zip"
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(kit, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(apk, f"TismaScanner-{version}.apk", compress_type=zipfile.ZIP_STORED)  # an APK is already a zip
        z.write(ROOT / "docs" / "PROBAR.md", "LEEME - PROBAR.md")
        z.write(ROOT / "docs" / "TESTING.md", "README - TESTING.md")
        z.write(ROOT / "LICENSE", "LICENSE")
        z.write(ROOT / "THIRD_PARTY.md", "THIRD_PARTY.md")
        z.writestr("SOURCE.txt", source_text(version, commit()))
        for paper in make_scan_mat.PAPERS_MM:
            canvas, meta = make_scan_mat.make_mat(paper)
            base = Path(tmp) / f"tapete_{paper}"
            make_scan_mat.save_mat(canvas, meta, base)
            z.write(f"{base}.pdf", f"tapetes/tapete_{paper}.pdf")
    return kit


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("apk", type=Path)
    ap.add_argument("--version", required=True)
    ap.add_argument("--out", type=Path, default=Path("dist"))
    a = ap.parse_args()
    kit = make_kit(a.apk, a.version, a.out)
    with zipfile.ZipFile(kit) as z:
        for i in z.infolist():
            print(f"{i.file_size / 1e6:8.1f} MB  {i.filename}")
    print(f"{kit} ({kit.stat().st_size / 1e6:.0f} MB)")


if __name__ == "__main__":
    main()
