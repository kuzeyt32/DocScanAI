# DocScan AI — Model Registry (MODELS.md)

> Companion to [`TECHNICAL_SPEC.md`](TECHNICAL_SPEC.md) and [`ARCHITECTURE.md`](ARCHITECTURE.md).
> This is the **locked** list of on-device AI models, their I/O contracts, licensing, sources, and fallbacks.

---

## 0. How to read this document (important)

- **All inference runs via ONNX Runtime for Android** (`onnxruntime-android`), execution provider **XNNPACK/CPU** by default, **NNAPI** opt-in with automatic fallback.
- **Approximate sizes** are for the ONNX artifact (float32 unless noted); INT8 variants where available are noted.
- **Source URLs are canonical project locations** (GitHub repos / Hugging Face orgs). The *exact* downloadable artifact file + its **SHA-256 must be pinned at implementation time** by the model-download subtask. This is intentional: upstream release assets change, and the app must verify a fixed hash.
- Where a specific ONNX file is not officially published by the authors, the pipeline is **"export to ONNX ourselves"** (a separate prep task), and the classical/other-model fallback covers the app until that export is available.
- Everything here is **fully local at runtime**; the only network use is the one-time first-launch download.

### 0.1 Model ID and storage convention

```
ID format : m1c_detect, m1_boundary, m2_dewarp_grid, m2_dewarp,
            m2p_dewarp_plus, m2f_dewarp_fallback, m3_shadow
Storage   : filesDir/models/<id>/<graph>.onnx   (+ external-data sidecars, meta.json)
            Single-file models keep `model.onnx`; m2_dewarp_grid uses
            `UVDoc_grid.onnx` + `UVDoc_grid.onnx.data` in the same directory.
Registry  : ModelRegistry.kt  (id, displayName, files[], url, sha256, sizeBytes, inputSpec, outputSpec, tier)
```

`meta.json` holds `{ "id", "version", "sha256", "source", "license", "files": [{name, sha256, bytes, url}] }`.
Multi-file models (SUBTASK H7) are downloaded per file, verified for exact byte count + SHA-256, promoted from `.part` to the final name, and the whole model directory is deleted on any mismatch.

---

## 1. Summary table

| ID | Role | Model | Approx size | License | ONNX | Tier |
|---|---|---|---|---|---|---|
| **m1c_detect** | Document boundary / 4-corner detection (**PRIMARY**) | **DocAligner LCNet100** heatmap corner regressor | **4.8 MB** | Apache-2.0 | Yes (pinned re-host) | Fast |
| **m1_boundary** | Boundary/edge map — *retained, no longer wired into detection* | **DexiNed** (dense extreme inception edge detector) | **≈ 45 MB** | MIT | Yes (pinned OpenCV Zoo export) | — |
| m1b_boundary_seg *(alt)* | Page segmentation (better corners) | **U-Net / DeepLabV3-MobileNet** semantic segmentation | ≈ 5–25 MB | depends on weights | Self-export | Optional upgrade |
| **m2_dewarp_grid** | Dewarp / flatten (**PRIMARY**, SUBTASK H7) | **UVDoc 45×31 grid** — graph + external-data sidecar | **31,831,242 B (≈ 31.9 MB)** | **Apache-2.0** | **Pinned ONNX (2 files)** | Fast (default) |
| **m2_dewarp** | Dewarp fallback (image→image) | **UVDoc** — neural grid-based unwarping at ~712 px | **31,684,150 B** | Apache-2.0 | Convertible (self-export) | Fallback (grid's, tooling-only) |
| **m2p_dewarp_plus** | Dewarp (higher quality) | **DRCNet / PaperEdge** or **DocTr++** | **≈ 100–300 MB** | MIT | Self-export | Quality (opt-in) |
| **m2f_dewarp_fallback** | Dewarp (light fallback) | **DewarpNet WC** | **≈ 8 MB** | MIT | Community ONNX | Fallback |
| **m3_shadow** | Neural shadow/illumination removal | **DocShadow** (or ShadowFormer) | **≈ 30–80 MB** | see repo | Self-export | Optional |
| *classical* | Diffuse illumination normalize | OpenCV: blur-divide + CLAHE + gamma + WB | 0 MB | Apache-2.0 (OpenCV) | n/a | Always |

**Locked defaults:** detection = **m1c_detect (DocAligner LCNet100 corner detector)** with a **classical multi-cue OpenCV** fallback (SUBTASK H4 — replaces DexiNed, see §2.0); dewarp = **m2_dewarp_grid (UVDoc 45×31 grid, one remap from the original still)**; image→image fallback = **m2_dewarp (UVDoc at ~712 px)**; enhancement = **classical OpenCV** (+ optional **m3_shadow**); classical fallback = **m2f_dewarp_fallback (DewarpNet WC)** → **classical TPS**.

**What the app actually asks the user to download (SUBTASK H42).** The in-app model setup offers
exactly the two artifacts the pipeline reads, totalling **~35 MB**:

| Offered in the app | Why |
|---|---|
| `m1c_detect` (~4.8 MB) | the primary detector; without it detection is classical-only |
| `m2_dewarp_grid` (~30 MB) | the primary dewarp; without it warping uses classical curvature correction |

Everything else stays in the registry (`ModelRegistry`, `ModelSpec.userFacing = false`) so presence
checks, `tooling/_provision_models.cmd` and the instrumented tests keep working, but it is **not
advertised**: `m1_boundary` is no longer consulted by detection (§2.1), `m2_dewarp` is only the grid's
fallback (§3.1), and `m2p_dewarp_plus` / `m2f_dewarp_fallback` / `m3_shadow` still carry `TODO`
placeholder URLs (§3.2, §3.3, §4.2) so a download from them could only fail. `fastTierIds` is
therefore exactly `[m1c_detect, m2_dewarp_grid]`.

---

## 2. M1 — Document boundary / corner detection

### 2.0 m1c_detect — DocAligner LCNet100 corner detector (**PRIMARY**, SUBTASK H4)

| Field | Value |
|---|---|
| **What it does** | Predicts the document's **four corners directly** as a `[1,4,128,128]` heat map (one channel per corner: **TL, TR, BR, BL**); the page quad is read from the four peaks. |
| **Architecture / params** | DocAligner heat-map regressor: **PP-LCNet-100** backbone + BiFPN neck (DocsaidLab). |
| **Approx size** | **4,767,987 bytes ≈ 4.5 MiB** (float32). |
| **License** | **Apache-2.0** (DocsaidLab/DocAligner); the pinned artifact is an unchanged re-host. |
| **ONNX availability** | Pinned single-file ONNX (re-hosted on Hugging Face; upstream ships an undocumented Google-Drive auto-downloader). Opset 16, no external data. |
| **Input** | `img` `float32 [1,3,256,256]` (**fixed**; **RGB**, `0..1` via `/255`, NCHW, **no mean/std**). |
| **Output** | `heatmap` `float32 [1,4,128,128]` (**already sigmoid-activated**), channels = TL, TR, BR, BL. |
| **Preprocess** | plain resize (squash, aspect not preserved — matches upstream `imresize(size=(256,256))`) → RGB → `/255` → NCHW. |
| **Postprocess** | per channel: peak-confidence gate (≥ 0.3) → resize the heat map to a bounded working size → threshold at 0.3 → **largest blob centroid** → 4 points → order TL,TR,BR,BL → **sanity gate** (area band 0.03–0.98, min-area-rect aspect ≤ 6, fill ≥ 0.30, convex, **not frame-hugging**). Gate failure → classical path. |
| **Latency target** | bounded 256×256 input, CPU EP only; measured per-image time is recorded in the SUBTASK H4 report. |
| **Source** | Upstream `https://github.com/DocsaidLab/DocAligner` · pinned artifact `https://huggingface.co/7rplus/pagescan-weights` |
| **Fallback** | **Classical multi-cue OpenCV** (`OpenCvQuadPageDetector.detectClassical`). |

> **Why this replaced DexiNed as the detection model (measured, SUBTASK H4).** The DexiNed boundary/edge path could not recover the user-visible failure mode: `bench_docunet_48` produced a quad whose correct left-edge corners were joined to two runaway corners pinned to the frame's right border, and `bench_docunet_45` ran one corner down onto the car-interior floor — its per-contour post-processing cannot repair a page whose border does not close. DocAligner regresses **all four corners jointly**, and on the same two images yields tight, bounded quads. `m1_boundary` is therefore **retained in the registry and still provisioned** (its presence is asserted by the neural-path tests) but is **no longer consulted** by the detector; the classical multi-cue path remains the fallback.

### 2.1 m1_boundary — DexiNed (*retained, no longer wired into detection*)

| Field | Value |
|---|---|
| **What it does** | Dense edge/boundary detector. Produces a 1-channel edge-probability map; the document border is the dominant closed contour. |
| **Architecture / params** | DexiNed (Dense Extreme Inception Network), ~3.5 M params. |
| **Approx size** | **≈ 35 MB** (float32). |
| **License** | **MIT**. |
| **ONNX availability** | Not always shipped as a release; **export from the PyTorch checkpoint** (`torch.onnx.export`) in a prep task, or use a community ONNX mirror. |
| **Input** | `float32 [1, 3, H, W]`, normalized with ImageNet mean `[0.485,0.456,0.406]`, std `[0.229,0.224,0.225]`. Working size **H=W=352** (bundled config; other sizes divisible by 16 also valid). |
| **Output** | Edge map `float32 [1,1,H,W]` in `[0,1]` (if the graph exposes multiple side outputs, use the **last fused** output; strip aux outputs at export). |
| **Preprocess** | Resize (letterbox, keep aspect) → RGB → /255 → normalize → NCHW. |
| **Postprocess** | `> 0.5` threshold → morphological close → **largest connected component** → external contour (`findContours`) → `approxPolyDP` (ε ≈ 2% perimeter) → pick the 4 strongest corners → validity checks (convex, area ≥ 10% of frame, no self-intersection). Optional: `minAreaRect` fallback when DP yields ≠ 4 points. |
| **Latency target** | ≤ ~25 ms at 352×352 (XNNPACK, 2–4 threads). |
| **Source** | https://github.com/xavysp/DexiNed |
| **Fallback** | Classical: Canny/Sobel + morphology + `findContours` + `approxPolyDP`. |

**Corner refinement on full-res still:** re-run at medium res (e.g. 640 px) seeded by the live quad, then refine each of the 4 edges by fitting a line through strong gradient points near the edge (least-squares), intersect adjacent lines → refined corners.

### 2.2 m1b_boundary_seg — semantic segmentation (OPTIONAL upgrade)

| Field | Value |
|---|---|
| **What it does** | Predicts a binary **page mask**; corners extracted from the mask are often more stable than edges on low-contrast pages. |
| **Candidate architectures** | MobileNetV3-DeepLabV3, or a small U-Net. Weights: train/fine-tune on a document-segmentation dataset (e.g. a DocUNet/SmartDoc-style set) or use a released checkpoint. |
| **Approx size** | 5–25 MB (INT8 smaller). |
| **License** | Depends on chosen base weights — **must be checked before shipping**. |
| **ONNX** | Self-export. |
| **Input** | `float32 [1,3,512,512]` (or 384×384), ImageNet-normalized. |
| **Output** | logits/prob `[1,1,512,512]` (sigmoid) or `[1,2,512,512]` (softmax). |
| **Postprocess** | `> 0.5` → largest contour → `approxPolyDP` → 4 corners (same as 2.1). |
| **Source** | No single canonical URL; select base from TorchVision/timm and document pinned weights. |
| **Fallback** | m1_boundary (DexiNed). |

### 2.3 m2_mlsd — M-LSD line segments (**SHIPPED, SCHEDULED**, SUBTASK H30/H31)

| Field | Value |
|---|---|
| **What it does** | Predicts line segments (junctions + displacement fields). H30 uses them as **evidence about where a page's borders end**, not as a corner detector: a spine-parallel line crossing the corner-model quad means the quad contains a facing page, and the quad is split there. |
| **Artifact** | `saved_model_320x320_large/model_float32.onnx` — **6,170,288 B**, SHA-256 `26c4d103cdc0e126af94b97ecd5b12ad85b1a7cc8179e6c94177ef603c419e2a` (PINTO model zoo `119_M-LSD`, converted from NAVER's official TFLite) |
| **Licence** | **Apache-2.0** (NAVER Corp.). Attribution is carried in [`MODEL_SOURCES.md`](MODEL_SOURCES.md); the NOTICE must ship with the artifact if it ever ships. |
| **ONNX / opset** | opset 11, standard domain only, no external data — loads through the existing CPU-EP path with no new runtime dependency |
| **Input** | `input_image_with_alpha:0` `float32 [1,320,320,4]` **NHWC**, 0–255, alpha = 1. **Not** the corner model's NCHW/255 contract — a separate preprocessor is mandatory. |
| **Outputs** | junctions `[1,200,2]` int32 (row, col in the 160×160 maps), scores `[1,200]` float32, displacement `[1,160,160,4]` float32 |
| **Postprocess** | NAVER's `pred_squares`: junction → segment, Hough `(distance, theta)` merge with 5×5 NMS, intersection graph → quads, degree/overlap/area score. Ported twice: `tooling/h30_mlsd.py` (numpy, reference + oracle) and `MlsdGeometry.kt` (device). |
| **Where it lives** | `filesDir/models/m2_mlsd/model.onnx` — the same directory contract the downloader uses. **Delivery: tooling-provisioned** (`tooling/_provision_models.cmd`, or any adb push). It is **not** in `ModelRegistry.models` and therefore **not downloadable in-app**: upstream (PINTO model zoo `119_M-LSD`) publishes all four graphs inside a single `resources.tar.gz`, so there is no single-file URL whose SHA-256 could be pinned and verified by `ModelDownloadManager`. **A hosted single-file mirror would be required before this can be user-downloadable** — that is the one open packaging item and is deliberately not a regression gate: the feature is optional and degrades to the 0.3.8 path without it. |
| **Fallback** | **Complete.** `ServiceLocator.mlsdModel` returns `null` when the file is absent; the trigger then never fires and the detector behaves exactly as 0.3.8 (no session, no milliseconds, no behaviour change). |
| **Status** | **SHIPPED in 0.3.9 / versionCode 12, SCHEDULED** (`MlsdMode.REPAIR`). H30 proved the quality win (merged slivers 7/12 → 2/12) but M-LSD cost +73.68 ms p50 per preview frame. H31 keeps the win and removes the rate: `ml/MlsdTrigger.kt` runs it only on the merge signature (quad w/h in [0.74, 1.75]), once the scene settles, at most 1 call per 24 eligible frames, with the fold cached as geometry in between. **8 of 200 frames (4.0 %)** invoke it; mean **+1.79 ms** and p95 **+17.96 ms** per frame (budgets +15 / +40), and a clean single page invokes it **0 times**. Quality with the trigger: merged **2/12**, corner **3.3495 %**, IoU **0.8593**. See `docs/RESULTS.md` H31. |

---

## 3. M2 — Dewarp / flatten (the critical model)

### 3.0 m2_dewarp_grid — UVDoc 45×31 grid (**PRIMARY**, SUBTASK H7)

| Field | Value |
|---|---|
| **What it does** | UVDoc predicts a **45×31 normalized sampling grid** (not an image). The app composes that grid with the 4-corner homography and applies **ONE `Imgproc.remap` directly to the original still**, so the delivered page is a resampling of the original pixels (no image decoder, no second resample). |
| **Files** | `UVDoc_grid.onnx` (242,890 B) + `UVDoc_grid.onnx.data` (31,588,352 B), **same model directory**; the graph resolves the sidecar by relative path. |
| **License** | **Apache-2.0** (community grid re-export of UVDoc; UVDoc reference code is MIT). |
| **Verified contract** | input `image` `float32 [1,3,720,496]` RGB `[0,1]`; output `grid_2d` `float32 [1,2,45,31]` in `[-1,1]`. 92 initializers, external data location `UVDoc_grid.onnx.data`. |
| **Why it is primary** | Removes the encoder→decoder image synthesis and the 2048 px over-resolution: measured sharpness ratio **1.0067** (vs 0.754 baseline), native heap peak **290.5 MB** (vs 638 MB). |
| **Fallback** | `m2_dewarp` image→image at the UVDoc reference working size (~712 px) when the grid artifact is missing/invalid or its grid fails validation. |

### 3.1 m2_dewarp — UVDoc image→image (FALLBACK)

| Field | Value |
|---|---|
| **What it does** | **Neural grid-based document unwarping**: predicts a 2D displacement grid over the page and resamples the image so curved/curled pages become flat and square. State-of-the-art-quality on real phone photos. |
| **Reference** | Verhoeven et al., *UVDoc: Neural Grid-based Document Unwarping*, SIGGRAPH Asia 2023. |
| **Architecture / params** | Encoder-decoder predicting a coordinate grid; ~tens of millions of params total (medium). |
| **Approx size** | **31,684,150 B (≈ 30.2 MiB)** (float32 ONNX, single file). |
| **License** | **Apache-2.0** (PaddlePaddle UVDoc ONNX export; UVDoc reference code is MIT). |
| **ONNX availability** | **Pinned single-file ONNX** (`inference.onnx`, no external data). |
| **Input** | `float32 [N,3,H,W]` (dynamic; RGB, `[0,1]`). **SUBTASK H7:** the fallback caps the working long edge at **~712 px** (UVDoc reference, 712×488) instead of the former 2048 px; dimensions are rounded to a multiple of 8. |
| **Output** | `float32 [N,3,H,W]` rectified RGB image in `[0,1]` (image→image decoder). |
| **Postprocess** | Resize the model output to the delivered page size (`INTER_LANCZOS4`); **no unsharp by default** (`DETAIL_RESTORE_AMOUNT = 0.0`). |
| **Latency target** | ≤ ~400–700 ms/page on CPU at the 712 px reference size. |
| **Source** | https://huggingface.co/PaddlePaddle/UVDoc_onnx |
| **Fallback** | `m2_dewarp_grid` is PRIMARY; if the image fallback itself fails, m2p (quality) → m2f (DewarpNet WC) → classical TPS. |

> **Why UVDoc over a giant model:** it is the best *quality-per-practicality* choice for on-device real photos — near-flat output at tens of MB with sub-second inference, unlike multi-hundred-MB / multi-GB transformer rectifiers that cannot run interactively on a phone (see [`TECHNICAL_SPEC.md`](TECHNICAL_SPEC.md) §5.1).

### 3.2 m2p_dewarp_plus — Quality tier (opt-in)

Choose **one** (document the final pick at prep time):

| Option | What | Approx size | License | ONNX | Source |
|---|---|---|---|---|---|
| **DRCNet / PaperEdge** | Displacement Rectification-Correction Network; strong on real captures | ≈ 100–150 MB | MIT | Self-export | https://github.com/cvlab-stonybrook/PaperEdge |
| **DocTr++** | Transformer-based rectification, high quality | ≈ 150–300 MB | MIT (microsoft/unilm) | Self-export | https://github.com/microsoft/DocTr-Plus |
| **DocTr (original)** | Earlier transformer rectifier | ≈ 100–200 MB | check repo | Self-export | https://github.com/fh2019ustc/DocTr |

- **Input/Output:** these are typically image→image (rectified RGB), input `float32 [1,3,H,W]` with H,W ≈ 288–512; output `[1,3,H,W]` in `[0,1]`. Confirm per export.
- **Fallback:** m2_dewarp (UVDoc).

### 3.3 m2f_dewarp_fallback — DewarpNet WC (light fallback)

| Field | Value |
|---|---|
| **What it does** | Predicts the **warping coordinate map** (WC) used to unwarp a document. Lighter and simpler than UVDoc; used when the primary model is unavailable. |
| **Approx size** | **≈ 8 MB**. |
| **License** | **MIT** (repo). |
| **ONNX** | Community ONNX conversions exist; otherwise self-export. |
| **Input** | `float32 [1,3,128,128]` (official WC resolution), normalized `[0,1]`. |
| **Output** | `float32 [1,2,128,128]` (x and y source-coordinate maps in `[0,1]`). |
| **Postprocess** | Upsample coordinate maps to image size → `remap` → crop. |
| **Source** | https://github.com/cvlab-stonybrook/DewarpNet |
| **Note** | Weaker on real photos than UVDoc; also its sibling models (BM blend mask, SH shading) are optional and **not** required here. |
| **Fallback** | classical TPS (below). |

### 3.4 classical TPS fallback (no-model safety net)

When **no dewarp model is available**, rectify classically:
1. From the detected/refined quad, assume the page may be curved; estimate the **top/bottom text-line or boundary curves** from edge/border points (or from the detected boundary polyline sampled at >4 points).
2. Fit a smooth curve per side (cubic / spline) and build a **thin-plate-spline** or piecewise-bilinear warp mapping the four curved sides to a straight rectangle.
3. `remap` and crop.

This guarantees a non-broken app even with zero models downloaded.

---

## 4. M3 — Enhancement (illumination / shadow)

### 4.1 classical OpenCV (LOCKED DEFAULT — no model)

Pipeline (fast, dependency-free, deterministic):
1. **Background/illumination estimate**: large-kernel Gaussian or morphological closing (kernel ≈ 1/8 of image size) on a downscaled image.
2. **Divide** the image by the background (removes shadows/uneven lighting) → normalizes to `~255` paper.
3. **CLAHE** on the L channel (LAB) for local contrast.
4. **Gray-world / white-balance** on the low-frequency component; **auto-levels** (histogram stretch).
5. **Gamma** correction (~0.8–1.2, user-adjustable).
6. **Clean-up modes**: *Original* (warp+dewarp only) → *Enhanced* (steps 1–5, default) → *B&W* (local adaptive / **Sauvola** binarization on the enhanced grayscale, hue/saturation preserved for stamps optionally).

### 4.2 m3_shadow — neural shadow removal (OPTIONAL, off by default)

| Field | Value |
|---|---|
| **What it does** | Deep removal of hard shadows / strong illumination gradients on documents. Nice-to-have; classical §4.1 already covers most cases. |
| **Candidates** | **DocShadow** (document-specific), **ShadowFormer** (general, AAAI 2023), **DocEnTr** (enhancement/binarization transformer). |
| **Approx size** | **≈ 30–80 MB**. |
| **License** | Check the chosen repo (ShadowFormer is MIT; verify the others). |
| **ONNX** | Self-export. |
| **Input** | `float32 [1,3,256,256]` (typical), `[0,1]`. |
| **Output** | shadow-free image `float32 [1,3,256,256]` `[0,1]` → upscale/blend back at full res. |
| **Sources** | DocShadow: *search the repo/paper release*; ShadowFormer: https://github.com/GuoLanqing/ShadowFormer ; DocEnTr: https://github.com/dali92002/DocEnTr |
| **Fallback** | classical §4.1 (always available). |

> **Rationale:** the user only needs *visual* quality (no OCR). Classical illumination normalization is faster, smaller, and more predictable than a neural model, so it is the default; M3 is an opt-in quality boost.

---

## 5. ONNX Runtime execution configuration

```text
OrtEnvironment.getEnvironment()                      // singletons, created once
SessionOptions:
  setIntraOpNumThreads(2..4)
  setOptimizationLevel(ORT_ENABLE_ALL)
  addXnnpack() (or addNnapi() when opted-in)
  setMemoryPatternOptimizationEnabled(true)
```

- **Provider policy:** default **XNNPACK/CPU**; **NNAPI** only if `OrtEnvironment.getAvailableProviders().contains(NNAPI)` **and** the model's ops are supported, wrapped in try/catch → fall back to CPU on any `OrtException`.
- **Warm-up:** run one dummy inference per model at app start (or after download) to pay JIT/allocation cost once.
- **Tensors:** reuse `OnnxTensor`/`FloatBuffer` buffers to cut GC; feed a preallocated `FloatBuffer` in NCHW.
- **Concurrency:** one session per model; serialize calls per session (ORT sessions are not guaranteed thread-safe).

---

## 6. Preprocessing / postprocessing constants (shared)

| Item | Value |
|---|---|
| Color space | Model input = **RGB**; CameraX frames are converted from YUV to RGB. |
| Bitmap format | `Bitmap.Config.ARGB_8888`; convert to float `[0,1]` or ImageNet-normalized per model. |
| Letterbox | Preserve aspect ratio; pad with 0 (or 114 gray); keep the pad scale to invert coordinates in postprocess. |
| Model working res | m1: 352² · m2: per-export (≈488×712/512²) · m2f: 128² · m3: 256² |
| Full-res cap | output page ≤ **3000 px** long edge (memory/latency control). |
| Coordinate mapping | Always map model-space → full-res using the exact letterbox scale/offset used in preprocess. |

---

## 7. Download & integrity procedure (per model)

1. `ModelRegistry` lists `{id, url, sha256, bytes, tier}` — all four **pinned** by the download subtask.
2. Download with **OkHttp Range** requests → resumable; persist `bytesDownloaded` + `ETag` in DataStore.
3. On completion: compute **SHA-256**, compare to registry; on mismatch → delete, `Retry`.
4. Store at `filesDir/models/<id>/model.onnx`; write `meta.json`.
5. **Offline/no-model behavior:** app selects the **Fallback tier** (§3.4 + §4.1) and shows a retry banner. Never crash, never block the UI on a model.

**Licenses to preserve:** when shipping/redistributing model files, include each model's LICENSE text in-app (About screen) and only host copies where the license permits redistribution; otherwise download from the original source.

---

## 8. Prep task (required before/with implementation)

A dedicated "model prep" subtask must:
1. Export **DexiNed** and **UVDoc** (and chosen fallback/quality models) to ONNX (opset ≥ 17), validate numerics vs the PyTorch reference on a few sample images.
2. Measure real latency on-device (S25 FE) for each model; confirm the Fast-tier budget.
3. Publish artifacts to a stable host (GitHub Release asset or HF repo) and record **exact URL + SHA-256 + byte size** into `ModelRegistry`.
4. Confirm each model's **LICENSE** and record it in `meta.json` + the About screen.

Until step 3 is done, the app ships working in the **Fallback tier**.
