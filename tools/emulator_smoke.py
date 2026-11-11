#!/usr/bin/env python3
"""
A smoke test of the app on an Android emulator (or a phone over adb), the first steps of docs/PROBAR.md:
install, open, the main screen without a crash, ⚙ → Nube with its sign-in. Then two of the phone checks of
PROGRESS.md: a capture setting survives closing the app, and a scan cut while recording (the state a killed app leaves:
pendiente.json «captura», a half line in a .csv) is rescued with its zip when the app opens. Screenshots and the
logcat go to <out>.

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


def restart() -> None:
    """Close the app as the system does (force-stop) and open it again."""
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity", timeout=180)
    wait_for("⚙", 120)


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
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        # 3. A capture setting survives closing the app: Vídeo 360 → Fotos, force-stop, open again.
        x, y = wait_for("▶", 30)                               # Vídeo 360's photo/video toggle
        adb("shell", "input", "tap", str(x), str(y))
        wait_for("Vídeo 360", 30)                              # the Fotos screen shows the mode chips
        time.sleep(2)
        restart()
        wait_for("Vídeo 360", 60)
        if any("Pon la pieza en el recuadro" in l for l, _ in screen()):
            raise AssertionError("the app opened in Vídeo 360 again: the chosen mode (Fotos) was not kept")
        shot(out, "3_fotos_tras_reabrir")
        x, y = wait_for("Vídeo 360")
        adb("shell", "input", "tap", str(x), str(y))           # back to Vídeo 360 for whoever comes next
        # 4. A scan cut while recording is rescued: its zip appears after the app opens.
        scans = f"/sdcard/Android/data/{PKG}/files/scans"
        cut = f"{scans}/scan_cortado"
        adb("shell", "mkdir", "-p", f"{cut}/images/0")
        adb("shell", f"echo '{{\"pasos\":[\"captura\"],\"intentos\":0}}' > {cut}/pendiente.json")
        adb("shell", f"printf 'frame,trigger,pass,camera_id,t_ns,file\\n1,1,FAR,0,100,images/0/100.jpg\\n2,1,FA' > {cut}/frames.csv")
        adb("shell", f"echo '{{}}' > {cut}/session.json")
        restart()
        deadline = time.time() + 90
        while time.time() < deadline and "scan_cortado.zip" not in adb("shell", "ls", scans, check=False):
            crashed(); time.sleep(3)
        listing = adb("shell", "ls", scans, check=False)
        if "scan_cortado.zip" not in listing:
            raise AssertionError(f"the scan cut while recording was not rescued (no zip): {listing.split()}")
        frames = adb("shell", "cat", f"{cut}/frames.csv", check=False)
        if frames.rstrip().endswith("FA"):
            raise AssertionError("the half line of frames.csv was left in the rescued scan")
        crashed()
        print("smoke test passed: opens, main screen, ⚙ → Nube → Entrar, the mode is kept after closing, "
              "a scan cut while recording is rescued, no crash")
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
