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
- (Relayed by the QA session, 2026-11-04 23:49 UTC.) The TismaScan-windows node package runs only on the owner's own
  servers and is never given to third parties, so no source companion artifact is needed for it. The note in
  BUNDLED_DLLS.txt still says what would be owed if that ever changed. The scanner app stays AGPL-3.0; the LICENSE
  file waits for the owner's direct confirmation.

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
  (3852e82). **Green** on scanner/next, runs 41903305452 (74af7ee) and 41903680499 (d720958): colmap.exe alone,
  `tisma_scan.exe --check`, and the synthetic box (16 views, 640x480) with the package up to pieza.stl:
  42.43 x 32.49 x 20.62 mm (+6 %, +8 %, +3 %, scale from the INS: the synthetic scan has no ArUco mat), closed
  mesh, 26.70 cm3, 16 min on a Windows runner without an NVIDIA card. Sent to the server session.
- CPU e2e on scanner/next: green for 815ed82 (run 41895135244), d720958 (41903680460) and the chessboard mat
  9cb085b (41909006923). Windows green for 9cb085b (41909007022).
- Licences of the Windows package (asked by QA through the server session; 830da96, run 41910637103 green): the
  COLMAP/OpenMVS licence checks now fail the job, every licence/notice file of both release zips is kept in
  licenses/colmap-release/ and licenses/openmvs-release/, and the OpenMVS v2.4.0 source ships as
  source/openMVS-2.4.0-source.zip. The releases ship no notices at all for the 95 DLLs they bundle, so
  server/third_party/ maps each DLL to its library (dlls.csv), keeps each library's official licence text (texts/,
  sources.csv) and check_bundled.py puts them in licenses/third-party/ + licenses/BUNDLED_DLLS.txt, failing the job
  on an unmapped DLL (1d07f56; package step green in run 41919077404).
- **Open:** libspqr.dll (SuiteSparse SPQR) is GPL-2.0+; Qt 5, GMP, LibRaw and CHOLMOD are LGPL. Their source must be
  offered with the package (Qt's archive link is in BUNDLED_DLLS.txt; the others are not done). Agreed with the
  server session (its view, not legal advice; the owner decides): while the package only runs on Tisma's own
  machines (beta, Tisma Cloud nodes) it is not conveyed, so nothing blocks the beta. Only if the owner's rule (never given to
  third parties) changed: a TismaScan-windows-source artifact with the exact sources of those libraries at the DLLs' versions, and a
  written offer in BUNDLED_DLLS.txt (whose note says so, 994a1c2). If the versions cannot be pinned from the
  official binaries, build COLMAP and OpenMVS in CI.

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

- The mats: the owner publishes them (2026-11-04) so anyone can print one, from a small sheet to a big one.
  `python3 tools/make_scan_mat.py --all --out-dir tapetes` makes A6, A5, A4, A3, Letter, Legal and Tabloid, each
  as PDF at the exact page size (what to print, at "actual size"), PNG with its DPI, and .json. Owner's idea: a
  chessboard of fixed squares, so any sheet works. It is a ChArUco-like board: 20 mm squares, a 15 mm
  DICT_4X4_250 marker in each white square, dark texture in the black ones (unique points for SIFT). Synthetic
  views at 35 cm, 2560 px: scale +0.37 to +0.40 % on every sheet, spread under 0.1 %. That constant +0.4 % is
  likely where the detector puts the marker corners: check it with a real printed mat before correcting it.
  Not yet tried: printing one, and a full scan on it through COLMAP. It uses a lot of black ink.

## Next

1. The full path on a real or turntable photo set (not only the synthetic box), with measured accuracy.
2. Capture: coverage, "Listo para enviar", resuming uploads, accuracy of the scale (mat > focus > INS).
3. User docs: docs/GUIA.md (es) and docs/GUIDE.md (en) are written from the code's labels and thresholds;
   to be checked against the app on a phone with the QA session.
