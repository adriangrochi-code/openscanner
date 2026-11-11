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


def test_each_job_has_its_own_work_folder_removed_after():
    """Two jobs side by side: the second never sees the first's files (a job without a part gives no_part, not the
    previous person's pieza.stl), and no work folder is left on the node's disk."""
    with tempfile.TemporaryDirectory() as d:
        d = Path(d)
        (d / "colmap").write_text("")
        ts.os.environ["TISMA_SCAN_DENSE"] = "colmap"
        seen = []

        def first(argv):
            work = Path(argv[argv.index("--work") + 1])
            work.mkdir(parents=True, exist_ok=True)      # as reconstruct.main
            seen.append(sorted(p.name for p in work.iterdir()))
            (work / "images").mkdir(exist_ok=True)
            (work / "images" / "foto_de_otro.jpg").write_text("x")
            (work / "pieza.stl").write_text("solid otra\nendsolid otra\n")

        def second_without_part(argv):
            work = Path(argv[argv.index("--work") + 1])
            work.mkdir(parents=True, exist_ok=True)      # as reconstruct.main
            seen.append(sorted(p.name for p in work.iterdir()))

        old = rc.COLMAP_EXE, rc.main
        try:
            rc.COLMAP_EXE, rc.main = str(d / "colmap"), first
            assert ts.main([str(d / "a.zip"), "-o", str(d / "a"), "--summary", str(d / "a.json")]) == 0
            rc.main = second_without_part
            assert ts.main([str(d / "b.zip"), "-o", str(d / "b"), "--summary", str(d / "b.json")]) == 1
        finally:
            rc.COLMAP_EXE, rc.main = old
            ts.os.environ.pop("TISMA_SCAN_DENSE", None)
        assert seen == [[], []], seen
        assert json.loads((d / "b.json").read_text(encoding="utf-8"))["code"] == "no_part"
        assert not (d / "b" / "pieza.stl").exists()
        assert not [p for p in d.iterdir() if p.name.startswith("tisma_work")], list(d.iterdir())


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
        # The layout of COLMAP 3.9.1's Windows release: COLMAP.bat, bin/colmap.exe, lib/, lib/plugins/.
        for sub in ("bin", "lib", "lib/plugins"):
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
            assert os.environ["QT_PLUGIN_PATH"] == str(d / "colmap" / "lib" / "plugins")
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


def test_a_measure_that_is_not_a_number_is_null_in_the_summary():
    """NaN or infinity in the report (a distance over an empty cloud) is not JSON: the app's org.json and strict parsers
    refuse the whole summary. It goes as null."""
    with tempfile.TemporaryDirectory() as d:
        d = Path(d)
        (d / "colmap").write_text("")
        ts.os.environ["TISMA_SCAN_DENSE"] = "colmap"

        def fake_main(argv):
            work = Path(argv[argv.index("--work") + 1])
            work.mkdir(parents=True, exist_ok=True)
            (work / "pieza.stl").write_text("solid pieza\nendsolid pieza\n")
            (work / "pieza_informe.json").write_text(json.dumps({"dimensions_mm_xyz": [40.0, 30.0, 20.0],
                "noise_mm": float("nan"), "mesh_vs_points_p95_mm": float("inf"), "volume_mm3": 24000.0}))

        old = rc.COLMAP_EXE, rc.main
        rc.COLMAP_EXE, rc.main = str(d / "colmap"), fake_main
        try:
            code = ts.main([str(d / "scan.zip"), "-o", str(d / "out"), "--summary", str(d / "s.json")])
        finally:
            rc.COLMAP_EXE, rc.main = old
            ts.os.environ.pop("TISMA_SCAN_DENSE", None)

        def strict(c):
            raise ValueError(f"not JSON: {c}")
        summary = json.loads((d / "s.json").read_text(encoding="utf-8"), parse_constant=strict)
        assert code == 0 and summary["noise_mm"] is None and summary["mesh_vs_points_p95_mm"] is None
        assert summary["volume_mm3"] == 24000.0 and summary["dimensions_mm_xyz"] == [40.0, 30.0, 20.0]


def test_a_reason_written_for_the_person_reaches_error_es():
    """The pipeline's own reasons («COLMAP no pudo ubicar las fotos…», «No queda pieza sobre la mesa…») only went to the
    English `error`; the person always got the same generic sentence. Now error_es carries them; other failures keep
    the generic one."""
    with tempfile.TemporaryDirectory() as d:
        d = Path(d)
        (d / "colmap").write_text("")
        ts.os.environ["TISMA_SCAN_DENSE"] = "colmap"
        old = rc.COLMAP_EXE, rc.main
        try:
            rc.COLMAP_EXE = str(d / "colmap")
            rc.main = lambda argv: (_ for _ in ()).throw(rc.for_person("COLMAP no pudo ubicar las fotos entre sí."))
            ts.main([str(d / "a.zip"), "-o", str(d / "a"), "--summary", str(d / "a.json")])
            rc.main = lambda argv: (_ for _ in ()).throw(RuntimeError("colmap terminó con código 1"))
            ts.main([str(d / "b.zip"), "-o", str(d / "b"), "--summary", str(d / "b.json")])
        finally:
            rc.COLMAP_EXE, rc.main = old
            ts.os.environ.pop("TISMA_SCAN_DENSE", None)
        a = json.loads((d / "a.json").read_text(encoding="utf-8"))
        b = json.loads((d / "b.json").read_text(encoding="utf-8"))
        assert a["code"] == "reconstruction_failed" and a["error_es"] == "COLMAP no pudo ubicar las fotos entre sí."
        assert b["error_es"].startswith("No se pudo armar la pieza con estas fotos")


def test_textura_zip_only_with_a_complete_texture():
    """OpenMVS's TextureMesh can crash after writing part of its files (on CI: code -11 after «Mesh texturing
    completed»). collect zipped every *texture* file it found, so textura.zip could carry an OBJ whose material or
    image is missing. Now only an OBJ with its .mtl files and their images goes in, and only the files they name."""
    import zipfile
    with tempfile.TemporaryDirectory() as d:
        d = Path(d)
        und = d / "work" / "undistorted"; und.mkdir(parents=True)
        out = d / "out"; out.mkdir()
        (und / "scene_texture.obj").write_text("mtllib scene_texture.mtl\nv 0 0 0\nf 1 1 1\n")
        ts.collect(d / "work", out)                                     # no .mtl: crashed before writing it
        assert not (out / "textura.zip").exists()
        (und / "scene_texture.mtl").write_text("newmtl m0\nmap_Kd scene_texture_map_Kd.jpg\n")
        ts.collect(d / "work", out)                                     # .mtl but not its image
        assert not (out / "textura.zip").exists()
        (und / "scene_texture_map_Kd.jpg").write_bytes(b"jpg")
        (und / "old_texture_unused.png").write_bytes(b"png")           # not named by the material
        ts.collect(d / "work", out)
        with zipfile.ZipFile(out / "textura.zip") as z:
            assert sorted(z.namelist()) == ["scene_texture.mtl", "scene_texture.obj", "scene_texture_map_Kd.jpg"]
        # In pieza.stl's frame (mm, on the table): the report's to_part_mm moves the OBJ's vertices, turns its normals.
        m = [[0, -1000, 0, 5], [1000, 0, 0, -2], [0, 0, 1000, 0], [0, 0, 0, 1]]     # x1000, 90° about Z, shifted
        (d / "work" / "pieza_informe.json").write_text(json.dumps({"to_part_mm": m}))
        (und / "scene_texture.obj").write_text("mtllib scene_texture.mtl\nv 0.01 0.02 0.03 0.5 0.5 0.5\nvn 1 0 0\nf 1 1 1\n")
        ts.collect(d / "work", out)
        with zipfile.ZipFile(out / "textura.zip") as z:
            lines = z.read("scene_texture.obj").decode().splitlines()
        v = [float(x) for x in lines[1].split()[1:4]]
        assert [round(x, 6) for x in v] == [-15.0, 8.0, 30.0] and lines[1].endswith("0.5 0.5 0.5"), lines[1]
        assert [round(float(x), 6) for x in lines[2].split()[1:]] == [0.0, 1.0, 0.0], lines[2]


if __name__ == "__main__":
    for fn in [v for k, v in dict(globals()).items() if k.startswith("test_")]:
        fn()
        print("ok", fn.__name__)
