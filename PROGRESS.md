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

## 2026-11-04: setup

- `scanner/next` = `scan/cpu-pipeline` at cb85aef, which already has all of `cloud/tisma-cloud`. Nothing was left to
  merge.
- Pipeline tests (`python3 -m pytest tools/`): 12 passed, locally with Python 3.13 and opencv-contrib 5.0.0. Open3D
  0.20 needs `libegl1 libgl1` on Linux.
- App: `./gradlew :app:assembleDebug :app:testDebugUnitTest` with Android SDK 35 (see the next entry for the result).
  Maven Central sometimes answers 429 through the session proxy: `--max-workers=1` and a retry got through.
- Full CPU path (CI "Escaneo completo en el procesador", run 41880333430 at 193b2aa, Ubuntu 24.04, COLMAP 3.9.1
  without CUDA, OpenMVS v2.3.0 built without CUDA): synthetic 40 x 30 x 20 mm box -> pieza.stl measuring
  41.17 x 31.08 x 20.14 mm (+2.9 %, +3.6 %, +0.7 %; the scale comes from the INS trajectory). It took 21 min on the
  runner. The mesh is not watertight, so there is no volume. **Not run in this session**: building OpenMVS here was
  refused by the session's permissions (it downloads and builds outside code), so the local check stops at COLMAP.
- Windows node package (TismaServer.exe workflow, runs 24 and 25): fails at the first COLMAP step. Through
  `colmap/COLMAP.bat`, cmd.exe splits the commas of `--ImageReader.camera_params`, and COLMAP refuses the camera.
  Fix (tisma_scan.bundled_programs): use `colmap/bin/colmap.exe` and set what COLMAP.bat sets (its `lib/` on the
  PATH, `QT_PLUGIN_PATH`). COLMAP.bat is only the last fallback. Raised with the server session. It is checked by
  the unit test only; the Windows run on CI will confirm it.

## What needs an NVIDIA GPU

- Nothing for a scan to finish: on the processor, COLMAP does SIFT and matching, and OpenMVS
  (`DensifyPointCloud`) does the dense cloud.
- With CUDA it is faster: COLMAP's SIFT on the GPU, and the COLMAP dense cloud (`patch_match_stereo`), which only
  exists with CUDA. `tisma_scan --check` says which one this machine uses.

## Next

1. The full path on a real or turntable photo set (not only the synthetic box), with measured accuracy.
2. Capture: coverage, "Listo para enviar", resuming uploads, accuracy of the scale (mat > focus > INS).
3. User docs in Spanish and English.
