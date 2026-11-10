"""Tests of the testers' kit (make_tester_kit.py). Its own file: the kit is built on Linux (App Android, release
workflows), and the Windows node package runs test_tisma_scan.py on a Python without Pillow, which the mats need."""
import os
import sys
import tempfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
pytest.importorskip("PIL")
pytest.importorskip("cv2")


def test_the_tester_kit_carries_licence_notices_and_where_the_source_is():
    """The kit hands out an APK (AGPL-3.0): it says where the source of that exact build is, and carries the licences
    of the libraries inside it (MIT ones such as Bouncy Castle ask for their notice with every copy)."""
    import zipfile
    import make_tester_kit as mtk
    with tempfile.TemporaryDirectory() as d:
        d = Path(d)
        apk = d / "app.apk"
        apk.write_bytes(b"PK fake apk")
        os.environ["GITHUB_SHA"] = "0123456789abcdef0123456789abcdef01234567"
        try:
            kit = mtk.make_kit(apk, "0.1.0-test", d / "out")
        finally:
            os.environ.pop("GITHUB_SHA", None)
        with zipfile.ZipFile(kit) as z:
            names = set(z.namelist())
            assert {"LICENSE", "THIRD_PARTY.md", "SOURCE.txt", "TismaScanner-0.1.0-test.apk",
                    "LEEME - PROBAR.md", "README - TESTING.md", "tapetes/tapete_a4.pdf"} <= names, names
            src = z.read("SOURCE.txt").decode("utf-8")
            assert "0123456789abcdef0123456789abcdef01234567" in src and mtk.SOURCE_URL in src and "AGPL" in src
            assert "Bouncy Castle" in z.read("THIRD_PARTY.md").decode("utf-8")
