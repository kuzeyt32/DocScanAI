# DocScan AI — Model Sources & Pinned Artifacts

> Companion to [`MODELS.md`](MODELS.md) / [`TECHNICAL_SPEC.md`](TECHNICAL_SPEC.md).
> Records the **real, verified** download URL + SHA-256 for each model as it is pinned by the
> implementation subtasks. This file is the source of truth for what is actually shipped/pinned.

Last updated by: subtask H7 (multi-file grid dewarp, single-sample remap, detection refinement).

---

## 1. Pinned now ✅

### `m1c_detect` — DocAligner LCNet100 page-corner detector (**PRIMARY detection model**, SUBTASK H4)

| Field | Value |
|---|---|
| **ID** | `m1c_detect` |
| **Artifact** | DocAligner **LCNet100** heat-map corner regressor, `lcnet100_h_e_bifpn_256_fp32.onnx` (unchanged re-host of DocsaidLab/DocAligner's weights) |
| **Download URL** | `https://huggingface.co/7rplus/pagescan-weights/resolve/main/lcnet100_h_e_bifpn_256_fp32.onnx` |
| **SHA-256** | `f4117b786e3a18470f3865c93f3c2bd69d9b998edd60f385574a5c665e79594e` |
| **Size** | `4,767,987` bytes (≈ 4.5 MiB) |
| **License** | **Apache-2.0** (© DocsaidLab) — the artifact is a byte-identical re-host; credit to DocsaidLab/DocAligner. |
| **Upstream / source** | Original project `https://github.com/DocsaidLab/DocAligner`; pinned artifact re-hosted at `https://huggingface.co/7rplus/pagescan-weights` because the upstream ships only an undocumented Google-Drive auto-downloader. |
| **Registry** | Pinned in [`ModelRegistry`](app/src/main/java/com/docscanai/models/ModelRegistry.kt) (`M1C_DETECT_URL`, `M1C_DETECT_SHA256`) |

**Verified I/O contract** (inspected with ONNX Runtime 1.31 on the pinned file — see `tooling/out/h4_scratch/inspect_onnx.py`):

1. **Input** `img`: `float32 [1,3,256,256]` — **fixed** spatial size, opset 16.
2. **Preprocess**: plain resize to `256×256` (aspect **not** preserved — matches upstream `cb.imresize(img, size=(256,256))`) → **RGB** → `/255` (range `0..1`) → **NCHW**. **No mean/std.**
3. **Output** `heatmap`: `float32 [1,4,128,128]`, **already sigmoid-activated**; the 4 channels are the **TL, TR, BR, BL** corner heat maps.
4. **Postprocess** (in `OpenCvQuadPageDetector`): per channel peak gate (≥ 0.3) → threshold 0.3 → largest-blob centroid → order TL,TR,BR,BL → sanity gate (area band, aspect, fill, convex, **not frame-hugging**). Gate failure → classical fallback.

> The SHA-256 above was computed with `certutil -hashfile <file> SHA256` and **equals the Hugging Face LFS ETag** (`X-Linked-ETag`) for the file, so it is intact and immutable for that revision.

> **Role change (SUBTASK H4):** `m1c_detect` is now the **primary AI detector**. `m1_boundary` (DexiNed) below is **retained and still provisioned** (the neural-path tests assert its presence) but is **no longer consulted** by the detector — measured rationale in [`MODELS.md` §2.0](MODELS.md).

### `m1_boundary` — DexiNed document-boundary detector (*retained, not wired into detection*)

| Field | Value |
|---|---|
| **ID** | `m1_boundary` |
| **Artifact** | OpenCV Zoo **DexiNed** ONNX export, `edge_detection_dexined_2024sep.onnx` |
| **Download URL** | `https://huggingface.co/opencv/edge_detection_dexined/resolve/main/edge_detection_dexined_2024sep.onnx` |
| **SHA-256** | `a50d01dc8481549c7dedb9eb3e0123b810a016520df75e4669a504609982cdd0` |
| **Size** | `47,235,563` bytes (≈ 45 MiB) |
| **License** | **MIT** (© 2019 Xavier Soria Poma) — redistributable with the license text. |
| **Upstream / source** | Hugging Face `opencv/edge_detection_dexined`; original network `https://github.com/xavysp/DexiNed` |
| **Registry** | Pinned in [`ModelRegistry`](app/src/main/java/com/docscanai/models/ModelRegistry.kt) (`M1_BOUNDARY_URL`, `M1_BOUNDARY_SHA256`) |

**Verified graph I/O** (inspected with `onnx` on the downloaded file):

```
ir_version 7, producer pytorch 1.10, opset ai.onnx 21
INPUT  : "img"     float32 [1, 3, 480, 640]      (FIXED shape)
OUTPUTS: "out_1" .. "out_6"  float32 [1, 1, 480, 640]   (side outputs)
         "block_cat"         float32 [1, 1, 480, 640]   (fused edge map — used)
```

**How the app uses it** (see [`OpenCvQuadPageDetector`](app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt)):

1. Plain resize the frame to the graph's `W×H` (aspect **not** preserved — matches the OpenCV
   Zoo reference `blobFromImage(..., size)` behaviour), read as a direct inverse resize later.
2. Channel order **BGR**; subtract the Caffe BGR mean `(103.5, 116.2, 123.6)` on the **0–255**
   scale (no `/255`). This is the DexiNed / Caffe convention used by OpenCV Zoo, **not** the
   ImageNet mean/std quoted generically in `MODELS.md §2.1`.
3. NCHW float32 → session run → take the **`block_cat`** output (name-based).
4. `sigmoid` the raw **logit** map → threshold `0.5` → morphological close → `findContours` →
   `approxPolyDP` → best convex quad (shared scorer) → map back to frame pixel coordinates.

> **Why the input is `480×640` and BGR-mean, not `352×352`/ImageNet:** `MODELS.md` describes the
> *generic* DexiNed contract, but the *only* reliable ready-made ONNX artifact we could obtain and
> verify is this fixed-shape OpenCV Zoo export. The detector reads the spatial size **from the
> graph** (`session.inputShape`) and uses the matching preprocessing, so it runs correctly. A
> future `352×352`/ImageNet export can be supported by re-exporting with those settings and
> updating the preprocessing constants.

**Reproduce / re-verify the pin:**

```bat
curl.exe -L -o dexined.onnx "https://huggingface.co/opencv/edge_detection_dexined/resolve/main/edge_detection_dexined_2024sep.onnx"
certutil -hashfile dexined.onnx SHA256
:: => a50d01dc8481549c7dedb9eb3e0123b810a016520df75e4669a504609982cdd0
```

> The SHA-256 above equals the upstream Git-LFS object id, so the file is intact and immutable for
> that revision. Pinning by digest means the app rejects any changed/re-hosted file.

### `m2_dewarp_grid` — UVDoc 45×31 grid (**PRIMARY dewarp**, SUBTASK H7)

| Field | Value |
|---|---|
| **ID** | `m2_dewarp_grid` |
| **Artifact** | Community **UVDoc grid-output** ONNX re-export: graph `UVDoc_grid.onnx` + external-data sidecar `UVDoc_grid.onnx.data` (both files MUST be in the same model directory) |
| **Graph URL** | `https://huggingface.co/fredcallagan/uvdoc-grid-onnx/resolve/main/UVDoc_grid.onnx` |
| **Graph SHA-256** | `044b406398e8accf2b8c896043f22d318221e443bb095c55d184e97f3eb003f7` |
| **Graph size** | `242,890` bytes |
| **Sidecar URL** | `https://huggingface.co/fredcallagan/uvdoc-grid-onnx/resolve/main/UVDoc_grid.onnx.data` |
| **Sidecar SHA-256** | `3a58a04944e59578200d7b0ed02ccb3ade934e9d88efa21454fbb8b53fa40f02` |
| **Sidecar size** | `31,588,352` bytes |
| **License** | **Apache-2.0** (model card); upstream UVDoc reference code is MIT. Community re-export — keep the upstream attribution. |
| **Upstream / source** | `https://huggingface.co/fredcallagan/uvdoc-grid-onnx` · UVDoc paper https://arxiv.org/abs/2302.02887 · reference code https://github.com/tanguymagne/UVDoc |
| **Registry** | Pinned in [`ModelRegistry`](app/src/main/java/com/docscanai/models/ModelRegistry.kt) (`M2_DEWARP_GRID_URL`, `M2_DEWARP_GRID_SHA256`, `M2_DEWARP_GRID_DATA_URL`, `M2_DEWARP_GRID_DATA_SHA256`; `ModelSpec.files`) |

**Verified graph I/O** — parsed with the JDK-only protobuf inspector
[`tooling/OnnxInspect.java`](tooling/OnnxInspect.java) (no Python / no ONNX Runtime):

```
MODEL UVDoc_grid.onnx  bytes=242890
ir_version=10 producer=pytorch graph.name=main_graph
INPUT  image    float32 [1,3,720,496]      (FIXED; RGB [0,1], NCHW)
OUTPUT grid_2d  float32 [1,2,45,31]        (normalized sampling coords in [-1,1])
initializers=92; EXTERNAL entries location=UVDoc_grid.onnx.data (offset/length)
```

**How the app uses it** (see [`NeuralGridDewarp.dewarpGrid`](app/src/main/java/com/docscanai/imaging/NeuralGridDewarp.kt)):

1. Render the 4-corner perspective-corrected page at **720×496 directly from the original still** (`M' = S · H`).
2. Run the graph; the output is the UVDoc sampling grid (`output → input` normalized coordinates).
3. Upsample the 45×31 grid to the delivered page size, map `[-1,1] → [0,1]`, then compose with `H⁻¹` so every delivered page pixel points at an **original full-resolution pixel**.
4. Apply **one** `Imgproc.remap` (LANCZOS4) to the original still. No intermediate page bitmap is delivered.
5. Any shape mismatch, non-finite value, degenerate grid, or singular homography returns `null` → the `m2_dewarp` image→image fallback at ~712 px.

**Reproduce / re-verify the pins:**

```bat
curl.exe -L -o UVDoc_grid.onnx "https://huggingface.co/fredcallagan/uvdoc-grid-onnx/resolve/main/UVDoc_grid.onnx"
curl.exe -L -o UVDoc_grid.onnx.data "https://huggingface.co/fredcallagan/uvdoc-grid-onnx/resolve/main/UVDoc_grid.onnx.data"
certutil -hashfile UVDoc_grid.onnx SHA256
:: => 044b406398e8accf2b8c896043f22d318221e443bb095c55d184e97f3eb003f7
certutil -hashfile UVDoc_grid.onnx.data SHA256
:: => 3a58a04944e59578200d7b0ed02ccb3ade934e9d88efa21454fbb8b53fa40f02
java tooling\OnnxInspect.java UVDoc_grid.onnx
```

### `m2_dewarp` — UVDoc neural dewarp (PaddlePaddle ONNX export, FALLBACK)

| Field | Value |
|---|---|
| **ID** | `m2_dewarp` |
| **Artifact** | PaddlePaddle **UVDoc** ONNX export, `inference.onnx` (single file, **no external data**) |
| **Download URL** | `https://huggingface.co/PaddlePaddle/UVDoc_onnx/resolve/main/inference.onnx` |
| **SHA-256** | `54cab30dc2cf347b4f3d6971c833b1c3d84f5ef17280f1f6d15796bff20e63d6` |
| **Size** | `31,684,150` bytes (≈ 30.2 MiB) |
| **License** | **Apache-2.0** (PaddlePaddle export). The original UVDoc reference implementation is **MIT**. |
| **Upstream / source** | Hugging Face `PaddlePaddle/UVDoc_onnx`; paper: Verhoeven et al., *UVDoc: Neural Grid-based Document Unwarping*, SIGGRAPH Asia 2023; reference: https://github.com/tanguymagne/UVDoc |
| **Registry** | Pinned in [`ModelRegistry`](app/src/main/java/com/docscanai/models/ModelRegistry.kt) (`M2_DEWARP_URL`, `M2_DEWARP_SHA256`) |

**Verified graph I/O** (inspected with `onnx` **and** executed with `onnxruntime` on the downloaded file):

```
ir_version 8, opset ai.onnx 16, 259 nodes, 282 initializers (no external data)
INPUT  : "image"        float32 [N, 3, H, W]   (DYNAMIC; RGB normalised to [0,1])
OUTPUT : "fetch_name_0" float32 [N, 3, H, W]   (DYNAMIC; rectified RGB image, values in [0,1])
         -> output spatial size == input spatial size.
```

> **Contract note (IMPORTANT):** this export is **image→image** — the grid-sample happens *inside*
> the graph — so the output is the **rectified RGB page**, **not** the `[1,45,45,2]` coordinate grid
> quoted generically in `MODELS.md §3.1`. [`NeuralGridDewarp`](app/src/main/java/com/docscanai/imaging/NeuralGridDewarp.kt)
> auto-detects the layout (image / grid / WC maps) from the declared output shape and the flat
> result length, so this artifact is handled by the **image→image** branch (rectified page resized
> back to the page size). Dynamic shapes are fed a concrete, aspect-preserving input
> (long edge ≤ 712 px, rounded to a multiple of 8) and the output range is normalised
> (`[0,1] → 0..255`). This is why the "grid-dewarp" description in `MODELS.md` does not literally
> match the shipped file while remaining functionally correct.

**How the app uses it** (see [`NeuralGridDewarp`](app/src/main/java/com/docscanai/imaging/NeuralGridDewarp.kt)):

1. Resize the (already perspective-corrected) page to the model input size.
2. RGB → `/255` → NCHW float32 → `OrtSession.run` (output selected by name).
3. Take the rectified RGB output, resize it back to the page size, and use it as the flattened page.
4. On any failure, silently fall back to the classical perspective + curvature path.

**Reproduce / re-verify the pin:**

```bat
curl.exe -L -o inference.onnx "https://huggingface.co/PaddlePaddle/UVDoc_onnx/resolve/main/inference.onnx"
certutil -hashfile inference.onnx SHA256
:: => 54cab30dc2cf347b4f3d6971c833b1c3d84f5ef17280f1f6d15796bff20e63d6
```

> **Alternate UVDoc ONNX candidates** (NOT pinned — kept for reference; verify checksum + license
> before using): `matrixmaven/uvdoc` (MIT); `Shivin11/occular-uvdoc` (onnx+safetensors);
> `twmht/UVDoc_infer_onnx`.
>
> **SUBTASK H7 update:** the former alternate `fredcallagan/uvdoc-grid-onnx` is now **PINNED as
> `m2_dewarp_grid`** above. The downloader was extended to support a per-file list, so the graph
> and its external-data sidecar are fetched into the same directory with per-file SHA-256 + byte
> verification. `m2_dewarp` remains the image→image fallback.

---

### `m2_mlsd` — M-LSD line-segment detector (**SHIPPED, SCHEDULED**, SUBTASK H30/H31)

| Field | Value |
|---|---|
| **Purpose** | Line-segment **evidence** for the facing-page merge: a spine-parallel line crossing the corner-model quad means the quad contains the neighbouring page. Used as a reject/repair rule, never as a corner detector. |
| **Upstream** | NAVER Corp. `mlsd` — <https://github.com/navervision/mlsd> (official TFLite `M-LSD_320_large` / `M-LSD_512_large`) |
| **Conversion** | PINTO model zoo `119_M-LSD` — <https://github.com/PINTO0309/PINTO_model_zoo/tree/main/119_M-LSD>, resources served from `https://s3.ap-northeast-2.wasabisys.com/pinto-model-zoo/119_M-LSD/resources.tar.gz` |
| **Licence** | **Apache-2.0** (NAVER Corp.). The upstream `LICENSE`/NOTICE must travel with the artifact if it ever ships. |
| **Shipped candidate** | `saved_model_320x320_large/model_float32.onnx` — **6,170,288 B**, SHA-256 `26c4d103cdc0e126af94b97ecd5b12ad85b1a7cc8179e6c94177ef603c419e2a` |
| **Larger candidate** | `saved_model_512x512_large/model_float32.onnx` — **6,170,338 B**, SHA-256 `6748da6c5fb406b9af56436ecca61493fa60da7d232e5baa9a3ce23dc4ff8ff7` (measured worse after the rule; not used) |
| **Graph** | opset 11, standard domain only, no external data, single input `input_image_with_alpha:0` `[1,320,320,4]` float32 NHWC (0-255, alpha = 1); outputs `[1,200,2]` int32 junctions, `[1,200]` float32 scores, `[1,160,160,4]` float32 displacement |
| **Verification** | Input/output shapes + SHA-256 recorded in `tooling/out/h29/mlsd_320_inspect.txt` / `mlsd_512_inspect.txt`; the H30 port was validated on synthetic maps (`tooling/h30_mlsd_test.py`, 38 tests) and scored offline on the 18 fixtures (`tooling/out/h30/scores.md`) |
| **Distribution** | **Tooling-provisioned** (`tooling/_provision_models.cmd` → `files/models/m2_mlsd/model.onnx`). **Not downloadable in-app**: upstream publishes all four graphs inside one `resources.tar.gz`, so there is no single-file URL whose SHA-256 can be pinned and verified by `ModelDownloadManager`. A hosted single-file mirror would be required for in-app download; it does not exist yet and that is the open packaging item. When the file is absent the detector degrades to the 0.3.8 path exactly (the H31 trigger never fires). **Shipped in 0.3.9 as the scheduled merge-repair evidence** (`MlsdMode.REPAIR`): 8 of 200 frames (4.0 %) invoke it, mean +1.79 ms / p95 +17.96 ms per frame. See `docs/RESULTS.md` H31 and `tooling/out/h31/SUMMARY.txt`. |
| **Attribution requirement** | "M-LSD — Copyright 2021-present NAVER Corp., Apache License 2.0" in the About/notices screen **if** the model is ever shipped |

---

## 2. Still PLACEHOLDER (not pinned) ⚠️

The following models have **no canonical, pre-built ONNX artifact published by their authors**, so
their `downloadUrl`/`sha256` in [`ModelRegistry`](app/src/main/java/com/docscanai/models/ModelRegistry.kt)
remain guarded placeholders (`PLACEHOLDER_BASE_URL` / `PLACEHOLDER_SHA256`). The download worker
therefore reports `NOT_CONFIGURED` for them and the app uses the corresponding **classical**
fallbacks (it does not crash and is fully usable).

| ID | Role | Candidate source(s) to export/obtain | Notes |
|---|---|---|---|
| `m2p_dewarp_plus` | DRCNet/PaperEdge or DocTr++ (Quality) | https://github.com/cvlab-stonybrook/PaperEdge · https://github.com/microsoft/DocTr-Plus | Self-export; large. |
| `m2f_dewarp_fallback` | DewarpNet WC (light fallback) | https://github.com/cvlab-stonybrook/DewarpNet · LiteRT/`.tflite` community: `litert-community/DewarpNet-LiteRT` | MIT; community ONNX exist but unverified. If added, the `[1,2,H,W]` WC map layout is already supported by `NeuralGridDewarp`. |
| `m3_shadow` | DocShadow / ShadowFormer (optional) | ShadowFormer: https://github.com/GuoLanqing/ShadowFormer (MIT) | Self-export, `[1,3,256,256]` image→image (handled by `ClassicalEnhancer.neuralIllumination`). |

**Recommended next steps for a later model-prep task:**

1. Export each model to ONNX (`opset ≥ 17`), validate numerics vs. the PyTorch reference.
2. Host the artifacts at a stable URL (GitHub Release asset or a HF repo you control) — **or** reuse
   an existing community mirror only after verifying its checksum and license.
3. Record the exact **URL + SHA-256 + byte size** here and in `ModelRegistry` (keep the same API).
4. Confirm each model's **LICENSE** and mirror it in the About screen / this file.

### Alternative boundary-model candidates (if DexiNed is ever replaced)

| Candidate | Source | Why consider |
|---|---|---|
| DexiNed (pinned above) | `opencv/edge_detection_dexined` | Verified, MIT, small, works well on document borders. |
| `m1b_boundary_seg` (semantic segmentation) | self-export U-Net / DeepLabV3-MobileNet | A binary **page mask** can be more stable than an edge map on low-contrast pages (e.g. white paper on a white desk). |
| HED / other edge detectors | https://github.com/s9xie/hed | Alternate edge backbone. |

> **Note (product feedback):** classical OpenCV edges alone are weak on low-contrast scenes
> (white page on a bright/white surface). The ONNX boundary path is therefore the **preferred**
> detector and runs **before** the classical fallback whenever `m1_boundary` is present.
