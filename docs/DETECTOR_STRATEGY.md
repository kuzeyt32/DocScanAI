# Detector strategy (H29)

Scope: the page-quad detector behind the live overlay **and** the import path. The owner's
observation is that the import path misses a little less often than the live path — but it
does miss. So this is a detector-quality problem, not a capture-UI problem.

Everything below is labelled by how well it can be verified:

| Label | Meaning |
|---|---|
| **[DOCUMENTED]** | Stated by the vendor in first-party docs, or directly observed by us (file, hash, tensor shapes, a run). |
| **[INFERENCE]** | A reasonable reading of documented behaviour / public artifacts, not stated anywhere. |
| **[UNVERIFIED]** | We could not confirm it. Do not build a decision on it. |

Evidence for every measured number in this file lives in `tooling/out/h29/`
(`fastvit_inspect.txt`, `mlsd_512_inspect.txt`, `mlsd_320_inspect.txt`, `candidates/`).

---

## 1. What Samsung does on-device

**Documented, user-facing behaviour [DOCUMENTED]** — Samsung's own support material:

* The Camera app detects a document in the viewfinder and shows a **Scan** button; the user
  taps it and the device scans.
  <https://www.samsung.com/us/support/answer/ANS10003696>,
  <https://insights.samsung.com/2023/08/01/how-to-scan-documents-on-your-galaxy-mobile-device>
* Per that support page, the scan "will automatically remove unwanted shadows, hands, and
  other items that may be on the document itself" and "will also align rectangular shapes so
  your document will appear perfectly straight and legible".
* An existing gallery photo can be re-scanned ("Scan" appears when the photo is recognised as
  a document), and the result can be edited/adjusted in Samsung Notes, exported as PDF.
* One UI 8.5-era material additionally advertises finger removal, folded-corner correction and
  moiré reduction. **[INFERENCE]** from video/community coverage; Samsung's support page
  above only commits to shadow/hand removal and straightening. Samsung's community forum
  states the unfold and shadow-removal features "use artificial intelligence"
  — <https://r2.community.samsung.com/t5/Tech-Talk/Document-Scan/td-p/13589920> — that is a
  community post, treat as **[INFERENCE]**.

**What that implies about the pipeline [INFERENCE]:**

1. A detector that runs **continuously on the preview stream** (the button appears
   automatically) — i.e. cheap enough for real-time, and it must survive motion blur.
2. A **geometry** stage producing a quad → perspective warp ("align rectangular shapes").
3. A **content-aware cleanup** stage that can delete a finger, flatten a fold, kill moiré and
   remove shadows. Removing a finger or repairing a folded corner is *not* something a
   four-corner regressor can do: it needs a **region/segmentation** understanding plus
   inpainting. So Samsung is not running "corner heatmap only".
4. It works on the import path too (gallery re-scan), which matches the owner's report that
   the problem is in detection, not in the camera.

**Not documented, and we did not reverse-engineer it:** model family, input resolution, and
whether detection is a corner heatmap, a segmentation mask, or a classical edge pipeline.
Samsung exposes **no public document-detection API** — there is nothing to license, link
against, or copy. Any claim of "Samsung uses model X" is **[UNVERIFIED]**.

**Actionable conclusion:** "what Samsung uses" cannot be adopted. What *can* be adopted is the
structural lesson: their detector is fast, semantic, and backed by more than four corner
points. That is the direction of the shortlist in §4.

---

## 2. Google ML Kit Document Scanner — evaluated and **rejected**

Docs: <https://developers.google.com/ml-kit/vision/doc-scanner> (page last updated 2025-09-30).

**[DOCUMENTED] facts:**

* Artifact `com.google.android.gms:play-services-mlkit-document-scanner`.
* "The UI flow, ML models and other large resources are delivered using Google Play services."
* "The entire document scanner flow operates on-device."
* The SDK **owns the whole UI flow**: your app triggers it, the user scans inside Google's
  viewfinder/preview screens, and control returns to your app afterwards. It does not require
  the camera permission from your app (Play services' own permission is used).
* Customisation is limited to: max page count, gallery-import on/off, and one of three
  `GmsDocumentScannerOptions` scanner modes (`SCANNER_MODE_BASE`, `..._WITH_FILTER`,
  `..._FULL`). `..._FULL` adds ML cleaning (stains, fingers) and is explicitly the mode that
  will gain features through Play services updates.
* The result surface is `GmsDocumentScanningResult` → `getPages()` (per-page image `Uri`) and
  `getPdf()`. **No corner/quad/polygon geometry is exposed anywhere in the API.** "Accurate
  edge detection for optimal crop results" is a *capability statement*, not an output.

**Why this cannot be used by DocScanAI:**

1. We need a **quad in our own preview frame** to draw the overlay, drive auto-capture gating,
   stability scoring and the seed/reprocess guard. ML Kit never returns a quad. **[DOCUMENTED
   by API surface]**
2. It brings **its own UI**; you cannot keep your viewfinder and just take the detector.
   **[DOCUMENTED]**
3. It depends on **Google Play services** — the scanning assets are downloaded by Play
   services. Devices/ROMs without GMS (Huawei, many custom builds, some emulators) cannot run
   it at all. **[INFERENCE from the "delivered using Google Play services" statement; the
   failure mode on non-GMS devices is widely reported but we did not run it]**
4. It is **proprietary** (Google Play Services / ML Kit Terms), so it cannot be vendored,
   inspected, or measured in our corpus harness. **[DOCUMENTED: it is a closed GMS artifact]**

So: do not adopt it because it is Google. It can only ever be an *alternative scanner screen*,
not our detector.

---

## 3. The taxonomy: what "understands what it is looking for" means

Three families are in practical use for on-device document capture:

**(a) Corner heatmap regression.** One CNN predicts 4 heatmaps (one per corner, or one
per-corner-channel) and you take the argmax/centroid of each. Examples: DocAligner LCNet100
(our current `m1c_detect`), DocAligner FastViT-SA24. Cheap, directly gives the quad, but the
output is a *point estimate*: at 128×128 output for a 256×256 input, one cell ≈ 5.6 px of the
input, and each channel collapses to **one global peak**. A thin facing-page sliver next to a
gutter is not a distinct "corner", so the model has no reason to prefer the page edge over the
spread's outer edge. This matches the observed failure.

**(b) Document segmentation.** Predict a paper mask, then take the largest polygon / contour.
Semantic: the network learns "paper" as a region, so a sliver *is* a region and a gutter is a
hole/dark band rather than a corner to be estimated. Downsides: heavier, mask→quad needs a
polygon-fit with its own tuning, and masks blur at 256×256 just like heatmaps.

**(c) Line-segment geometry.** Detect straight segments, then reason about them
(intersections, parallelism, orthogonality, Hough-style accumulation) to form a rectangle.
M-LSD is the small learned version of this. Semantics here are *structural*: text lines,
table rules and page edges are all "straight lines", so the method "understands" that a page
is bounded by long straight edges and that the gutter is an interior line — which is exactly
the failure mode we need to fix. It also produces many lines, not four points, so it can
*reject* a merged spread (the gutter line is present and long).

**(d) Fusion.** Corner heatmap gives the semantic prior and a stable answer on clean pages;
line geometry acts as a checker: if a long interior line passes through the candidate quad,
the quad is a spread, not a page. The brief explicitly allows this, and it is the most
promising direction because it can be added *without* replacing the model we already trust.

Our classical fallback is essentially (c) done with generic edge/gradient cues
(Canny ∪ Otsu-Sobel, adaptive threshold, Hough) plus a quad scorer that rewards **large,
convex, centered, page-bright** quadrilaterals. That scorer is precisely why a book spread
(outer rectangle, larger area, still convex) wins over the one page that was asked for.

---

## 4. Shortlist (licence-clean, ≤3)

| # | Candidate | Licence | Size (bytes) | ONNX availability | Contract (verified) | ORT 1.22 CPU EP | Verdict |
|---|---|---|---|---|---|---|---|
| 1 | **M-LSD** (NAVER, `navervision/mlsd`) | Apache-2.0 | 6,170,338 (512) / 6,170,288 (320) | Third-party ONNX export of the official weights (PINTO model zoo #119) | input `input_image_with_alpha:0` float32 `[1,512,512,4]` (or `[1,320,320,4]`) NHWC **RGBA, 0–255, unnormalised**; outputs `Identity` int32 `[1,200,2]` (junction `y,x` in half-res map space), `Identity_1` float32 `[1,200]` scores, `Identity_2` float32 `[1,256,256,4]` (or `[1,160,160,4]`) displacement field `dx_start,dy_start,dx_end,dy_end` | opset 11, ir_version 6, standard ops only; loaded and ran on host CPU EP | **In** — line geometry provider |
| 2 | **FastViT-SA24 DocAligner** | Repo Apache-2.0; HF weights carry no explicit licence tag **[UNVERIFIED]** | 83,084,930 | Yes, `fastvit_sa24_h_e_bifpn_256_fp32.onnx` on the same HF repo as our LCNet100 | input `img` float32 `[batch,3,256,256]` RGB /255; output `heatmap` float32 `[batch,4,H,W]` already sigmoid; in practice `[1,4,128,128]` | opset 16, ir_version 8, 326 embedded initialisers; loaded and ran on host CPU EP | **In** — drop-in heatmap upgrade (same postprocess) |
| 3 | DeepLabV3-MobileNetV3 "docseg" | Card itself says upstream provenance/licence **not traceable** | 44 MB (as re-hosted) | Yes, but re-hosted | n/a | n/a | **Out** — not licence-clean |

Budget check against the owner's 100 MB ceiling: LCNet100 (4.77 MB) + M-LSD 512 (6.17 MB) =
**10.94 MB**, leaving ~89 MB of headroom and no change to the shipped download UX. Swapping in
FastViT is 83.08 MB (+78 MB over today), which still fits but consumes almost the whole budget
on a single model.

### 4.1 Why M-LSD is the shape of the fix

The M-LSD postprocess ([`pred_squares`](https://github.com/PINTO0309/PINTO_model_zoo/blob/main/119_M-LSD/demo/mlsd/utils_onnx.py))
does exactly what we lack:

1. For each of the 200 junction points above a score threshold, read the displacement field
   and reconstruct the **line segment** the point belongs to.
2. Deduplicate/collapse collinear segments with a Hough-style `(distance, theta)`
   quantisation.
3. Build the junction graph and enumerate quadrilaterals from segment pairs.
4. Score candidates on degree (angle) similarity, area, centre proximity and overlap.

Steps 1–2 are the valuable part for us and are cheap to port (~60 lines of Kotlin, no OpenCV
required beyond what we already use). Steps 3–4 we would *not* port verbatim: we already have
`orderCorners` + `isPlausiblePageQuad`, and porting NAVER's scorer would smuggle in a second
tuned quad chooser. The plan is: **use M-LSD segments as geometry evidence, not as a quad
oracle.**

Cost: 512×512 RGBA float32 is 4.2 MB of input tensor, and the host CPU run measured
**176.8 ms** for one inference (cold, single run, desktop CPU) — that is *not* preview rate.
At 320×320 the work is ~2.6× smaller; measured on-device latency is Step 4 work. This is why
M-LSD is proposed as a **refine/reject stage on the still frame and on the import path**, not
as the per-frame detector.

### 4.2 FastViT-SA24 is the highest-value *cheap* experiment

It is a drop-in: identical input tensor and identical output semantics as LCNet100 (the
postprocess, peak gate, ordering and plausibility gate all apply unchanged), and it loaded and
ran under host ORT CPU EP in 37.6 ms. The only variable is the weight file, so the head-to-head
against LCNet100 is a clean A/B with **zero** new postprocess code. The open question is purely
whether the bigger, stronger backbone separates a sliver corner from a spread corner at
128×128 — which is exactly what Step 4 must measure rather than assume.

---

## 5. Verified artifact record

All verified with [`tooling/OnnxInspect.java`](../tooling/OnnxInspect.java) (JDK-only protobuf
inspector) plus `certutil -hashfile ... SHA256`; raw output kept in `tooling/out/h29/`.

| Artifact | Bytes | SHA-256 | Notes |
|---|---|---|---|
| `m1c_detect.onnx` (shipped, LCNet100) | 4,767,987 | `f4117b786e3a18470f3865c93f3c2bd69d9b998edd60f385574a5c665e79594e` | baseline |
| `tooling/out/h29/candidates/fastvit_sa24.onnx` | 83,084,930 | `7f9f5a8935b2eb22b3ee0245d34996063f54562df390d34714af2d76928695bc` | `img[·,3,256,256]` → `heatmap[·,4,·,·]`, opset 16 |
| `.../candidates/mlsd/saved_model_512x512_large/model_float32.onnx` | 6,170,338 | `6748da6c5fb406b9af56436ecca61493fa60da7d232e5baa9a3ce23dc4ff8ff7` | opset 11, NHWC RGBA |
| `.../candidates/mlsd/saved_model_320x320_large/model_float32.onnx` | 6,170,288 | `26c4d103cdc0e126af94b97ecd5b12ad85b1a7cc8179e6c94177ef603c419e2a` | opset 11, NHWC RGBA |

Runtime class: both are single-file float32 ONNX graphs with only standard-domain ops and no
external data, so they load through the existing `OrtSessionFactory` CPU-EP path with no new
runtime dependency. Host verification used ORT 1.31; the device uses ORT 1.22
(`gradle/libs.versions.toml`), which supports opset 16 — but on-device load/run must still be
proven in the emulator lane before anything is shipped.

M-LSD provenance chain: weights Apache-2.0 from `navervision/mlsd` (official TFLite), converted
to ONNX by `PINTO0309/PINTO_model_zoo` `119_M-LSD` (resources served from
`https://s3.ap-northeast-2.wasabisys.com/pinto-model-zoo/119_M-LSD/resources.tar.gz`). If it
ships, the Apache-2.0 NOTICE/attribution must be carried in `MODEL_SOURCES.md`.

---

## 6. Plan of record

1. **Fixtures first.** ≥8 deterministic book-spread fixtures (sliver-left, sliver-right,
   large-sliver, gutter-only, curved-spine, plus clean controls) at full still resolution *and*
   1280×720, GT quads by construction, under `app/src/androidTest/assets/bookspread/` with a
   manifest. `BookSpreadDetectionTest` scores detection rate per variant, corner error as % of
   image diagonal, and the specific failure: did the quad absorb the sliver?
2. **A/B the weight file.** LCNet100 vs FastViT-SA24 on: the 8 fixtures × 2 resolutions, the
   existing 20-image benchmark, synthetic GT corner error, negatives (must stay 0/5), latency,
   heap. This decides whether the coarse 128×128 heatmap is the bottleneck.
3. **Add line evidence.** M-LSD 512 (host + emulator) → segments → a *rejection/repair* rule
   layered on the existing quad: a long line crossing the candidate quad's interior, roughly
   parallel to the spine, means "this is a spread" and forces the page side. Measured on the
   same fixtures before it is allowed near the shipped path.
4. **Ship only what wins.** If neither beats the baseline on the fixtures without regressing
   the benchmark/negatives gates, ship nothing and record the table in `RESULTS.md`.
5. **Integration constraints** are non-negotiable: keep the multi-file download contract
   (graph + optional `.data` sidecar) with size + SHA-256 verification, keep the deterministic
   CPU session factory, keep the H26 seed guard and the `seed = null` reprocess behaviour, keep
   the page gate/auto-capture/stability policy untouched, and make any new model optional with
   graceful fallback to today's path.

### Outcome of the plan (measured 2026-10-20)

Steps 1 and 2 are done, step 3 was blocked by the environment in H29 and **completed in H30**,
step 4 fired both times.

| step | result |
|---|---|
| 1 fixtures + gate | **done** — 9 scenes × 2 canvases (18 assets), GT exact by construction, `BookSpreadDetectionTest` pinned to the measured baseline |
| 2 A/B the weight file | **done** — LCNet100: 7/12 slivers merged, 3.9562 % corner error, 33.0 ms, 4.77 MB. FastViT-SA24: 11/12 merged, 2.9340 %, 86.5 ms, 83.08 MB. FastViT loses on the failure that matters and on cost — **not shipped** |
| 3 line evidence (M-LSD) | **done in H30** — the decoder was ported to numpy (no `cv2`/`tf`) and validated by 38 synthetic-map tests; the merge-rejection rule then took the fixtures from **7/12 to 2/12** merged slivers on-device (3.9562 % → 3.5672 % corner, IoU 0.8279 → 0.8442). It also cost **+70…+99 ms per detection**, 2.5–3.3× the live-frame budget and 1.2–1.4× the still budget, so it is **not shipped** (default `MlsdMode.OFF`) |
| 4 ship only what wins | **nothing ships in either subtask** — no version bump. See the H29 and H30 sections of `RESULTS.md` |

What step 2 established, and what changes the plan: the facing-page merge is a **formulation limit,
not a weight-file limit.** Both corner models put their four corners on the paper — one at the gutter,
one at the outer edge of the facing page — whenever they succeed, so no replacement corner-regression
model can fix it. Only a different question ("where do this page's borders end?") can.

What step 3 (H30) established, and what changes the plan again: the different question **works** —
line evidence plus a geometric split removes 5 of the 7 merges — but M-LSD is a ~6 MB dense
predictor and cannot be paid for on every frame at 320 on the reference hardware. The remaining
problem is therefore **cost, not formulation**: a smaller/quantised line model, or running the rule
only where a still is available, is what step 5 would have to answer. Until then the shipped path
keeps the four-corner formulation and the pinned gate keeps measuring its failure honestly.

### H30 measurements, in one place

| question | answer | evidence |
|---|---|---|
| Does M-LSD's own square decoder find *the page*? | **No** — 12/12 slivers absorbed, area/page = 1.10. It bounds the spread, because that is what the lines bound | `tooling/out/h30/scores.md` |
| Does using its segments as a **reject/repair rule** on the corner quad work? | **Yes** — 7/12 → 2/12 merged, corner 3.9562 → 3.5672, IoU 0.8279 → 0.8442 | `tooling/out/h27/H30B/logcat.txt` (`H30_AB`) |
| Does 512 beat 320? | **No** — 1/12 vs 0/12 merged offline and 2.5× the model time | `tooling/out/h30/scores.md` |
| Does the text-block side selector beat the area prior? | **No** — text chose the sliver on 3 assets (3/12 merged, 3.8984 %); area gave 0/12 and 3.6923 % | `tooling/out/h30/scores.md` |
| Is it affordable? | **No** — +73.68 ms p50 / +99.39 ms p95 on the live canvas (budget +30); +69.76 / +81.89 on the still (budget +60) | `tooling/out/h30/h30b_key_numbers.txt` |

---

## 7. Risks

* **Domain gap.** M-LSD is trained for wireframes (building facades, indoor scenes). Printed
  text lines and table rules are also long straight lines, so it may flood us with candidates.
  **[INFERENCE]** — must be measured, not assumed.
* **NHWC + unnormalised + alpha channel.** The M-LSD graph wants `[1,H,W,4]`, 0–255, alpha = 1.
  Our corner preprocessor produces NCHW RGB /255. A separate preprocessor is mandatory; the
  contracts are not interchangeable.
* **`Identity` is int32 coordinates in half-resolution map space.** Easy to get wrong by a
  factor of two; the reference implementation multiplies by 2 before scaling to the original
  frame.
* **200 junctions maximum.** Dense pages may exceed it; the model returns the top-scoring
  points, so a page edge that lost to text lines simply will not be present.
* **Latency at 512.** Not preview-rate on desktop CPU. Keep it off the live per-frame path
  unless the 320 variant measures fast enough on the emulator.
* **Licence bookkeeping.** Apache-2.0 needs attribution; the HF-hosted FastViT repo has no
  explicit licence tag, so the chain "Apache-2.0 repo → published weights" is **[UNVERIFIED]**
  and would need to be stated as such if FastViT shipped.

---

## 8. Sources

Vendor / primary:

* Samsung support, "Scan and edit documents on your Galaxy phone or tablet" —
  <https://www.samsung.com/us/support/answer/ANS10003696>
* Samsung Business Insights, "How to scan documents on your Galaxy mobile device" —
  <https://insights.samsung.com/2023/08/01/how-to-scan-documents-on-your-galaxy-mobile-device>
* Samsung community, "Document Scan" (AI unfold / shadow removal) —
  <https://r2.community.samsung.com/t5/Tech-Talk/Document-Scan/td-p/13589920>
* Google ML Kit, Document scanner —
  <https://developers.google.com/ml-kit/vision/doc-scanner>
* NAVER M-LSD — <https://github.com/navervision/mlsd>
* DocAligner — <https://github.com/DocsaidLab/DocAligner>; weights
  <https://huggingface.co/7rplus/pagescan-weights>
* PINTO model zoo, M-LSD — <https://github.com/PINTO0309/PINTO_model_zoo/tree/main/119_M-LSD>

Accessed 2026-10-20. Anything marked **[UNVERIFIED]** above was not confirmable from a
first-party source at that time.


---

## H31 — the merge repair is scheduled (0.3.9)

The facing-page merge fix (H30) is **shipped**, but as a *scheduled* consumer of M-LSD rather than
an unconditional one. The detector consults [`MlsdTrigger`](../app/src/main/java/com/docscanai/ml/MlsdTrigger.kt)
before any model work and runs M-LSD only when the corner quad carries the **merge signature**
(w/h in [0.74, 1.75]), the scene has **settled** (H21 motion guard + ≥2 stable frames), and the
**cadence** allows it (1 call per 24 eligible frames). The fold is cached as geometry
(side + strip fraction) and re-projected onto later frames by two lerps, so the overlay never
flickers between invocations.

**Invocation rate:** 8 of 200 frames (4.0 %) on a spread scan, **0 of 200** on a clean single page.
**Average cost:** +1.79 ms/frame mean, +17.96 ms p95 (budgets +15 / +40). **Still path:** p50
+11.4 ms (budget +60), and a clean capture pays nothing.

**Quality (18 fixtures):** merged slivers 7/12 → 2/12, mean corner 3.9562 % → 3.3495 %,
mean IoU 0.8279 → 0.8593, detection 18/18.

**Guards ("do no harm", the H30 OPEN item):** a repair is accepted only when its strip is ≥ 7.5 %
of the quad width *and* it moves the quad towards the paper aspect. This is what keeps the corpus
at the 0.3.8 numbers (corner 0.03440 %, placement 24/0/16) while still fixing the merge.

**Shipped mode: `MlsdMode.REPAIR`.** The candidate branch (`CANDIDATE` / `BOTH`) is measured but
not shipped: it can only grow a validated corner quad and it damaged `syn_persp_mild_2` on the
corpus (0.18 % → 2.30 % corner error).

Priority order: 1) warping/geometry, 2) auto-capture, 3) additional features such as pencil/handwriting enhancement.

---

## H32 — real book-spread page-pick (0.3.10): the fold rule now reaches every quad

H31 shipped the merge repair as a scheduled consumer of M-LSD for **corner-model quads only**.
The real-photo evaluation (H32) showed that this is exactly the wrong boundary: on a real open
book the classical fallback supplies the quad far more often than the corner model (13/16 real
photos have near-zero corner heat), and those quads were returned **raw** — 6 of 8 merged cases
never saw the rule. The plan of record is therefore amended as follows, each clause measured:

1. **Every quad reaches the same evidence stage.** `OpenCvQuadPageDetector.detect()` now routes
   the classical-fallback quad through `applyMlsdEvidence` exactly like a corner-model quad. The
   rule only ever *shrinks* a quad at measured evidence (`MlsdTrigger.acceptsRepair`), so the
   routing cannot grow or damage a classical quad.
2. **The fold search must see a balanced spread.** A real gutter sits at 45–52 % of the quad
   width; the H30 windows (5–45 % strip, nearest-per-side, 0.80 chord) made it invisible.
   `MlsdMergeRepair.findAllMergeLines` now evaluates **all** candidates with an adaptive strip
   window (up to 55 % of the width when the quad's w/h ≥ 1.10 — a spread signature) and a chord
   window of 0.70. The nearest-per-side view (`findMergeLines`) is kept for cheap callers.
3. **Side selection is a shape prior, not a size prior.** The kept half is the one whose
   paper-aspect prior (the same Gaussian the page gate uses) is higher, with a penalty when the
   kept half is a partial page. The H30 "larger half" rule is the fallback when no pixels are
   available (JVM-only callers).
4. **A partial page is never the target.** `MlsdMergeRepair.borderIsPaper` samples luma on the
   frame borders a half *reaches and the sibling half does not* (spine-parallel splits share
   top/bottom — a first implementation that sampled every reached border marked a full page as
   "cut" and a unit test caught it). Paper-like luma at that unique border = the page runs off the
   frame = partial page, penalised by −0.25 in the selection score. A side-FLIPPING variant was
   measured and **rejected**: it traded a merge for a wrong-page, and wrong-page is the owner's
   primary gate.
5. **The trigger band is widened to match reality** (`aspectMax` 1.75 → 2.30): real spread quads
   measure 1.85–2.15 under perspective and cropping and never fired at 1.75. The H31 corpus
   guard (`asp=3.861`) is still refused at 2.30 — `MlsdTriggerTest` holds it.

The **uniformity-along-the-fold guard** (the documented H31 open item) was implemented in two
forms — a margin-gap/abut test and a text-line-width consistency test — and measured **no effect**
on the real set (the failures it targeted are dominated by `no_interior_line` and by the classical
bypass, not by text-edge confusion). It is therefore **not shipped** and not claimed. A
brightness-valley fold fallback (for spreads with no line evidence at all) was also measured and
rejected: it fixed one merge but introduced a wrong-page on another image.

Measured effect (real set, device, 0.3.9 → 0.3.10): wrong-page **3 → 0**, merged **8 → 2**,
mean corner **18.03 % → 13.05 %**. Synthetic set: merged slivers **2 → 1 / 12**, corner
**3.3495 % → 2.9694 %**, IoU **0.8593 → 0.8727**, detection 18/18 held. Latency: 200-frame mean
**+1.98 ms**, p95 **+23.96 ms** (8 invocations / 200 frames) — inside the H31 budgets.

Residuals (reported per image in `RESULTS.md` H32): two merged images (rb012, rb022) and two
no-detections (rb014, rb028 — a detection-domain gap, not a page-pick gap).

**Priority order: 1) warping/geometry, 2) auto-capture, 3) additional features such as pencil/handwriting enhancement.**
