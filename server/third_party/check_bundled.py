#!/usr/bin/env python3
"""
The licences of the libraries bundled in the Windows scan package (tisma-scan).

The COLMAP and OpenMVS releases ship their DLLs (Qt, Boost, OpenCV, the CUDA runtime...) without the licences of
those libraries. This maps every DLL in the package to its library (dlls.csv), copies the licence texts
(texts/, fetched from each library's official source: sources.csv) into licenses/third-party/, writes
licenses/BUNDLED_DLLS.txt (each DLL, its library and licence) and fails when a DLL is not in the table.

    python server/third_party/check_bundled.py scan-out/tisma-scan
"""
import csv
import re
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
NOTE = """\
Libraries bundled in the COLMAP and OpenMVS releases of this package, each with its licence.

Some are GPL (libspqr.dll, SuiteSparse SPQR: GPL-2.0-or-later) or LGPL (Qt 5, GMP, LibRaw, SuiteSparse CHOLMOD).
While this package only runs on Tisma's own machines (the beta and Tisma Cloud's nodes) it is not conveyed to anyone.
Before it is given to a third party (outside nodes, a public download), the exact sources of those libraries, at the
versions of these DLLs, must go with it (a TismaScan-windows-source artifact) and this file must carry a written
offer of them. OpenMVS (AGPL-3.0, unmodified) already ships its source in source/.

"""
# Texts other texts refer to (LGPL-3.0 and the GCC exception build on the GPL-3.0; SuiteSparse and LibRaw modules).
ALWAYS = ("gpl-3.0", "gpl-2.0", "lgpl-2.1")


def table():
    rows = list(csv.DictReader(open(HERE / "dlls.csv", newline="")))
    licences = {r["library"]: r["licence"] for r in csv.DictReader(open(HERE / "sources.csv", newline=""))}
    return [(re.compile(r["pattern"]), r["library"], r["notes"]) for r in rows], licences


def check(pkg: Path) -> list[str]:
    """Writes the licences into pkg/licenses and returns the DLLs that have none."""
    rules, licences = table()
    dlls = sorted(p.relative_to(pkg).as_posix() for d in ("colmap", "openmvs") for p in (pkg / d).rglob("*.dll"))
    out = pkg / "licenses" / "third-party"
    out.mkdir(parents=True, exist_ok=True)
    lines, missing, used = [], [], set(ALWAYS)
    for dll in dlls:
        hit = next(((lib, notes) for rx, lib, notes in rules if rx.match(Path(dll).name)), None)
        if hit is None:
            missing.append(dll)
            continue
        lib, notes = hit
        if lib == "openmvs":
            lines.append(f"{dll}\topenmvs\tAGPL-3.0\t{notes}")
            continue
        used.add(lib)
        lines.append(f"{dll}\t{lib}\t{licences[lib]}\t{notes}".rstrip())
    for lib in sorted(used):
        shutil.copy(HERE / "texts" / f"{lib}.txt", out / f"{lib}.txt")
    shutil.copy(HERE / "sources.csv", out / "SOURCES.csv")
    (pkg / "licenses" / "BUNDLED_DLLS.txt").write_text(
        NOTE + "DLL\tlibrary\tlicence\tnotes (texts in licenses/third-party/<library>.txt)\n" + "\n".join(lines) + "\n",
        encoding="utf-8")
    print(f"{len(dlls)} bundled DLLs, {len(used)} licence texts, {len(missing)} without a licence")
    return missing


if __name__ == "__main__":
    missing = check(Path(sys.argv[1]))
    for dll in missing:
        print("no licence for", dll, "(add it to server/third_party/dlls.csv)", file=sys.stderr)
    sys.exit(1 if missing else 0)
