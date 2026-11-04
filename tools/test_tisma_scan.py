"""Tests of tisma_scan, the program the nodes of Tisma Cloud run (without COLMAP: the pipeline is replaced)."""
import io
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import reconstruct as rc  # noqa: E402
import tisma_scan as ts  # noqa: E402


def test_progress_of_the_stages():
    assert ts.progress_of("[hdr] 12 fotos") == 5
    assert ts.progress_of("[run] C:/COLMAP/bin/colmap.exe feature_extractor") == 25
    assert ts.progress_of("[run] /opt/openMVS/TextureMesh scene.mvs") == 84
    assert ts.progress_of("[post] limpieza") == 90
    assert ts.progress_of("cualquier otra línea") is None


def test_without_colmap_a_coded_error():
    with tempfile.TemporaryDirectory() as d:
        d = Path(d)
        old = rc.COLMAP_EXE
        rc.COLMAP_EXE = str(d / "no-such-colmap")
        try:
            code = ts.main([str(d / "scan.zip"), "-o", str(d / "out"), "--summary", str(d / "s.json")])
        finally:
            rc.COLMAP_EXE = old
        summary = json.loads((d / "s.json").read_text(encoding="utf-8"))
        assert code == 1 and summary["code"] == "no_colmap" and summary["error_es"]


def test_a_rebuilt_part_gives_its_files_progress_and_summary():
    with tempfile.TemporaryDirectory() as d:
        d = Path(d)
        (d / "colmap").write_text("")
        ts.os.environ["TISMA_SCAN_DENSE"] = "colmap"   # as on a COLMAP with CUDA

        def fake_main(argv):
            work = Path(argv[argv.index("--work") + 1])
            work.mkdir(parents=True, exist_ok=True)
            print("[hdr] 3 fotos")
            print("[run] colmap feature_extractor")
            print("[post] limpieza")
            (work / "pieza.stl").write_text("solid pieza\nendsolid pieza\n")
            (work / "pieza_informe.json").write_text(json.dumps({"dimensions_mm_xyz": [40.2, 30.2, 20.3],
                                                                  "volume_mm3": 24300.5, "other": 1}))

        old = rc.COLMAP_EXE, rc.main
        rc.COLMAP_EXE, rc.main = str(d / "colmap"), fake_main
        out = io.StringIO()
        try:
            summary = ts.run(d / "scan.zip", d / "out", 2, True, out)
        finally:
            rc.COLMAP_EXE, rc.main = old
            ts.os.environ.pop("TISMA_SCAN_DENSE", None)
        assert out.getvalue().split() == ["progress", "5", "progress", "25", "progress", "90", "progress", "100"]
        assert summary == {"dimensions_mm_xyz": [40.2, 30.2, 20.3], "volume_mm3": 24300.5}
        assert (d / "out" / "pieza.stl").exists() and "[hdr]" in (d / "out" / "log.txt").read_text(encoding="utf-8")


def test_a_failed_pipeline_is_a_coded_error():
    with tempfile.TemporaryDirectory() as d:
        d = Path(d)
        (d / "colmap").write_text("")

        ts.os.environ["TISMA_SCAN_DENSE"] = "colmap"

        def broken(argv):
            raise SystemExit("COLMAP no registró ninguna foto")

        old = rc.COLMAP_EXE, rc.main
        rc.COLMAP_EXE, rc.main = str(d / "colmap"), broken
        try:
            code = ts.main([str(d / "scan.zip"), "-o", str(d / "out"), "--summary", str(d / "s.json")])
        finally:
            rc.COLMAP_EXE, rc.main = old
            ts.os.environ.pop("TISMA_SCAN_DENSE", None)
        summary = json.loads((d / "s.json").read_text(encoding="utf-8"))
        assert code == 1 and summary["code"] == "reconstruction_failed" and "ninguna foto" in summary["error"]


def test_missing_modules_are_a_coded_error():
    import builtins
    real_import = builtins.__import__

    def no_reconstruct(name, *args, **kwargs):
        if name == "reconstruct":
            raise SystemExit("Falta OpenCV: pip install opencv-contrib-python numpy")
        return real_import(name, *args, **kwargs)

    with tempfile.TemporaryDirectory() as d:
        d = Path(d)
        builtins.__import__ = no_reconstruct
        saved = sys.modules.pop("reconstruct")
        try:
            code = ts.main([str(d / "scan.zip"), "-o", str(d / "out"), "--summary", str(d / "s.json")])
        finally:
            builtins.__import__ = real_import
            sys.modules["reconstruct"] = saved
        summary = json.loads((d / "s.json").read_text(encoding="utf-8"))
        assert code == 1 and summary["code"] == "scan_setup" and "pip" not in summary["error_es"]


def test_check_says_what_is_missing():
    old = rc.COLMAP_EXE
    rc.COLMAP_EXE = "/no/such/colmap"
    try:
        result = ts.check()
    finally:
        rc.COLMAP_EXE = old
    assert not result["ready"] and any("COLMAP" in p for p in result["problems"])


def test_without_a_way_to_the_dense_cloud_not_ready():
    import shutil
    colmap = shutil.which("colmap")
    if not colmap or ts.colmap_has_cuda(colmap):
        print("skip: needs a COLMAP without CUDA")
        return
    old = rc.COLMAP_EXE, rc.OPENMVS_DIR
    rc.COLMAP_EXE, rc.OPENMVS_DIR = colmap, ""
    try:
        result = ts.check()
    finally:
        rc.COLMAP_EXE, rc.OPENMVS_DIR = old
    assert not result["ready"] and any("dense cloud" in p for p in result["problems"])


def test_bundled_programs_next_to_it():
    import os
    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        # The layout of COLMAP's Windows release: COLMAP.bat, bin/colmap.exe, lib/, plugins/.
        for sub in ("bin", "lib", "plugins"):
            (d / "colmap" / sub).mkdir(parents=True)
        (d / "colmap" / "COLMAP.bat").write_text("@echo off\n")
        (d / "colmap" / "bin" / "colmap.exe").write_bytes(b"")
        (d / "openmvs").mkdir()
        (d / "openmvs" / "DensifyPointCloud.exe").write_bytes(b"")
        saved = {k: os.environ.pop(k, None) for k in ("TISMA_COLMAP", "TISMA_OPENMVS", "QT_PLUGIN_PATH", "PATH")}
        os.environ["PATH"] = saved["PATH"] or ""
        try:
            found = ts.bundled_programs(d)
            # The program, not the .bat (cmd.exe would split the commas of the camera parameters).
            assert found == {"TISMA_COLMAP": str(d / "colmap" / "bin" / "colmap.exe"),
                             "TISMA_OPENMVS": str(d / "openmvs")}
            assert os.environ["TISMA_OPENMVS"] == str(d / "openmvs")
            assert os.environ["PATH"].split(os.pathsep)[0] == str(d / "colmap" / "lib")
            assert os.environ["QT_PLUGIN_PATH"] == str(d / "colmap" / "plugins")
            # A COLMAP.bat set by the person: the colmap.exe next to it, too.
            os.environ["TISMA_COLMAP"] = str(d / "colmap" / "COLMAP.bat")
            os.environ.pop("TISMA_OPENMVS")
            assert ts.bundled_programs(d / "nothing") == {"TISMA_COLMAP": str(d / "colmap" / "bin" / "colmap.exe")}
            assert os.environ["TISMA_COLMAP"] == str(d / "colmap" / "bin" / "colmap.exe")
            # Without bin/colmap.exe, the .bat stays.
            os.environ.pop("TISMA_COLMAP")
            (d / "colmap" / "bin" / "colmap.exe").unlink()
            assert ts.bundled_programs(d)["TISMA_COLMAP"] == str(d / "colmap" / "COLMAP.bat")
            # Set by the person: kept.
            os.environ["TISMA_COLMAP"] = "C:/mine/colmap.exe"
            os.environ.pop("TISMA_OPENMVS")
            assert ts.bundled_programs(d) == {"TISMA_OPENMVS": str(d / "openmvs")}
            assert os.environ["TISMA_COLMAP"] == "C:/mine/colmap.exe"
        finally:
            for k, v in saved.items():
                os.environ.pop(k, None)
                if v is not None:
                    os.environ[k] = v
        assert ts.bundled_programs(d / "nothing") == {}


if __name__ == "__main__":
    for fn in [v for k, v in dict(globals()).items() if k.startswith("test_")]:
        fn()
        print("ok", fn.__name__)
