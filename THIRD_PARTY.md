# Tisma Scanner: licence and third-party software

Tisma Scanner (the Android app, the `tools/` pipeline and `server/`) is free software under the
**GNU Affero General Public License 3.0** ([LICENSE](LICENSE)). Owner's decision, 2026-11-04.

Every dependency below has a licence compatible with the AGPL-3.0. A new dependency is added here, with its
licence, before it is used; non-commercial or research-only licences are not accepted.

## Android app (`app/`)

| Dependency | Version | Licence | Use |
|---|---|---|---|
| AndroidX Compose (BOM 2024.12.01: ui, material3, ui-tooling-preview) | 2024.12.01 | Apache-2.0 | UI |
| androidx.activity:activity-compose | 1.9.3 | Apache-2.0 | UI |
| androidx.lifecycle (viewmodel-compose, runtime-compose) | 2.8.7 | Apache-2.0 | UI state |
| androidx.core:core-ktx | 1.15.0 | Apache-2.0 | Android APIs |
| kotlinx-coroutines-android | 1.9.0 | Apache-2.0 | concurrency |
| Kotlin standard library | 2.1.21 | Apache-2.0 | language |
| ONNX Runtime for Android (com.microsoft.onnxruntime:onnxruntime-android) | 1.19.2 | MIT | on-device AI inference |
| Depth Anything 3 Small, as ONNX (`app/src/main/assets/models/da3_small.*`) | DA3-SMALL | Apache-2.0 | depth model (model weights) |

Only for tests and debug builds (not in the release app): JUnit 4.13.2 (EPL-1.0), Robolectric 4.14.1 (MIT),
Roborazzi 1.32.2 (Apache-2.0), androidx.compose.ui:ui-test (Apache-2.0).

The larger Depth Anything 3 models (Large, Giant, Nested) are CC BY-NC (non-commercial) and are **not** used:
`tools/export_da3_onnx.py` refuses them.

## Pipeline (`tools/`) and server (`server/`)

| Dependency | Licence | Use |
|---|---|---|
| Python 3 | PSF-2.0 | language |
| NumPy | BSD-3-Clause | arrays |
| OpenCV (opencv-contrib-python) | Apache-2.0 | images, ArUco, SIFT |
| Open3D | MIT | point cloud, mesh, STL (`postprocess.py`) |
| Pillow | MIT-CMU (HPND) | mat PNG/PDF (`make_scan_mat.py`) |
| COLMAP 3.9 (separate program) | BSD-3-Clause | photogrammetry |
| GLOMAP (separate program, optional) | BSD-3-Clause | global SfM |
| OpenMVS 2.x (separate program, unmodified) | AGPL-3.0 | dense cloud and mesh on the processor |
| ONNX Runtime (optional) | MIT | AI depth model on the server |
| PyTorch (optional) | BSD-3-Clause | learned matching, model export |
| LightGlue + ALIKED (optional, `learned_matching.py`) | Apache-2.0 / BSD-3-Clause | learned matching (SuperPoint is research-only and is not used) |
| Depth Anything 3 (only to export the model, `export_da3_onnx.py`) | Apache-2.0 (DA3-SMALL / DA3-BASE) | model export |
| PyInstaller (builds the Windows .exe) | GPL-2.0 with bootloader exception | packaging |

## Windows scan node package (TismaScan-windows)

It bundles the official releases of COLMAP 3.9.1 and OpenMVS 2.4.0 and the libraries inside them (Qt 5, Boost,
OpenCV, SuiteSparse, the CUDA runtime and others). The licence of each bundled DLL is listed by
`server/third_party/check_bundled.py` in `licenses/BUNDLED_DLLS.txt`, with the texts in
`licenses/third-party/`, and the CI fails on a DLL with no licence. That package runs only on the owner's own
machines and is never given to third parties (owner's decision, 2026-11-04).
