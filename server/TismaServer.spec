# PyInstaller: TismaServer (un solo ejecutable con el servidor y el pipeline de fotogrametría).
# Construir:  pyinstaller TismaServer.spec   ->  dist/TismaServer(.exe)
from PyInstaller.utils.hooks import collect_all

datas, binaries, hiddenimports = [], [], []
for pkg in ("open3d", "cv2"):
    d, b, h = collect_all(pkg)
    datas += d; binaries += b; hiddenimports += h

a = Analysis(
    ["tisma_server.py"],
    pathex=["../tools"],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports + ["reconstruct", "postprocess", "depth_from_focus", "ins_accuracy"],
    excludes=["tkinter", "matplotlib", "IPython", "notebook", "jupyter",
              "open3d.visualization.gui", "PyQt5", "PySide2", "PySide6"],
)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, a.binaries, a.datas, name="TismaServer", console=True, upx=False)
