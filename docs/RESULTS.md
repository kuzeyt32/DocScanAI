# DocScanAI 0.3.8 — Per-image results and release verification

**Version under test:** `versionCode 11` / `versionName 0.3.8`
**Release APK:** `app/build/outputs/apk/release/app-release.apk` — 125,528,642 bytes (119.7 MiB), SHA-256 `32675a8292f48ff58807b71711f8e4af7d8dc6375eb4c13f5fc6de44a350a4bd`
**Debug APK:** `app/build/outputs/apk/debug/app-debug.apk` — 144,388,249 bytes (137.7 MiB), SHA-256 `82bbf53f35bdb49b900d6d82abc17858a4c8eb8dd7564befec6d1a32509d024d`
**Signing certificate SHA-256:** `7a6977076f2c16e1c521b9301339ec9196aaac6f6a7641d7ec3fc965854253dc` (identical to 0.3.7, 0.3.6, 0.3.5, 0.3.4, 0.3.3, 0.3.2, 0.3.1 and 0.3.0)
**H30 line-evidence evidence (no build change):** `tooling/out/h30/` — `SUMMARY.txt` (green/red), `scores.md`/`scores.json` (M-LSD 320 vs 512, merge rule, fusion, M-LSD-alone), `mlsd_port_tests.log` (38 port tests), `resize_probe.json` (offline-replica calibration), `corner_baseline.*`, `h30b_key_numbers.txt` (on-device A/B + latency), `corpus_gate.txt`, `check16k_debug.txt`/`check16k_release.txt`, `h7_h30.log` (13 classes, PASS); instrumentation `tooling/out/h27/H30A/`, `H30B/`; corpus run `tooling/out/corpus_Sal20102026_21555589/`
**H29 detector-strategy evidence (no build change):** `tooling/out/h29/` — `HEADTOHEAD.md` (graph contracts, per-asset tables, decision), `results_h29_section.md` (the H29 section), `fastvit_inspect.txt`, `mlsd_320_inspect.txt`, `mlsd_512_inspect.txt`, `models_fastvit/`; `tooling/out/h27/H29/`, `H29B/`, `H29P/`, `FASTVIT/` (instrumentation output + logcat); 12-class lane `tooling/out/h7_tests/`
**0.3.8 determinism + B&W evidence (H27):** `tooling/out/h27/` — `SUMMARY.txt`, `DET1/` + `DET2/` (`WarpDeterminismTest.txt`, `logcat.txt` — same process and cross-process), `REFINE5/` (pixel-identity run), `REFINE/`–`REFINE4/` (the rejected content-metric attempts, kept), `BW_BEFORE/` + `BW_AFTER/` (`metrics.txt`, `contact_sheet.png`), `BW_SIGMA06/` (the rejected σ=0.6 trial), `pix_metric.py` (offline metric calibration), `H7.log` (11 classes), `CORPUS.log`, `badging_release.txt`, `signer_release.txt`, `check16k_debug.txt`, `check16k_release.txt`, `launch_smoke.png`, `smoke_*.txt`, `build_038_debug.log`, `build_038_release.log`
**0.3.7 geometry-fix evidence (H26):** `tooling/out/h26/` — `SUMMARY.txt`, `RED/RefineSeedGuardTest.txt` (red run, verbatim), `GREEN4/` (green run + `logcat.txt`), `h7_final/` (10-class lane on the shipped tree), `geometry_h26.csv`, `badging_release.txt`, `signer_release.txt`, `check16k_debug.txt`, `check16k_release.txt`, `launch_smoke.png`, `smoke_*.txt`, `build_release.log`
**0.3.6 detection-regression evidence (H24):** `tooling/out/h24/` — `flip_table.md` / `.csv` / `flips.txt` (per-image reconciliation across five runs), `corpus_fixed/` (fixed-tree corpus lane + `benchmark_report.json`), `geometry_fixed.csv` + `geometry_summary.txt`, `warp_metrics.txt`, `meminfo.txt`, `check_16k.txt`, `dark_test.txt`, `dark_logcat.txt`, `h7/`, `build.log`, `release_build.log`
**Handwriting attempt-2 evidence (H25, feature NOT shipped):** `tooling/out/h25/` — `SUMMARY.txt` (green/red), `CAPTURE_BUG_DIAGNOSIS.md` (field-bug analysis), `h25_results.json`, `sheets/` (row/zoom/overview contact sheets), `device_202601Pa_2106/` (raw in/ridge pairs), `h7/`, `dark_test.txt`, `corpus2/` (ai_on + ai_off reports), `check_16k.txt`, `launch_smoke.png`, `build.log`
**0.3.5 auto-capture evidence (H21):** `tooling/out/h21/` — `live_room_red.txt` (RED on 0.3.4), `live_room_green.txt` (GREEN), `dup_test.txt`, `gate_bench.txt`, `after_room_logcat.txt` (120 s room hold, 0 captures), `toggle_logcat.txt`, `manual_logcat.txt`, `badging.txt`, `h7/`, and the corpus run `tooling/out/corpus_Paz18102026_10581939/`
**0.3.4 corpus run (final, refinement OFF):** `tooling/out/h18_refine/corpus_off_reconfirm` — `corpus_out/ai_on/corpus_report.json`, `corpus_out/ai_off/corpus_report_ai_off.json`, `warp_summary.json`
**0.3.4 geometry artifacts:** `tooling/out/h19/geometry_table_0.3.4.csv` + `geometry_table_0.3.4.md` (H19 regeneration, bench run `tooling/out/h19/geom`; identical numbers to `tooling/out/h18_refine/final_geometry.csv`)
**0.3.4 fidelity lane:** `tooling/out/h19/h7_tests` (all seven classes `OK`, re-run by H19 on the shipped tree); H18's earlier identical run: `tooling/out/h18_refine/h7_tests`
**0.3.4 release evidence (H19):** `tooling/out/h19/` — `badging_release.txt`, `apksigner_release.txt`, `check_16k_debug.txt`, `check_16k_release.txt`, `sha256_release.txt`, `dumpsys_package_release.txt`, `logcat_launch_release.txt`, `launch_release_0.3.4.png`
**0.3.3 corpus run:** `tooling/out/h15_warp/corpus_final/corpus_out` (AI-ON report plus `ai_off/corpus_report_ai_off.json`)
**0.3.3 fidelity lane:** `tooling/out/h15_warp/h7` (all seven classes `OK`)
**0.3.2 baseline run:** `tooling/out/h14_final/corpus/corpus_out`

> **Measurement scope: every corpus and device number in this document was measured on the Android
> emulator `docscan_dbg_api35` (x86_64 system image). No number here comes from ARM hardware.
> ARM timings, thermals and battery behaviour on the Samsung Galaxy S25 FE are still unmeasured —
> see the checklist in §7.**

---

## Latest — H30 (0.3.8, no build change): M-LSD line evidence fixes 5 of 7 facing-page merges, and is too slow to ship

**Decision: NOTHING SHIPS.** `versionCode 11` / `0.3.8` stays exactly as it was — same binary, same
size, same SHA-256. H30 built the missing measuring instrument (a dependency-free port of M-LSD's
square decoder), used it as a merge-rejection rule on the existing corner-model quad, and proved on
the 18 fixtures that the rule fixes **5 of the 7** facing-page merges and improves corner error and
IoU. It also measured M-LSD's cost: **+70…+99 ms per detection** on the reference emulator, against
a `+30 ms` live-frame and `+60 ms` still budget. Quality wins, latency loses, so no release is cut.

**Recorded priority order (unchanged, verbatim):**
> Priority order: 1) warping/geometry, 2) auto-capture, 3) additional features such as pencil/handwriting enhancement.

### H30.1 What was actually built

| piece | file | what it is |
|---|---|---|
| numpy port | [`tooling/h30_mlsd.py`](../tooling/h30_mlsd.py), [`tooling/h30_corner.py`](../tooling/h30_corner.py) | `pred_squares`/`pred_lines` rewritten without `cv2`/`tensorflow`, plus a replica of the shipped corner path so the rule is measured against the real quads |
| port tests | [`tooling/h30_mlsd_test.py`](../tooling/h30_mlsd_test.py) | 38 unit tests on synthetic maps with answers known by construction |
| merge rule | [`tooling/h30_merge.py`](../tooling/h30_merge.py), [`MlsdMergeRepair.kt`](../app/src/main/java/com/docscanai/ml/MlsdMergeRepair.kt) | split the quad on an interior fold, keep the page side |
| Kotlin port | [`MlsdGeometry.kt`](../app/src/main/java/com/docscanai/ml/MlsdGeometry.kt), [`MlsdModel.kt`](../app/src/main/java/com/docscanai/ml/MlsdModel.kt) | junction/score/displacement decode, Hough merge, square enumeration, NHWC+alpha preprocessing |
| wiring | [`OpenCvQuadPageDetector.kt`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt) | `MlsdMode` flag (default **OFF**), `[MLSD]` log line, graceful fallback when the optional artifact is absent |

### H30.2 Port validation (no correctness claims from "it produced something")

`python tooling\h30_mlsd_test.py` → **38 tests, OK**. Each synthetic map is built so the expected
result is known:

| case | construction | asserted result |
|---|---|---|
| rectangle | four sides written into the junction/displacement maps | exactly 4 merged lines; exactly **1** square, corners within 1.5 px, area 20,000 px² |
| diagonal | one segment | 1 line, **0** squares |
| T-junction | 3 segments meeting | 3 lines, **0** squares |
| empty | zero junctions / zero maps / short displacement / low score | nothing out, right shapes, no exception |
| noisy | 5 random seeds × 200 junctions | bounded, finite, deterministic, under 10 s, cap never fires |
| planted rectangle + 25 clutter segments | page edges voted twice | the planted rectangle is still the largest square (20,000 px² ±400) |
| merge rule | fold at 10 % / 3 % / 62 %, horizontal line, half-height line, no segments | split keeps the page; edge shadow (3.5 %) is **not** cut; the symmetric reading at 62 % is asserted as a documented limitation |

Two real defects were caught by this process and are now regression-tested:

1. **`map_to_frame` scaled only the first endpoint** of a 4-column segment (column 0 by `sx`,
   column 1 by `sy`), leaving `(x2, y2)` in map space. Every straight line became a diagonal, so
   the merge rule found no gutter and M-LSD looked useless for a reason that had nothing to do with
   M-LSD. Fixed and pinned by `TestFrameMapping`.
2. **Non-finite intersections** (parallel lines) were cast to an arbitrary int with a numpy warning;
   now clamped deterministically.

Calibration of the one thing that cannot be copied — Android's resize kernel — is in
`tooling/out/h30/resize_probe.json`: against the device's published per-asset numbers, `box`
reproduces the merged count exactly (7/12) with mean |Δ| 0.70 pp, while plain bilinear gets 6/12.
The offline replica is therefore labelled a **proxy**: it reproduces the aggregate direction and
magnitude, the device remains the authority, and the largest per-asset residual (bs08 still, 4.7 pp)
is reported rather than hidden.

### H30.3 M-LSD scored offline (18 fixtures, both canvases, desktop CPU, ORT 1.31.0)

Corner model `f4117b78…594e` (the shipped artifact). M-LSD 320 `26c4d103…19e2a` (6,170,288 B) and
512 `6748da6c…8ff7` (6,170,338 B).

| variant | detected | merged slivers | mean corner % diag | still | preview | mean IoU | area/page |
|---|---|---|---|---|---|---|---|
| corner baseline (replica) | 18/18 | 7/12 | 4.6018 | 7.4422 | 1.7613 | 0.7943 | 0.9202 |
| **corner + merge rule, 320** | 18/18 | **0/12** | **3.6923** | 6.6800 | **0.7046** | **0.8319** | 0.8366 |
| corner + merge rule, 512 | 18/18 | 1/12 | 4.0705 | 6.7165 | 1.4246 | 0.8136 | 0.8535 |
| corner + fusion (candidate gen.), 320 | 18/18 | 0/12 | 3.7019 | 6.6800 | 0.7238 | 0.8309 | 0.8361 |
| corner + fusion, 512 | 18/18 | 1/12 | 3.9767 | 6.7165 | 1.2369 | 0.8179 | 0.8564 |
| M-LSD alone, best square, 320 | 18/18 | 12/12 | 2.5262 | 2.8858 | 2.1666 | 0.8848 | **1.0987** |
| M-LSD alone, 512 | 18/18 | 12/12 | 2.3266 | 2.7076 | 1.9455 | 0.8981 | **1.1041** |

Three findings, all measured:

* **M-LSD alone is not the fix.** Its squares take the *spread* 12/12 — area/page = 1.10 on both
  sizes. It is a line detector, and the spread is what the lines bound. This is why the rule is
  layered on the corner quad instead of replacing it.
* **The 320 graph beats the 512 graph after the rule** (0/12 vs 1/12 merged, 3.69 % vs 4.07 %) at
  2.5× lower model time, so 512 buys nothing.
* **The text-block selector was rejected by measurement.** “Keep the side containing the page's text
  block” was implemented and scored: it chose the facing-page sliver on bs02/bs03/bs07 preview,
  giving 3/12 merged and 3.8984 %. The area prior (the requested page dominates the frame) gave
  **0/12 and 3.6923 %**. The shipping rule uses the area prior; the text scores are still computed
  and logged so a future dataset can re-test the idea.

### H30.4 The merge rule, stated exactly

Given the corner quad `Q` and M-LSD's merged segments `S`:

1. a segment is a **fold candidate** when it is within 25° of `Q`'s spine (its left/right edge
   direction), at least 0.45 × `Q`'s height long, and its *infinite line* covers ≥ 80 % of `Q`'s
   extent along that direction (the infinite line, because M-LSD segments stop a few pixels short);
2. its distance from `Q`'s left (or right) edge must be **5 %–45 %** of `Q`'s width — below 5 % it is
   the page's own edge shadow (bs06's gutter shadow sits 3.5 % inside the page and must **not** be
   cut), above 45 % the “sliver” would be the page;
3. the nearest qualifying line on each side is tried, the thinner strip wins, `Q` is split, and the
   **larger** half is kept;
4. the kept half must still pass the app's own `isPlausiblePageQuad` and be ≥ 12 % of the frame —
   otherwise the original quad is returned and the reason is logged.

Anything else returns `Q` untouched with a reason (`no_segments`, `no_interior_line`, `strip_size`,
`line_misses`, `half_implausible:*`). The Kotlin constants are identical to the Python ones, and the
`[MLSD] segments=… squares=… chosen=… reason=…` line is emitted once per detection.

**Known limitation, stated rather than hidden:** a purely geometric rule cannot tell “spread with a
wide sliver on the right” from “page with a long vertical rule at 62 %”; it reads the second as a
37.5 % strip on the first. In practice it also fires on the clean controls (bs01/bs09 preview:
`split(left, strip≈8 %)`, corner error 0.17 % → 1.30 %), because the fixture's text-block edge is a
full-height spine-parallel line. A uniformity test along the fold (a gutter is uniform along its
length, a text edge is not) is the next guard to add; the 20-image corpus lane is what would catch a
rule line masquerading as a gutter.

### H30.5 On-device A/B (emulator `docscan_dbg_api35`, CPU EP, same binary, flag only)

| metric | M-LSD OFF (production) | M-LSD BOTH | delta |
|---|---|---|---|
| quads returned | 18/18 | 18/18 | = |
| merged slivers (of 12) | **7** | **2** | **−5** |
| mean corner error, % diag | 3.9562 | **3.5672** | −0.3890 |
| mean IoU vs GT page | 0.8279 | **0.8442** | +0.0163 |
| per-asset `[MLSD]` | — | 15/18 assets got `chosen=mlsd` | — |

The OFF column reproduces H29 exactly (`18/18, 7/12, 3.9562, 0.8279`), which is the strongest
available evidence that the new code path is inert when the flag is off.

### H30.6 Latency — the reason nothing ships

Measured as the paired difference over the same 9 assets per canvas (warm session, `H30_LATENCY`):

| canvas | extra p50 | extra p95 | budget | verdict |
|---|---|---|---|---|
| preview (1280×720, live path) | **+73.68 ms** | **+99.39 ms** | +30 ms | **FAIL (2.5–3.3×)** |
| still (3024×4032) | **+69.76 ms** | **+81.89 ms** | +60 ms | **FAIL (1.2–1.4×)** |

Cold-start (first still of a process) is far worse: +446 ms at p95, dominated by M-LSD session
creation. On desktop CPU the model alone is 30.7 ms p50 / 57.3 ms p95 at 320, and 78.9 / 116.0 at
512, with the numpy preprocessing excluded — so the emulator's 60–150 ms is the graph, not the
harness. The subtask's budget assumes a phone-class CPU; this reference emulator is not one, and the
rule as specified cannot meet either budget here.

### H30.7 The gate, as it stands after H30

`BookSpreadDetectionTest` (6 tests, **PASS**) now contains:
`fixtureManifestIsConsistent`, `scoresEveryFixtureOnBothCanvases`, `pinnedDetectionQuality`,
`mlsdAbIsAttributableAndDoesNotCostDetection`, `latencyBudgetIsRespected`,
`productionModeMatchesTheShippedDecision`.

The pins are **not** tightened, deliberately:

```kotlin
PIN_DETECT_FRACTION       = 1.00   // measured 18/18 (unchanged)
PIN_MAX_MERGED_SLIVERS    = 7      // measured 7/12 — the candidate that reaches 2/12 is not shipped
PIN_MAX_MEAN_CORNER_PCT   = 3.97   // measured 3.9562 (unchanged)
```

Tightening them to the unshipped candidate's numbers (2/12, 3.5672) would make the regression gate
assert something the shipped binary does not do, which is the exact failure mode H29 warned about.

### H30.8 What would make this shipable

1. **Cut the cost.** The graph is 6.17 MB at 320 and dominates the budget. Either a smaller export
   (M-LSD-tiny), an int8 build, or running it only on the capture still would be required — and each
   needs its own measurement.
2. **Add the fold-uniformity guard** so the rule stops shaving 8 % off clean pages.
3. Re-run the 18 fixtures and the 20-image corpus with the guard in place, then tighten the pins in
   the same commit that changes the detector.

### H30.9 Evidence

`tooling/out/h30/` — `SUMMARY.txt` (green/red), `scores.json` + `scores.md` (offline variants),
`mlsd_port_tests.log` (38 tests), `resize_probe.json` (resize calibration + residuals),
`corner_baseline.*` (replica vs H29), `h30b_key_numbers.txt` (on-device A/B + latency),
`h7_h30.log` (13-class lane, PASS), `corpus_h30.log` + the `corpus_Sal20102026_21555589/` run,
`check16k_*.txt`; instrumentation output in `tooling/out/h27/H30A/` and `tooling/out/h27/H30B/`
(`BookSpreadDetectionTest.txt`, `logcat.txt`).

---

## H29 (0.3.8, no build change): the over-detection is the corner-heatmap paradigm, not the model

**Owner brief (verbatim):**

> before you start the sub5ask it happens when i import too just a little less like %1 of the time it will dteec6t more than the app itself you can probably look whatsamsung uses for its own detection if it is local?like a detector that can understand what it is looking for lşike the other scanners?

**Decision: NOTHING SHIPS.** No detector change, no new model file, no version bump. The brief asked
for a fix and a ship; the measurement says the obvious fix does not exist. What the brief actually
uncovered is that the app has never had a *test* for this failure, so the failure could drift in and
out of the build unobserved. That test now exists, with exact ground truth, and it is pinned to the
measured baseline.

**Recorded priority order (unchanged, verbatim):**
> Priority order: 1) warping/geometry, 2) auto-capture, 3) additional features such as pencil/handwriting enhancement.

### 1. What Samsung and Google actually do on-device

Asked directly by the owner ("look what samsung uses"), so the answer is separated by certainty.
Full detail, links and the rejected option are in [`DETECTOR_STRATEGY.md`](DETECTOR_STRATEGY.md).

| claim | certainty |
|---|---|
| Samsung Document scan (Camera → Scene optimiser "Document scan", and the separate Document scan mode) runs **on the device** and offers crop/rotate, filter, and "AI unfold"/shadow removal | **[CONFIRMED]** by Samsung's own support and newsroom pages |
| Samsung's scanning pipeline is **not** the open ML Kit Document Scanner and its internal detector is not published — no vendor page documents the model, the licence or the architecture | **[INFERENCE]** from the absence of any such disclosure; Samsung publishes *features*, never detector internals |
| Google's ML Kit **Document Scanner** does expose a `GmsDocumentScanning` quad and runs on-device, but it requires Google Play services, is closed-source, and its UI is **not** bypassable — you get Google's whole scanner screen, not a quad | **[CONFIRMED]** from the ML Kit documentation; this is why it was rejected for DocScanAI, which needs the quad for its own viewfinder, gate and dewarp |
| "Understands what it is looking for" = **segmentation/line-geometry** rather than four-corner regression | **[INFERENCE]** — the taxonomy in §3 of the strategy doc. It is the only formulation that matches the observed behaviour: a four-corner heat map asks *where is the sheet*, a segmentation or line model asks *where does this page end* |

### 2. A dataset for the exact failure, with ground truth that is exact

`app/src/androidTest/assets/bookspread/` — **9 scenes × 2 canvases = 18 assets**, ground truth
produced by construction (homography of the unit square, banded rasterisation, `sin(πv)` spine
warping that vanishes at the corners so the GT quad stays exact):

| asset | variant |
|---|---|
| `bs01_control_clean` | clean single page — control |
| `bs02_sliver_left_thin` | thin facing-page sliver, left |
| `bs03_sliver_right_thin` | thin facing-page sliver, right |
| `bs04_sliver_both_large` | large slivers, both sides |
| `bs05_gutter_only` | gutter shadow, no visible facing page — control |
| `bs06_curved_spine` | curved spine |
| `bs07_perspective_skew` | strong perspective |
| `bs08_uneven_light` | uneven lighting + gutter |
| `bs09_control_strong_persp` | strong perspective, clean — control |

Each at 3024×4032 ("still", the import path) and 1280×720 ("preview", the viewfinder path).
`book_spread_manifest.json` states the GT quad, the page quadrilateral and the sliver polygon per
asset; the manifest is treated as a pixel claim, not as documentation — `h29_fixtures.py --verify`
checks that pixels inside the GT page really are page-like and pixels outside really are not.

### 3. Head-to-head — LCNet100 (shipped) vs DocAligner FastViT-SA24

Both columns from the **same test APK**, the **same 18 assets**, the **same metric code**, the same
emulator (`docscan_dbg_api35`, CPU EP, `enableXnnpack = false`). The only difference was the bytes of
`files/models/m1c_detect/model.onnx`: the two graphs have an identical contract (`[1,3,256,256]` →
`[1,4,128,128]`, squash-resize, RGB/255, NCHW, no mean/std, sigmoid), so the swap needed no code.

| metric | LCNet100 (shipped) | FastViT-SA24 | winner |
|---|---|---|---|
| quads returned | 18/18 | 18/18 | tie |
| **facing page absorbed (of 12 sliver assets)** | **7** | **11** | **LCNet100** |
| mean corner error, % of diagonal | 3.9562 | 2.9340 | FastViT |
| mean corner error, stills only (9) | 6.1852 | 4.1296 | FastViT |
| mean corner error, previews only (9) | 1.7271 | 1.7385 | tie |
| mean IoU vs GT page | 0.8279 | 0.8691 | FastViT |
| quads from the corner model (vs classical fallback) | 18/18 | 13/18 | **LCNet100** |
| mean latency per image | 33.0 ms | 86.5 ms | **LCNet100** (2.6×) |
| artifact size | 4.77 MB | 83.08 MB | **LCNet100** (+78.31 MB) |

The baseline is repeatable: `H29`, `H29B` and `H29P` are three separate instrumentation processes and
reported **bit-identical** `meanCornerPct=3.9562`, `meanIouPage=0.8279`, `mergedSlivers=7/12`,
`detected=18/18`; only latency varied (33.70 / 29.68 / 32.96 ms).

### 4. Why the facing page is absorbed — and why a better model does not help

Both corner models, when they succeed, put the four corners on the **paper**: one at the gutter, one
at the outer edge of the *facing* page. On the preview canvas FastViT merged 6/6 sliver previews and
LCNet100 merged 6/7, with 84–100 % of the sliver inside the returned quad. Neither model is
malfunctioning; both are answering *"where is the sheet of paper?"* correctly. The owner's report —
"it detects more than the app itself" — is the app asking for **one page of a two-page object** and
getting the object.

FastViT's lower corner error is therefore not a win: the gap is entirely in the stills, and 4 of its
11 merges are not the model at all — FastViT failed its own plausibility gate on bs02/bs03/bs07/bs08
stills (5/9 stills fell back to the classical construct, versus 0/9 for LCNet100), and every fallback
hands the frame to the path that grabs the whole spread. Paying +78.31 MB and 2.6× latency for a
57 % regression on the *specific* failure the subtask is about is not a trade any reading supports.

**M-LSD was not scored.** It is the cheapest candidate (+1.40 MB) and the only one whose paradigm
could refuse to merge, but its reference decoder needs `cv2` and `tensorflow` (both
`ModuleNotFoundError` here) and rewriting the Hough/NMS stage in numpy was not validated well enough
to put a number in this table. **No M-LSD number is claimed.** It remains the recommended next
experiment.

### 5. "Just fix the import resize" is not available

At still resolution the shipped model is not accurate, and it is not inaccurate in one direction.
The detected/ground-truth area ratio on the 9 stills ranges from **0.82** (it crops *inside* the
page: bs06 0.8181, bs02 0.8219) to **1.16** (bs05, where it runs past the page). No single scale or
aspect correction repairs that, which closes the cheapest hypothesis first.

### 6. What does ship in the repository

* `tooling/h29_fixtures.py` — deterministic fixture generator with `--verify`.
* `app/src/androidTest/assets/bookspread/` — 18 assets + `book_spread_manifest.json`.
* `BookSpreadDetectionTest` — scores detection rate, corner error as % of diagonal, IoU, and the
  sliver-merge failure against the real production detector (`ServiceLocator.pageDetector`), and
  asserts the measured baseline as a **gate**:

```kotlin
PIN_DETECT_FRACTION      = 1.00   // measured 18/18
PIN_MAX_MERGED_SLIVERS   = 7      // measured 7/12
PIN_MAX_MEAN_CORNER_PCT  = 3.97   // measured 3.9562
```

* `docs/DETECTOR_STRATEGY.md` — what Samsung/Google do on-device with certainty levels, the
  taxonomy, and the ≤3 licence-clean shortlist with recorded ONNX contracts.
* `tooling/out/h29/HEADTOHEAD.md` — the per-asset evidence behind the table above.

The gate is real: pointed at FastViT-SAv24 it **fails**, `AssertionError: the detector absorbed the
facing page on 11/12 sliver asset(s), pinned budget is 7`. A future candidate that loses to the
baseline cannot pass silently. When something does beat it, these three constants must be tightened
to the new measurement in the same commit — a pin left at the old, worse number stops guarding the
improvement it exists to protect.

### 7. No regression

Nothing under `app/src/main` changed in this subtask (newest main-source file is
`ClassicalEnhancer.kt`, untouched since the H27 chain), so today's behaviour is untouched by
construction and the shipped APK is the 0.3.8 binary described at the top of this document. The full
instrumented gate — now **12 classes**, including `BookSpreadDetectionTest` — was re-run on the tree
that holds the new test: `FINAL: PASS exit=0`, all twelve classes `OK`, `BookSpreadDetectionTest`
`OK (3 tests)` in 1.3 s. The fixture numbers inside the gate reproduce the baseline exactly —
`detected=18/18 mergedSlivers=7/12 meanCornerPct=3.9562 meanIouPage=0.8279 meanMs=31.5243` — which is
a fourth independent instrumentation process, identical to the last digit. Log:
`tooling/out/h7_tests/progress.txt`; per-class output `tooling/out/h7_tests/<Class>.txt`.

### 8. State of the product

0.3.8 still scans reliably on the paths that were already gated (corpus AI-ON ≥19/20, negatives 0/5,
live-room false positive closed, geometry deterministic), and it still over-detects an open book
spread on the import path roughly as often as the owner reported — that behaviour is now measured
rather than anecdotal: 7 of 12 sliver assets absorb the facing page, and at full still resolution the
corner model's area error runs from −18 % to +16 % with no single correction in between. The failure
is a formulation limit of four-corner regression, not a tunable defect, so it is recorded as a known
gap with a named next step (line/segmentation evidence from M-LSD, layered as a rejection/repair rule
on the existing quad) instead of being papered over. Everything else about the release — determinism
of the grid dewarp, B&W softness, auto-capture safety, the H26 seeded-`refine()` guard — is unchanged
and still proven by the gate.

### 9. Evidence

`tooling/out/h29/` — `HEADTOHEAD.md` (contracts, per-asset tables, decision), `fastvit_inspect.txt`,
`mlsd_320_inspect.txt`, `mlsd_512_inspect.txt` (ONNX I/O recorded before any integration code),
`models_fastvit/` (the artifact-only A/B composition), `SUMMARY.txt` (green/red of the gate);
`tooling/out/h27/H29/`, `H29B/`, `H29P/`, `FASTVIT/` (instrumentation logs + logcat, the raw numbers
in the table above); `tooling/out/h7_tests/` (the 12-class lane).

### 10. Why this is not version 0.3.9

A version bump on an unchanged binary would claim a fix that was not made and would put a number in
the field whose behaviour is byte-identical to 0.3.8. The subtask's own rule is *ship only a measured
winner*; there was no winner, so the release stays 0.3.8 / `versionCode 11` and this section is the
record of why.

---

## H27 (0.3.8): the grid dewarp is bit-deterministic, and B&W is one modest step softer

**Decision: SHIP.** Two items, both measured before and after. The second-stage UVDoc grid dewarp —
which H26 could only prove "stable enough to gate geometry" — is now **bit-identical for identical
input**: the same bitmap and quad produce the same grid floats, the same remap and the same JPEG
bytes, in-session, across sessions and across every thread configuration tested. B&W mode, which the
owner called "too sharp", is exactly one step softer (a 0.4 px anti-alias on the 1-bit output),
measured on three real corpus pages; B&W mode has no unsharp mask at all, its harshness was the
0↔255 threshold cliff.

**Recorded priority order (unchanged, verbatim):**
> Priority order: 1) warping/geometry, 2) auto-capture, 3) additional features such as pencil/handwriting enhancement.

### 1. Item A — the warp is now deterministic, and it is asserted

**Reproduction** ([`WarpDeterminismTest`](../app/src/androidTest/java/com/docscanai/WarpDeterminismTest.kt:1), 5 runs per
configuration from one decoded bitmap and one fixed quad, AVD `docscan_dbg_api35`):

| configuration | grid floats SHA-256 | grid ms | delivered page RGBA SHA-256 | JPEG bytes | JPEG SHA-256 | delivered ms |
| --- | --- | --- | --- | --- | --- | --- |
| baseline (default threads) | `49ab2768…e57ca` | 87 | `13b98787…b0534` | 449 961 | `ada3b772…58a3a` | 558 |
| **deterministic compute** (shipped) | `49ab2768…e57ca` | 94 | `13b98787…b0534` | 449 961 | `ada3b772…58a3a` | 563 |
| intra-op = 1 | `49ab2768…e57ca` | 288 | `13b98787…b0534` | 449 961 | `ada3b772…58a3a` | 753 |
| intra-op = 1 + deterministic | `49ab2768…e57ca` | 290 | `13b98787…b0534` | 449 961 | `ada3b772…58a3a` | 746 |

`stable=true distinct=1` for grid floats, page RGBA and JPEG at every configuration, `psnr=inf`, and
the cross-session lane (`DET2`, two different processes, PID 4359 → 4523) reports
`previous=13b98787… ada3b772… 449961 current=<same> matches=true`.

**Cause.** The graph itself was already reproducible: all four session configurations produced the
identical grid hash, so ORT intra/inter-op thread count is not the source (and pinning one thread
costs 3.3× latency for the same bytes, which is why it was rejected). What was *not* guaranteed was
deterministic compute — the runtime is free to use non-deterministic reductions — and H26's byte
difference came from the caller, not the model: `reEnhance` passes `seed = null`, so the quad is
re-detected, and the page size follows from the quad (784x1027 vs 785x1030, corner shift 0.76 %),
which is enough to change the graph raster, the remap and therefore the JPEG.

**Fix.** A dedicated ORT session factory for the grid model requests `setDeterministicCompute(true)`
([`ServiceLocator.kt`](../app/src/main/java/com/docscanai/core/di/ServiceLocator.kt:1)
`dewarpGridSessionFactory`), with the model loaded through it. Cost: grid graph 87 → 94 ms,
delivered warp 558 → 563 ms (**+1.2 %**, inside run-to-run spread).

**Assertion.** [`WarpDeterminismTest`](../app/src/androidTest/java/com/docscanai/WarpDeterminismTest.kt:1) asserts
bit-identical grid floats, page RGBA and JPEG at N = 5 in-session **and** equality with the record
written by the previous process, for every configuration, and — at the production configuration
`det` — across thread settings.

**The pixel-level assertion H26 had to skip.** Three states are now proven, and the honest one is
stated rather than fudged:

| case | evidence (REFINE lane) |
| --- | --- |
| same bitmap through the production chain, twice | identical file SHA-256 `418b2577…02c509`, 253 256 B, **pixel MAE 0.0000** |
| the stored raw capture re-warped twice | identical SHA-256 `ea757aad…105fbd`, 258 204 B |
| import vs Edit → ORIGINAL | differ (34.1 grey levels raw, 30.7 at the best integer shift) — and that is *content displacement*, not variance |

The third row is explained and bounded, not hidden: `reEnhance` re-warps `page.rawFile`, a JPEG
re-encode of the capture, while the import warps the pristine frame; from that noisier bitmap the
corner model re-detects a slightly different quad (IoU 0.9930, worst corner 0.76 %), and the warp is
a pure function of (bitmap, quad, page size). H26's two mystery sizes are therefore both
reproducible today: 258 204 B is a re-warp of the stored raw, 288 331 B is the delivered reprocess,
253 256 B is the import. A content-metric threshold was measured and **rejected**: on these
bitmaps the same-page pair and the H26 defect class are not separable by any low-pass or ink-overlap
metric (r = 3 px: 21.5 vs 21.9 grey levels; ink IoU 0.50 vs 0.24) because the defect class is *the
same page zoomed* — so the gate is stated on the quantity that has a right answer (identical input →
identical bytes, MAE 0) plus the geometric bounds (`cornerIou ≥ 0.95`, corner shift ≤ 1 %, output
size ratio ±10 %).

### 2. Item B — B&W "too sharp": one modest step, measured on real pages

Owner, verbatim: *"B&W is too sharp, you can decrease it a lil bit"*. The B&W branch of
[`ClassicalEnhancer`](../app/src/main/java/com/docscanai/imaging/ClassicalEnhancer.kt:340) never
calls `unsharpMask` — its strokes were hard because Sauvola is applied with a single `CMP_GT`
decision, so 100 % of pixels were exactly 0 or 255. The one adjustment: anti-alias that 1-bit output
with a Gaussian of σ = **0.4 px** (a convex mix of the two existing values, so it cannot create
overshoot or tint the paper), `BW_SOFTEN_SIGMA` at
[`ClassicalEnhancer.kt:507`](../app/src/main/java/com/docscanai/imaging/ClassicalEnhancer.kt:507).

Measured on 3 real corpus pages at 675x900 (`tooling/out/h27/BW_BEFORE` → `BW_AFTER`):

| page | B&W stroke Laplacian (mean abs, ink) | clipped pixels | overshoot (2-px ring) | B&W / ORIGINAL | B&W / ENHANCED |
| --- | --- | --- | --- | --- | --- |
| `docunet_01` | 156.475 → **136.691** (−12.6 %) | 100.000 % → 89.960 % | 0.000 → 0.000 | 6.94 → 6.05 | 1.32 → 1.16 |
| `docunet_18` | 277.029 → **239.557** (−13.5 %) | 100.000 % → 96.669 % | 0.000 → 0.000 | 5.93 → 5.13 | 1.96 → 1.69 |
| `docunet_35` | 218.724 → **190.848** (−12.7 %) | 100.000 % → 96.990 % | 0.000 → 0.000 | 8.51 → 7.43 | 3.25 → 2.84 |

Reference points on the same pages: ORIGINAL 22.6 / 46.7 / 25.7 and ENHANCED 118.2 / 141.6 / 67.2
with real halos (overshoot 20.4 / 39.9 / 17.1, clipping 21.6 / 10.7 / 8.0 %). So after the change
B&W is still 5–7× sharper than ORIGINAL and 1.16–2.84× sharper than ENHANCED, clipping did not rise,
and overshoot is still exactly zero. A **rejected** trial at σ = 0.6 is kept as evidence
(`tooling/out/h27/BW_SIGMA06/`): it took the stroke Laplacian to 80.3 / 132.5 / 111.4 (−49…−52 %) and
made B&W *softer than ENHANCED* on `docunet_01` (ratio 0.687) — more than "a lil bit" — so it was
reverted. Before/after contact sheet with 3× detail crops: `tooling/out/h27/BW_AFTER/contact_sheet.png`.

### 3. No regression (shipped tree, 0.3.8)

| gate | result |
| --- | --- |
| `tooling\h7_tests.cmd` (11 classes) | **PASS** — `DetectionRegressionTest`, `DewarpFidelityTest`, `NeuralPathReproTest`, `NeuralPathXnnpackTest`, `PageViewerUiTest`, `DetectorLatchRuntimeTest`, `AutoCaptureGatingTest`, `LiveCapturePathTest`, `LiveRoomNegativeTest`, `AutoCaptureDuplicateTest`, `RefineSeedGuardTest` all `RESULT=PASS`, `FINAL: PASS exit=0` |
| `tooling\corpus_run.cmd` | **PASS** — AI-ON 19/20, AI-OFF 14/20, negatives 0/5 both, `distorted_suspect` 0, corner error ≤ 0.03440 % diag, placement 24/0/16, border RMS ≤ 10.02 |
| `tooling\check_16k.py` | **16/16** on both APKs |

### 4. Evidence

`tooling/out/h27/` — `SUMMARY.txt`, `DET1/`, `DET2/` (determinism lanes + `logcat.txt`),
`REFINE5/` (determinism + pixel identity through the production chain), `REFINE4/pix/` (the three
bitmaps the pixel claims are computed on) and `pix_metric.py` (the metric calibration),
`BW_BEFORE/`, `BW_AFTER/` (+ `contact_sheet.png`), `BW_SIGMA06/` (rejected trial), `H7.log`,
`CORPUS.log`, `HEARTBEAT.txt`.

### 5. Release proof (0.3.8 / `versionCode 11`)

| item | value |
| --- | --- |
| version | `versionCode 11` / `versionName 0.3.8` ([`app/build.gradle.kts:59`](../app/build.gradle.kts:59)) |
| release APK | 125,528,642 bytes, SHA-256 `32675a8292f48ff58807b71711f8e4af7d8dc6375eb4c13f5fc6de44a350a4bd` |
| debug APK | 144,388,249 bytes, SHA-256 `82bbf53f35bdb49b900d6d82abc17858a4c8eb8dd7564befec6d1a32509d024d` |
| `aapt2 dump badging` | `package: name='com.docscanai' versionCode='11' versionName='0.3.8'`, `targetSdkVersion:'35'` — `tooling/out/h27/badging_release.txt` |
| `apksigner verify --print-certs` | certificate SHA-256 `7a6977076f2c16e1c521b9301339ec9196aaac6f6a7641d7ec3fc965854253dc`, unchanged since 0.3.0 — `tooling/out/h27/signer_release.txt` |
| launch smoke | release APK installed (`Success`), `topResumedActivity=com.docscanai/.MainActivity`, `versionCode=11` / `versionName=0.3.8`, empty `AndroidRuntime:E` log — `tooling/out/h27/launch_smoke.png`, re-runnable with `tooling\h27_smoke.cmd` |
| 16 KB page size | `16KB-OK` for both APKs — `tooling/out/h27/check16k_debug.txt`, `check16k_release.txt` |

The geometry bench was deliberately **not** re-run: the deterministic session produces pixels
bit-identical to the previous default session (`minPsnr=inf`), and `BW_SOFTEN_SIGMA` touches only the
B&W branch, so the H26 geometry table (corner error ≤ 0.03440 % diag, placement 24/0/16, border RMS
≤ 10.02) cannot move.

---

## H26 (0.3.7): the seeded `refine()` can no longer replace a correct seed — capture and Edit → ORIGINAL geometry fixed

**Decision: SHIP.** The owner's priority-1 complaint — a page captured in the app, or re-processed with
Edit → ORIGINAL, comes out as a **sub-rectangle of the page** (an inner text block / table frame /
ruled margin instead of the document border) — was reproduced in an acceptance test, root-caused in
three places, fixed without touching any threshold, and re-verified on the whole chain:
`versionCode 10` / `versionName 0.3.7`, 125,528,642 bytes, SHA-256 `59114bdb…`.
Import was never broken and still is not.

**Recorded priority order (unchanged, verbatim):**
> Priority order: 1) warping/geometry, 2) auto-capture, 3) additional features such as pencil/handwriting enhancement.

### 1. The defect (diagnosed in H25, fixed here)

[`tooling/out/h25/CAPTURE_BUG_DIAGNOSIS.md`](../tooling/out/h25/CAPTURE_BUG_DIAGNOSIS.md) established that
the enhancement stage and the AI are innocent; the geometry is decided earlier:

| path | quad source | verdict |
| --- | --- | --- |
| Gallery **Import** | `detect()` on the whole still | correct before and after H26 |
| In-app **capture** | `refine(still, liveQuad)` — the live normalized quad was used as a *seed* | wrong: `refine()` cropped a 1.15× box around the seed, re-detected inside it, and then took `refinedMedium ?: detectClassical(medium) ?: seedMedium` **without ever comparing the winner with the seed** |
| **Edit → Reprocess** (any mode) | `refine(still, storedQuad)` — the latched quad was used as a seed again | wrong for the same reason, and it could shrink again on every pass |

When the true page border is at (or outside) the edge of that 1.15× crop, the strongest 4-gon *inside*
the crop is an inner text block. The still page gate cannot catch it: `requireLineProof = false` and
coverage gets full credit over 0.20–0.85. The warp at
[`ScanPipeline.kt:227`](../app/src/main/java/com/docscanai/capture/ScanPipeline.kt:227) runs **before**
enhancement and is mode-independent, which is why ORIGINAL mode looked just as broken.

### 2. RED evidence (acceptance tests written first, production untouched)

[`RefineSeedGuardTest`](../app/src/androidTest/java/com/docscanai/RefineSeedGuardTest.kt:1) —
verbatim from [`tooling/out/h26/RED/RefineSeedGuardTest.txt`](../tooling/out/h26/RED/RefineSeedGuardTest.txt)
(`Tests run: 4, Failures: 3`):

```
java.lang.AssertionError: refine() must keep the seed: IoU(refined, seed)=0.5293 < 0.85 (refined=(47,107),(693,137),(688,1181),(36,1183) seed=(0,0),(1000,0),(1000,1300),(0,1300))
java.lang.AssertionError: a repeated seeded pass must not leave the original seed's quad: IoU=0.5293 < 0.85
java.lang.AssertionError: Edit -> ORIGINAL must not change the page geometry: border-band RMS=71.1450 > 8 grey levels (maxAbs=255.0000)
```

The trap is a real benchmark page pasted full-bleed into a 1000×1300 camera frame with a strong dark
inner block at a 0.25 margin: inside `refine()`'s crop the only 4-gon is that inner block, so
`refine()` returned a quad covering **52.93 %** of the correct seed. The fourth test
(`importPath_detectionUnchanged`) already passed on the broken build, which is the expected asymmetry.

### 3. The fix (three changes, no threshold was tuned)

1. **Seed guard in `refine()`** — [`OpenCvQuadPageDetector.kt:217`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt:217).
   The crop's winner is accepted only when it is the same quad as the seed:
   `IoU(candidate, seed) >= 0.85` ([`:1737`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt:1737)),
   area ratio in `[0.90, 1.10]`, and a containment sanity test (a candidate strictly inside the seed
   with a corner inset larger than the 0.15 refine margin is rejected). Otherwise the **seed itself** is
   returned. One line per decision:
   `[Refine] seedIou=… area=… inset=… accept=… reason=…`
   ([`seedDecision()`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt:1004)).
2. **Mode-only reprocess must not re-seed** — [`ScanSessionViewModel.kt:243`](../app/src/main/java/com/docscanai/ui/session/ScanSessionViewModel.kt:243)
   passes `quad = null`, so Edit → reprocess re-detects exactly like an import, and the stored
   `corners` are re-synced to what was actually warped.
3. **Analysis-vs-still aspect mapping** — new [`SeedMapping.kt:34`](../app/src/main/java/com/docscanai/capture/SeedMapping.kt:34)
   (used from [`CameraScreen.kt:330`](../app/src/main/java/com/docscanai/ui/screens/camera/CameraScreen.kt:330)).
   `ImageAnalysis` is pinned to 1280×720 (16:9) while `ImageCapture` resolves the native 4:3 still, so
   the old per-axis scaling put the seed off the page. The seed is now mapped through the centred-crop
   transform and the ratios are logged: `[Seed] analysisAspect=1.778 stillAspect=1.333 …`.

Not touched, as required: the global page gate, warp constants, corner-model input size, model pins
(`m1c_detect` / `m2_dewarp_grid` byte sizes unchanged), and the downloader contract.

### 4. GREEN evidence — the trap, the repeat pass, the real chain, the import path, the mapping

[`tooling/out/h26/GREEN4/`](../tooling/out/h26/GREEN4/) — `RefineSeedGuardTest` `OK (5 tests)`,
`LiveCapturePathTest` `OK (9 tests)`:

| check | before (RED) | after (GREEN) |
| --- | --- | --- |
| (a) trap: `IoU(refined, seed)` / area | **0.5293 / 0.5293** | **1.0000 / 1.0000** (`accept=false reason=iou` → seed returned) |
| (b) 3 chained seeded passes: worst total IoU / area dev | **0.5293** | **1.0000 / 0.0000** |
| (c) Edit → ORIGINAL: corner IoU / area ratio | sub-rectangle | **0.9930 / 1.0028** |
| (c) max corner movement | — | **0.7556 %** of the short side (gate 1 %) |
| (c) saved-bitmap size ratio W/H | — | **1.0013 / 1.0029** (`784×1027` → `785×1030`) |
| (c) raw border-band RMS (diagnostic only) | 71.1450 (maxAbs 255) | 36.58–50.01 (maxAbs 206–241) |
| (d) import path: stored quad == `detect()` | — | **1.0000**, truth IoU **0.9726** |
| (e) 16:9 analysis → 4:3 still mapping | — | **PASS** (`(0,375)…(4000,2625)` vs expected top 375.0) |

The guard does **not** block legitimate refinement: the live-capture chain logs five accepts
(`seedIou=0.9169/0.9145/0.9591/0.9183/0.9177`, `accept=true reason=ok`), and the trap logs
`seedIou=0.5293 area=0.5293 inset=0.1372 accept=false reason=iou` on every pass.

`LiveCapturePathTest` now also proves the saved page covers the whole page on all four sides
(`h=coverage saved=(123,139),(881,163),(847,1159),(117,1138) truth=(100,130),(900,130),(900,1170),(100,1170) iou=0.892 area=0.892 insets=0.016,0.036,0.017,0.020 steps=163,186,193,222`)
and that a page whose border sits near the seed edges is not shrunk
(`h=near_edge … iou=0.937 area=0.937 steps=161,176,188,156`).

### 5. Import vs capture vs reprocess — behaviour matrix

| path | 0.3.6 | 0.3.7 |
| --- | --- | --- |
| Import | `detect()` on the full still — correct | unchanged (asserted: stored quad identical to `detect()`) |
| In-app capture, live quad good | seeded `refine()` — usually fine, **inner block on border-at-edge pages** | seeded `refine()` + guard: the seed is kept unless the winner is the same quad (IoU ≥ 0.85, area 0.90–1.10, containment) |
| In-app capture, live quad wrong | wrong seed accepted or refined away | seed rejected by the gate upstream (`PagePlausibility`), and a bad refinement now falls back to the seed instead of an inner block |
| Edit → Reprocess (ORIGINAL / ENHANCED / BW) | re-seeded from the stored (possibly already-shrunk) quad — shrinks again | `seed = null` → full-frame `detect()`, exactly like import; stored corners re-synced |

### 6. No-regression (same tree that was shipped)

| gate | result |
| --- | --- |
| `tooling\h7_tests.cmd` — the 10 required classes (`DetectionRegressionTest`, `DewarpFidelityTest`, `NeuralPathReproTest`, `NeuralPathXnnpackTest`, `PageViewerUiTest`, `DetectorLatchRuntimeTest`, `AutoCaptureGatingTest`, `LiveCapturePathTest`, `LiveRoomNegativeTest`, `AutoCaptureDuplicateTest`) **plus `RefineSeedGuardTest`**, which is now part of the lane | **FINAL: PASS** (11/11) — `tooling/out/h26/h7_final/` (`progress.txt`); the earlier 10-class runs are `tooling/out/h26/h7/` and the pre-bump `h7_final` |
| `tooling\corpus_run.cmd` — AI-ON | **PASS** — benchmark **19/20**, `distortedSuspect=0`, negatives **0/5**, synthetic 44/51, 2 sessions (`corpus_Paz18102026_22250313`) |
| `tooling\corpus_run.cmd` — AI-OFF | **PASS** — benchmark **14/20**, `distortedSuspect=0`, negatives **0/5**, 0 sessions |
| mean corner error (40 GT pages, AI-ON) | **0.03440 %** of the diagonal (unchanged) |
| quad placement INSIDE / OUTSIDE / MIXED | **24 / 0 / 16** (unchanged) |
| border RMS mean (19 detected) | **10.0140 px** (worst `bench_docunet_62` 24.04 px) — baseline 10.01 px |
| text-line RMS / angle-std means | **0.5865 px / 0.1169°** (baseline 0.5865 / 0.1169) |
| `tooling\check_16k.py` | **16/16 `16KB-OK`, 0 `NOT-OK`** on both the debug and the release APK |

### 7. Ship

| item | value |
| --- | --- |
| version | `versionCode 10` / `versionName 0.3.7` ([`app/build.gradle.kts:54`](../app/build.gradle.kts:54)) |
| release APK | 125,528,642 bytes, SHA-256 `59114bdb78224a5b1d023fc09234d38a361b851464decd77d6278af884a1465e` |
| `aapt2 dump badging` | `package: name='com.docscanai' versionCode='10' versionName='0.3.7'`, `targetSdkVersion:'35'` |
| `apksigner verify --print-certs` | certificate SHA-256 **`7a697707…253dc`** — identical to 0.3.0–0.3.6 (no signing change) |
| launch smoke | release APK installed on `docscan_dbg_api35`, `topResumedActivity=com.docscanai/.MainActivity`, `versionName=0.3.7`, empty `AndroidRuntime:E` log — `tooling/out/h26/launch_smoke.png` |

### 8. Observation recorded for the next subtask

The second-stage UVDoc grid dewarp is model-driven and **not bit-stable across invocations**: the same
quad (corner IoU 0.9930) produced reprocessed pages of 258,204 B and 288,331 B in different runs, which
moves a raw pixel comparison (band RMS 36.6 … 50.0, row-profile correlation 0.66 … 0.92). That is why
the H26 pixel-level assertion is expressed as *geometry* (corner movement, warp output size) plus a
border-ink check on the synthetic capture chain, and the raw band RMS is kept as a diagnostic number
only. If a future subtask needs pixel-identical reprocessing, that nondeterminism is the thing to fix
first.

---

## Previous — H25 (0.3.6 unchanged): handwriting attempt 2 failed the gate, warp timing attributed, capture field bug diagnosed

**Decision: DO NOT SHIP the handwriting feature.** The single reuse-first candidate
(`Variant.RIDGE`, Frangi/Hessian ridge) failed 4 of 6 gates on the same 12 real pencil pages used in
H23, so the production default was reverted to `Variant.FULL` and **the shipped binary is unchanged**:
`versionCode 9` / `versionName 0.3.6`, 125,528,546 bytes, SHA-256
`6cab7e8411b8dbf59016913a6d42d8d51521bfc389e73111fbc8830d0a2ccb01`. No 0.3.7 was produced. Warp
behaviour, the page gate, corner detection and the model pins were not touched.

**Recorded priority order (unchanged, verbatim):**
> Priority order: 1) warping/geometry, 2) auto-capture, 3) additional features such as pencil/handwriting enhancement.

**Provenance rule (unchanged):** every evaluation below uses real collected pages, never generated
data. The 12 pencil pages are the same physical pages photographed for H23 with the same masks.

### 1. Reuse-first hunt (§10.1 of [`HANDWRITING_ENHANCEMENT.md`](HANDWRITING_ENHANCEMENT.md))

| Work | Licence | Cost | Verdict |
|---|---|---|---|
| DocShadow-ONNX-TensorRT | MIT | `docshadow_sd7k/jung/kligler.onnx` = **120,041,674 B** each, 256² tiles | wrong problem (shadow removal, not stroke contrast) |
| DocShadow-SD7K | MIT | archived | same family |
| DocEnTr | **CC BY-NC 4.0** | — | **excluded** (non-commercial) |
| DE-GAN | GPL-3.0 + "academic research use only" | — | **excluded** |
| CV-Reimplementation | MIT | — | already implemented (flat-field) |
| onnx/models | Apache-2.0 | — | no document/handwriting model |

ORT 1.22.0 CPU EP would need ~80 tiles/page at 15–40 ms/tile = **1.2–3.2 s/page** plus ~120 MB of
assets, against a 120 ms median budget — a new dependency and a new model pin, both out of scope.
Shape discriminants: Frangi **implemented**; stroke-width consistency rejected (chicken-and-egg);
path openings rejected (cost); phase congruency rejected (no better than the existing DoG).

### 2. Measurement on the 12 real pencil pages (STEP 3)

| gate | target | measured | result |
|---|---|---|---|
| contrast | ≥ +30 % | **+5.27 %** mean (min −7.25, max +36.17; **5 of 12 negative**) | **FAIL** |
| blank noise | ≤ +10 % | −37.53 % mean (one page +26.55 %) | PASS |
| non-stroke PSNR | ≥ 30 dB | **27.75 dB** mean (max 29.34 — the maximum never reaches 30) | **FAIL** |
| sharpness | not down | 1.28× | PASS |
| components | not up | **+128.39 %** mean (worst +498.19, 221 → 1322) | **FAIL** |
| latency @2000 px | ≤ 120 ms | **142.5 ms** median / 154 p95 / 161 max, n = 36 | **FAIL** |

Faint-mark detector behaviour is unchanged at **11/12** pages suggesting the boost. Normal-paper
pages degrade: contrast **−31.09 %** mean, PSNR **25.41 dB** mean with a **20.05 dB** worst case
(`a_woman_fills_out_a_bear_information_management.jpg`), components +0.10 % mean but +68.27 % on
`receiptswiss`. The full 12-row table and the four reasons for failure are in
[`HANDWRITING_ENHANCEMENT.md`](HANDWRITING_ENHANCEMENT.md) §10.4–10.5.

### 3. Warp timing attribution — 239/453/669 ms explained (STEP 4a, no warp code changed)

| population | median | p95 | max |
|---|---|---|---|
| all 76 corpus pages | 225.5 ms | 440.5 ms | 664 ms |
| excluding the first page | 225.0 ms | 387.1 ms | 664 ms |
| first page (`syn_rot_small_0`) | 498 ms (warp), 394 ms (detect) | — | 2.21× median |

**The worst case is a per-page outlier, not cold start.** The maximum is page #74
`bench_docunet_62` at **664 ms**, where detection is only 98 ms — the time is in the neural grid
warp, not in warm-up. Five pages exceed 400 ms (`bench_docunet_62` 664, `real_receiptswiss` 547,
`syn_rot_small_0` 498, `real_media_viewer_launch_retrospective_whiteboard_apr` 460,
`real_open_internal_pages_...` 434). Output size does not explain it: smallest output quartile
median 207 ms vs largest 213 ms. Cold start explains only the elevated **first** page, whose 394 ms
detect time is model warm-up.

### 4. Launch smoke on the shipped build (STEP 4b)

`versionCode 9` / `versionName 0.3.6`, `LaunchState: COLD`, `TotalTime: 866` ms, `topResumedActivity`
and `ResumedActivity` both `com.docscanai/.MainActivity`, no `FATAL EXCEPTION` and no
`Fatal signal` in logcat. Screenshot [`tooling/out/h25/launch_smoke.png`](../tooling/out/h25/launch_smoke.png)
(first-run "Set up AI models" onboarding; all models "Not downloaded" after the reinstall).

### 5. No regression

| lane | result |
|---|---|
| `tooling\h7_tests.cmd` | **PASS** `FINAL: PASS exit=0`, all 10 classes |
| `HandwritingDarkPathTest` (not part of h7's 10) | **OK (1 test)** |
| corpus lane (`DOCSCAN_RUN_DIR=corpus2`) | AI-ON **19/20** benchmark, AI-OFF **14/20**, negatives **0/5** both modes, `distorted_suspect` **0**, sessions AI-ON 2 / AI-OFF 0 |
| `tooling\check_16k.py` both APKs | **16/16** `PT_LOAD` segments at `0x4000` |
| release identity | SHA-256 identical to 0.3.6 |

### 6. Field bug reported during H25 — **not fixed, diagnosed**

The owner reported that in-app capture sometimes stores a **sub-region** of the page although the
overlay showed the whole page; the same page **imported** is clean; the corruption also appears in
`ORIGINAL` mode, and re-processing an imported photo then choosing `ORIGINAL` reproduces it.

The code confirms the owner's reading that this is **not** the enhancement stage and **not** the AI.
Capture and `reEnhance()` both enter
[`OpenCvQuadPageDetector.refineQuad()`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt:167)
with a seed, while import enters `detect()` with `seed = null`
([`CameraScreen.kt:225`](../app/src/main/java/com/docscanai/ui/screens/camera/CameraScreen.kt:225)).
`refineQuad()` accepts whatever 4-gon wins inside a 1.15× crop around the seed and **never compares
it with the seed** — no IoU floor, no area bound, no containment test — so an inner rectangle (text
block, table frame, ruled margin) can replace a correct seed. The still page gate cannot catch it
because coverage earns full credit from 0.20 to 0.85. `ORIGINAL` does not bypass dewarp: warp runs
before enhancement in every mode. Full analysis, proposed minimal fix and the regression test that
would have caught it: [`tooling/out/h25/CAPTURE_BUG_DIAGNOSIS.md`](../tooling/out/h25/CAPTURE_BUG_DIAGNOSIS.md).

---

## H24 (0.3.6): the detection regression is explained, fixed minimally, geometry restored, release shipped

**Decision: LANE-SCOPED FIX — the H21 text-line proof stays on the LIVE auto-capture gate and no longer
vetoes the post-shutter still warp. No threshold was lowered, no room rejection weakened, warp constants,
corner-model input size, model pins and the downloader contract are untouched. Pencil/handwriting stays
dark and OFF. Shipped as `versionCode 9` / `versionName 0.3.6`.**

**Recorded priority order (unchanged, verbatim):**
> Priority order: 1) warping/geometry, 2) auto-capture, 3) additional features such as pencil/handwriting enhancement.

### 1. The contradiction — both numbers were real, they measured two different gates

The hypothesis that `benchDetected` changed definition is **false**. The definition is identical in every
lane: [`CorpusRealLifeTest.kt`](../app/src/androidTest/java/com/docscanai/CorpusRealLifeTest.kt) marks a page
detected only when the pipeline log contains `[Detect]` **and** `found=true`:

```kotlin
val found = docLines.any { it.contains("[Detect]") && it.contains("found=true") }
```

The artefact was upstream of the metric: **which gate was consulted, and with what provenance.**

| Source of the number | Gate call | Provenance passed? | Result |
|---|---|---|---|
| H21 `AutoCaptureGatingTest` lane | [`PagePlausibility.evaluate(bitmap, it, id)`](../app/src/androidTest/java/com/docscanai/AutoCaptureGatingTest.kt:280) | **No** → `UNKNOWN` | 19/20 |
| H23/H24 `corpus_run.cmd` lane | [`PagePlausibility.evaluate(source, it, "still", provenance, …)`](../app/src/main/java/com/docscanai/capture/ScanPipeline.kt:147) | **Yes** → `CLASSICAL_FALLBACK`/`CORNER_MODEL` | 17/20 before the fix |

With no provenance the quad is `UNKNOWN`, which the H21 rule (`needsLineProof` only for guessed quads)
never asks for a text-line proof — so the test lane could not see the regression that the production path
suffered. **H21's 19/20 was a test-double number, not a production measurement.** The production number on
the 0.3.5 tree was 17/20, and it is 19/20 again on 0.3.6.

### 2. Flip table — the flips are exactly the classical-fallback benchmark pages

The full 20-row table (five runs × AI-ON/AI-OFF) is [`tooling/out/h24/flip_table.md`](../tooling/out/h24/flip_table.md)
(CSV: `flip_table.csv`, machine list: `flips.txt`), produced by `tooling/h24_reconcile.py` from the four
per-image reports plus the fixed run.

| run | AI-ON detected | AI-OFF detected |
|---|---|---|
| `h14_final` (0.3.2 baseline) | 19/20 | 14/20 |
| `h18_refine` (0.3.4) | 19/20 | 14/20 |
| `corpus_Paz18102026_10581939` (0.3.5, pre-H23) | 17/20 | 0/20 |
| `corpus_Paz18102026_15560151` (0.3.5, post-H23) | 17/20 | 0/20 |
| `h24/corpus_fixed` (0.3.6) | **19/20** | **14/20** |

Exact flips (from `flips.txt`):

- AI-ON `h18 → paz_1058`: **LOST `bench_docunet_08`, `bench_docunet_62`**, gained none.
- AI-OFF `h18 → paz_1058`: **LOST all 14** previously-detected benchmark pages.
- AI-ON `paz_1556 → h24_fixed`: **GAINED `bench_docunet_08`, `bench_docunet_62`**, lost none.
- AI-OFF `paz_1556 → h24_fixed`: **GAINED all 14**, lost none.

No image flipped in any other direction; the 0.3.5 tree was bit-identical before and after H23, so the
regression landed with H21, not H23.

### 3. Root cause — the H21 line proof was applied to the still warp as well as the live gate

H21 added the text-line coherence proof to the **`passed`** expression inside
[`PagePlausibility.evaluateInternal`](../app/src/main/java/com/docscanai/capture/PagePlausibility.kt:341).
`passed` is consumed by **two** callers: the live auto-capture gate *and* the post-shutter still gate in
[`ScanPipeline`](../app/src/main/java/com/docscanai/capture/ScanPipeline.kt:145). A classical-fallback quad
on a perfectly good page usually has no detectable *text grid* at gate time (`coh=0.000`, `lines<5`), so
the still warp was vetoed and the page fell back to full frame. The detector itself had not changed — same
corner peak, same classical cue, same quad:

```
[OpenCvPageDetector] corner detect: channel 0 peak=0.001 < 0.3 -> classical
[OpenCvPageDetector] classical cues=3 best=otsu score=0.689 top=[otsu:0.689(area=0.359,page=0.677) | ...]
```

`bench_docunet_08` (AI-ON), 0.3.5 tree — rejected on lines, all other signals unchanged:

```
[Gate] id=still score=0.646 cov=1.000 asp=0.464 paper=0.788 text=0.413 edge=1.000 glyphs=22 angle=1.000
       minAng=83 maxAng=97 convex=true selfInt=false coh=0.000 lines=2 span=0.44 hfrac=0.013
       prov=CLASSICAL_FALLBACK auto=false pass=false
[Detect] exit found=false (fallbackToFullFrame=true) gateRejected=true gateScore=0.6456 areaFrac=-1
```

`bench_docunet_62` (AI-ON): `score=0.726 … coh=0.000 lines=0 span=0.00 … pass=false`.
AI-OFF `syn_rot_small_3`: `score=0.979 cov=1.000 paper=0.994 text=1.000 glyphs=637 … coh=0.000 lines=4
span=0.63 … pass=false` — a 0.979-score page rejected purely by the line proof.

Same image, same tree, after the fix (`h24/corpus_fixed`) — **identical gate line except `pass=true`**:

```
[Gate] id=still score=0.646 cov=1.000 asp=0.464 paper=0.788 text=0.413 edge=1.000 glyphs=22 angle=1.000
       minAng=83 maxAng=97 convex=true selfInt=false coh=0.000 lines=2 span=0.44 hfrac=0.013
       prov=CLASSICAL_FALLBACK auto=false pass=true
[Detect] exit found=true (fallbackToFullFrame=false) gateRejected=false gateScore=0.6456 areaFrac=0.3591
```

**Classification: gate-scope bug, not a detector regression.** The detector returned the same quad before
and after; only the acceptance rule that the still path consulted changed.

### 4. The fix (minimal, line-scoped)

1. [`PagePlausibility.evaluate`](../app/src/main/java/com/docscanai/capture/PagePlausibility.kt:222) gained
   `requireLineProof: Boolean = true`; `needsLineProof = requireLineProof && isGuessedQuad` (line 345).
2. The **live** gate keeps the default (`true`) — room rejection is unchanged.
3. The **still** gate passes `requireLineProof = false`
   ([`ScanPipeline.kt:147`](../app/src/main/java/com/docscanai/capture/ScanPipeline.kt:147)): the text-line
   proof is an auto-capture *confidence* requirement, not a condition for warping a page the user just shot.
4. Auto-capture eligibility still demands coherence for guessed quads **unconditionally**
   ([`PagePlausibility.kt:353`](../app/src/main/java/com/docscanai/capture/PagePlausibility.kt:353)):
   `autoCoherenceOk = !isGuessedQuad || content.coherence >= AUTO_CAPTURE_COHERENCE_MIN`.

`THRESHOLD`, `TEXT_FLOOR`, `FALLBACK_COHERENCE_MIN`, `AUTO_CAPTURE_COHERENCE_MIN`, warp constants, model
pins and the downloader contract are **unchanged**.

### 5. Two-sided proof — pages come back, the room stays rejected

| Lane | Result |
|---|---|
| `tooling\corpus_run.cmd` (fixed tree) | **FINAL: PASS** — AI-ON `benchDetected=19/20`, `detected_found=63`, `warp_neural=61`, `warp_classical=0`, `rectifiedSkip=2`, `notDetectedFallback=13`; AI-OFF `benchDetected=14/20`, `warp_classical=22`. Negatives **0/5 in both lanes**, `distortedSuspect=0` |
| `tooling\h7_tests.cmd` | **FINAL: PASS** — all 10 classes `OK`: `DetectionRegressionTest` 5, `DewarpFidelityTest` 5, `NeuralPathReproTest` 1, `NeuralPathXnnpackTest` 1, `PageViewerUiTest` 5, `DetectorLatchRuntimeTest` 1, `AutoCaptureGatingTest` 6, `LiveCapturePathTest` 7, `LiveRoomNegativeTest` 2, `AutoCaptureDuplicateTest` 6 |
| live room (same build) | `LIVE_ROOM RESULT frames=16 detected=8 gatePass=0 fires=0`; `LIVE_ROOM HOLD fed=48 fires=0` — every room frame `pass=false` despite `score=0.752…0.762`, because the **live** gate still requires text lines |
| synthetic/GT groups | `synthetic 44/51` (AI-ON) / `42/51` (AI-OFF); `within_tol_5pct_diag=31`; `quad_irregular=0` |

### 6. Geometry re-measured on the fixed tree (benchmark group, no corner GT)

Corner/placement ground truth exists only for the **synthetic** group (40 images with corner GT), which is
why older notes show `corner_err=-1` for benchmark rows. Benchmark rows below are border/text/fidelity
metrics; the corner figure is the whole-corpus GT figure.

| Metric | Value (AI-ON, fixed tree) |
|---|---|
| mean corner error (% of diagonal, 40 GT pages) | **0.03440** |
| within 5 % of diagonal | 31 / 40 |
| quad placement INSIDE / OUTSIDE / MIXED | **24 / 0 / 16** |
| near-full-frame quads | 13 (was 20 on AI-OFF; AI-ON restores the neural warps) |
| `distorted_suspect` | **0** |
| border RMS (mean / worst, 19 detected) | **10.01 px / 24.04 px** (`bench_docunet_62`), then `bench_docunet_08` 17.29 px |
| text-line RMS (mean) | **0.503 px** |
| text-line angle std (mean / max) | **0.0581° / 0.4847°** |
| sharpness at crops (mean) | **12,072.6** |
| benchmark PSNR / SSIM (mean) | **11.245 dB / 0.1179** |

Full per-image rows: [`tooling/out/h24/geometry_fixed.csv`](../tooling/out/h24/geometry_fixed.csv); aggregates
in `geometry_summary.txt`. The two restored pages are the *worst* two on border RMS (24.04 px and 17.29 px)
— they are hard classical-fallback pages, and they are now warped instead of silently full-framed.

### 7. Timing and memory (emulator `docscan_dbg_api35`, 76 images × 2 lanes)

| Metric | AI-ON | AI-OFF |
|---|---|---|
| warp median / p95 / max | **239 / 453 / 669 ms** | 13 / 37 / 60 ms |
| warp, detected only (median / max) | 253 / 669 ms | 18 / 60 ms |
| detect median / max | 61.5 / 308 ms | 40.5 / 77 ms |
| enhance median / max | 61.5 / 204 ms | 78 / 223 ms |
| total median / max | 1228.5 / 2651 ms | 958.5 / 2569 ms |

Heap after launch of the 0.3.6 debug build (`dumpsys meminfo com.docscanai`, natives mapped, models not yet
loaded — a floor, not a processing peak): **Java 19,952 KB PSS**, **Native 127,512 KB PSS**,
**TOTAL PSS 264,953 KB**, **TOTAL RSS 370,944 KB**, swap PSS 249 KB.

### 8. Release proof (0.3.6 / `versionCode 9`)

| Check | Result |
|---|---|
| `aapt2 dump badging` | `package: name='com.docscanai' versionCode='9' versionName='0.3.6'`, `targetSdkVersion:'35'`, `native-code: 'arm64-v8a' 'x86_64'` |
| `apksigner verify --print-certs` | `CN=DocScanAI …` — `7a6977076f2c16e1c521b9301339ec9196aaac6f6a7641d7ec3fc965854253dc` **unchanged** |
| release APK | **125,528,546 bytes** — SHA-256 `6cab7e8411b8dbf59016913a6d42d8d51521bfc389e73111fbc8830d0a2ccb01` |
| debug APK | **144,371,865 bytes** |
| `tooling\check_16k.py` | **16/16 `16KB-OK`** (`p_align=0x4000`) in **both** `app-debug.apk` and `app-release.apk` |

The release is +16,388 bytes over 0.3.5 (125,512,158 → 125,528,546), consistent with the added guard
function and R8 re-layout; no dependency was added and `libonnxruntime.so` / `libopencv_java4.so` are
byte-identical in alignment.

### 9. Pencil/handwriting stays dark — proven, not asserted

`SettingsRepository.handwritingEnhanceEnabled` is still `false`, the edit-screen control is still not
rendered, and stage 3b of [`ScanPipeline`](../app/src/main/java/com/docscanai/capture/ScanPipeline.kt:249)
now routes **both** the measurement call and the enhancement call through one guard,
[`shouldApplyHandwriting`](../app/src/main/java/com/docscanai/capture/ScanPipeline.kt:434). The new
`HandwritingDarkPathTest` proves the path cannot execute with the flag off (and proves the assertion is
non-vacuous by flipping the flag on):

```
OK (1 test)   com.docscanai.HandwritingDarkPathTest#handwritingStage_isDarkByDefault
H24_DARK_PATH defaultHandwriting=false guard=false persisted=false suggested=false PASS
```

### 10. Evidence and files changed

**Files changed by H24:** [`capture/PagePlausibility.kt`](../app/src/main/java/com/docscanai/capture/PagePlausibility.kt)
(`requireLineProof`), [`capture/ScanPipeline.kt`](../app/src/main/java/com/docscanai/capture/ScanPipeline.kt)
(still gate opt-out, single handwriting guard), `app/src/androidTest/java/com/docscanai/HandwritingDarkPathTest.kt`
(new), [`app/build.gradle.kts`](../app/build.gradle.kts:51) (`versionCode 9` / `0.3.6`), plus the host tools
`tooling/h24_reconcile.py` and the lane scripts under `tooling/out/h24/`.
**Not changed:** warp constants, gate thresholds, corner-model input size, model pins, downloader contract,
Gradle dependencies.

**Real-data provenance rule (still binding, from H23):**
> Evaluation data must be **real, already-collected data fetched from the internet**, with a recorded
> source URL, licence name, licence URL, attribution and SHA-256 for every file. Generated data may
> only be used as an explicitly labelled augmentation tier *derived from* those real files, and any
> number produced from it must be reported as "augmented from real page X" — never as a measurement
> on real data.

---

## H23 (pencil/handwriting enhancement): measured on REAL pencil pages, **NOT shipped** (historical; superseded by H24 above)

**Decision: REVERT. The baseline did not move at H23 — `versionCode 8` / `versionName 0.3.5`.
No 0.3.6 / `versionCode 9` was produced *by H23*, no release APK was signed *by H23*, and the feature flag
defaults to OFF with the edit-screen affordance dark. (H24 later shipped 0.3.6 with the pencil still dark.)**

**Recorded priority order (unchanged, verbatim):**
> Priority order: 1) warping/geometry, 2) auto-capture, 3) additional features such as pencil/handwriting enhancement.

**Real-data provenance rule (new, binding on every future measurement):**
> Evaluation data must be **real, already-collected data fetched from the internet**, with a recorded
> source URL, licence name, licence URL, attribution and SHA-256 for every file. Generated data may
> only be used as an explicitly labelled augmentation tier *derived from* those real files, and any
> number produced from it must be reported as "augmented from real page X" — never as a measurement
> on real data.

**Data (H23):** 12 pages of Kate Chopin's original pencil manuscripts (Wikimedia Commons / DPLA),
all **Public domain**, 14.95 MB downloaded / **2,158,060 bytes** bundled at ≤1400 px in
`app/src/androidTest/assets/pencil/` with `pencil_manifest.json` (page URL, direct URL, licence URL,
attribution, original + asset SHA-256). 10 Commons queries and the Hugging Face dataset searches are
logged in `tooling/out/h23/queries.json`. H22's generated fixtures were removed from the APK and
preserved out-of-tree at `E:\DocScanAI-cache\pencil\h22_synthetic\`.

**Result against the H23 gates** (`[OURS]`, 12 real pages, emulator):

| Gate | Target | FULL candidate | CLAHE-only alternative |
|---|---|---:|---:|
| Stroke-to-background contrast | ≥ +30 % | **+2.20 %** FAIL | +198.94 % PASS |
| Blank-region noise | ≤ +10 % | −37.92 % PASS | +0.71 % PASS (worst page **+134 %**) |
| Non-stroke PSNR | ≥ 30 dB | **27.75 dB** FAIL | **24.80 dB** FAIL |
| Stroke sharpness | not lower | 1.02× PASS | 2.87× PASS |
| Stroke continuity (components) | not worse | **2.29×** FAIL | **3.47×** FAIL |
| Latency at 2000 px (median / max) | ≤ 120 ms | **205 / 217 ms** FAIL | 111 / 140 ms FAIL |

**Why (measured, not argued):** on these pages the mean pencil mark is **20.2 levels** below paper
while the blank paper *texture* is **13.5 levels** — texture is 67 % of the mark amplitude. Three
designs were measured (tone LUT + unsharp; single-Gaussian detail boost; difference-of-Gaussians
band + paper-median anchor) and none reached the contrast target without either failing non-stroke
PSNR (paper altered) or failing continuity (texture promoted to "strokes"). The full analysis,
per-page table, per-revision table and contact sheets are in
[`HANDWRITING_ENHANCEMENT.md`](HANDWRITING_ENHANCEMENT.md) §6.

**What did work and is kept (dark):** the faint-mark detector fired on **11 of 12** real pencil pages
(depth 16–26 levels), `HandwritingEnhancer` is wired into `ScanPipeline` stage 3b behind
`SettingsRepository.handwritingEnhanceEnabled` (default false), per-page state is persisted, RAW is
never enhanced, and the measurement lane `com.docscanai.HandwritingEnhanceTest` is reproducible.

**No-regression on the shipped paths (`[OURS]`, same tree, emulator):**

| Lane | Result |
|---|---|
| `tooling\h7_tests.cmd` | **FINAL: PASS** — all 10 classes OK (`DetectionRegressionTest`, `DewarpFidelityTest`, `NeuralPathReproTest`, `NeuralPathXnnpackTest`, `PageViewerUiTest`, `DetectorLatchRuntimeTest`, `AutoCaptureGatingTest`, `LiveCapturePathTest`, `LiveRoomNegativeTest`, `AutoCaptureDuplicateTest`) |
| `tooling\corpus_run.cmd` | **FINAL: PASS** — `corpus_Paz18102026_15560151`; AI-ON `benchDetected=17/20`, `benchPsnr=10.81075133222338`, `benchSsim=0.10842162920001208`, `notDetectedFallback=28`, `distortedSuspect=0`; AI-OFF `benchDetected=0/20`. These are **bit-identical** to the pre-H23 baseline run on the same day (`corpus_Paz18102026_10581939`), so H23 changed nothing on the production path. The `19/20` benchmark figure quoted in older notes belongs to the pre-0.3.5 trees (`tooling/out/h14_final`, `h18_refine`); the current 0.3.5 tree scores 17/20 both before and after this task. |
| `tooling\check_16k.py` | **16/16 ELF objects `16KB-OK`** in `app-debug.apk` (`p_align=0x4000`); `app-debug-androidTest.apk` contains no ELF objects (0 to check) |
| negatives / distorted | `distortedSuspect=0` in both modes; negatives stay rejected by the existing content gate (unchanged code) |

**Files changed by H23:** `app/src/main/java/com/docscanai/imaging/HandwritingEnhancer.kt` (new),
`capture/ScanPipeline.kt` (stage 3b + constructor param), `data/SessionModels.kt`,
`data/SessionStore.kt`, `data/SettingsRepository.kt`, `ui/session/ScanSessionViewModel.kt`,
`ui/screens/edit/PageEditScreen.kt` (dark affordance), plus
`app/src/androidTest/java/com/docscanai/HandwritingEnhanceTest.kt` and the 12-page asset set.
**Not changed:** warp constants, gate thresholds, corner-model input size, model pins, downloader
contract, Gradle dependencies (`app/build.gradle.kts` untouched; still `versionCode 8` / `0.3.5`).

**Evidence:** `tooling/out/h23/` — `device4/h23_results.json` + `device4/pairs/` (raw lane output),
`device4/h23_summary.json` (means + verdict), `sheets/` (12 per-page input|FULL|CLAHE rows, 2× zoom
crops, and `overview_all_pages.jpg`), `queries.json`, `real_pool_contact_sheet.jpg`,
`check_16k.txt`; h7 in `tooling/out/h7_tests/`; corpus in
`tooling/out/corpus_Paz18102026_15560151/`.

---

## 0.3.5 ship — H21: the live-room auto-capture false positive is fixed and proven on the real camera chain

**Decision: ROOT-CAUSE FIX — the score threshold was NOT raised.** Priority-2 (auto-capture) failed
on a real camera feed with no document in view. Reproduced on `docscan_dbg_api35` with
`-camera-back virtualscene`: **18 auto-captures in ~100 s** and 35 room pages in H20 (0.3.4); the H21
reproduction run measured **16** captures in ~100 s before the fix and **0** in 120 s after it.

**Root cause (measured, not guessed).** The corner model does not fire on the virtual room —
`[OpenCvPageDetector] corner detect: channel 0 peak=0.001 < 0.3 -> classical` — so the *classical
Hough/contour fallback* returns a quad covering ~47 % of the frame. Its rectified interior contains
periodic horizontal structure (wood panelling, shelf boards, a checkerboard poster) which the
periodicity-only `text` signal scores 0.68–0.73 with 50–68 fragmented "glyphs", while `paper` — the
one signal that says "this is a sheet of paper" — is only 0.25–0.35 and carries just 0.14 weight.
Weighted score ≈ 0.75 > 0.55, so the room passed. `BitmapUtils.fullFrameQuad` was **not** involved
(`fallbackToFullFrame=false`).

**Fix (three independent parts).**
1. **Quad provenance** ([`PageDetector.kt`](../app/src/main/java/com/docscanai/capture/PageDetector.kt:1),
   [`OpenCvQuadPageDetector.kt`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt:1)):
   every emitted quad is tagged `CORNER_MODEL` / `CLASSICAL_FALLBACK` / `FULL_FRAME`. A fallback quad
   is a *guess*, so it must independently prove text-line structure before it may pass at all; a
   full-frame substitute is never auto-capturable.
2. **Text-line coherence** ([`PagePlausibility.kt`](../app/src/main/java/com/docscanai/capture/PagePlausibility.kt:562)):
   glyph boxes are grouped into lines and scored on height uniformity, baseline tightness, span and
   line-spacing regularity; a text *block* needs ≥ 5 accepted lines with median span ≥ 0.45 of the
   page width. `THRESHOLD` stays 0.55 — the gate gains a structural requirement, it is not tightened.
3. **Capture-safety rules** ([`CaptureStateMachine.kt`](../app/src/main/java/com/docscanai/capture/CaptureStateMachine.kt:59)):
   a content-motion guard (a page turning inside a still quad restarts the hold), a 350 ms minimum
   hold time measured on the frame clock, a 2.5 s post-capture debounce, and duplicate suppression
   (quad IoU ≥ 0.95 **and** 16×16 luminance MAD ≤ 0.02). The manual shutter is never suppressed, and
   [`FrameAnalyzer.kt`](../app/src/main/java/com/docscanai/capture/FrameAnalyzer.kt:1) now skips page
   detection entirely while the phone is moving.

**Before / after on the same lane.**

| Metric | 0.3.4 (before) | 0.3.5 (after) |
|---|---|---|
| `[Gate] … pass=true` during a room hold | every frame | **0 of 1254** |
| `[AutoCapture] … -> capture` in ~100–120 s | 16 (H20: 18) | **0** |
| Pages saved during the room hold | 16 (H20: 35) | **0** |
| Manual shutter in the same session | works | works — 1 page saved (377 682 B) |
| `LiveRoomNegativeTest` | **RED** (2 failures) | **OK (2 tests)** |
| Benchmark positives | 19/20 | **19/20**, 19 auto-captured |
| `AutoCaptureDuplicateTest` | n/a | **OK (6 tests)** |

**Acceptance gate.** [`LiveRoomNegativeTest`](../app/src/androidTest/java/com/docscanai/LiveRoomNegativeTest.kt:1)
replays the 16 real frames harvested from the H20 camera run
(`app/src/androidTest/assets/live_room/room_00..15.jpg`, 960×1280) through the real detector and the
real gate. It was recorded **red** on 0.3.4 (`room_00` passed the gate, 1 auto-capture) and is green
now: every frame `pass=false`, 0 captures. The decisive measurement was `room_09`: `coh=0.575
lines=4 span=0.73` — four accidentally aligned panel rows are not a text block, which is exactly the
distinction the new structural proof makes.

**Field feedback from testing 0.3.4 on a physical phone** (folded into this release): auto-capture
fired too fast to see the lock; it fired while pages were still moving or being closed even though the
quad was steady; it sometimes shot the same thing twice; and page detection should not start until the
phone is steady. All four are addressed above (minimum hold time, content-motion guard, post-capture
debounce + duplicate suppression, motion pre-gate). The bar is deliberately *good enough*, not
perfect: a human cannot hold a phone perfectly still for a second, so the hold requirement is 350 ms.

## Latest — H19 (0.3.4 ship): the gate re-verified, refinement REVERTED, release shipped

**Decision: REVERTED (unchanged).** H19 added **no new measurement variant** — it reconciled H18's
state, re-verified every gate number independently, and re-ran the fidelity and geometry lanes on
the shipped tree. Sub-pixel corner refinement stays off; 0.3.4 is the 0.3.3 corner model plus the
version bump.

### What was tried

Exactly the H18 experiment, re-checked rather than re-invented: refinement ON at the 1.5 % clamp,
one bounded mitigation at 0.75 %, then a revert plus a baseline reconfirmation. H19 read those three
corpus runs, confirmed the arithmetic below, and then re-ran `h7_tests.cmd`, the geometry bench and
the release packaging checks from the current tree.

### The gate, re-verified

| Gate metric | 0.3.3 baseline | ON @ 1.5 % | ON @ 0.75 % | 0.3.4 shipped (OFF) | Verdict |
|---|---:|---:|---:|---:|---|
| Mean corner error (% diag) | 3.440 % | 3.585 % | 3.522 % | **3.440 %** | ≤ 3.44 % → ON **FAIL** |
| Benchmark detection | 19/20 | 19/20 | 19/20 | **19/20** | hold |
| Placement INSIDE / OUTSIDE / MIXED | 24 / 0 / 16 | 26 / 0 / 14 | 26 / 0 / 14 | **24 / 0 / 16** | hold |
| `distorted_suspect` | 0 | 0 | 0 | **0** | hold |
| Negative false positives | 0/5 | 0/5 | 0/5 | **0/5** | hold |
| Sharpness ratio (AI-ON / AI-OFF) | 1.1128× | 1.1265× | 1.1237× | **1.1128×** | ≥ 1.00 |
| Native heap high-water | 306.7 MB | 306.7 MB | 307.8 MB | **306.7 MB** | ≤ 320 MB |
| Warp latency, mean | 218.8 ms | 218.9 ms | 213.4 ms | **218.8 ms** | ≤ 300 ms |
| 16 KB page-size libs (debug / release) | 16/16 | — | — | **16/16, 16/16** | hold |

The geometry half of the rule *was* satisfied by refinement (border RMS 10.0226 → 9.1447 px,
−8.76 % relative, from the same-build A/B in §H18), so the revert is caused **only** by the corner
error, not by a lack of geometric benefit.

### Reasoning

1. The stated priority order makes the corner model a gate, not a tuning target. A 0.44 pp corner
   regression (3.44 % → 3.59 %) on a feature whose only purpose is to move corners is a bad trade
   even when borders get straighter.
2. The one permitted mitigation was spent and did not recover the number (3.522 %). A third variant
   would be unbounded tuning against a fixed gate, so it was not attempted.
3. With refinement off the tree is behaviourally identical to 0.3.3, and H19's independent re-runs
   confirm that: corner error back to exactly 3.440 %, placement 24/0/16, negatives 0/5, and the
   19-row geometry means identical to the H16 baseline (border 10.0226 px, text 0.5869 px, angle
   0.116°, sharpness 12,091.8, PSNR 11.236, SSIM 0.1245).
4. The feature stays implemented behind `REFINE_ENABLED` (currently `false`) with its constants
   documented, so a future attempt starts from measured ground rather than from scratch.

**Priority order: 1) warping/geometry, 2) auto-capture, 3) additional features such as pencil/handwriting enhancement.**

See §H18 below for the experiment itself, the per-image geometry table, and the release proof.

---

## H18 (0.3.4 measurement): corner refinement measured and gated OFF

**Decision: REVERT.** Sub-pixel corner refinement was implemented, measured on the full corpus lane,
and **not** shipped enabled. `REFINE_ENABLED = false`
([`OpenCvQuadPageDetector.kt:1578`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt:1578))
and `REFINE_MAX_MOVE_FRAC = 0.015`
([`:1581`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt:1581)) — 0.3.4 therefore
keeps the 0.3.3 corner model exactly. Implementation and constants are documented in
[`tooling/README.md`](../tooling/README.md) → "Geometry refinement — implemented, measured, gated OFF".

### What was tried

For each detected quad side, the detector samples the perpendicular luminance gradient inside a
narrow band, fits a robust edge line (`fitEdgeLine`), and intersects adjacent lines to obtain a
sub-pixel corner ([hook](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt:1058),
[implementation](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt:1218)). Each
refined corner is clamped to `REFINE_MAX_MOVE_FRAC` of the image diagonal and the refined quad is
re-checked with the same plausibility gate; the original detection is kept on any failure.

### Keep rule and measured result

The gate required **all** of: geometry improvement ≥ 2 % relative, corner error ≤ **3.44 %**,
benchmark detection 19/20, negatives 0/5, `distorted_suspect` 0, sharpness ratio ≥ 1.00, native heap
≤ 320 MB, warp ≤ 300 ms. One bounded mitigation (clamp 1.5 % → 0.75 %) was allowed.

| Metric | 0.3.4 final (refine OFF) | ON, clamp 1.5 % | ON, clamp 0.75 % | Gate |
|---|---:|---:|---:|---|
| Mean corner error (% diag) | **3.440 %** | 3.585 % | 3.522 % | ≤ 3.44 % → **FAIL** |
| Benchmark detection | **19/20** | 19/20 | 19/20 | 19/20 |
| Placement INSIDE / OUTSIDE / MIXED | **24 / 0 / 16** | 26 / 0 / 14 | 26 / 0 / 14 | unchanged-ish |
| `distorted_suspect` | **0** | 0 | 0 | 0 |
| Negative false positives | **0/5** | 0/5 | 0/5 | 0/5 |
| AI-ON / AI-OFF sharpness | **1.1128×** | 1.1265× | 1.1237× | ≥ 1.00 |
| Native heap high-water | **306.7 MB** | 306.7 MB | 307.8 MB | ≤ 320 MB |
| Warp latency, mean | **218.8 ms** | 218.9 ms | 213.4 ms | ≤ 300 ms |
| Mean GT PSNR / SSIM (corpus) | **12.954 / 0.21742** | 12.792 / 0.18546 | 12.845 / 0.19486 | information |
| AI-OFF benchmark detection | **14/20** | 14/20 | 14/20 | information |

The geometry gain itself was real (same-build bench A/B, 19 detected pages):

| Metric | refine OFF | refine ON (1.5 %) | Δ |
|---|---:|---:|---:|
| Border RMS (px) | 10.0226 | **9.1447** | **−8.76 %** |
| Text-line RMS (px) | 0.5869 | 0.5906 | +0.64 % (worse) |
| Text-angle std (deg) | 0.116 | 0.130 | +11.68 % (worse) |
| Laplacian sharpness (crops) | 12,091.8 | **12,504.8** | **+3.42 %** |
| PSNR vs GT scan | 11.236 | **11.452** | **+1.92 %** |
| SSIM vs GT scan | 0.1245 | **0.1363** | **+9.46 %** |

So refinement flattened borders and sharpened strokes, but it also moved corners measurably: mean
corner error rose to 3.585 % (1.5 % clamp) and still 3.522 % after the single permitted 0.75 % clamp
mitigation. Geometry first means the corner-error gate wins, so the feature is reverted rather than
tuned indefinitely.

Raw runs: [`corpus_on`](../tooling/out/h18_refine/corpus_on/warp_summary.json) (1.5 % clamp),
[`corpus_on_clamp075`](../tooling/out/h18_refine/corpus_on_clamp075/warp_summary.json) (0.75 %),
[`corpus_off_reconfirm`](../tooling/out/h18_refine/corpus_off_reconfirm/warp_summary.json) (final).

### Final geometry table (0.3.4, refinement OFF)

19 of 20 benchmark pages detected (`bench_docunet_01` is the long-standing miss); full CSV with all
columns: [`tooling/out/h18_refine/final_geometry.csv`](../tooling/out/h18_refine/final_geometry.csv).
The owner's target case `bench_docunet_25` is **highlighted**.

| Image | Out (px) | Border RMS px | Text RMS px | Angle std ° | Sharpness | PSNR | SSIM |
|---|---|---:|---:|---:|---:|---:|---:|
| bench_docunet_04 | 277×756 | 7.90 | 0.725 | 0.358 | 8,046.6 | 12.31 | 0.0331 |
| bench_docunet_08 | 1505×719 | 17.72 | 0.526 | 0.040 | 686.5 | 3.33 | 0.0070 |
| bench_docunet_11 | 686×817 | 10.76 | 0.826 | 0.148 | 9,520.5 | 10.83 | 0.0262 |
| bench_docunet_14 | 675×911 | 13.31 | 0.468 | 0.050 | 1,822.7 | 11.85 | 0.3712 |
| bench_docunet_18 | 616×744 | 5.10 | 0.595 | 0.089 | 26,719.2 | 13.24 | 0.2359 |
| bench_docunet_21 | 466×759 | 3.96 | 0.818 | 0.485 | 13,399.4 | 10.17 | 0.2496 |
| **bench_docunet_25** | **664×841** | **4.93** | **0.623** | **0.248** | **19,109.0** | **12.55** | **0.4658** |
| bench_docunet_28 | 471×615 | 4.90 | 0.486 | 0.096 | 5,444.4 | 8.99 | 0.2048 |
| bench_docunet_31 | 473×648 | 6.92 | 0.499 | 0.110 | 15,509.6 | 14.17 | 0.2568 |
| bench_docunet_35 | 584×730 | 5.62 | 0.506 | 0.106 | 12,229.6 | 13.59 | 0.1463 |
| bench_docunet_38 | 870×653 | 12.52 | 0.488 | 0.028 | 15,483.4 | 12.40 | 0.0075 |
| bench_docunet_41 | 850×575 | 5.22 | 0.533 | 0.022 | 23,242.8 | 12.70 | 0.0277 |
| bench_docunet_45 | 583×799 | 3.93 | 0.537 | 0.148 | 10,288.9 | 11.23 | 0.0137 |
| bench_docunet_48 | 1310×841 | 16.24 | 0.550 | 0.031 | 21,975.3 | 12.65 | 0.0065 |
| bench_docunet_52 | 785×585 | 13.88 | — | — | 14,084.2 | 13.44 | 0.0570 |
| bench_docunet_55 | 821×831 | 11.54 | 0.548 | 0.033 | 11,207.0 | 11.34 | 0.0420 |
| bench_docunet_58 | 709×585 | 7.92 | 0.558 | 0.042 | 9,791.3 | 9.47 | 0.0741 |
| bench_docunet_62 | 1790×1489 | 25.12 | 0.448 | 0.011 | 2,003.1 | 7.53 | −0.0164 |
| bench_docunet_65 | 1229×319 | 12.94 | 0.828 | 0.048 | 9,179.7 | 11.69 | 0.0158 |
| **Mean (19 detected)** | — | **10.0226** | **0.5869** | **0.116** | **12,091.8** | **11.236** | **0.1245** |

These means are identical to the 0.3.3 H16 baseline (border 10.01 px, text 0.5865, angle 0.1169,
sharpness 12,073, PSNR 11.245, SSIM 0.1253), confirming the revert restored shipped behaviour.

### Release proof (0.3.4 / 7)

* **Release APK:** 125,512,162 bytes; `versionCode=7`, `versionName=0.3.4` (`aapt2 dump badging`,
  [`badging_release.txt`](../tooling/out/h18_refine/badging_release.txt)).
* **Certificate:** SHA-256 `7a6977076f2c16e1c521b9301339ec9196aaac6f6a7641d7ec3fc965854253dc` —
  unchanged from 0.3.3, continuity verified with `apksigner verify --print-certs`
  ([`apksigner_release.txt`](../tooling/out/h18_refine/apksigner_release.txt)).
* **16 KB page size:** **16/16** libraries `16KB-OK` in **both** the debug and release APKs
  ([`check_16k_debug.txt`](../tooling/out/h18_refine/check_16k_debug.txt),
  [`check_16k_release.txt`](../tooling/out/h18_refine/check_16k_release.txt)).
* **Tests:** `tooling\h7_tests.cmd` — `DetectionRegressionTest`, `DewarpFidelityTest`,
  `NeuralPathReproTest`, `NeuralPathXnnpackTest`, `PageViewerUiTest`, `DetectorLatchRuntimeTest`,
  `AutoCaptureGatingTest`, `LiveCapturePathTest` all **PASS** (`tooling/out/h18_refine/h7_tests`,
  `tooling/out/h20`).
* **Install / launch:** release installed on `docscan_dbg_api35` after `pm clear`; `monkey` launcher
  start; `Displayed com.docscanai/.MainActivity: +830ms`; `dumpsys package` reports `versionCode=7`,
  `versionName=0.3.4`; crash scan (`FATAL EXCEPTION` / `Fatal signal`) **0 lines**;
  screenshots [`launch_release_0.3.4.png`](../tooling/out/h18_refine/launch_release_0.3.4.png) and
  [`launch_release_0.3.4_home.png`](../tooling/out/h18_refine/launch_release_0.3.4_home.png).

**Priority order: 1) warping/geometry, 2) auto-capture, 3) additional features such as pencil/handwriting enhancement.**

**Test coverage — what H20 proved, and the one residual gap.** The corpus/benchmark lanes call
`ScanPipeline.process()` directly on bundled images. H20 (0.3.4, no production change) closes the
rest of the chain with [`LiveCapturePathTest`](../app/src/androidTest/java/com/docscanai/LiveCapturePathTest.kt),
which drives the **real, unmodified** `OpenCvQuadPageDetector` → `PagePlausibility` gate →
`AutoCaptureStability` → `CaptureStateMachine` → `captureRequests` (the shutter) →
`ScanPipeline.process()` → saved raw/page/thumb files → `SessionStore` → `PageReviewScreen` →
`PageViewerContent`, using synthesised camera frames (the bundled page photograph perspective-warped
onto a desk; negatives are full-bleed framing shifts of the known scene photo). Measured
(`tooling/out/h20/attempt_b.txt`): a held page fires auto-capture **exactly once** (indicator
`DOCUMENT_DETECTED → HOLD_STEADY → capture`, 5/5 gate passes, score 0.896); **30** negative frames
produce **0** gate passes, **0** fires and the indicator never leaves `NONE`; after the 1 s thumbnail
window (cooldown provably expired) the same page leaving the frame produces **no** further fire; a
jittered hold (measured quad IoU **0.710** < the 0.85 floor) does not fire until the quad is held
steady; the manual shutter fires with an **empty** stability window and still saves
(274 635 B page + 323 113 B raw); the saved page decodes (744×998), the session entry round-trips
through `SessionStore`, Review shows `1 page captured` / `Page 1`, and the viewer renders it.

**What remains simulated, and what is only verified on hardware.** The *positive* path is still
simulated: the frames `LiveCapturePathTest` composes, not optics, so the **real optics + CameraX
binder hand-off** link (`ImageAnalysis → FrameAnalyzer.analyze → BitmapUtils.imageProxyToBitmap`) is
exercised only on physical hardware. H21 changes what *is* covered on the emulator: the virtual-scene
**room with no document** defect is now reproduced and closed on the real chain (`FrameAnalyzer` → real
detector → gate → state machine), and 16 frames from that run were promoted into the permanent fixture
set `app/src/androidTest/assets/live_room/`. A **live positive** capture on the emulator is still out
of reach — emulator 37.2.12 ignores `-virtualscene-poster` (the stock checkerboard poster stays) and a
headless AVD has no camera-pose control (`adb emu help` exposes no pose command, `-no-window` has no
pointer) — so the positive path is covered by `LiveCapturePathTest` plus the corpus lane, **not** by a
virtual-scene page capture. No positive result is claimed from the virtual scene.

That attempt did produce a real, still-open product measurement: on the plain virtual-scene room
(wall + bookshelf + TV, no page anywhere) the gate reported `pass=true` with score 0.73–0.77
(`cov=1.000 text=0.72 paper=0.26`) and auto-capture fired **18 times in ~100 s**, saving **35**
room pages (`tooling/out/h20/a02_room_logcat.txt`, `a07_review_room_pages.png`). That is the
owner-reported "photos of random shit" behaviour, reproduced on the real capture chain. Closing it
needs a gate threshold/weight change, which H20 was explicitly not allowed to make; it is recorded
here as the **open risk** of the capture path, not as a pass.

> Every number above was measured on the x86_64 emulator `docscan_dbg_api35`. No ARM hardware was
> involved; the S25 FE checklist in §7 still applies.

---

## 0. H15 adaptive warp (0.3.3) — the over-correction fix

The owner-reported defect was that the learned grid over-corrects real captures: it deforms page
geometry and visibly damages character strokes. The root cause was measured, not assumed: the 45×31
UVDoc grid is **near-identity on a flat page but not exactly identity**, and applying it at 100 %
re-samples every glyph for no benefit. H15 measures the grid-implied sampling map against the pure
4-corner homography and applies it at an **adaptive strength**, with a per-vector clamp.

### 0.1 Policy constants and implementation

| Constant | Value | File:line | Meaning |
|---|---|---|---|
| `RESIDUAL_FLAT_MEAN` | **0.015** (1.5 % of diagonal) | [`WarpPolicy.kt`](../app/src/main/java/com/docscanai/imaging/WarpPolicy.kt:35) | mean residual ≤ 1.5 % ⇒ **strength 0** (homography only) |
| `RESIDUAL_FULL_MEAN` | **0.05** (5 %) | [`WarpPolicy.kt`](../app/src/main/java/com/docscanai/imaging/WarpPolicy.kt:38) | linear ramp 0 → 1 between 1.5 % and 5 % |
| `RESIDUAL_MAX_CLAMP` | **0.06** (6 %) | [`WarpPolicy.kt`](../app/src/main/java/com/docscanai/imaging/WarpPolicy.kt:41) | each residual vector is length-capped at 6 % before the strength is applied |

The strength interpolates the **sampling map** (`final = homography + strength · clamp(residual)`),
never two output images. The adaptive policy is applied inside the grid composition
([`NeuralGridDewarp.kt`](../app/src/main/java/com/docscanai/imaging/NeuralGridDewarp.kt:464)) and the
pipeline still performs exactly **one** `Imgproc.remap` from the original still on the CPU EP
(`allowXnnpack=false`). The processor contract is documented at
[`PerspectiveDewarpProcessor.kt`](../app/src/main/java/com/docscanai/imaging/PerspectiveDewarpProcessor.kt:28).
Per-page audit lines:

```
[Warp] residual mean=0.0183 max=0.0541 p95=0.0311 curv=0.0009
[Warp] strength=0.09 mean=0.0183 max=0.0541
[Warp] residual suppressed (flat) mean=0.0090 max=0.0210
[Warp] clamp vectors=1234 limit=0.0600
```

### 0.2 Measured residual distribution (0.3.3 corpus, 61 grid runs)

`mean` / `max` / `p95` / `curv` are fractions of the page diagonal (curv is the mean absolute second
difference of the residual-magnitude field on the 45×31 grid). Values are averages per group unless
noted.

| Group | Pages | Grid runs | mean (avg) | max (avg, worst) | p95 (avg) | curv | strength 0 / partial / 1.0 |
|---|---:|---:|---:|---:|---:|---:|---|
| Benchmark (20 DocUNet photos) | 20 | 19 | **0.02563** | 0.08295 (worst 0.1852) | 0.05099 | 0.00111 | **3 / 14 / 2** |
| Synthetic controlled | 49 | 42 | **0.01476** | 0.05402 (worst 0.1491) | 0.02986 | 0.00094 | **27 / 15 / 0** |
| Already-rectified (`rectified_skip`) | 7 | **0** | — | — | — | — | gate skipped the grid entirely |
| **All** | **76** | **61** | — | — | — | — | **30 / 29 / 2** |

Reading: flat synthetic pages sit at **1.48 %** mean residual — just below the 1.5 % flat threshold —
so **27 of 42** were delivered as pure homography. Real benchmark captures sit at **2.56 %**, so most
get a partial correction (14 partial, 2 full), which is the intended behaviour. Every page that the
F2 rectangularity gate classified as already rectified ran **zero** grid passes, so the flat-page
hypothesis is confirmed from both directions.

### 0.3 Interpolation decision (STEP 3)

Both candidates were measured on the **same composed map** on four benchmark captures, central text
crop (`DewarpFidelityTest#interpolationComparison_benchmarkStrokes`):

| Interpolation | Laplacian variance (stroke sharpness) | Text-line straightness |
|---|---:|---:|
| `INTER_CUBIC` | **2190.63** | 21.63517 |
| `INTER_LANCZOS4` | 2130.39 | **21.63551** |

CUBIC is **+2.8 %** sharper on the specified text-crop metric and gentler on high-contrast strokes
(LANCZOS4 can ring), while straightness is identical to four decimals. Production is therefore
`INTER_CUBIC` ([`NeuralGridDewarp.kt`](../app/src/main/java/com/docscanai/imaging/NeuralGridDewarp.kt:876)).
Trade-off reported honestly: on the **full 76-image corpus** the LANCZOS4 candidate produced a higher
global Laplacian mean (AI-ON/AI-OFF **1.174×** vs **1.113×** for CUBIC), because ringing inflates
Laplacian energy. The specified text-crop A/B is the deciding measurement; both corpus ratios remain
≥ 1.00.

### 0.4 Fidelity proof (`DewarpFidelityTest`, all assertions pass)

```
WARP_FLAT_INVARIANCE ok strength=0.00 residualMean=0.00766 maxMovePct=0.00000 straightnessHom=109.82597 straightnessAdaptive=109.82597 sharpHom=21044.82027 sharpAdaptive=21044.82027
WARP_STROKE_PRESERVATION ai=16711.07295 homography=16710.95515 ratio=1.00001 straightAi=93.33832 straightHom=93.33549
WARP_CURVED_BENEFIT strength=0.22 mean=0.02282 curv=0.00001 straightHomography=39.44018 straightAdaptive=43.78033 gain=1.11004
WARP_CLAMP_PROOF ok strength=1.00 clampedVectors=45468 maxAppliedPct=6.00000 limitPct=6.00000 minJacobian=1.13819
WARP_INTERP_COMPARISON images=4 cubicSharp=2190.63405 lanczosSharp=2130.39306 cubicStraight=21.63517 lanczosStraight=21.63551 chosen=INTER_CUBIC
```

### 0.5 Before / after (0.3.2 → 0.3.3)

| Metric | 0.3.2 | 0.3.3 (H15) | Guardrail |
|---|---:|---:|---|
| Benchmark detection | 19/20 | **19/20** | no regression |
| Mean corner error (40 GT images) | 3.44 % diag | **3.44 % diag** | ≤ 5 % |
| Placement INSIDE / OUTSIDE / MIXED | 24 / 0 / 16 | **24 / 0 / 16** | unchanged |
| `distorted_suspect` | 0 | **0** | 0 |
| Negatives false positives | 0/5 | **0/5** | 0/5 |
| AI-ON / AI-OFF mean sharpness | 1.13× | **1.11×** | ≥ 1.00 |
| Warp latency, mean | 174.6 ms | **219.3 ms** | page still < 1.5 s |
| Native heap high-water | 287.3 MB | **306.7 MB** | measured (logcat, not PSS) |
| Benchmark PSNR / SSIM vs GT scan | 10.563 / 0.09332 | **10.741 / 0.11109** | improved (+0.18 dB) |
| Mean GT PSNR / SSIM (all GT pages) | 12.533 / 0.14663 | **12.954 / 0.21742** | improved (different physical GT) |
| AI-OFF benchmark detection | 14/20 | **14/20** | informational |
| AI-ON corpus wall clock | 100,096 ms | **100,442 ms** | measured |

The PSNR/SSIM direction is **up** (benchmark +0.18 dB, all-GT +0.42 dB), but this must not be
over-read: the GT is a different physical capture (flatbed scan), not the same scene under identical
lighting. The cost of the fix is a **+44.7 ms** mean warp latency (the residual statistics pass) and
a **+19.4 MB** measured native high-water; both remain well inside the 1.5 s capture budget. The
benchmark case the owner called out,
`bench_docunet_25`, is detected (`neural`, 664×841), sharpness **14,260.8** vs input **593.4**, text
straightness **53.26** vs input **29.41**, PSNR **11.91 dB**, SSIM **0.424**.

### 0.6 Owner-requested roadmap / scope note

The owner identified **benchmark 25** as the near-exact target use case; it still performs well after
the adaptive gate (numbers above). The requested product ordering is recorded explicitly:

1. **Warping / geometry first** — this H15 change. Flatten the page and preserve strokes before any
   new capture or enhancement feature.
2. **Auto-capture second** — only after warping is trustworthy, auto-capture should fire **only when a
   document is detected and the gate/stability window says it is stable**. That gate already exists
   (`PagePlausibility` + 3-frame IoU ≥ 0.85); H15 does not change its thresholds.
3. **Additional features third** — for example, make **pencil writing easier to read** (a
   pencil-specific contrast/stroke enhancement). This is a new feature, not part of the H15 warp fix;
   it must be evaluated after (1) and (2) are demonstrably great, and it is **out of scope for
   0.3.3**.

### 0.7 Release proof and emulator-only caveat

* **Release APK:** 125,512,162 bytes; `versionCode=6`, `versionName=0.3.3`
  ([`badging_release.txt`](../tooling/out/h15_warp/badging_release.txt)).
* **Certificate:** SHA-256 `7a6977076f2c16e1c521b9301339ec9196aaac6f6a7641d7ec3fc965854253dc`,
  unchanged from 0.3.2 ([`apksigner_release.txt`](../tooling/out/h15_warp/apksigner_release.txt)).
* **16 KB:** **16/16** native libraries OK in both the debug and release APKs, 0 failures
  ([`check_16k_debug.txt`](../tooling/out/h15_warp/check_16k_debug.txt),
  [`check_16k_release.txt`](../tooling/out/h15_warp/check_16k_release.txt)).
* **Install / launch:** release installed on `docscan_dbg_api35` after `pm clear`; cold launch
  `Status: ok`, `LaunchState: COLD`, `TotalTime 1306 ms`; crash scan empty; screenshot
  [`release_launch.png`](../tooling/out/h15_warp/release_launch.png)
  ([`release_launch.txt`](../tooling/out/h15_warp/release_launch.txt)).

Every number in this section is from the x86_64 emulator `docscan_dbg_api35`. The ARM kernels differ;
the S25 FE checklist in §7 still applies.

---

## 1. Headline table (0.3.2 historical baseline)

> The per-image tables in §1–§5 are the **0.3.2** run kept for comparison. The 0.3.3 headline numbers
> and the new adaptive-warp evidence are in §0 above.

Targets: the only numeric budgets fixed by [`TECHNICAL_SPEC.md`](../TECHNICAL_SPEC.md) are the
preview frame budget (≈30 ms/frame, §4.1) and the capture budget (< 1.5 s per page, §4.2), plus the
API/16 KB requirements. All other "target" values below are the self-imposed acceptance bar used by
this verification pass and are marked as such.

| Metric | Baseline (h4) | Final (0.3.2) | Target | Verdict |
|---|---|---|---|---|
| Benchmark detection | 19/20 (95.0%) | **19/20 (95.0%)** | 19/20 — no regression (self-imposed) | PASS (flat) |
| Mean corner error, 40 images with corner GT | 3.45% of diagonal | **3.44% of diagonal** | ≤ 5.0% (self-imposed) | PASS (flat) |
| Placement INSIDE / OUTSIDE / MIXED | 21 / 0 / 19 | **24 / 0 / 16** | INSIDE ≥ 24/40 (self-imposed) | PASS (INSIDE +3) |
| `distorted_suspect` | 0 | **0** | 0 (self-imposed) | PASS |
| Negatives false positives | 3/5 | **0/5** | 0/5 (self-imposed) | **PASS (defect fixed)** |
| Content gate: negatives rejected | `n/a` (no gate) | **5/5** | 5/5 (self-imposed) | PASS |
| Content gate score, 19 accepted benchmark positives | `n/a` | **min 0.646 / p50 0.765 / max 0.892** | ≥ 0.55 and text ≥ 0.30 | PASS |
| Auto-capture stability | none | **3 frames @ IoU ≥ 0.85, test PASS** | 3 @ 0.85 | PASS |
| AI-ON / AI-OFF mean sharpness (Laplacian variance) | 10,682.1 / 13,172.4 = **0.81×** | 14,794.4 / 13,062.6 = **1.13×** (per-image mean ratio **1.38×**) | ≥ 1.00× (self-imposed) | PASS (crossed over) |
| Dewarp latency, mean | 175.2 ms | **174.6 ms** | keeps page < 1.5 s (spec §4.2) | PASS (flat) |
| Detection latency, mean | 58.2 ms | **71.7 ms** | no separate budget (spec §4.1 applies to preview, not stills) | PASS (higher due to the gate) |
| Mean end-to-end per page | 1,250 ms (94,972 ms / 76) | **1,315 ms** per-image mean (100,096 ms wall clock / 76 = 1,317 ms) | < 1,500 ms (spec §4.2) | PASS |
| Native heap peak | 275,439 KB ≈ 269 MiB | **287.3 MB** (max `native=` in AI-ON logcat) | no hard target | measured |
| AI-OFF ONNX sessions | 0 | **0** | 0 | PASS |
| 16 KB page compliance | 16/16 libs | **16/16 (debug) and 16/16 (release)** | 16/16 | PASS |
| Viewer detail tier (long edge, Laplacian ratio) | test OK (2 tests) | test OK (2 tests), **numbers not logged** | — | `n/a` |
| Release APK size | `n/a` (no 0.3.0 APK on disk) | **125,512,162 bytes** | no target | — |
| Release cert SHA-256 | `7a69…53dc` | **`7a69…53dc`** | unchanged for upgrade | PASS |

Supporting facts for the table:

* `16 KB` — all 8 native libraries in both ABIs report
  `PT_LOAD min_p_align=0x4000 (16384) -> 16KB-OK`: 16 `16KB-OK` lines and 0 failures in
  [`check_16k_debug.txt`](../tooling/out/h14_final/check_16k_debug.txt) and
  [`check_16k_release.txt`](../tooling/out/h14_final/check_16k_release.txt).
* Signature — `apksigner verify --print-certs --verbose` →
  `Verified using v1 scheme (JAR signing): false`, `v2 scheme: true`, `v3 scheme: false`,
  `v3.1: false`, `v4: false`, `Signer #1 certificate SHA-256 digest:
  7a6977076f2c16e1c521b9301339ec9196aaac6f6a7641d7ec3fc965854253dc`.
  Raw output: [`tooling/out/h14_final/apksigner_release.txt`](../tooling/out/h14_final/apksigner_release.txt).
* Version proof from the APK itself — `aapt2 dump badging` (0.3.3, historical; 0.3.4 is `versionCode='7'
  versionName='0.3.4'`, see the H18 section above):
  `package: name='com.docscanai' versionCode='6' versionName='0.3.3'`,
  `minSdkVersion:'26'`, `targetSdkVersion:'35'`, `native-code: 'arm64-v8a' 'x86_64'`.
  Raw output: [`tooling/out/h15_warp/badging_release.txt`](../tooling/out/h15_warp/badging_release.txt).
* Native heap — max `native=` on the 0.3.3 AI-ON run is **306.7 MB**
  ([`logcat_ai_on.txt`](../tooling/out/h15_warp/corpus_final/logcat_ai_on.txt)); the 0.3.2 value was
  287.3 MB. This is a logcat high-water mark, not a `dumpsys meminfo` PSS.
* AI-ON / AI-OFF sessions — `CORPUS_SESSIONS_AI_ON=2`, `CORPUS_SESSIONS_AI_OFF=0`,
  `native=0` in [`tooling/out/h14_final/corpus/logcat_ai_on.txt`](../tooling/out/h14_final/corpus/logcat_ai_on.txt)
  and [`logcat_ai_off.txt`](../tooling/out/h14_final/corpus/logcat_ai_off.txt).
* AI-OFF benchmark note — the classical fallback detects **14/20** benchmark pages at 0.3.2
  (15/20 in the interim pre-angle-rule run). The single change is `bench_docunet_45`, whose
  classical quad has a 127° corner and is rejected by the hard rule:
  `[Gate] id=still score=0.000 ... angle=0.951 minAng=69 maxAng=127 ... pass=false`. The AI-ON
  neural quad for the same image is 82°–94° and passes, so the primary benchmark is unchanged at
  **19/20**. The rule was deliberately not loosened: it is what rejects the scene-photo negative,
  and the stated acceptance bar applies to the AI-ON path.
* Viewer — [`tooling/out/h11/HighResViewerTest.txt`](../tooling/out/h11/HighResViewerTest.txt) reports
  `test=detailTier_zoomDecodesRealSourcePixels_sharperAndBounded` twice and `OK (2 tests)`;
  it asserts rather than prints, so the concrete long edge and Laplacian ratio are recorded as `n/a`.

### On-device verification status

**0.3.2 was installed and launched on `docscan_dbg_api35`.** The signed release APK was installed
after uninstalling the debug-signed build (the two use different certificates), then
`pm clear com.docscanai`, then a cold `am start -W`: `Status: ok`, `LaunchState: COLD`,
`TotalTime: 1420 ms`. `dumpsys package` reports `versionCode=5`, `versionName=0.3.2`. The crash
scan (`FATAL EXCEPTION` / `Fatal signal` / `E AndroidRuntime` / `SIGSEGV` / `SIGABRT` /
`Process: com.docscanai`) is **0 lines**; the screenshot shows the first-run model screen
([`release_launch.png`](../tooling/out/h14_final/release_launch.png)). Models were then written to
the release app's private dir via `adb root` (the release is not debuggable, so `run-as` cannot be
used), the onboarding Download button was tapped, and the app logged
`[Model] m2_dewarp_grid files=2 cached ok graph=243KB data=31.6MB` from the worker's cached fast
path (size + SHA-256 re-verified). The relaunch shows the camera permission prompt, i.e. onboarding
auto-advanced because both Fast-tier models were present
([`release_models.png`](../tooling/out/h14_final/release_models.png)). The `files=2 ok` wording in
the S25 FE checklist is the fresh-download branch; the pre-provisioned branch says `cached ok`.

The upgrade-over-0.3.1 test was **not executed**: no 0.3.1 APK is kept on this machine. Upgrade
compatibility is supported by the certificate identity above (byte-identical SHA-256 to 0.3.1),
not by an actual `adb install -r` over 0.3.1.

---

## 2. Benchmark per-image results (20 rows)

Verdict rule (applied identically to every table in this document):

* **POOR** — not detected and not a legitimate full-frame pass-through, **or** corner error > 5% of
  diagonal, **or** quad placed OUTSIDE the GT page, **or** `distorted_suspect` set.
* **ACCEPTABLE** — detected, but corner error is 2–5%, **or** placement is MIXED, **or** the output
  is not sharper than the input.
* **GOOD** — detected with corner error ≤ 2% and a sharpness gain, **or** a correct full-frame
  pass-through (an already-rectified frame that needs no quad, corner error ≤ 1%).
* Negatives use a separate rule: **PASS** when nothing is detected, **POOR** when a page is
  "detected" (a false positive).

`corner err %` is `corner_err_pct_diag` from the report × 100; the harness writes `-100` when no
corner GT exists, and those rows are shown as `n/a`. Corner GT exists on the 40 synthetic renders
only: the 20 DocUNet benchmark images carry a flat *scan* GT (so PSNR/SSIM are measurable) but no
corner annotation, which is why every benchmark row shows `n/a` for corner error and `no GT` for
placement. That is a property of the dataset, not missing instrumentation.

| id | detected | corner err % | placement | dewarp | AI-ON sharp | AI-OFF sharp | verdict |
|---|---|---|---|---|---|---|---|
| `bench_docunet_01` | N | n/a | no GT | skipped_no_detection | 11562 | 11562 | POOR (no detection -> fallback warp) |
| `bench_docunet_04` | Y | n/a | no GT | neural | 9029 | 5173 | GOOD |
| `bench_docunet_08` | Y | n/a | no GT | neural | 2773 | 1851 | GOOD |
| `bench_docunet_11` | Y | n/a | no GT | neural | 14235 | 3234 | GOOD |
| `bench_docunet_14` | Y | n/a | no GT | neural | 4257 | 4211 | GOOD |
| `bench_docunet_18` | Y | n/a | no GT | neural | 21162 | 5226 | GOOD |
| `bench_docunet_21` | Y | n/a | no GT | neural | 14503 | 2164 | GOOD |
| `bench_docunet_25` | Y | n/a | no GT | neural | 15372 | 8995 | GOOD |
| `bench_docunet_28` | Y | n/a | no GT | neural | 4482 | 24436 | GOOD |
| `bench_docunet_31` | Y | n/a | no GT | neural | 26063 | 13184 | GOOD |
| `bench_docunet_35` | Y | n/a | no GT | neural | 7784 | 1861 | GOOD |
| `bench_docunet_38` | Y | n/a | no GT | neural | 11288 | 11379 | GOOD |
| `bench_docunet_41` | Y | n/a | no GT | neural | 17319 | 9397 | GOOD |
| `bench_docunet_45` | Y | n/a | no GT | neural | 9312 | 9092 | GOOD |
| `bench_docunet_48` | Y | n/a | no GT | neural | 16035 | 6710 | GOOD |
| `bench_docunet_52` | Y | n/a | no GT | neural | 6794 | 11790 | GOOD |
| `bench_docunet_55` | Y | n/a | no GT | neural | 8627 | 4007 | GOOD |
| `bench_docunet_58` | Y | n/a | no GT | neural | 7648 | 2465 | GOOD |
| `bench_docunet_62` | Y | n/a | no GT | neural | 4477 | 6799 | GOOD |
| `bench_docunet_65` | Y | n/a | no GT | neural | 6900 | 1702 | GOOD |

---

## 3. Synthetic per-image results (51 rows)

| id | detected | corner err % | placement | dewarp | AI-ON sharp | AI-OFF sharp | verdict |
|---|---|---|---|---|---|---|---|
| `syn_rot_small_0` | Y | 1.23% | INSIDE | neural | 17633 | 14974 | GOOD |
| `syn_rot_small_1` | Y | 1.17% | MIXED | neural | 17569 | 14733 | ACCEPTABLE (mixed corner placement) |
| `syn_rot_small_2` | Y | 12.05% | INSIDE | neural | 29152 | 27912 | POOR (corner error > 5%) |
| `syn_rot_small_3` | Y | 1.17% | MIXED | neural | 17640 | 14733 | ACCEPTABLE (mixed corner placement) |
| `syn_rot_small_4` | Y | 3.52% | INSIDE | neural | 18807 | 20075 | ACCEPTABLE (corner error 2-5%) |
| `syn_rot_90_0` | Y | 13.37% | INSIDE | neural | 22118 | 22312 | POOR (corner error > 5%) |
| `syn_rot_90_1` | Y | 2.68% | INSIDE | neural | 16246 | 16174 | ACCEPTABLE (corner error 2-5%) |
| `syn_rot_90_2` | Y | 9.04% | INSIDE | neural | 12981 | 15519 | POOR (corner error > 5%) |
| `syn_persp_mild_0` | Y | 0.71% | INSIDE | neural | 16200 | 15942 | GOOD |
| `syn_persp_mild_1` | Y | 0.59% | MIXED | neural | 17284 | 17783 | ACCEPTABLE (mixed corner placement) |
| `syn_persp_mild_2` | Y | 0.18% | MIXED | neural | 16109 | 16130 | ACCEPTABLE (mixed corner placement) |
| `syn_persp_mild_3` | Y | 0.67% | INSIDE | neural | 16071 | 13559 | GOOD |
| `syn_persp_mild_4` | Y | 1.13% | INSIDE | neural | 17250 | 14296 | GOOD |
| `syn_persp_mod_0` | Y | 1.52% | INSIDE | neural | 18672 | 18091 | GOOD |
| `syn_persp_mod_1` | Y | 1.01% | INSIDE | neural | 18645 | 15953 | GOOD |
| `syn_persp_mod_2` | Y | 0.42% | MIXED | neural | 20211 | 20739 | ACCEPTABLE (mixed corner placement) |
| `syn_persp_mod_3` | Y | 0.68% | INSIDE | neural | 17929 | 15155 | GOOD |
| `syn_persp_strong_0` | Y | 0.74% | INSIDE | neural | 17808 | 14896 | GOOD |
| `syn_persp_strong_1` | Y | 1.21% | INSIDE | neural | 19907 | 19197 | GOOD |
| `syn_persp_strong_2` | Y | 0.28% | MIXED | neural | 20203 | 17628 | ACCEPTABLE (mixed corner placement) |
| `syn_persp_strong_3` | Y | 0.57% | MIXED | neural | 17199 | 18046 | ACCEPTABLE (mixed corner placement) |
| `syn_persp_offcentre_0` | Y | 2.95% | INSIDE | neural | 17696 | 14166 | ACCEPTABLE (corner error 2-5%) |
| `syn_persp_offcentre_1` | Y | 11.93% | INSIDE | neural | 28676 | 25598 | POOR (corner error > 5%) |
| `syn_persp_offcentre_2` | Y | 5.14% | INSIDE | neural | 20343 | 20386 | POOR (corner error > 5%) |
| `syn_partial_oof_0` | Y | 14.71% | INSIDE | neural | 29237 | 26463 | POOR (corner error > 5%) |
| `syn_partial_oof_1` | Y | 13.56% | INSIDE | neural | 30924 | 29138 | POOR (corner error > 5%) |
| `syn_partial_oof_2` | Y | 14.01% | INSIDE | neural | 28739 | 25936 | POOR (corner error > 5%) |
| `syn_rectified_full_0` | N | 0.05% | MIXED | skipped_no_detection | 13165 | 13165 | GOOD (correct full-frame pass-through) |
| `syn_rectified_full_1` | N | 0.05% | MIXED | skipped_no_detection | 13165 | 13165 | GOOD (correct full-frame pass-through) |
| `syn_rectified_full_2` | N | 0.05% | MIXED | skipped_no_detection | 13165 | 13165 | GOOD (correct full-frame pass-through) |
| `syn_light_brightness` | N | 0.05% | MIXED | skipped_no_detection | 12926 | 12926 | GOOD (correct full-frame pass-through) |
| `syn_light_vignette` | N | 0.05% | MIXED | skipped_no_detection | 12629 | 12629 | GOOD (correct full-frame pass-through) |
| `syn_light_shadow_band` | Y | 16.84% | INSIDE | rectified_skip | 3762 | 3762 | POOR (corner error > 5%) |
| `syn_light_lowcontrast` | Y | 1.03% | INSIDE | neural | 16743 | 13502 | GOOD |
| `syn_deg_blur_0` | Y | 0.31% | MIXED | neural | 231 | 192 | ACCEPTABLE (mixed corner placement) |
| `syn_deg_blur_1` | Y | 0.56% | INSIDE | neural | 205 | 350 | GOOD |
| `syn_deg_noise_0` | Y | 0.87% | MIXED | neural | 20274 | 19680 | ACCEPTABLE (mixed corner placement) |
| `syn_deg_noise_1` | Y | 0.80% | MIXED | neural | 19319 | 18955 | ACCEPTABLE (mixed corner placement) |
| `syn_deg_small_0` | Y | 0.54% | INSIDE | neural | 613 | 693 | GOOD |
| `syn_deg_small_1` | Y | 0.17% | MIXED | neural | 598 | 582 | ACCEPTABLE (mixed corner placement) |
| `real_cpcct_baochangkeng_station_uniform_invoice_20120` | Y | n/a | no GT | neural | 7014 | 2582 | GOOD |
| `real_dr_deutsche_reichsbahn_computer_printed_ticket_a` | N | n/a | no GT | skipped_no_detection | 13249 | 13249 | POOR (no detection -> fallback warp) |
| `real_efta00002964_construction_yard_spiral_bound_note` | Y | n/a | no GT | neural | 1763 | 1910 | GOOD |
| `real_hk_sheung_wan_tung_kee_restaurant_food_order_lis` | Y | n/a | no GT | neural | 2542 | 3505 | GOOD |
| `real_kfc_linkou_wenhua_uniform_invoice_20120727` | Y | n/a | no GT | neural | 6149 | 6863 | GOOD |
| `real_media_viewer_launch_retrospective_whiteboard_apr` | Y | n/a | no GT | neural | 1829 | 2123 | GOOD |
| `real_open_internal_pages_of_the_book_detr_s_del_espej` | Y | n/a | no GT | neural | 1821 | 1944 | GOOD |
| `real_receiptswiss` | Y | n/a | no GT | neural | 1436 | 1419 | GOOD |
| `real_sherwin_carlquist_field_notebook_from_1967_exped` | Y | n/a | no GT | rectified_skip | 364 | 518 | GOOD |
| `real_starbucks_hanzhong_store_invoice_qt35958794` | Y | n/a | no GT | neural | 8432 | 3777 | GOOD |
| `real_us_navy_050420_n_3503m_001_lt_mark_hathcock_fill` | N | n/a | no GT | skipped_no_detection | 3688 | 3688 | POOR (no detection -> fallback warp) |

---

## 4. Real photos (0 rows available) and negatives (5 rows)

**Real photos: no data exists.** The corpus manifest used by every run on this machine
(`h3a`, `h3b_final`, `h4_final`, `h7_final`, `h10_corpus`, `h11`) contains exactly
20 benchmark + 51 synthetic + 5 negatives = **76 images**. There is no 12-image "real photos"
group in any report artifact, so a 12-row table cannot be produced without inventing rows. The
five photographs whose ids begin with `real_` are carried inside the corpus under the manifest
categories that the harness labels `synthetic` and `negatives`; their group label is reproduced
verbatim from the report.

Consequence: **this release has no evidence for real, positive (non-synthetic, non-rectified)
photographs of documents.** That is a genuine hole in the evidence, recorded in §6 as gap G13.

### Negatives (5 rows)

| id | detected | corner err % | placement | dewarp | AI-ON sharp | AI-OFF sharp | verdict |
|---|---|---|---|---|---|---|---|
| `syn_neg_texture_0` | N | n/a | no GT | skipped_no_detection | 88198 | 88198 | PASS (correctly not a page; no quad found) |
| `syn_neg_texture_1` | N | n/a | no GT | skipped_no_detection | 88479 | 88479 | PASS (correctly not a page; no quad found) |
| `syn_neg_clutter_0` | N | n/a | no GT | skipped_no_detection | 4536 | 4536 | PASS (gate: score 0.551, text 0.115 < 0.30) |
| `syn_neg_clutter_1` | N | n/a | no GT | skipped_no_detection | 4478 | 4478 | PASS (gate: score 0.560, text 0.105 < 0.30) |
| `real_a_woman_fills_out_a_bear_information_management` | N | n/a | no GT | skipped_no_detection | 3307 | 3307 | PASS (gate: angle 41°/133° outside 55°–125°) |

**0 of 5 negatives are false positives** (was 3/5 at 0.3.1). The two texture negatives never
produced a quadrilateral, so the gate was not even consulted. The two clutter negatives produced
quads that cleared the 0.55 score but failed the hard `text >= 0.30` floor, and the real scene
photo failed the **55°–125° hard angle rule** (`minAng=41 maxAng=133`) before any content analysis:

```
[Gate] id=still score=0.551 cov=1.000 asp=0.813 paper=0.932 text=0.115 edge=1.000 glyphs=7 angle=1.000 minAng=74 maxAng=118 convex=true selfInt=false pass=false
[Gate] id=still score=0.560 cov=1.000 asp=0.967 paper=0.946 text=0.105 edge=1.000 glyphs=6 angle=1.000 minAng=87 maxAng=92 convex=true selfInt=false pass=false
[Gate] id=still score=0.000 cov=1.000 asp=0.697 paper=0.000 text=0.000 edge=0.000 glyphs=0 angle=0.607 minAng=41 maxAng=133 convex=true selfInt=false pass=false
```

All 19 detected benchmark positives still pass the gate (scores 0.646–0.892, median 0.765), so
benchmark detection stays 19/20. See gaps G1/G2 in §6 — both are now CLOSED.

---

## 5. Worst 10 images across all groups

Ranking metric: sort by (1) genuine detection miss first, then (2) corner error as a percentage of
the diagonal, then (3) smallest AI-ON sharpness gain over the input. Correct full-frame
pass-throughs are excluded because they are not failures. `gain` is
`out_sharpness / in_sharpness` for the AI-ON run.

| id | group | rank metric | diagnosis |
|---|---|---|---|
| `real_dr_deutsche_reichsbahn_computer_printed_ticket_a` | synthetic | detect=N err=n/a% gain=7.63 | detection: no quad found, fell back to full-frame warp |
| `real_us_navy_050420_n_3503m_001_lt_mark_hathcock_fill` | synthetic | detect=N err=n/a% gain=27.63 | detection: no quad found, fell back to full-frame warp |
| `bench_docunet_01` | benchmark | detect=N err=n/a% gain=27.85 | detection: no quad found, fell back to full-frame warp |
| `real_efta00002964_construction_yard_spiral_bound_note` | synthetic | detect=Y err=n/a% gain=2.83 | dewarp: measurable loss vs GT scan despite detection |
| `real_receiptswiss` | synthetic | detect=Y err=n/a% gain=3.02 | dewarp: measurable loss vs GT scan despite detection |
| `bench_docunet_28` | benchmark | detect=Y err=n/a% gain=5.12 | source quality: low PSNR vs GT scan (8.30 dB) |
| `real_kfc_linkou_wenhua_uniform_invoice_20120727` | synthetic | detect=Y err=n/a% gain=6.66 | dewarp: measurable loss vs GT scan despite detection |
| `real_hk_sheung_wan_tung_kee_restaurant_food_order_lis` | synthetic | detect=Y err=n/a% gain=7.59 | dewarp: measurable loss vs GT scan despite detection |
| `bench_docunet_45` | benchmark | detect=Y err=n/a% gain=8.68 | dewarp: measurable loss vs GT scan despite detection |
| `real_cpcct_baochangkeng_station_uniform_invoice_20120` | synthetic | detect=Y err=n/a% gain=8.85 | dewarp: measurable loss vs GT scan despite detection |

Reading of the list:

* **3 genuine detection misses** — `bench_docunet_01` (the single benchmark failure),
  `real_dr_deutsche_reichsbahn_computer_printed_ticket_a` and
  `real_us_navy_050420_n_3503m_001_lt_mark_hathcock_fill`. Cause: **detection**. The classifier
  never locked a quad, so the pipeline fell back to a full-frame warp.
* **7 detected images with the weakest output** — these are `real_*` photographs and two benchmark
  pages whose output is only ~3–9× sharper than the input and scores below 10 dB PSNR against the
  synthetic GT scan where a GT exists. Cause: **dewarp / source quality**, not detection. The
  images are photographed paper in real lighting against a GT scan that was produced under
  different conditions, so PSNR is a harsh proxy here.

---

## 6. Known gaps

| # | Gap | Severity | Smallest next step |
|---|---|---|---|
| G1 | **CLOSED at 0.3.2 — negatives false positives: 3/5 → 0/5.** A content page-ness gate now runs before any state may reach `LOCKED`: [`PagePlausibility.kt`](../app/src/main/java/com/docscanai/capture/PagePlausibility.kt:64) scores coverage/aspect/angles/convexity/paper/text/edge; `score >= 0.55` and `text >= 0.30` ([`PagePlausibility.kt`](../app/src/main/java/com/docscanai/capture/PagePlausibility.kt:67)), with **hard** convexity and **55°–125°** corner-angle rules ([`PagePlausibility.kt`](../app/src/main/java/com/docscanai/capture/PagePlausibility.kt:225)). It is enforced fail-closed in [`FrameAnalyzer.kt`](../app/src/main/java/com/docscanai/capture/FrameAnalyzer.kt:111) and [`ScanPipeline.kt`](../app/src/main/java/com/docscanai/capture/ScanPipeline.kt:134) (a rejected quad becomes NO DETECTION, no warp). Measured: `syn_neg_clutter_0` 0.551/text 0.115, `syn_neg_clutter_1` 0.560/text 0.105, `real_a_woman…` minAng 41/maxAng 133 — all rejected; 19/20 benchmark positives still accepted (0.646–0.892). | Closed | — |
| G2 | **CLOSED at 0.3.2 — auto-capture requires a stable, gate-passing page.** [`AutoCaptureStability.kt`](../app/src/main/java/com/docscanai/capture/AutoCaptureStability.kt:78) requires **3 consecutive** gate-passing quads with IoU **≥ 0.85**; [`CaptureStateMachine.kt`](../app/src/main/java/com/docscanai/capture/CaptureStateMachine.kt:113) consults it before arming the shutter. The indicator is NONE / DOCUMENT_DETECTED / HOLD_STEADY ([`CaptureStateMachine.kt`](../app/src/main/java/com/docscanai/capture/CaptureStateMachine.kt:48)). The manual shutter bypasses gate, stability and cooldown ([`CaptureStateMachine.kt`](../app/src/main/java/com/docscanai/capture/CaptureStateMachine.kt:40)). Auto-capture remains ON by default, but `AutoCaptureGatingTest` proves a 30-frame clutter sequence never fires; the old re-fire-on-anything behaviour is gone. | Closed | — |
| G3 | **`bench_docunet_01` is never detected** (the only benchmark miss). | Medium | Inspect [`overlay_quad.png`](../tooling/out/h14_final/corpus/corpus_out/ai_on/images/bench_docunet_01/overlay_quad.png) for that id and lower the detector's confidence floor or add it to the regression set. |
| G4 | **MIXED corner placement on 16 of 40 GT images (40%)**, INSIDE on 24, OUTSIDE 0. Better than the baseline (19 MIXED) but still a systematic inward/outward bias on one or more corners. | Medium | Measure which corner is biased across the 16 MIXED cases and apply a per-corner offset, or feed the model a higher-resolution crop around the biased corner. |
| G5 | **13 images end with no detected quad.** 5 are legitimate full-frame pass-throughs (`syn_rectified_full_0..2`, `syn_light_brightness`, `syn_light_vignette`, all ≤ 0.05% corner error), **all 5 negatives are now correctly rejected** (2 never found a quad, 3 failed the gate), and **3 are true misses** (`bench_docunet_01`, `real_dr_deutsche_reichsbahn_computer_printed_ticket_a`, `real_us_navy_050420_n_3503m_001_lt_mark_hathcock_fill`). | Medium | Same fix path as G3; keep the pass-through cases as regression tests so a future detector that "fixes" them by locking a spurious quad is caught. |
| G6 | **CLOSED at 0.3.2 — on-device launch verified.** Signed release installed on `docscan_dbg_api35`, `pm clear`, cold launch (`Status: ok`, `TotalTime 1420 ms`), `versionCode=5`/`versionName=0.3.2`, crash scan 0 lines, screenshots captured, and the app logged `[Model] m2_dewarp_grid files=2 cached ok graph=243KB data=31.6MB`. Evidence: [`tooling/out/h14_final`](../tooling/out/h14_final). | Closed | — |
| G7 | **Upgrade-over-0.3.1 never exercised** — no 0.3.1 APK is kept on this machine. Certificate identity is proven, the actual `adb install -r` transition is not. | Medium | Keep the 0.3.2 APK next time; then `adb install -r` 0.3.3 over it and confirm `lastUpdateTime` changed without an uninstall. |
| G8 | **Native heap PSS unmeasured on device.** The 0.3.2 logcat high-water is 287.3 MB native (AI-ON), but that is not a `dumpsys meminfo` PSS. | Low | `adb shell dumpsys meminfo com.docscanai` after a 20-page batch. |
| G9 | **Viewer detail-tier numbers are not logged** by the test, only asserted. | Low | Add the measured long edge and Laplacian ratio to the test's report line. |
| G10 | **All timings are x86_64-emulator-only.** Dewarp (174.6 ms), detection (71.7 ms) and end-to-end (1,315 ms) on ARM will differ, likely slower. | Medium | Lane on the S25 FE (§7). |
| G11 | **Thermal behaviour unmeasured.** The 76-image corpus runs 100.1 s on an emulator with no thermal envelope. | Medium | 200-page soak on the S25 FE while sampling `dumpsys thermalservice`. |
| G12 | **Keystore custody.** `keystore.properties` and `keystore/docscan-release.jks` live only on this desktop and are git-ignored. Loss makes upgrades impossible. | High | Copy both into an offline password manager / encrypted vault and record the store/key passwords separately. |
| G13 | **No real positive photographs in the corpus.** Only 5 real photos exist and all 5 are negatives; there is zero evidence for real document photographs in normal lighting. | Medium | Add 10–12 genuinely real page photos (different lighting, shadows, curl) to the corpus manifest. |
| G14 | **AI-OFF is 12.4 s faster over the whole corpus** (87,737 ms vs 100,096 ms) while producing **5 fewer benchmark detections** (14/20 vs 19/20) and visibly worse output — the neural path is not free. | Low | Accept as the price of quality; document it so nobody "optimises" it away. |

---

## 7. What still must be validated on the S25 FE

A human can execute this list top to bottom; each step has a pass condition.

- [ ] **Install and version.** `adb install -r app-release.apk`, then
      `adb shell dumpsys package com.docscanai | findstr versionName versionCode` →
      expect `0.3.4` and `7`.
- [ ] **Cold launch, no models.** `adb shell pm clear com.docscanai`, launch, wait 8 s.
      Pass: the app reaches the camera screen with no `FATAL` / `AndroidRuntime` in
      `adb logcat -d`.
- [ ] **Model provisioning.** Complete the download, relaunch, scan one page.
      Pass: `[Model] m2_dewarp_grid files=2 ok` (fresh download) or
      `[Model] m2_dewarp_grid files=2 cached ok` (already on disk), plus `[Dewarp] uv=grid`.
- [ ] **Real-page detection (the evidence gap).** Photograph 10 real pages (A4 print, receipt,
      book page, low light, shadow, curled page). Pass: ≥ 9/10 detected, corner visually on the
      page.
- [ ] **Negative/clutter behaviour (G1 + G2).** Point the camera at a desk, a wall, a person, a
      window for 30 s each with auto-capture ON. Pass: **zero** captures. This is the exact case
      that failed here and is the highest-value manual check in this list.
- [ ] **Auto-capture sanity.** With a page in frame, confirm it captures once and does not
      re-fire while the page is stationary and already captured.
- [ ] **Output quality.** For 10 pages, confirm text is sharp at 100% zoom and page edges are
      straight. Pass: no visible keystone, no clipped edge.
- [ ] **Viewer detail tier.** Open a scan, pinch to 4×. Pass: text sharpens after the detail tier
      loads and does not stay soft at the 2048 px preview.
- [ ] **ARM timings.** Record capture → result time for 10 pages. Pass: < 1.5 s per page
      (spec §4.2). Compare against the emulator's 1,315 ms mean.
- [ ] **Thermal soak.** 200 pages back to back while sampling `dumpsys thermalservice`.
      Pass: no throttling-induced failure, no OOM kill.
- [ ] **Native heap.** `adb shell dumpsys meminfo com.docscanai` after the soak. Compare with the
      287.3 MB logcat high-water recorded here.
- [ ] **AI-OFF ONNX sessions.** With the AI toggle off, scan a page. Pass:
      `CORPUS_SESSIONS_AI_OFF`-equivalent session count stays 0 and no ONNX runtime loads.
- [ ] **Upgrade continuity.** With 0.3.1 installed and some saved pages, `adb install -r` 0.3.2.
      Pass: install succeeds, previously saved pages still open.
- [ ] **16 KB device.** Confirm the S25 FE image runs with 16 KB pages (Android 15+ 16 KB mode) and
      the app starts without a linker error.

---

## 8. Reproduction

All commands assume `cd /d C:\Users\Kuzey\Desktop\DocScanAI` and the environment
`JAVA_HOME=C:\Program Files\Java\jdk-21`, `ANDROID_HOME=C:\Users\Kuzey\AppData\Local\Android\Sdk`.

**Lane 1 — build both variants (one invocation, 57 s observed):**

```
gradlew.bat assembleDebug assembleRelease --no-daemon
```

**Lane 2 — 16 KB and signature proof:**

```
python tooling\check_16k.py app\build\outputs\apk\debug\app-debug.apk
python tooling\check_16k.py app\build\outputs\apk\release\app-release.apk
"%ANDROID_HOME%\build-tools\36.0.0\apksigner.bat" verify --print-certs --verbose app\build\outputs\apk\release\app-release.apk
"%ANDROID_HOME%\build-tools\36.0.0\aapt2.exe" dump badging app\build\outputs\apk\release\app-release.apk
```

Expected: `16/16` OK lines per APK, `v2 scheme: true`, certificate SHA-256 `7a69…53dc`,
`versionCode='7' versionName='0.3.4'`.

**Lane 3 — device install and launch (completed at 0.3.2 on the x86_64 emulator; see
`tooling/out/h14_final/release_launch.png` and `release_models.png`):**

```
emulator -avd docscan_dbg_api35 -no-snapshot-save -no-boot-anim
adb install -r app\build\outputs\apk\release\app-release.apk
adb shell pm clear com.docscanai
adb shell monkey -p com.docscanai -c android.intent.category.LAUNCHER 1
adb shell dumpsys package com.docscanai | findstr versionName versionCode
adb logcat -d | findstr /I "FATAL EXCEPTION Fatal-signal E-AndroidRuntime SIGSEGV SIGABRT"
adb exec-out screencap -p > tooling\out\h14_final\release_launch.png
```

**Lane 4 — full corpus (AI-ON and AI-OFF), 76 images, ~99 s:**

```
tooling\corpus_run.cmd        (wraps the instrumentation + artifact pull; see tooling\h7_tests.cmd)
```

Artifacts land in `tooling/out/<run>/corpus/corpus_out/`:
`corpus_report.json` (AI-ON), `ai_off/corpus_report_ai_off.json` (AI-OFF),
`ai_on/images/<id>/{input,raw,page,overlay_quad,overlay_input,gt_scan}.jpg`, plus
`logcat_ai_on.txt` and `logcat_ai_off.txt` with the `CORPUS_RUN …` summary lines.

**Lane 5 — regenerate every table in this document from the artifacts:**

```
python tooling\extract_results_h12.py
```

Writes into `tooling/out/h12_final/`:

| Output | Contents |
|---|---|
| `rows.json` | the 76 joined per-image rows used by every table |
| `rows.csv` | the same rows, flat, for spreadsheet inspection |
| `aggregates.json` | the final-column numbers in §1 |
| `baseline_h4.json` | the baseline-column numbers in §1, computed by the same code path |
| `table_benchmark.md`, `table_synthetic.md`, `table_negatives.md`, `table_worst10.md` | the tables spliced into §2–§5 |
| `corpus_scan.json` | inventory of every corpus report on disk with its group composition |
| `apksigner_release.txt`, `badging_release.txt` | signature and manifest proof |
| `build_h12.log`, `progress.txt` | build log and timestamped progress |

§2–§3 are generated by that script from the `corpus_report.json` and
`corpus_report_ai_off.json` artifacts. The headline table (§1), the negatives table (§4) and the
known-gaps table (§6) were updated from the H14 run (`tooling/out/h14_final/corpus`) for the
capture-gating release; they are still derived from the same report fields, not hand-invented.

---

## 8. Scope decisions (product owner, 0.3.2)

These are deliberate scope calls, not measurement failures. They are recorded here so the next
task does not treat them as regressions to "fix":

1. **The AI path is the product.** The neural detector (AI-ON) is what ships and it detects well;
   this release verifies that path (19/20 benchmark, 0/5 negatives through the gate).
2. **The classical path (AI-OFF) does not have to work well.** It exists only as a fallback and as
   a differential signal in the corpus (it is what proves the neural path is doing the work). Its
   AI-OFF numbers in §1–§3 are informational. The AI-OFF benchmark dip 15/20 → 14/20 is an
   accepted consequence of the new 55–125° angle rule, not a release blocker.
3. **ADDRESSED in 0.3.3 (H15) — grid over-correction.** On real captures the grid dewarp used to
   run at 100 % and distorted geometry / damaged glyphs. H15 measures the residual against the pure
   homography and applies it adaptively: strength 0 below 1.5 % mean residual, ramp to 1.0 at 5 %,
   and a 6 % per-vector clamp ([`WarpPolicy.kt`](../app/src/main/java/com/docscanai/imaging/WarpPolicy.kt:35)),
   applied to the sampling map with one remap from the original still. Flat pages now get the
   homography only; curved pages still get the correction (curved synthetic gain +11 %). See §0.
4. **Thumb/finger removal is out of scope.** It does not really work today and is not required.

Anything in §6 (known gaps) that is only about AI-OFF fallback quality or thumb removal can be
closed as "won't do" rather than carried forward.


---

## 9. H31 — M-LSD made affordable (0.3.9 / versionCode 12)

H30 proved the merge repair fixes the facing-page merge but left the feature OFF because it cost
**+73.68 ms p50 / +99.39 ms p95** on a preview frame. H31 does not make the model cheaper; it stops
calling it when it cannot help, and proves the **average over a frame sequence** instead of the
worst-case per call.

> Priority order: 1) warping/geometry, 2) auto-capture, 3) additional features such as pencil/handwriting enhancement.

### Trigger design (each one measured)

| # | Trigger | Rule (shipped) | Measured effect |
|---|---------|----------------|-----------------|
| 1 | **Merge signature** | quad w/h in **[0.74, 1.75]** (a spread is ~1.42 w/h, a page 0.709) | catches all 7 merged fixtures (w/h 0.747–0.971); 1 harmless false positive among 11 clean (0.647–0.751) |
| 2 | **Motion / stability** | H21 motion guard (MAD > 0.06 ⇒ skip) + ≥2 stable frames | 40/200 frames moving (cost 0 by construction), 8 more counted `skipSettling` |
| 3 | **Cadence + cache** | 1 invocation per 24 eligible frames; fold cached as geometry and re-projected | 8 invocations / 200 frames = **4.0 %**, 144 cache hits; cache path is two lerps |
| 4 | **Explicit invalidation** | quad IoU < 0.90, TTL 1200 ms, signature lost (hysteresis 0.68), `reset()` | 4 counted reasons; the overlay keeps the repaired quad continuously (no flicker) |
| 5 | **Still path** | one attempt per capture, only when the signature is present | 2/9 stills paid, 7 paid nothing |
| 6 | **Session warm-up** | `ServiceLocator.warmMlsdModel()` on camera-screen open | cold capture 121.3 ms → warm 110.0 ms; the 24 ms session creation is off the critical path |

Rejected as default-on (kept implemented, OFF, and logged as such): **border-hug** (fires on every
preview frame including the clean controls) and **area fraction** (≥0.55 misses 6/7 merged; ≤0.45
fires on 8/18 including 6 clean). `evidence` (paper vs text disagreement) is a live-path
refinement and is OFF.

### Cost over a 200-frame sequence (not one call)

| sequence | invocations | mean extra | p95 extra | budget | verdict |
|----------|-------------|------------|-----------|--------|---------|
| spread scan (open book, 40 moving frames, new spread every 40) | **8 / 200 (4.0 %)** | **+1.793 ms** | **+17.960 ms** | +15 / +40 | PASS |
| clean single page (steady hand) | **0 / 200** | −0.454 ms | +14.371 ms (noise) | 0 | PASS |
| still path (9 captures) | 2/9 | — | p50 **+11.435 ms**, worst +82.375 | p50 +60 | PASS |

A clean single-page sequence is statistically indistinguishable from zero: **0 invocations**, mean
−0.45 ms (inside the ±1.0 ms paired-noise band), and every non-zero delta is measurement noise.

### Quality (18 book-spread fixtures, trigger enabled)

| | OFF (0.3.8) | REPAIR (shipped) | BOTH (measured) |
|---|---|---|---|
| detected | 18/18 | 18/18 | 18/18 |
| merged slivers | 7/12 | **2/12** | 2/12 |
| mean corner % diag | 3.9562 | **3.3495** | 3.3053 |
| mean IoU page | 0.8279 | **0.8593** | 0.8602 |

### "Do no harm" — the H30 OPEN item, closed

A repair may reach the warp only when it cuts a sliver of plausible size (**≥ 7.5 %** of the quad
width; every fold that helped cut 0.076–0.259, every fold that damaged a page cut 0.061–0.067)
**and** moves the quad **towards** the paper aspect (the same Gaussian prior the page gate uses).
Together with the w/h upper bound 1.75 this refused the only real damage observed (2 corpus images,
`syn_persp_*`, where a 6.7 % strip or a grown candidate pushed corner error 0.18 % → 1.78 %).

### Final decision

**`MlsdMode.REPAIR` ships.** `BOTH` is marginally better on the fixtures but its candidate branch
grew an already-correct quad on the corpus (corner 0.18 % → 2.30 %), and H30's own matrix has
`fuse_320` (repair + candidate) behind `merge_320` (repair) on both headline numbers. REPAIR can
only ever shrink a quad at a measured sliver — the failure under test.

Pins tightened in [`BookSpreadDetectionTest`](../app/src/androidTest/java/com/docscanai/BookSpreadDetectionTest.kt):
merged **≤ 2** (was 7), corner **≤ 3.5672 %** (was 3.97), mean IoU **≥ 0.8442** (new),
`PRODUCTION_MODE` **REPAIR** (was OFF), and `productionModeMatchesTheShippedDecision` also asserts
the shipped [`MlsdTrigger.Policy`](../app/src/main/java/com/docscanai/ml/MlsdTrigger.kt).

### No regression

h7 lane **12/12 classes PASS** (the harness enumerates 12, not 13 — measured, harness unchanged);
corpus AI-ON 19/20, AI-OFF 14/20, negatives 0/5 both, `distorted_suspect` 0, mean corner
**0.03440 %** (exactly the 0.3.8 baseline), placement **24/0/16**, sessions AI-ON 3 / AI-OFF 0,
no native signal; `check_16k` 16/16 release and debug. The corpus is deterministic: two runs of
the same binary differ in 0/40 corner-scored images.

Evidence: [`tooling/out/h31/SUMMARY.txt`](../tooling/out/h31/SUMMARY.txt), `SHIP.txt`,
`H31F/logcat.txt`, `corpus_h31e/`, `h7_h31.log`, `trigger_probe.md`, `jvm_trigger_tests.log`
(25/25 [`MlsdTriggerTest`](../app/src/test/java/com/docscanai/ml/MlsdTriggerTest.kt)).

---

# SUBTASK H32 — pick the page that is fully seen on a book spread (0.3.10 / versionCode 13)

## Owner directive (verbatim intent)

> "be sure that it can detect a page of a book when the other page is not fully seen so it
> detects the only one that is fully seen"

> "you can download data from net via tavily to test how the ai detects when there is a book with
> two pages shown when the user tries to just scan one of the pages"

**Real-data provenance rule (still binding, from H23 — restated here because it governed this
subtask's dataset):**
> Evaluation data must be **real, already-collected data fetched from the internet**, with a
> recorded licence, attribution and SHA-256. Synthetic data may supplement, never replace, the
> claim under test.

**Recorded priority order (unchanged, verbatim):**
> Priority order: 1) warping/geometry, 2) auto-capture, 3) additional features such as pencil/handwriting enhancement.

## The dataset (real photographs of real open books)

H29–H31 measured this failure on **synthetic** renders (`app/src/androidTest/assets/bookspread/`,
18 fixtures). H32 adds a **real** tier — photographs of real open books fetched from Wikimedia
Commons (the safest open-licence source: per-file machine-readable `extmetadata`, CC0/CC-BY/
CC-BY-SA/PD only, every NC licence rejected at fetch time). Tavily discovery is recorded in
[`tooling/out/h32/tavily_discovery.md`](../tooling/out/h32/tavily_discovery.md); the fetch lane is
[`tooling/h32_fetch_real.py`](../tooling/h32_fetch_real.py). Getty/Shutterstock/Alamy/Pinterest
were **excluded** (rights-managed, no open licence). An Openverse channel was attempted and
**excluded** (401/429, no anonymous access) — recorded honestly, not hidden.

| item | value |
|------|-------|
| source | Wikimedia Commons (`commons.wikimedia.org/w/api.php`), 18 recorded queries + 10 category probes |
| licences accepted | CC0, CC-BY, CC-BY-SA, PD (NC/non-commercial **rejected** at fetch) |
| candidates considered | 383 accepted, 90 rejected (licence/title/size) |
| originals cached | `E:\DocScanAI-cache\bookspread_real\original\` (off C:), 1280 px thumbs in `thumb\` |
| bundled subset | 16 images, ≤ 1400 px long edge, 5.07 MB total → `app/src/androidTest/assets/bookspread_real/` |
| manifest | [`real_bookspread_manifest.json`](../app/src/androidTest/assets/bookspread_real/real_bookspread_manifest.json) — per-image source URL, direct file URL, licence name + URL, attribution, byte size, SHA-256 (original + bundled rendition) |

Scene labels: `real_partial_left` (the owner case: one page fully seen, facing page cut at the
frame edge) **9**; `real_spread_full` (both pages seen — a single-page pick is legitimate either
way) **6**; `real_blank_control` (no text at all) **1**. Two candidates were **excluded with
reasons**: rb016 (no fully-seen page: truncated on three edges) and rb017 (two books at different
depths — ambiguous target). Exclusions are recorded in `gt_final.json`, never silently dropped.

**Annotation method (reproducible).** Ground truth is an annotation, not a measurement, and it is
independent of the detector under test. [`tooling/h32_grid.py`](../tooling/h32_grid.py) renders a
labelled 10 %/2 % coordinate grid per image; the agent reads each page corner to ~±1 % of the
frame **by vision**; quads are stored as frame fractions so the annotation is resolution-independent
(`gt_target_quad`, `gt_facing_quad`, `gt_alt_quad`). Each annotated quad was then re-rendered as a
verification overlay (`tooling/out/h32/gtcheck/`) and visually re-checked. Method recorded in the
manifest: `visual_fraction_annotation`.

## Red baseline (device, before any change)

Replay of the **real detector path** (corner model → gate → **classical fallback** →
`MlsdTrigger`/`MlsdMergeRepair`, `MlsdMode.REPAIR`, still hint) on the 16 real images, 0.3.9
binary — [`tooling/out/h32/H32RED/`](../tooling/out/h32/RED_BASELINE.md), per-image table kept
verbatim there. Summary (`H32_REAL_SUMMARY`):

    detected=14/16  wrongPage=3  merged=8  frameCut=7  meanCornerPct=18.0295  mlsdInvoked=4

Failure decomposition (measured, each line below became a STEP 3 item):

1. **The classical fallback bypassed the M-LSD repair entirely.** 6/8 merged and 2/3 wrong-page
   cases were `CLASSICAL_FALLBACK` quads returned raw by
   [`OpenCvQuadPageDetector.detect()`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt:214)
   — the rule never saw them.
2. **The fold search could not see a balanced-spread gutter.** Real gutters sit at 45–52 % of the
   quad width; the 5–45 % window plus "nearest-per-side" made the gutter invisible (rb009/rb012
   `no_interior_line`; rb015 kept a text-margin line at 8.8 % over the gutter at 42.8 %).
3. **Side selection was "larger half" only** — on a spread split the kept side could be the facing
   page (rb015 → wrong-page).
4. **Frame-cut quads were accepted** — 10/16 quads touch the border; a quad on the PARTIAL page is
   exactly the "other page is not fully seen" case.

Also measured (offline NN-path only, [`tooling/out/h32/RED_BASELINE_OFFLINE.md`](../tooling/out/h32/RED_BASELINE_OFFLINE.md)):
the corner model's heat peaks are ~0 on 13/16 real photos (museum glass-case, dark vellum, heavy
curvature) — a genuine detection-domain gap that the classical fallback rescues on device.

## STEP 3 — each fix measured separately (only measured wins shipped)

Every variant was measured offline on the real set (device input quads + offline M-LSD) AND on the
18 synthetic fixtures, then the winner re-measured on device. Numbers:
[`tooling/out/h32/policy_ab.json`](../tooling/out/h32/policy_ab.json).

| # | change | real merged | real wrong-page | synth merged/corner | verdict |
|---|--------|-------------|-----------------|---------------------|---------|
| red | shipped 0.3.9 | 8 | 3 | 2/12 · 3.35 | baseline |
| a | uniformity-along-the-fold (margin-gap) guard | 7 | 1 | 0/12 · 3.50 | **no effect → not shipped** |
| b/c | side selection: paper-prior + partial-page pixel cut-penalty | 2 | **0** | 0/12 · 3.50 | **shipped** (penalty alone measured NEUTRAL: `h32_nopen` ≡ `h32_v2_novalley` — kept, not claimed) |
| d | strip window 5–45 %→5–55 % on spreads + chord 0.80→0.70 + all-candidate folds | 2 | **0** | 0/12 · 3.50 | **shipped** (the enabling fix) |
| — | classical-quads reach the repair (the dominant lever) | — | — | — | **shipped** |
| — | brightness-valley fold fallback | 1 | 1 | 0/12 · 3.50 | **REJECTED** — traded a merge for a wrong-page (the worse failure) |
| — | chord 0.65 (`h32_v3`) | 1 | 1 | 0/12 · 3.50 | **REJECTED** — same trade |

The uniformity guard was the documented H31 open fix and it measured **no effect** on this data
(the margin-gap form and the text-line-width form both); it is not shipped and not claimed.

**Shipped rule** ([`MlsdMergeRepair.kt`](../app/src/main/java/com/docscanai/ml/MlsdMergeRepair.kt),
[`OpenCvQuadPageDetector.kt`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt),
[`MlsdTrigger.kt`](../app/src/main/java/com/docscanai/ml/MlsdTrigger.kt)):

* classical-fallback quads now pass through `applyMlsdEvidence` exactly like corner-model quads
  (the rule only shrinks at measured evidence, so this is safe by construction);
* `findAllMergeLines` evaluates **every** fold candidate, with the strip window widened to 55 %
  of the width on spread-signature quads (`w/h ≥ 1.10`) and the fold-line chord window 0.80 → 0.70;
* the kept half is chosen by the **paper-aspect prior** (the shape the page gate already prefers),
  with a −0.25 penalty when that half is a partial page (its unique frame border is still paper —
  [`borderIsPaper`](../app/src/main/java/com/docscanai/ml/MlsdMergeRepair.kt) is sibling-aware:
  shared top/bottom borders are ignored, which a unit test caught);
* the trigger band `aspectMax` 1.75 → 2.30 so real spread quads (measured 1.85–2.15 under
  perspective) fire at all — the H31 corpus guard (`asp=3.861`) is still refused at 2.30;
* [`MlsdMergeRepair.Fractional`](../app/src/main/java/com/docscanai/ml/MlsdMergeRepair.kt) now
  records the kept side so the cadence cache projection cannot flip a page after an app update.

## Gate (mechanical — every number)

| gate | target | measured | verdict |
|------|--------|----------|---------|
| **real: wrong-page picks** | 0 | **0/16** (H32GREEN5) | PASS |
| **real: merged (both pages returned)** | 0 (target) | **2/16** — rb012, rb022 (reasons below) | residual |
| real: detection | floor | 14/16 (rb014, rb028 = no-detection, a detection-domain gap) | residual |
| real: mean corner | ≤ 14.5 % pin | **13.0482 %** (red 18.0295 %) | PASS |
| synthetic: detection | 18/18 | 18/18 | PASS |
| synthetic: merged slivers | ≤ 2 | **1/12** (H31: 2) | PASS |
| synthetic: mean corner | ≤ 3.5672 % | **2.9694 %** (H31: 3.3495) | PASS |
| synthetic: mean IoU | ≥ 0.8442 | **0.8727** (H31: 0.8593) | PASS |
| latency: 200-frame mean / p95 | ≤ +15 / +40 ms | **+1.98 / +23.96 ms** (8 invocations, 4.0 %) | PASS |
| corpus AI-ON | ≥ 19/20 | 19/20 | PASS |
| corpus AI-OFF | ≥ 14/20 | 14/20 | PASS |
| corpus negatives | 0/5 both | 0/5 both | PASS |
| corpus `distorted_suspect` | 0 | 0 | PASS |
| corpus mean corner | ≤ 0.03440 % | 0.03440 % | PASS |
| corpus placement | 24/0/16 | 24/0/16 | PASS |
| corpus sessions / native | AI-ON > 0, AI-OFF 0, no crash | 3 / 0 / none | PASS |
| h7 instrumented classes | all PASS | **13/13** (incl. `RealBookSpreadDetectionTest`) | PASS |
| JVM tests | all PASS | **43/43** (`MlsdTriggerTest` 25/25 incl. the `asp=3.861` guard, `MlsdMergeRepairH32Test` 11/11, +7) | PASS |
| `check_16k` release / debug | 16/16 | 16/16 both | PASS |
| install + launch smoke | no crash | install rc=0, no crash markers, `h32_smoke.png` | PASS |

**Residual failures (reported, not hidden).** The two merged images and two no-detections stand:

* **rb012** (Galleria, partial-left): the gutter's chord is 0.74–0.83 and the 0.70 window +
  acceptance guards refuse every candidate; chord 0.65 fixes it but was measured to introduce a
  wrong-page elsewhere (`h32_v3`), and wrong-page is the primary gate.
* **rb022** (blank book): no text lines anywhere → the fold search has no evidence; the guarded
  brightness-valley fallback was measured to pick the wrong side and is rejected.
* **rb014** (horizontal-spine spread), **rb028** (low-light novel): no-detection — the corner
  model's heat peaks are ~0 *and* the classical fallback fails. A detection-domain gap outside the
  "which page" failure this subtask fixed; it cannot be a wrong page (no quad), and is pinned
  separately (`PIN_MIN_DETECTED = 0.875`) so it stays visible.

## Tests

New instrumented class [`RealBookSpreadDetectionTest`](../app/src/androidTest/java/com/docscanai/RealBookSpreadDetectionTest.kt)
(added to [`h7_tests.cmd`](../tooling/h7_tests.cmd) as the 13th class) replays the bundled real
images through the production detector and pins: `wrong-page ≤ 0` (the owner directive),
`merged ≤ 2` (with per-image reasons in the pin comment), `corner ≤ 14.5 %`, detection floor
0.875, and the blank-control contract (detected, never a wrong-page). New JVM class
[`MlsdMergeRepairH32Test`](../app/src/test/java/com/docscanai/ml/MlsdMergeRepairH32Test.kt) (11
tests) covers the adaptive window, all-candidate evaluation, the sibling-aware
[`borderIsPaper`](../app/src/main/java/com/docscanai/ml/MlsdMergeRepair.kt) cut test, the
paper-prior selection, and the `Fractional.keep` cache round-trip. `BookSpreadDetectionTest` pins
were **not** loosened: the synthetic set improved past them (1/12 merged, 2.9694 %, IoU 0.8727)
and the pins at the H31 floors still hold.

## Ship decision: **SHIP 0.3.10 / versionCode 13**

The measured-win rule is satisfied (chord/window/all-candidates + prior selection + classical
routing: wrong-page 3 → 0, merged 8 → 2, corner 18.03 → 13.05; synthetic improved on every headline;
every regression gate green; latency within budget). Residuals are reported above with reasons.

| artefact | value |
|----------|-------|
| release APK | `app/build/outputs/apk/release/app-release.apk`, **125,545,234 B**, SHA-256 `751a65832aa4fce9ffa485706a1cbd5574c1466d3645bd73481bd98d8b288963` |
| debug APK | 144,453,785 B, SHA-256 `f7e45871ff1d2252b2371175476e1d015d3ade05884942575e46732c87f12859` |
| badging | `versionCode='13' versionName='0.3.10'` (`aapt2 dump badging`) |
| certificate | **UNCHANGED** `7a6977076f2c16e1c521b9301339ec9196aaac6f6a7641d7ec3fc965854253dc`, DN `CN=DocScanAI, OU=Release, O=DocScanAI, L=Istanbul, C=TR` — asserted by [`h32_ship.cmd`](../tooling/h32_ship.cmd) (`h32_cert_check.py`, exit-code gated) |
| 16 KB page size | release 16/16, debug 16/16 |
| smoke | install rc=0, launch, no crash markers, `tooling/out/h32/h32_smoke.png` |

Evidence: [`tooling/out/h32/SUMMARY.txt`](../tooling/out/h32/SUMMARY.txt), `SHIP.txt`,
`RED_BASELINE.md` (device red, verbatim table), `H32RED/`, `H32GREEN3/`, `H32GREEN5/`,
`H32GATE/`, `policy_ab.json`, `gtcheck/`, `tavily_discovery.md`, `HEARTBEAT.txt`;
corpus `tooling/out/corpus_Cum23102026_22243456/` (`_corpus_gate_h32.py` → CORPUS GATE: PASS).

**Priority order: 1) warping/geometry, 2) auto-capture, 3) additional features such as pencil/handwriting enhancement.**

**H33 (0.3.11)** - "show what it sees": preview FOV == capture FOV (Preview + ImageCapture pinned to one 4:3 stream, `FIT_CENTER` letterbox, `PreviewFrameMapping.PREVIEW_ASPECT = 3/4` upright); overlay-vs-still residual 5.6e-17 frame fraction (`PreviewAlignmentTest`, 4/4 pass). Oracle harness `tooling/oracle/` (`oracle_query.py`/`oracle_batch.py`/`oracle_calibrate.py`) selftested offline; live Qwen3-VL-8B is loaded in LM Studio but intentionally NOT queried in this subtask - 111 owner photos waiting in `corpus_owner/`.

## H35 — the REAL in-app detector path on the owner photos (0.3.11, nothing shipped)

H34 measured a host mirror of the NN corner path only and reported **10/111 detected** ("peak gate
misses" on 101). That mirror had no [`detectClassical()`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt),
no [`PagePlausibility`](../app/src/main/java/com/docscanai/capture/PagePlausibility.kt) gate and no
M-LSD repair, so in-app recall was unknown. H35 replayed the **111 bundled owner photos**
(`assets/owner110/`, downscaled copies ≤ 1400 px, 25.3 MB; `corpus_owner/` originals untouched)
through the **real production path** in one instrumented run
([`Owner110RealPathTest`](../app/src/androidTest/java/com/docscanai/Owner110RealPathTest.kt)):
[`OpenCvQuadPageDetector.detect()`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt)
(NN corner → plausibility gate → classical fallback → `MlsdTrigger`/`MlsdMergeRepair`, production
`MlsdMode.REPAIR`, `Hint.STILL`) + the same still-path
[`PagePlausibility.evaluate(requireLineProof = false)`](../app/src/main/java/com/docscanai/capture/PagePlausibility.kt)
gate `ScanPipeline` applies. Scored against the H34 oracle pseudo-GT in the same pixel frame (raw
JPEG pixels, no EXIF transpose) with the **exact H34 definitions** — every IoU/corner number is a
**lower bound** because the oracle's corners are ~10 % of the diagonal off on real photos (H34
calibration: real-slice 18.17 % diag / IoU 0.414, corners **rejected** as ground truth).

| metric | real pipeline (H35) | NN-only mirror (H34) |
|--------|---------------------|----------------------|
| detected | **91/111** (29 `CORNER_MODEL` + 62 `CLASSICAL_FALLBACK`) | 10/111 |
| no-detection | 20 | 101 |
| wrong-page | 27 (all `CLASSICAL_FALLBACK`) | 1 |
| merged | 34 (all `CLASSICAL_FALLBACK`) | 0 |
| mean IoU (detected) | 0.4071 (lower bound) | 0.6708 (on 10) |
| mean corner % diag | 18.24 (lower bound) | 6.88 (on 10) |
| M-LSD invoked | 87/111 | n/a (not mirrored) |

**Recall verdict (STEP 2b): there is NO recall defect.** The classical fallback — which H34's mirror
lacked — turns 10/111 into 91/111. H34's "101 peak-gate misses" were a measurement artifact: the NN
misses fall through to `detectClassical()` exactly as designed, and 81 of the 101 are rescued.

**Peak distribution (the STEP 2a question).** The gate is *not* over-strict: the min of the four
corner peaks per photo is **< 0.05 on 73/111** (no corner response at all — genuinely not
recognisable pages at the 256² working resolution), 0.05–0.30 on 9, and ≥ 0.30 on 29 (which all
detect). The histogram (buckets 0.05 wide):

| 0–.05 | .05–.10 | .10–.15 | .15–.20 | .20–.25 | .25–.30 | .30–.35 | .35–.50 | .50–.70 | .70–1 |
|-------|---------|---------|---------|---------|---------|---------|---------|---------|-------|
| 73 | 4 | 2 | 1 | 1 | 1 | 3 | 3 | 9 | 14 |

A measurement-only probe (faithful mirror of the documented `preprocessForCornerModel`, the same
`max` peak statistic) was cross-checked against the production `corner detect` log: **82/82 exact
matches** — the histogram is the production peak, not an approximation.

**Named candidate fixes, measured one at a time (STEP 2c/2d) — none won, none landed:**

* **Lower the peak gate 0.30 → 0.20**: recovers **1** photo (`20261024_185748`, min-peak 0.258)
  out of 111 — named reason (the 0.20–0.30 band has exactly 1 resident) says the change is
  immaterial, so it is not made. All other NN misses are ≤ 0.06 and would need a ~20× relaxation.
* **Higher-resolution / centre-crop NN input**: the pinned `lcnet100` graph **rejects a 512 input**
  (`probe512 = rejected`, probed once at runtime) and centre-crop would change the frame the gate
  was calibrated on. Closed without a new model pin — out of scope for "measurement + targeted fix".
* **Frame-cut / frame-hug rejection on classical quads** (the named simple rule for the wrong-page
  class): measured — it removes **all 43** bad quads (27 wrong-page + 34 merged, overlapping) but
  **discards 15 clean classical detections** (91 → 33 detected). Fails the subtask's own keep
  criterion ("keep only what *increases* detection"), so it is **measured and REJECTED**, same
  trade pattern H32 recorded for its chord/window variants. A stricter
  `area_ratio ≥ 0.7` variant is no better (14 clean lost).
* **The 1 wrong-page case of H34 (`20261024_190249`)**: the real path returns a clean
  `CORNER_MODEL` quad (peaks 0.54–0.88) that does **not** frame-hug → `wrong_page = false` by the
  H34 definition; the oracle's own quad on this photo was flagged SUSPICIOUS in H34's vision pass
  (skewed, corners off-page). Not a rule gap — nothing to fix.

**Residual worst cases** (detected, lowest IoU vs the noisy oracle — lower bounds):
`185351` (0.000, classical, other), `190138`/`190145`/`190149`/`190151`/`190237` (0.000, classical,
table-edge-coincidence — the quad frame-hugs the table edge), `190256` (0.000, NN, other),
`190152` (0.008, second-page-showing), `190250` (0.021, second-page-showing), `190232` (0.041,
lighting). Category counts: other 65, second-page-showing 34, table-edge-coincidence 9,
lighting 3. The 20 no-detections are mostly `peak ≈ 0` on every channel (the NN has no corner
response) **and** the classical cues find nothing — a real detection-domain gap, reported as-is.

**Ship decision: NOTHING SHIPPED (0.3.11 / versionCode 14 unchanged).** No production source, model
pin, gate threshold or warp was touched. Per the brief, "if classical fallback already rescues most
… there is NO recall defect — say so and stop"; every named fix was measured and either immaterial
(+1 photo), impossible without a new model pin, or a recall *loss* (−58 detections). No threshold
fishing was performed; each measurement above has a named reason tied to the recorded distribution.
The new lane is a measurement asset only:
[`Owner110RealPathTest`](../app/src/androidTest/java/com/docscanai/Owner110RealPathTest.kt)
+ [`h35_lane.cmd`](../tooling/h35_lane.cmd)/[`h35_bundle.py`](../tooling/h35_bundle.py)/
[`h35_report.py`](../tooling/h35_report.py)/[`h35_analyze.py`](../tooling/h35_analyze.py)/
[`h35_framecut.py`](../tooling/h35_framecut.py). Evidence: [`tooling/out/h35/`](../tooling/out/h35/).
## H36 - SAM 2.1 segmentation oracle + classical-path quality (0.3.11, nothing shipped)

**STEP 0 - oracle swap: SAM 2.1 Hiera Large (measurement only) - VALIDATED, NOT ADOPTED.**
Pinned ONNX (onnx-community conversion of facebook/sam2.1-hiera-large) in ``E:\DocScanAI-cache\sam\`` (911 MB; URLs + SHA-256 in the header of ``tooling/h36_sam.py``), run via onnxruntime-directml (RX 9070 XT) with CPU fallback. Protocol: box prompt = Qwen page-choice box (full frame otherwise), largest PAPER-REGION mask (paper_score filter - a naive largest-mask pick returned the scene union), min-area-rect + per-edge line-fit corner refinement, spread-split page-choice rule (keep the fully visible half when a gutter dip is visible).

| set | n | corner err %% diag (gate) | IoU (gate) | verdict |
|---|---|---|---|---|
| synthetic bookspread | 18 | 3.51 (<= 3.0) | 0.855 (>= 0.85) | |
| real annotated | 16 | 16.98 (<= 8.0) | 0.517 (>= 0.50) | |
| | | | | **REJECT - not adopted as pseudo-GT** |

Falls short on BOTH gates (synthetic marginal, real decisive - page-choice on partial spreads: SAM's mask sees the whole spread). Per the brief: fall back to the H35 truth setup (hand GT where it exists); owner-110 truth-based metrics are skipped (its oracle - Qwen - is demoted and uncalibrated). SAM is retained ONLY as the subset selector (mask-contour bow = curvature; interior shadow_frac = adjacent-page shadow -> ``sam_gt.json``).

**STEP 1/2 - named fixes, one at a time (owner-110 real pipeline, H35 truth setup; baseline = H35: 91 detected / 27 wrong-page / 34 merged / 20 no-detection).**

| fix | tag | detected | wrong-page | merged | no-det | verdict |
|---|---|---|---|---|---|---|
| 1a+1b paper-interior + frame-contact rank | fixab | 91 | **18** | **23** | 20 | **survives** (27->18, 34->23, no recall loss) |
| 1c wider M-LSD fold window (1.10->0.74) | fixc | 91 | 18 | 23 | 20 | neutral - reverted |
| 1d H32 frame-cut pixel penalty in rank | fixd2 | 91 | 18 | 24 | 20 | merged +1 - rejected |
| 2e curve cue + curvature epsilon tail | fixe | **101** | 24 | 26 | **10** | +10 detections (the curved-page miss is real) but wrong/merged rise - fails the gate |
| 2f shadow-robust normalized-gradient band | fixf | 91 | 19 | 23 | 20 | wrong-page +1 - rejected |

Owner's two new failure classes: (a) bumpy/curved pages - confirmed as the dominant no-detection cause; fix 2e recovers 10 of the 20 no-detections (101/111 detected) but its detections land on the wrong page or merged at the same rate as the baseline (24/26), i.e. the recall gap is a QUALITY gap. (b) adjacent-page shadow - the normalized-gradient band adds no detections and flips one clean row to wrong-page; not a fix. The SAM-selected curved/shadow subsets and their per-fix detection counts: ``tooling/out/h36/h36_subsets.json``.

**Curated sets (final tree = fixes 1a+1b only, all pins PASS).**

| set | measured | gate |
|---|---|---|
| synthetic book-spread | detected 18/18, merged 1/12 | 18/18, merged <= 2 |
| corpus real book-spread (AI-ON) | detected 14/16 (rb014, rb028), wrong 0, merged 1 (rb012), corner 12.48 %% | det >= 14/16, wrong = 0, merged <= 2, corner <= 14.5 %% |

**GATE + SHIP DECISION: SHIP NOTHING.** Owner-110 targets were wrong-page <= 5, merged <= 10 - the best measured state (fixes 1a+1b) is 18/23, and the one fix that moves the recall needle (2e) fails the wrong/merged targets. No configuration in the measured family reaches the gate, so per the brief: ship nothing, report the numbers. 0.3.11 / versionCode 14 UNCHANGED (no release build, no version bump, no cert check needed). The full suite was not run - only the affected classes (``Owner110RealPathTest``, ``BookSpreadDetectionTest``, ``RealBookSpreadDetectionTest``), all green.

Artifacts: ``tooling/out/h36/`` (``sam_validate.json`` oracle validation, ``sam_gt.json`` subset metadata, ``report_*.md`` per-fix tables, ``h36_subsets.json``, ``SUMMARY.txt``); drivers ``tooling/h36_sam.py``, ``tooling/h36_lane.cmd``, ``tooling/h36_report.py``.

Subset counts (SAM-selected, ``tooling/out/h36/h36_subsets.json``): curved subset (bow >= 0.22, p75, n=27) 21/27 -> **23/27** detected with fix 2e (+2); shadow subset (shadow_frac >= 0.27, p75, n=28) 24/28 -> 27/28 with fix 2e (+3) but the shadow fix 2f gives 24/28 -> 24/28 (no improvement). So the curved class (owner class a) is measurably improved by 2e, and the shadow class (b) is NOT improved by its own fix.

### H38 — 5 new struggle photos (curvy book pages + dyed-paper class), 0.3.13 / versionCode 16

**Owner verdict on 0.3.12:** "hell a lot better at near edges and on the books". Remaining problems: (1) bumpy/curvy book pages (book curvature) sometimes not detected, (2) non-white pages (e.g. green paper) not detected — root-cause hint: paper-likeness assumed bright paper, (3) vertical whole-book capture lacks detection, (4) rare detector lockup after a miss -> a small reset button. 5 new struggle photos added to `corpus_owner/` (`20261025_13*`, TEST data, ~115 total → lane runs 116). Fixes are named constants + signals in hand-written code around the frozen ONNX models.

**Ground truth for the new-5 (measurement only).** The SAM 2.1 full-frame protocol from H36 is unusable here: on these photos the table is as bright/flat as the page, so `best_mask`'s largest-paper rule selects the table/background (all 5 quads came out near-full-frame, `tooling/out/h38/mask_cands/`); point-prompted SAM candidates still could not isolate the page on 3 of 5. GT corners were therefore read off 5 % graticules by Zoo vision (`tooling/h38_graticule.py`, same oracle class accepted in earlier subtasks), verified with overlays (`tooling/out/h38/gt_overlay/`) and patched into the three oracle manifests (`tooling/h38_patch_gt.py`). Targets = the photographed book page (photos 1–3) / the open book spread (photos 4–5).

**Fixes (one change, one re-measure; keep wins, revert failures).**

| fix | change | tag | detected | wrong | merged | new-5 | verdict |
|---|---|---|---|---|---|---|---|
| (b) hue-agnostic paper | `PagePlausibility` paper = uniformity + ink-contrast + ink-band (interior histogram, NO brightness/saturation/bright-mode); `paperInteriorOf` = uniformity + inkContrast + texture (no frame p95); `pageLikeness` = \|interior−surround\| either direction | fixb | 96 | **17** | 27 | **5/5, 0 wrong** | **kept** — the dyed-paper root cause; delivers all 5 new detections |
| (c) reset chain | `OpenCvQuadPageDetector.reset()` (→ `MlsdTrigger.Schedule.reset()`), `CaptureStateMachine.reset()` (→ `AutoCaptureStability.reset()` + cooldown + duplicate fingerprint), "Reset" button on `CameraScreen` | — | — | — | — | — | **kept**; `ResetChainWiringTest` PASS |
| (a) curve cue 2e | `CURVE_CUE=true` (corner-extremes cue + epsilon tail 0.085/0.12) WITH 1a+1b rank guards | fix | 108 | 24 | 29 | 5/5, 0 wrong | **reverted** — detection +12 but wrong 24>20, merged 29>25 (its extra candidates still win as wrong/merged on the owner-110 set) |
| (d) aspectMax | `MlsdTrigger.Policy.aspectMax` 2.30 → 3.0 (vertical whole-book lever; widens M-LSD split band) | fixd | 96 | 17 | 27 | 5/5, 0 wrong | neutral on lane — kept as the one-shot fix (d) |
| W_RANK_PAPER 0.50 | rank weight on the paper term | h38ship | 96 | 18 | 27 | 5/5, 1 wrong→fixed | reverted (wrong +1, merged flat) |
| MIN_SCORE 0.45 | let score-rejected page candidates reach the rank | h38min | 98 | 18 | 27 | 5/5, **1 wrong** | reverted (a low-score wrong quad won one new photo) |

**Final lane (`tooling/h38_lane.cmd h38fixd`, [`Owner110RealPathTest`](../app/src/androidTest/java/com/docscanai/Owner110RealPathTest.kt), 116 photos).** detected 96 (CORNER_MODEL 31 / CLASSICAL_FALLBACK 65), no-detection 20, wrong-page **17** (gate ≤20 ✓), merged **27** (gate ≤25, miss +2).

**The 5 new photos (gate: all 5 detected, wrong-page NOT).** 5/5 detected, **0 wrong-page** — 130818 correct-page IoU 0.603 / 9.0 %, 130821 merged 0.488 / 16.0 %, 131201 merged 0.459 / 14.6 %, 131246 correct-page IoU 0.840 / 2.7 % (whole book spread), 131249 merged 0.555 / 9.9 %. The 3 "merged" are page+table unions (area_ratio 1.7–2.2) whose page-only candidate exists and is rank-preferred (paper 0.94 vs union 0.76) but is score-rejected before ranking: the base score is area-biased (`W_AREA`), e.g. page `hough:0.471` vs union `otsu:0.851`, and `MIN_SCORE=0.55` filters it out. The one lever that would let it compete (`MIN_SCORE` 0.45) also lets wrong quads through (measured, reverted). That is the documented next-step for the merged class.

**GATE: new-5 requirement MET (5/5 detected, 0 wrong-page; merged OK per the gate). Aggregate: det 96 ✓, wrong 17 ✓, merged 27 ✗ (+2 over ≤25).** Per the standing rule ("keep wins, revert failures — never block the ship") the tree ships as-is; the merged miss is reported, not hidden. The class is the pre-existing `corner(no_interior_line)` union-merge population (24 of the 27 are old photos), not a regression from the shipped fixes.

**SHIP: 0.3.13 / versionCode 16** (from 0.3.12 / 15). debug + signed release built; `aapt2 dump badging` `versionCode='16' versionName='0.3.13'`; `apksigner verify --print-certs` cert SHA-256 `7a6977076f2c16e1c521b9301339ec9196aaac6f6a7641d7ec3fc965854253dc` (continuity OK); install + launch smoke OK, no crash markers (screenshot `tooling/out/h38/h38_launch_camera_screen.png`). Hand-over: `release/DocScanAI-0.3.13.apk` (125,561,618 B, SHA-256 `a53bd8f740c6d352c93d2d8a50f0ead01c92450a042db31db6a4b6261f8e5725`) + `release/SHA256.txt` + `release/INSTALL.txt`. Artifacts: `tooling/out/h38/` (per-tag reports `report_*.md/.json`, `sam_gt_new5.json`, `graticule/`, `gt_overlay/`, `SHIP.txt`, `SUMMARY.txt`); drivers `tooling/h38_assets.py`, `tooling/h38_gt5.py`, `tooling/h38_graticule.py`, `tooling/h38_patch_gt.py`, `tooling/h38_lane.cmd`, `tooling/h38_gate.py`, `tooling/h38_ship.cmd`.


### H40 â€” make the AI/ink evidence visible, and test the "bad ink map" hypothesis (diagnostics only, no version change)

**Owner report:** the curved-page attempt was rejected because "the AI was giving a bad ink map"; no artifact existed to check that claim.

**What was built (diagnostics, production untouched).**

* `tooling/h39_dump.cmd` (H39 lane) gained `DOCSCAN_TRACE_JSON_ONLY=1`: writes `trace.json` (corner heat peaks, quad, gate) for every photo and skips the PNG maps, so the whole owner set can be traced and pulled in one run. The raw-folder flatten step was also fixed for the case where `adb pull` lands the scene folders directly (it previously left `raw/` behind and the lane exited before writing the run summary).
* `TraceDump.imagesEnabled`: the switch behind that mode. Only image writes are skipped; the scalar trace is identical.
* `tooling/h40_ink_probe.py` â€” re-implements the gate's exact rectification + INK extraction in Python and runs it on the detected quad AND the oracle quad of the same photo.
* `tooling/h40_ink_report.py` â€” builds `docs/evidence/h39-ink/index.html`: per photo, `corner_input` / AI heat montage / final quad / rectified page / INK map / glyph boxes, plus the oracle-quad INK panel and the numbers.
* `tooling/h40_quad_ink_eval.py` â€” the same comparison over all 116 `owner110` photos, using the H38 real-path quads (`owner110_realpath.json`) and labels (`report_h38fixd.json`); writes `docs/evidence/quad-vs-ink/`.

**Measured answers (evidence in `docs/evidence/`).**

1. The INK map is **not** the AI: it is `adaptiveThreshold(MEAN_C, block 25, C 10)` on the rectified page (`PagePlausibility.analyseRectifiedInterior`). The AI's output is the four corner heat maps, dumped as `heat_c0..c3`.
2. Same photo, oracle (correct) quad vs detected quad, over the 34 bad quads of the 116-photo run: mean **text-score change = -0.183** â€” the wrong/merged region scores *higher* than the correct page. The text score saturates at 0.85-0.95 on any textured region.
3. No INK-side feature separates bad from good quads: `border_ink` AUC 0.462, `ink_ratio` 0.529, paper-mode fraction 0.431, paper p50 0.306 (`docs/evidence/quad-vs-ink/SUMMARY.md`). A margin/paper guard would reject good photos too.
4. Every bad quad (34) was `CLASSICAL_FALLBACK`; all 31 `CORNER_MODEL` quads were correct. No-detection photos (e.g. `20261024_185245`) have corner-heat peaks at ~0.002 â€” the model is blind there, not mis-thresholded; `20261024_185748` has 3/4 peaks >= 0.3 but one below the gate.
5. A candidate "reject the classical quad when 3+ confident heat peaks sit far from its corners" guard was evaluated on all 116 traced photos: the 7 classical quads with 3+ peaks (>= 0.20) split 4 good / 2 wrong / 1 merged, and the geometry does not separate them (good quads also have peaks 8-23 % of the diagonal from their corners). **Not implemented** â€” it would cost more good quads than it saves.

**Verdict:** the ink evidence cannot be the fix. The merged class is a candidate-selection problem in the classical fallback (page+table union wins the rank and is score-accepted before ranking; see the H38 note above). This subtask ships no detector behaviour change; it ships the harness and the visual proof so the next attempt is measured against the right thing.

**Also fixed (diagnostics correctness).** Three harness bugs made the evidence itself unreliable; all three are fixed and verified on lane tag `h40v8` (12 photos, `LANE_DONE`, RC=0):

* `OpenCvQuadPageDetector.lastMlsdLog` is now cleared at the start of every `detect()` (it was reset only by `reset()`), so a trace scene no longer carries the previous photo's `[MLSD]` line.
* **Junk files at the repo root** (`1`, `AI`, `the`, `uninstall`, `DetectionRegressionTest`, ...): every lane's heartbeat used `echo ... %~1>> "file"`. When the message contained a `>`, as in `app install rc=1 -> uninstall + retry`, batch parsed the rest as a redirect and wrote a file named after the next word. The message is now assigned to a quoted variable and echoed through delayed expansion (8 lanes + `h7_tests.cmd`). Reproduced and verified: the new form leaves no stray file.
* **The H39 lane aborted after the pull on every run.** `echo LANE_FAILED tag=%TAG% (no trace pulled)` inside an `if not exist (...)` block: a bare `)` in an `echo` inside a block is counted as the block terminator, so cmd aborted the script with errorlevel 1 even when the branch was skipped, leaving `raw/` behind and never writing sheets or `RUN.txt`. Replaced with a `goto no_trace` label. This is why the earlier `h40all` run had to be flattened by hand.
