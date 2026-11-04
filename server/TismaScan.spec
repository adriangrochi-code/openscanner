# PyInstaller: tisma_scan (the pipeline of photogrammetry for the computing nodes of Tisma Cloud, tools/tisma_scan.py).
# Build:  pyinstaller TismaScan.spec   ->  dist/tisma_scan(.exe)
from PyInstaller.utils.hooks import collect_all

datas, binaries, hiddenimports = [], [], []
for pkg in ("open3d", "cv2"):
    d, b, h = collect_all(pkg)
    datas += d; binaries += b; hiddenimports += h

a = Analysis(
    ["../tools/tisma_scan.py"],
    pathex=["../tools"],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports + ["reconstruct", "postprocess", "depth_from_focus", "ins_accuracy", "learned_matching"],
    excludes=["tkinter", "matplotlib", "IPython", "notebook", "jupyter",
              "open3d.visualization.gui", "PyQt5", "PySide2", "PySide6"],
)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, a.binaries, a.datas, name="tisma_scan", console=True, upx=False)
