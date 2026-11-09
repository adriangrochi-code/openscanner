#!/usr/bin/env python3
"""
A smoke test of the app on an Android emulator (or a phone over adb), the first steps of docs/PROBAR.md:
install, open, the main screen without a crash, ⚙ → Nube with its sign-in. Screenshots and the logcat go to <out>.

    python3 tools/emulator_smoke.py app/build/outputs/apk/debug/app-debug.apk out/

Exits with 1, saying why, when the app crashes or a step does not show what it should. Uses only adb (and its
uiautomator dump to find what is on screen and tap it).
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "com.tisma.scanner"


def adb(*args: str, check: bool = True, timeout: int = 120) -> str:
    p = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if check and p.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {p.stderr.strip() or p.stdout.strip()}")
    return p.stdout


def screen() -> list[tuple[str, tuple[int, int]]]:
    """The texts on screen (text and content-desc) with the centre of each, from uiautomator."""
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        xml = adb("shell", "cat", "/sdcard/ui.xml", check=False)
        if xml.strip().startswith("<?xml"):
            break
        time.sleep(2)
    else:
        return []
    found = []
    for node in ET.fromstring(xml).iter("node"):
        label = node.get("text") or node.get("content-desc") or ""
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if label and m:
            x0, y0, x1, y1 = map(int, m.groups())
            found.append((label, ((x0 + x1) // 2, (y0 + y1) // 2)))
    return found


def wait_for(text: str, seconds: int = 60) -> tuple[int, int]:
    deadline = time.time() + seconds
    while time.time() < deadline:
        for label, centre in screen():
            if text in label:
                return centre
        crashed()
        time.sleep(2)
    raise AssertionError(f"«{text}» did not appear on screen; seen: {[l for l, _ in screen()][:40]}")


def crashed() -> None:
    log = adb("logcat", "-d", "-b", "crash", check=False)
    if PKG in log:
        raise AssertionError("the app crashed:\n" + log[-4000:])


def shot(out: Path, name: str) -> None:
    png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout
    (out / f"{name}.png").write_bytes(png)


def main() -> int:
    apk, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    adb("wait-for-device")
    adb("logcat", "-c", check=False)
    try:
        adb("install", "-r", "-g", str(apk), timeout=600)  # -g: the permissions the app asks for, granted
        adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity", timeout=180)
        # 1. The main screen: the settings button and the capture controls.
        wait_for("⚙", 120)
        time.sleep(5)
        crashed()
        if not adb("shell", "pidof", PKG, check=False).strip():
            raise AssertionError("the app is not running after opening it")
        shot(out, "1_principal")
        # 2. ⚙ → Nube: where testers sign in.
        x, y = wait_for("⚙")
        adb("shell", "input", "tap", str(x), str(y))
        x, y = wait_for("Nube")
        adb("shell", "input", "tap", str(x), str(y))
        wait_for("Entrar", 30)
        shot(out, "2_nube")
        crashed()
        print("smoke test passed: opens, main screen, ⚙ → Nube → Entrar, no crash")
        return 0
    except (AssertionError, RuntimeError) as e:
        (out / "fallo.txt").write_text(str(e), encoding="utf-8")
        shot(out, "fallo")
        print("SMOKE TEST FAILED:", e, file=sys.stderr)
        return 1
    finally:
        (out / "logcat.txt").write_text(adb("logcat", "-d", check=False), encoding="utf-8", errors="replace")


if __name__ == "__main__":
    sys.exit(main())
