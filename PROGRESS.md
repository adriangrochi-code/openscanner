# Tisma Scanner: progress and handoff

State of the work on the scanner (Android capture app, `tools/` pipeline, `tisma_scan` for Tisma Cloud nodes).
Branch `scanner/next`. Newest first. Each entry says what was done, what was tested and what was not.

## Owner's decisions (2026-11-04)

- Open source under AGPL-3.0, confirmed by the owner directly on 2026-11-05: LICENSE (the full gnu.org text) and
  THIRD_PARTY.md (every dependency and its licence, all AGPL-compatible) are in the repo.
- Only relative poses between shots leave the phone. `gps.csv` stays on the phone, and Tisma Cloud refuses a zip
  that has one.
- Release in January 2027, with the desktop app and the Android slicer app; closed beta in December 2026.
- Changes to `tisma_scan`'s command line or summary are agreed with the Tisma Cloud server session first.
- (Relayed by the QA session, 2026-11-04 23:49 UTC.) The TismaScan-windows node package runs only on the owner's own
  servers and is never given to third parties, so no source companion artifact is needed for it. The note in
  BUNDLED_DLLS.txt still says what would be owed if that ever changed. The scanner app stays AGPL-3.0; the LICENSE
  file waits for the owner's direct confirmation.

## 2026-11-06: scale bias, cloud upload in the background

- Mat scale: AprilTag corner refinement instead of cornerSubPix (5ac5396): +0.4 % -> +0.03 to +0.06 % in synthetic
  views (see the mat entry below).
- Following a job on Tisma Cloud now survives network drops and 5xx (CloudClient.waitForJob, 88fe29f), tested with
  tools/fake_tisma_cloud.py --flaky-jobs.
- The upload and the wait for the result are protected by the foreground service (notification with progress,
  wake lock), like the processing; the service has holders so the upload ending does not stop the processing's
  protection.
- Then the whole send moved to WorkManager: cloud/CloudSend.kt (the logic, no Android, tested against
  fake_tisma_cloud.py: cut after one chunk, resumed on the same upload, part and measures downloaded) and
  CloudSendWorker (foreground notification, needs network, retries with exponential backoff up to 20 times, so it
  survives the app being closed and resumes when the network comes back). The ViewModel queues it and shows its
  state, also after the app is reopened. Dependency androidx.work 2.10.0 (Apache-2.0, in THIRD_PARTY.md).
  107 unit tests pass. **Not tested on a phone**: if Android refuses the foreground start from the background,
  the work still runs (and is retried if stopped), only without its notification.
- Tisma Cloud answers 507 (+ Retry-After 300) when its disk is full (its caeb35e), on POST /v1/scans and PUT
  .../data. The upload now stops at once on 507 and keeps what was received (no minutes of short retries); the
  worker retries later with its backoff and the app says "Tisma Cloud no tiene lugar ahora…". Tested with
  fake_tisma_cloud.py's POST /test/full (2026-11-09).
- Tisma Cloud's scan-e2e.yml now runs scanner/next through master, node and result zip, on its pushes and every
  Monday 06:17 UTC: if it goes red after a change here, the node contract moved; tell the server session first.
- APK for the owner: dist/TismaScanner-0.1.0-debug-<commit>.apk (dist/ is not in git).

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

## Tester kit (2026-11-09)

- docs/PROBAR.md (es) and docs/TESTING.md (en): 15 minutes, install, print the mat at actual size and check the
  bar, scan the Lejos pass to "Listo para enviar", send, report measures vs calipers + phone + paper size, and
  where the diagnostic log is (⚙ → Registro → Compartir registro de diagnóstico).
- tools/make_tester_kit.py builds TismaScanner-kit-<version>.zip (APK, both instructions, LICENSE, mats as PDF for
  A6..Tabloid). The "App Android" workflow builds it on every app change: artifact tisma-scanner-kit.
  First CI run green: 42646004610 (bdfd743), artifact tisma-scanner-kit 139 MB, kept 30 days.

## Emulator smoke test (2026-11-09)

- No KVM in these cloud sessions, so no emulator here. .github/workflows/app-emulator.yml runs one on GitHub's
  runner (Android 13, x86_64 with arm64 translation): tools/emulator_smoke.py installs the APK with its permissions,
  opens it, waits for the main screen, goes ⚙ → Nube → Entrar, fails on a crash in logcat, and keeps screenshots and
  logcat (artifact emulator-smoke). It also runs with a phone on adb.

- First green run 42717167937 (7517c2c, after an x86_64 build was added with -PwithX86_64: the image does not
  translate arm64). Its screenshots showed the tester instructions did not match the app: it opens in "Vídeo 360"
  (red button, % cubierto, then → and "Crear modelo final en …" on the review screen), while they described the
  "Fotos" mode (Lejos, dome, "Listo para enviar", ☁ Enviar). PROBAR.md/TESTING.md now follow Vídeo 360, GUIA/GUIDE
  describe both; the review button said "Tisma Server", now "Tisma Cloud".

## «Imprimir» and «Publicar» (owner's request, 2026-11-10; Tisma Cloud contract from the server session)

- On «Tu pieza», once the cloud part is there: «Imprimir» = POST /v1/files {"scan_job"} (the part stays in the
  account, Mi biblioteca on phone and PC; nothing uploaded, results kept 72 h); «Publicar» = a form (name, author,
  licence from the library's list, description) → POST /v1/library/models "from_file", with the files of the kept
  item (pieza.stl "project", pieza_informe.json "analysis"), the canonical manifest signed in Ed25519 with this
  install's peer key (POST /v1/library/peer-key once per account; 409 → register and retry), and a WebP thumbnail
  (≤ 64 KB) drawn on the phone from the STL (cloud/Thumbnail.kt).
- Ed25519 via Bouncy Castle (Android has it only from 13; minSdk 30): +11 MB on the debug APK until a release build
  with R8. The peer key's seed lives in the app's private preferences.
- Tested: CloudClientServerTest against fake_tisma_cloud.py (keep, keep again = same item, publish without key 409,
  with key 201, other key 422; the fake checks the signature with Python's cryptography); LibraryPublishTest
  (canonical JSON = Python's json.dumps(sort_keys, separators); signature verifies; limits); ThumbnailTest
  (binary/ASCII STL, render). 111 unit tests. **Not tried** against the real Tisma Cloud or on a phone.
- Tisma Cloud has it on main (its 50307c1, same contract). Library lists answer 503 until the library key is
  certified; a test master needs TISMA_LIBRARY_UNCERTIFIED=on. No G-code in the library (the app only publishes
  pieza.stl and its report).

## Bug pass (owner's rule: part of every 3 h on bugs and optimizing; 2026-11-10)

- Texts: the app talked to the person as tú except 8 messages (mostly the new cloud ones) in voseo
  ("Entrá", "probá", "escaneá", "borrá"); now all tú, as the docs. tisma_scan's error_es too ("Prueba con…").
- Measures: the CPU e2e part came out not closed, without volume (pieza.ply from run 42650053332: 278 open edges,
  27 boundaries). Postprocess simplified the mesh (1.3 M → 500 k triangles) after closing it, and the
  simplification opens holes and leaves crossed edges. Now it simplifies first, then closes and smooths.
  boundary_loops also follows a border through a pinched vertex and edges in crossed directions.
  tools/test_postprocess.py: a box on a table simplified to 8 k triangles stays closed, volume within 5 %.
  CI on 159a8e9 (42743873886): all green, measures 40.63 x 30.63 x 20.05 mm, mat scale -0.2 %, but still not
  closed: 3371 open edges in one border, and 10 edges in 4 faces.
- Second fix (this pass): that border was the base. Near the mat the walls are missing up to 13 mm (the
  "narrowing" seen before): the base border ran along the walls at 0-13.6 mm, 630 mm long, so it exceeded the
  cap limit (4 x extent), and the lowest-border rule had flattened a small hole instead. Now the base is the
  border with the widest footprint, and it goes straight down to Z = 0 (a vertical skirt, skirt_to_floor) and is
  capped flat there, whatever its length. The 10 edges in 4 faces were fans over holes whose border passes
  twice through a vertex; simple_loops splits those first. On the CI mesh the footprint is full from z = 0 up
  (40.6 x 30.6 at the bottom). Test: walls missing 2-13 mm along the base (fails on the old code, passes).
  scan-e2e.yml now asserts watertight with a volume within 15 % of 24000 mm³.
  CI on bd0f85a (42749126710, green; Windows green too): **closed, volume 24308 mm³ (+1.3 %)**, measures
  40.86 x 30.82 x 20.20 mm (+2.1 %, +2.7 %, +1.0 %), mat scale -0.2 %, mesh to points mean 0.019 mm. Area
  6194 mm² against the box's 5200 (+19 %): surface roughness of the dense mesh, not a fault; worth a look later.
- «Publicar» limits (hourly bug pass, 04:20 UTC): name/author/description were checked before trimming but sent
  trimmed, and counted in UTF-16 units while Tisma Cloud counts characters (Python len). A 120-character name
  with a trailing space, or 31+ emojis as author, was refused; the dialog's take(n) could cut an emoji in half.
  Now LibraryPublish.chars/clip/fieldsOk, shared by body and the dialog. Test: limitsCountWhatIsSent…
- Upload resume (hourly bug pass, 05:15 UTC): CloudSend asked the server how far a pending upload had got and,
  on any error (a network drop, a 5xx), took it as gone and started a new upload from zero: the whole scan sent
  again and the old one left half-done on the server. Now only 404/410 start over; anything else is an error
  for the worker to retry the same upload. fake_tisma_cloud.py: POST /test/status-down {"n"} answers the next
  n status checks with 503. CloudClientServerTest checks the same upload stays (fails on the old code).
- Base choice (hourly bug pass, 07:15 UTC): the base was the border with the widest footprint at any height, so
  a part with an open top wider than its base (a cup, a flared tray) would have had its rim pulled down to the
  floor (76 % volume error in the test). Now only borders reaching the lowest quarter of the part's height are
  candidates. Test: a flared open tube, volume within 1 % (fails on the old code). 20 pipeline tests pass.
  CI on 7c8a265 (42773889929, green; Windows green): closed, volume 24333 mm³ (+1.4 %), 40.74 x 30.69 x 20.13 mm, mat scale -0.2 %.
- tisma_scan work folder (hourly bug pass, 08:15 UTC): it was out.parent / "tisma_work", shared by every job with
  its output folder in the same place and never removed. reconstruct.py clears the COLMAP database and sparse
  model but not images/, undistorted/ or pieza.stl, so a job could run COLMAP over the previous scan's photos,
  zip its texture, or, with no part of its own, hand over the previous person's pieza.stl as a success. Now each
  job has its own folder (tempfile.mkdtemp next to the output), removed at the end (also frees the node's disk).
  Command line and summary unchanged. Test: two jobs side by side (fails on the old code). 21 pipeline tests pass.
  Server session (08:20): Tisma Cloud's nodes give -o <work>/<job>/scan-out and delete each job's folder, so the
  mix-up could not happen there; the fix matters for tisma_scan run by hand or by other nodes.
- Opening the scan zip (hourly bug pass, 09:15 UTC): load_session took the first folder in the zip as the scan.
  A scan zipped again on a Mac (__MACOSX first) or zipped without a folder failed or took the wrong folder. Now
  it is the folder of session.json (the shallowest, never __MACOSX), and a zip without one is a clear error.
  Also no limit on what a zip expands to: a zip bomb could fill the node's disk. Now refused before extracting
  if it expands to more than 20 x its size + 64 MB (photos barely compress) or 40 GB. Test with all four cases
  (fails on the old code). 22 pipeline tests pass. (zipfile already strips ".." and absolute paths.)
  Server session: the master already refuses scans over TISMA_MAX_SCAN_UNZIPPED_GB (12 GB) from the zip's
  directory; the 20x ratio here also catches small bombs under that. Both kept.
- Mat scale check (hourly bug pass, 10:15 UTC): the standard mat was dropped if it was 20 % from ANY other scale,
  so one bad INS scale (jerky moves) threw away the most accurate one even with the focus scale agreeing. Now
  against the median of the others: one bad scale neither vetoes the mat nor lets a mis-printed mat through.
  Tests: INS 30 % off with focus agreeing keeps the mat (fails on the old code); a 133 % print with one other
  scale near it is still refused. drop_blurry checked too: fine (each video keyframe is its own trigger).
- Upload resume state on disk (hourly bug pass, 11:15 UTC): CloudSendWorker saved the upload and job ids with
  apply(), which writes later; if the system killed the app right after the upload was created, the next try
  found nothing and sent the whole scan again. Now CloudSendWorker.prefsStore writes with commit() on the
  worker's thread (an IOException if it fails, so the worker retries). Test: CloudSendStoreTest. 114 app tests.

## Coverage table (owner, 2026-11-10 21:00; worked through until 2026-11-12 03:10 UTC)

«revisado» = read against what it must do, tested (unit/fake server/synthetic/CI), findings fixed with a test.
«parcial» = some of it reviewed, the rest pending. Times are UTC, 2026-11-10 unless said.

| Area | State | What was tested · findings |
|---|---|---|
| **App** | | |
| Capture: Vídeo 360 | revisado | 17:15 instructions; 11:45 thresholds match the guides. 00:15 (11-11) frame choice (moved enough, not too fast, max rate), counters and last pose reset per scan: fine. Coverage dome: its own tests (CoverageMapTest, CoverageGuidanceTest). |
| Capture: Fotos (passes, bursts, HDR, focus sweep) | parcial | 00:15 (11-11) automatic shot (KeyframeSelector: 8°/3 cm, never > 0.6 rad/s, every 3 s), fire(): marks before and after the burst, HDR and focus sweep only when still: fine. Pending: the camera side (needs a phone). |
| On-screen guides / instructions | revisado | 17:15 every «Toca/Pulsa» names a real control; GuideLabelsTest keeps guides and app in step. |
| Processing on the phone, resume after the app is killed | revisado | 12:20 pendiente.json steps, attempt guard, only a complete zip is offered. 23:15 killed DURING capture, or in the 1-9 s after «Terminar» before finish() wrote the steps: the scan stayed on the phone with no zip, no way to send it, taking space. Now marked «captura» from the start; on reopen its files are cut to the last whole line (ScanSession.repairTruncated, RepairTruncatedTest) and it gets its zip (+ quick mesh if saved). Phone check: kill the app mid-scan, reopen. |
| Cloud send (upload, resume, retry, 507) | revisado | 05:15 status-check failure no longer restarts the upload; 11:15 resume ids on disk at once (commit); 18:15 result tied to its zip, no double send. |
| Results: «Tu pieza», measures, STL | revisado | 11:45 area + volume shown; 18:15 previous scan's part no longer shown; two scans in a row: phone check by the owner. |
| «Imprimir» / «Publicar» | revisado | 04:20 limits in characters; 11:45 thumbnail drew hidden faces; 19:05 author = account's library_name, licence picker. 01:35 (11-11) the publication's code (its library id, lm_…, p2p_library 3 quater/3 novies: how one publication alone is blocked) is shown in «Tu pieza» after publishing and after reopening. The blocklist's `models`: the scanner never lists or opens library models, so nothing to filter. |
| Account (login, logout, expired session, server texts) | revisado | 16:15 wrong password text; 16:22 all server texts in Spanish. |
| Offline / back online | parcial | 20:30 network errors in Spanish. Pending: send queued offline then online (end to end). |
| Settings (⚙ Captura, Cámaras, calibration, INS, sensors) | parcial | 22:20 capture settings were memory-only: every reopen reset mode (Vídeo 360/Fotos), HDR, auto shots, deblur, AI, JPEG quality… to the defaults. Now saved/restored (SavedSettings; torch not kept on purpose). SavedSettingsTest; the restore in init is a phone check (change, close, reopen). Pending: Cámaras tab, calibration flows. |
| About / licences | revisado | 14:20 AGPL notices in the app, licences in the APK. Owner: © name, repo public? |
| Texts (plurals, tú) | revisado | 11:45, GuideLabelsTest + TextsTest. |
| **Mat** | | |
| Printable mat (sheets, margins, check bar) | revisado | 13:20 bar inside printer margins; every sheet tested. Already published mats must be made again. |
| Mat detection and scale | parcial | 10:15 median of other scales; synthetic views per sheet within 0.2 %. 21:05 partly hidden (middle, a corner, half-covered markers): within 0.05 %, test added; curled paper: by design (each marker's own 15 mm edges in 3D, median of many), not tested. Pending: glare/low light (needs real photos). |
| **Pipeline / nodes** | | |
| Scan zip intake | revisado | 09:15 session.json folder, __MACOSX, zip bomb. |
| Photo selection (blurry drop) | revisado | 10:15 fine. |
| Scale (mat > focus > INS) | revisado | 10:15 mat vs median. 21:15 INS: frames outside the trajectory's time span were all put at its first/last point (np.interp clamps): -27 % scale with 40 % of frames outside; now skipped, test (real: photos saved while «stopping» come after the trajectory ends). Focus scale read: per-point ratio, median, MAD rejection: fine. |
| COLMAP SfM / OpenMVS dense | revisado | CI full CPU path green. 00:15 (11-11) when COLMAP splits a scan into several models, the «largest» was picked by number of FILES (same for all): a sub-model could become the part. Now by photos placed (largest_model). No model at all: clear message instead of IndexError. The pipeline's own reasons now reach the person (error_es). Tests. 00:20 textura.zip: only a complete OBJ + .mtl + images (TextureMesh can crash mid-export, as on CI), test. 00:30: the OBJ was in metres and the reconstruction's frame (downloaded next to pieza.stl, it showed 1000x smaller and offset); now in pieza.stl's frame via the report's to_part_mm. Tests. |
| Table removal | revisado | 20:00 sparse table (part removed as table), thin parts message; 20:30 deterministic plane. 01:20 (11-11) REGRESSION from 20:00 seen on CI (7ab4c2d: box 40.9 x 37.7 mm): with small planes allowed, a thin layer of points under the mat (0.8 %) was the «lowest» horizontal plane and became the table; the mat stayed. Now the biggest horizontal plane with the part above. Test. |
| Meshing and closing | revisado | 03:20-07:15 base skirt, pinches, open top; 20:00 nothing below the table. |
| Report and measures | revisado | 12:20 NaN → null; 20:00 measures from measured points (thin plates +19 % → +1 %); 20:30 same scan, same measures. |
| tisma_scan (node program) | revisado | 08:15 own work folder; summary contract with the server session. |
| Windows node package | revisado | CI green on every push; licences checked. |
| Tester kit and release workflow | revisado | 14:20 SOURCE.txt + THIRD_PARTY.md; 15:20 all pipeline tests on CI. 01:30 (11-11) notes updated in docs/releases/borradores/0.1.0-beta.1.md (scale 0.1-0.2 %, author = account, measures, rescue, settings, licence): outside the workflow's path filter, so nothing is published. **On the owner's «sí»:** `git mv` it over docs/releases/0.1.0-beta.1.md and push to scanner/next: the workflow builds, tests and publishes scanner-v0.1.0-beta.1. |

**Open (01:20, 11-11):** Open3D's Poisson segfaulted once on CI (7c63b75, inside test_the_same_cloud_gives_the_same_
measures, five reconstructions in a row; the next run passed). A native crash cannot be caught in Python: on a node
it would fail a person's job at random. 01:50: Poisson now runs in a child process (spawn), retried once if it dies; freeze_support in tisma_scan, reconstruct and tisma_server entries for the Windows .exe. Test with a child made to abort. Windows CI to confirm.

## Deep bug hunt (owner, 2026-11-10 20:00 → 11-12 03:10): hard shapes in postprocess

Synthetic clouds of known parts on a table at real density (0.05-0.1 mm between points, as OpenMVS on CI):

| Part | Before | After |
|---|---|---|
| 2 mm plate | thickness 2.38 (+19 %) | 2.02 (+1.2 %) |
| 1 mm plate | 1.32 (+32 %), not closed | 0.99 (-1 %), **still not closed** |
| 6x5x4 mm | height +1.6 % | +1.3 %; footprint +2.5/+3.2 % |
| 40x30x20 box, table with ~1 % of the points | whole part removed as «table» → error | 40.15 x 30.15 x 20.08, closed, volume -0.1 % |
| thin part, coarse cloud | NumPy «zero-size array» | clear message (strip removed with the table) |

Fixes (tools/postprocess.py), each with a test that fails without it:
- Measures from the measured points (0.05-99.95 percentile, base at Z = 0), not from the mesh's extreme vertices,
  which Poisson and the smoothing leave a few tenths past the surface (mesh box kept as mesh_dimensions_mm_xyz).
  And nothing of the mesh below the table after smoothing. test_a_thin_plate_measures_its_thickness.
- The support plane: searched down to small planes (0.5 % / 200 points, up to 8), and a plane with most other
  points below it is never the table (it was the box's top with a sparse table).
  test_a_sparse_table_under_a_dense_part_is_still_the_table.
- Nothing left above the table: a clear SystemExit instead of a NumPy error. test_a_part_thinner_than_….
- Hour 2: the same cloud gave different measures and volume on each run: Open3D's RANSAC runs in threads and
  picks a slightly different table plane each time, even seeded (0.22 % on the box; a 40x30x1 plate gave 1008 and
  1154 mm³ on two runs). refine_plane fits each plane by least squares until it settles: 0.01 %, the rest is Poisson's
  own threads. test_the_same_cloud_gives_the_same_measures. The 1 mm plate now closes; its volume -4 % (1154/1200).
- Offline: sign-in, «Imprimir», «Publicar» and the upload's last failure showed Java's English network text
  («Unable to resolve host…»). CloudClient.userMessage gives Spanish by kind. UserMessageTest. 133 app tests.

## «Publicar»: the author is the account (owner's rule, 2026-11-10 18:45; spec 967f7a3, p2p_library 7 bis)

- A scanned piece belongs to the person who scanned it. The dialog no longer has a free «Autor» field (it was
  filled with the part of the e-mail before the @, which would have gone public): it shows «Autor: <name> (tu
  cuenta)», the account's one public name in the library (GET /v1/me → library_name, never the e-mail), with the
  licence picker as before. If the account has no name yet, it asks once («Nombre en la biblioteca») and sends it
  as author_name; afterwards author_name is not sent (the server ignores it). CloudClient.libraryName /
  setLibraryName; LibraryPublish.body with author optional (not in the signed manifest, unchanged).
- Only registered accounts publish: login already needs a confirmed e-mail (server). New server texts translated:
  «Elige el nombre con el que vas a publicar», «La publicación fue retirada de la biblioteca».
- fake_tisma_cloud.py follows the rule (library_name, GET /v1/me, PUT /v1/me/library-name). CloudClientServerTest:
  no name → 422 in Spanish; first publication sets it; later ones without author_name, another author_name ignored;
  renaming. 131 app tests pass. The About «©» stays «los autores de Tisma Scanner» (owner via QA).
  Server contract on tisma-cloud 717a5a3; PUT /v1/me/library-name is limited to 5 a day (fed260b): 429 translated.

## Bug pass 18:15 UTC (2026-11-10): the cloud part of the previous scan

- Starting a scan did not clear the cloud state: after a first cloud scan, the next scan's «Tu pieza» showed the
  previous part («✓ Pieza lista», «Pieza STL») and its «Imprimir»/«Publicar» acted on the OLD job; in Fotos mode
  the stale «⬢ Pieza STL» took the place of «☁ Enviar», so the new scan could not be sent from the camera. And
  WorkManager's watcher could re-apply an old job's result at any time. Now each send is tagged with its zip
  (CloudSendWorker.tagFor/isFor), the watcher skips jobs of another zip, and starting a scan clears the cloud and
  library state. Also: with this scan's part back, «Crear modelo final» reads «✓ Modelo final listo» and does not
  send again (it would spend another scan of the plan); sendToCloud refuses too.
  Test: CloudSendWorkerTagTest. **Not unit-tested**: the reset in startScan and the disabled button (the view model
  needs the camera); to check on a phone: two scans in a row with a cloud send each. 130 app tests pass.

## Bug pass 17:15 UTC (2026-11-10): instructions that name a button that is not there

- Vídeo 360's last instruction said «¡Listo! Toca detener»; there is no «detener» button, you stop with the red
  record button (as the guides say). Now «¡Listo! Toca el botón rojo para terminar». CaptureGuideTest.
- The Nube tab said «pulsa ☁ Enviar en la pantalla de la cámara»; in Vídeo 360 (the default) the camera has no
  ☁ Enviar, it is «Crear modelo final en Tisma Cloud» on «Tu pieza». The hint now follows the mode (sendHint,
  TextsTest). Every other «Toca/Pulsa» text checked. 128 app tests pass.

## Bug pass 16:15 UTC (2026-11-10)

- Sign-in with a wrong password said «La sesión venció o se cerró: entra de nuevo» (every 401 got that text).
  CloudClient.login now says «Correo o contraseña incorrectos». CloudClientServerTest checks the text (fails on
  the old code). 123 app tests pass.
- Tisma Cloud's `detail` texts are English for every client (server session, 16:22): the app now translates the
  ones a person can meet (account, sending, «Imprimir», «Publicar»; list from the server session, put in tú) by
  status code + start of the text, keeping numbers (CloudClient.HttpError.spanish). A disabled account at sign-in
  says so; any other 422 on publish shows «No se pudo publicar» and logs the detail. ServerTextsTest. 127 app tests.

## Bug pass 15:20 UTC (2026-11-10): pipeline tests on CI

- Windows CI went red on e89bc97: the new kit test was in test_tisma_scan.py, which the Windows job runs without
  Pillow. Moved to tools/test_tester_kit.py (skips without Pillow/OpenCV).
- Found while fixing it: no workflow ran test_postprocess.py (all of today's mesh fixes) or the kit test, and only
  the Windows job ran test_reconstruct.py and test_tisma_scan.py. scan-e2e.yml now runs `pytest tools/` before
  the full scan (pillow and pytest added there).
  CI on 4f3cfcb: Windows green again (42842917880); scan-e2e's «Pruebas del pipeline» step green on Linux.

## Bug pass 14:20 UTC (2026-11-10): licence notices

- Missing: the app had no licence notice at all. AGPL-3.0 section 5(d) asks an interactive interface to show
  Appropriate Legal Notices (licence, no warranty, how to see the licence). Now ⚙ → Registro → «Acerca de»:
  version, AGPL-3.0, no warranty, where the source is, and buttons that show the full LICENSE and THIRD_PARTY.md,
  which the build copies from the repo root into the APK (assets/legal/, so they never drift). AboutTest.
- The tester kit now carries THIRD_PARTY.md (Bouncy Castle and ONNX Runtime are MIT: their notice must travel with
  copies) and SOURCE.txt (repository, exact commit, source zip link). Test in test_tisma_scan.py.
- **For the owner:** (1) the notice says «© 2026 los autores de Tisma Scanner»: no copyright holder is named
  anywhere in the repo; say whose name goes there. (2) The source link is github.com/adriangrochi-code/openscanner:
  testers can only follow it if that repository is public (or the source is offered another way). Not legal advice.

## Bug pass 13:20 UTC (2026-11-10)

- The mat's 100 mm check bar was 3 mm from the bottom edge, where many printers cannot print (lasers 4-5 mm,
  inkjets 5 mm and more at the bottom): clipped or missing, and it is the check the guides ask testers to make.
  Now at the 8 mm margin like the rest. Test: on every sheet nothing printed within 5 mm of an edge, and the bar
  measures check_bar_mm (fails on the old code). 24 pipeline tests pass. **Mats generated before this commit (any
  already published) should be made again** (`python3 tools/make_scan_mat.py --all --out-dir tapetes`); the kit
  makes them fresh.

## Bug pass 12:20 UTC (2026-11-10)

- A non-finite measure (NaN/inf: e.g. the mesh-to-points distance over an empty cloud) was written as NaN /
  Infinity into pieza_informe.json and tisma_scan's summary: not JSON, so the app's org.json and strict parsers
  refuse the whole report. Now null, written with allow_nan=False (summary and report); the log says "sin medir".
  Test: test_a_measure_that_is_not_a_number_is_null_in_the_summary (fails on the old code). 23 pipeline tests.
  Server session told (a summary value may now be null).
- Checked, fine: the on-phone resume (pendiente.json, attempts guard), only a completed zip is offered.
  Not changed: the zip also carries imu.csv and the phone's preview meshes, which the server does not read
  (documented scan format; to agree with the server session before leaving them out).

## Missing and wrong outputs (owner's request via QA, 2026-11-10 11:45 UTC)

Checked against the guides (GUIA/GUIDE, PROBAR/TESTING), the beta notes and the owner's requests. Each fix has its
test; GuideLabelsTest now keeps the guides' quoted labels and the tú form in step with the app.

| What | Found | Fix |
|---|---|---|
| Library thumbnail | depth sign reversed: thumbnails showed the base and back faces | 4b361f7, ThumbnailTest |
| Measures with the STL | guides promise size, area and volume; app showed size + «cerrada» only | eaae3ac, CloudSendSummaryTest |
| Singular/plural | «1 fotos», «1 descartadas», «1 fotogramas», «1 vistas con IA» | eaf242a, TextsTest |
| Guides vs app | «¡DEMASIADO RÁPIDO!» in GUIA/GUIDE, never shown by the app | eaf242a, GuideLabelsTest |
| Voseo | «mantené el móvil quieto», «sacás cada foto» | eaf242a, GuideLabelsTest |
| Imprimir / Publicar, Nube, Registro, Enviar, Pieza STL, Terminar, «% cubierto», «Listo para enviar» | present | - |
| 75 % / 40 photos / 20 % blurry in the guides | match MIN_COVERAGE, MIN_IMAGES, MAX_BLUR_RATIO | - |
| Privacy: location stays on the phone | gps.csv left out of the zip; no GPS tag in the photos | - |
| Mesh holes, scale | closed (CI volume +1.4 %), mat scale -0.2 % (fixed earlier today) | - |
| Beta notes: «error de alrededor del 0,1 %» | CI through COLMAP gives -0.2 %: should say «entre 0,1 y 0,2 %» | **not edited**: a change to docs/releases/*.md publishes the pre-release, which waits for the owner's «sí» |

## Open for the beta

- "Listo para enviar" counts a dome sector with 1 photo as covered (>= 75 % of the Lejos pass, coverage(1)); the
  dome paints it yellow and only 2+ green. Not changed blind: one pass at a height fires every 8 deg over 15 deg
  sectors (about 2 per sector). quality_summary.json already has coverage_2plus: compare it with the part's
  quality on the beta scans and raise the rule (e.g. coverage(2) >= 50 %) if 1-photo sectors give holes. The
  tester instructions say so (greener is better) instead of "turns green".

- The mats: the owner publishes them (2026-11-04) so anyone can print one, from a small sheet to a big one.
  `python3 tools/make_scan_mat.py --all --out-dir tapetes` makes A6, A5, A4, A3, Letter, Legal and Tabloid, each
  as PDF at the exact page size (what to print, at "actual size"), PNG with its DPI, and .json. Owner's idea: a
  chessboard of fixed squares, so any sheet works. It is a ChArUco-like board: 20 mm squares, a 15 mm
  DICT_4X4_250 marker in each white square, the full multiscale texture in the others (unique points for SIFT;
  2026-11-09: a darkened texture there let COLMAP register only 3 of 36 synthetic views, the full one 36 of 36). Synthetic
  views at 35 cm, 2560 px: scale +0.37 to +0.40 % on every sheet at first. Cause found (2026-11-06): cornerSubPix
  is made for X-corners and pulled the markers' L-corners outwards. With the detector's AprilTag refinement:
  **+0.03 to +0.06 %** on A6, A4 and Tabloid at 25, 35 and 55 cm (spread under 0.12 %). It costs 0.25 s per 3200 px
  photo, so at most 80 photos spread over the scan are used (MAT_MAX_IMAGES).
  Through COLMAP (synthetic scan on the A5 mat, 36 views, 800x600): mat scale within 0.1-0.2 % of the exact one;
  scan-e2e.yml now checks the scale comes from the mat, within 1 %. First run green (42650053332, 1d7a7da, full
  CPU path with OpenMVS): mat scale -0.2 % vs exact, part 40.85 x 30.87 x 20.24 mm (+2.1 %, +2.9 %, +1.2 %: the
  rest is the dense cloud and mesh, not the scale). Windows package green on it too (42650053314, 640x480).
  Not yet tried: printing one.

## Next

1. The full path on a real or turntable photo set (not only the synthetic box), with measured accuracy.
2. Capture: coverage, "Listo para enviar", resuming uploads, accuracy of the scale (mat > focus > INS).
3. User docs: docs/GUIA.md (es) and docs/GUIDE.md (en) are written from the code's labels and thresholds;
   to be checked against the app on a phone with the QA session.
