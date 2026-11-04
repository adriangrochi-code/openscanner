# Tisma Scanner: progress and handoff

State of the work on the scanner (Android capture app, `tools/` pipeline, `tisma_scan` for Tisma Cloud nodes).
Branch `scanner/next`. Newest first. Each entry says what was done, what was tested and what was not.

## Owner's decisions (2026-11-04)

- Open source under AGPL-3.0. LICENSE is not added yet: the owner still has to confirm it directly. When they do,
  add the full AGPL-3.0 text and record every dependency (all AGPL-compatible) in a THIRD_PARTY file.
- Only relative poses between shots leave the phone. `gps.csv` stays on the phone, and Tisma Cloud refuses a zip
  that has one.
- Release in January 2027, with the desktop app and the Android slicer app; closed beta in December 2026.
- Changes to `tisma_scan`'s command line or summary are agreed with the Tisma Cloud server session first.

## 2026-11-04: scale accuracy, Windows package

- **The mat's scale was never used on Tisma Cloud**: tisma_scan and TismaServer never pass `--mat`, so every
  cloud scan took its scale from the focus or the INS (5-10 %). Now (d720958) reconstruct.py looks for the
  standard mat (make_scan_mat.py's default, 30 mm markers) in every scan. As the marker size is assumed, it is
  kept only with a spread under 2 % and within 20 % of the focus/INS scale; a mat with other markers needs its
  .json with `--mat`. Measured on synthetic views of the mat (tools/test_reconstruct.py): **+0.34 %** scale error,
  spread 0.07 %; a 40 mm mat is rejected. **Not tested** on a real printed mat or through COLMAP end to end. If a
  scan has no focus sweep and no INS movement, a wrong-size mat would be used without a check.
- Windows package: with COLMAP.bat, `%1` cuts the arguments at commas (the camera parameters), and QT_PLUGIN_PATH is
  `lib\plugins`. tisma_scan.exe (PyInstaller) passed its DLL directory on to COLMAP: reset while starting a program
  (3852e82). With the .bat's PATH and QT_PLUGIN_PATH, `colmap.exe help` starts on the runner (run 41901450857);
  `--check` and the synthetic scan with the package are still to be seen green (run of 74af7ee and later).
- CPU e2e on scanner/next (815ed82, run 41895135244): green.

## 2026-11-04: setup

- `scanner/next` = `scan/cpu-pipeline` at cb85aef, which already has all of `cloud/tisma-cloud`. Nothing was left to
  merge.
- Pipeline tests (`python3 -m pytest tools/`): 12 passed, locally with Python 3.13 and opencv-contrib 5.0.0. Open3D
  0.20 needs `libegl1 libgl1` on Linux.
- App: `./gradlew :app:assembleDebug :app:testDebugUnitTest` with Android SDK 35 and JDK 21: the debug APK builds and
  the 105 unit tests pass (0 failed), at 0f8500f. Not run on an emulator or a phone in this session.
  In a cloud session, Maven Central (repo.maven.apache.org) answered 429 through the proxy on every try. What got
  through, on this machine only and not in the repo: a Gradle init script that swaps Maven Central for Google's
  mirror (https://maven-central.storage-download.googleapis.com/maven2/), and Robolectric's android-all-instrumented
  jars (14 and 15) and nativeruntime-dist-compat put in ~/.m2/repository from that mirror, with their sha1 checked.
- Full CPU path (CI "Escaneo completo en el procesador", run 41880333430 at 193b2aa, Ubuntu 24.04, COLMAP 3.9.1
  without CUDA, OpenMVS v2.3.0 built without CUDA): synthetic 40 x 30 x 20 mm box -> pieza.stl measuring
  41.17 x 31.08 x 20.14 mm (+2.9 %, +3.6 %, +0.7 %; the scale comes from the INS trajectory). It took 21 min on the
  runner. The mesh is not watertight, so there is no volume. **Not run in this session**: building OpenMVS here was
  refused by the session's permissions (it downloads and builds outside code), so the local check stops at COLMAP.
- Windows node package (TismaServer.exe workflow, runs 24 and 25): fails at the first COLMAP step. Through
  `colmap/COLMAP.bat`, cmd.exe splits the commas of `--ImageReader.camera_params`, and COLMAP refuses the camera.
  Fix (tisma_scan.bundled_programs): use `colmap/bin/colmap.exe` and set what COLMAP.bat sets (its `lib/` on the
  PATH, `QT_PLUGIN_PATH`), also when the person sets TISMA_COLMAP to a COLMAP.bat (e964132, 815ed82). Agreed with
  the server session, which now leaves server-windows.yml to this session; it keeps the tisma-cloud side. Checked by
  the unit test; the Windows CI run on scanner/next has to confirm it. PR #2 (scan/cpu-pipeline) does not have this
  fix yet: how it gets there is the owner's call.

## What needs an NVIDIA GPU

- Nothing for a scan to finish: on the processor, COLMAP does SIFT and matching, and OpenMVS
  (`DensifyPointCloud`) does the dense cloud.
- With CUDA it is faster: COLMAP's SIFT on the GPU, and the COLMAP dense cloud (`patch_match_stereo`), which only
  exists with CUDA. `tisma_scan --check` says which one this machine uses.

## Open for the beta

- The mat cannot be downloaded by a user today: it is made with `tools/make_scan_mat.py` (Python). The guides say
  "print the Tisma Scanner mat"; the A4/A3/Letter PNGs (and their 100 mm check bar) should be published, in the
  app or on the Tisma site.

## Next

1. The full path on a real or turntable photo set (not only the synthetic box), with measured accuracy.
2. Capture: coverage, "Listo para enviar", resuming uploads, accuracy of the scale (mat > focus > INS).
3. User docs: docs/GUIA.md (es) and docs/GUIDE.md (en) are written from the code's labels and thresholds;
   to be checked against the app on a phone with the QA session.
