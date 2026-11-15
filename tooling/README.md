# DocScan AI — tooling (`tooling/`)

Everything needed to **build → install → provision → run → collect evidence → tear down** the app on
an emulator or a real phone, without unbounded waits and without touching unrelated processes.

All lanes are plain `cmd.exe` scripts. They set `JAVA_HOME` / `ANDROID_HOME` inline (neither is a
global env var on this machine), run Gradle with `--no-daemon`, and take their budgets from constants
at the top of each script.

| Item | Value |
|---|---|
| JDK (runs Gradle) | `C:\Program Files\Java\jdk-21` (app bytecode target stays JVM 17) |
| Android SDK | `C:\Users\Kuzey\AppData\Local\Android\Sdk` (junction → `E:\DocScanAI-cache\android-sdk`) |
| adb | `%ANDROID_HOME%\platform-tools\adb.exe` |
| emulator | `%ANDROID_HOME%\emulator\emulator.exe` |
| app package | `com.docscanai` |
| test package | `com.docscanai.test` |
| runner | `com.docscanai.test/androidx.test.runner.AndroidJUnitRunner` |
| version | `versionName 0.3.6` / `versionCode 9` |

---

## 1. Lanes at a glance

| Lane | What it runs | Default AVD | Exit codes |
|---|---|---|---|
| [`corpus_run.cmd`](corpus_run.cmd) | `CorpusRealLifeTest#corpus_ai_on` then `#corpus_ai_off`, pulls artifacts + benchmark + gallery | `docscan_dbg_api35` | 0 PASS / 1 FAIL / 2 INVALID / 3 INFRA |
| [`h7_tests.cmd`](h7_tests.cmd) | `DetectionRegressionTest`, `DewarpFidelityTest`, `NeuralPathReproTest`, `NeuralPathXnnpackTest`, `PageViewerUiTest`, `DetectorLatchRuntimeTest`, `AutoCaptureGatingTest`, `LiveCapturePathTest`, `LiveRoomNegativeTest`, `AutoCaptureDuplicateTest`, `RefineSeedGuardTest` (one process each) | `docscan_dbg_api35` | 0 / 1 / 3 |
| [`h26_lane.cmd`](h26_lane.cmd) | Any subset of instrumented classes on the *already running* AVD, writing `tooling/out/h26/<Tag>/<Class>.txt` + `logcat.txt` (used for the H26 red/green runs) | existing device | 0 / 1 |
| [`h26_smoke.cmd`](h26_smoke.cmd) | Boots the AVD, installs a given APK (default: the release APK), launches it, screenshots, graceful `adb emu kill` | `docscan_dbg_api35` | 0 / 1 / 3 |
| [`e2e.cmd`](e2e.cmd) | `NeuralPathReproTest` (CPU EP) then `NeuralPathXnnpackTest` (XNNPACK) | `docscan_test` (override!) | 0 / 1 / 2 / 3 |
| [`ai_off_check.cmd`](ai_off_check.cmd) | `PipelineInstrumentedTest#pipeline_neuralDisabled_usesClassicalPath`; asserts **0** `session EP=` lines | `docscan_dbg_api35` | 0 / 1 / 3 |
| [`subtask_f_check.cmd`](subtask_f_check.cmd) | `PageViewerUiTest` + `DetectorLatchRuntimeTest` (latch markers vs `session EP=` ordering) | `docscan_dbg_api35` | 0 / 1 / 2 / 3 |
| [`check_16k.py`](check_16k.py) | Reads `PT_LOAD p_align` from every `.so` inside an APK/AAR/dir and prints `16KB-OK` / `NOT-16KB` | — | 0 all OK / non-zero otherwise |
| [`h15_warp_summary.py`](h15_warp_summary.py) | Aggregates `[Warp] residual` / `[Warp] strength` lines + guardrail metrics from a corpus run | — | 0 |
| [`h16_bench.cmd`](h16_bench.cmd) | `WarpGeometryBenchTest` — same-build variants (`raw`/`poly2`/`poly3`/`bicubic` × refine on/off), pulls `geom_out/<variant>/<image>/` | `docscan_dbg_api35` | 0 / 1 / 3 |
| [`h16_geometry.py`](h16_geometry.py) | Measures one variant: border RMS, text-line RMS/angle, Laplacian sharpness, PSNR/SSIM → per-image CSV | — | 0 / 2 |
| [`h16_compare.py`](h16_compare.py) | A/B deltas between two `h16_geometry.py` tables (`bench_` rows, detected only) | — | 0 |
| [`h23_fetch_real.py`](h23_fetch_real.py) | Wikimedia Commons search → `queries.json` + `candidates.json`; `download` pulls 1200 px thumbs to `E:\DocScanAI-cache\pencil\original\` with SHA-256; `sheet` writes the pool contact sheet | — | 0 / 2 |
| [`h23_prepare_assets.py`](h23_prepare_assets.py) | Selects ≥10 **distinct real** pencil pages from the vetted pool, downscales to ≤1400 px, writes `assets/pencil/pencil_real_XX.jpg` + `pencil_manifest.json` (licence + both SHA-256s) | — | 0 / 2 |
| `HandwritingEnhanceTest` (via `adb shell am instrument`) | Runs the **production** `HandwritingEnhancer` (FULL + CLAHE-only) on the real pages and the normal corpus; writes `h23_results.json`, `pairs/*_{in,full,clahe}.jpg`, latency samples | `docscan_dbg_api35` (booted) | 0 / non-zero |
| [`h23_summary.py`](h23_summary.py) | Per-page table, means, and the mechanical keep/revert against the H23 gates; reads the newest `h23_results.json` under `tooling/out/h23/` | — | 0 |
| [`h23_sheets.py`](h23_sheets.py) | Per-page input / FULL / CLAHE contact rows, 2× centre-crop zooms, `overview_all_pages.jpg` | — | 0 |
| [`h24_reconcile.py`](h24_reconcile.py) | Reconciles benchmark `detected` per image across N corpus runs (both report schemas), emits `flip_table.md` / `.csv` / `flips.txt` + run counts | — | 0 / 2 |
| [`h25_lane.cmd`](h25_lane.cmd) | H25 measurement lane: runs `HandwritingEnhanceTest` (the production `HandwritingEnhancer`, `Variant.RIDGE` candidate + FULL baseline) on the 12 real pencil pages and the normal corpus, pulls `h25_results.json` + in/ridge pairs into `tooling\out\h25\<device_TS>\` | `docscan_dbg_api35` (booted) | 0 / non-zero |
| [`h25_table.py`](h25_table.py) | Per-page table + means + min/max for contrast, noise, PSNR, sharpness, components; `suggested` count; normal-page means and worst PSNR; latency line; the gates dict. Reads the newest `h25_results.json` under `tooling/out/h25/` | — | 0 |
| [`h25_sheets.py`](h25_sheets.py) | Per-page input / RIDGE contact rows, 2× centre-crop zooms, `overview_all_pages.jpg` (suffix `ridge`, H25 dir) | — | 0 |
| [`h25_warp_timing.py`](h25_warp_timing.py) | Warp-time attribution from `corpus_report_ai_on.csv`: all-pages vs excluding-first percentile stats, first-page line, slowest 6, pages ≥400 ms, "first page is max" verdict, first-vs-warm ratio, output-size quartile medians. Explains the 239/453/669 ms median/p95/max without touching warp code | — | 0 |
| [`h27_boot.cmd`](h27_boot.cmd) | Idempotent boot of the AVD for the H27 lanes: short-circuits when `adb devices` already shows an emulator (`emulator already running`), otherwise boots and polls `sys.boot_completed` | `docscan_dbg_api35` | 0 / 2 (no device) |
| [`h27_lane.cmd`](h27_lane.cmd) | H27 lane: one or more instrumented classes on the running AVD into `tooling/out/h27/<Tag>/<Class>.txt` + `logcat.txt` (used for all warp-determinism and refine-pixel runs) | existing device | 0 / 1 |
| [`h27_bw.cmd`](h27_bw.cmd) | H27 B&W measurement lane: runs `BwSharpnessTest` with `-e tag BEFORE|AFTER`, pulls `files/h27_bw/<tag>/metrics.txt` + the before/after contact sheet into `tooling/out/h27/BW_<TAG>/`, and requires the `before` folder left by the previous `BEFORE` run to build the sheet | existing device | 0 / 1 / 2 |
| [`h27_smoke.cmd`](h27_smoke.cmd) | H27 release launch smoke: boots the AVD if needed, installs the signed release APK, launches it, captures `launch_smoke.png`, the resumed activity, the installed version and `AndroidRuntime:E`, then tears the emulator down | `docscan_dbg_api35` | 0 / 1 (install failed) / 3 (boot timeout or APK missing) |

> **Use `docscan_dbg_api35`.** `docscan_test` (API 37) blocks `run-as` and `adb root`, so model
> provisioning and crash-artifact pulls fail there. `docscan_dbg_api35` supports both.

Common per-lane env vars:

| Var | Meaning |
|---|---|
| `DOCSCAN_AVD` | AVD to boot (defaults shown above) |
| `DOCSCAN_SERIAL` | set ⇒ **device mode**: emulators are neither booted nor killed; every `adb` call gets `-s <serial>` |
| `DOCSCAN_MODELS_DIR` | local model source dir (default `<repo>\..\dsmodels`) |
| `DOCSCAN_RUN_DIR` | pin the run folder (corpus + h7 lanes) |
| `DOCSCAN_EMULATOR_TIMEOUT` | boot wait in seconds (e2e / ai_off / subtask_f; default 300) |
| `DOCSCAN_WIPE_DATA` | e2e only; `1` boots with `-wipe-data` |
| `DOCSCAN_KEEP_ADB_SERVER` | e2e only; leave `adb` server running at teardown |

### Bounded budgets

| Lane | Step | Build | Boot | Suite | Per mode |
|---|---|---|---|---|---|
| `corpus_run.cmd` | 120 s | 600 s | 180 s | 900 s | ON 780 s / OFF 600 s |
| `h7_tests.cmd` | 180 s | 600 s | 120 s (`:waitboot` × 2 s) | 900 s | — |
| `e2e.cmd` | — | unbounded (logged) | 300 s | — | — |

Every long step in `corpus_run.cmd` / `h7_tests.cmd` runs through [`_exec_watchdog.vbs`](_exec_watchdog.vbs).

### Reconciling a detection-count contradiction (H24 procedure)

Two lanes can legitimately disagree on "detected" and both be "correct" for their own code path. Before
believing any regression, reconcile per image:

1. **Pin the definition.** `benchDetected` is `[Detect] … found=true` parsed from the per-image log in
   `CorpusRealLifeTest` (`val found = docLines.any { it.contains("[Detect]") && it.contains("found=true") }`).
   If a lane does not use that string, it is not the same metric.
2. **Record the lane, not just the number.** A test that calls `PagePlausibility.evaluate(...)` **without**
   provenance is evaluating the `UNKNOWN` path — it will skip the classical-quad text-line proof and report a
   higher count than production. H21's `19/20` was exactly this; the production corpus lane on the same tree
   was `17/20`.
3. **Run the reconciler.** Edit the `RUNS = [(label, path), …]` list at the top of
   [`h24_reconcile.py`](h24_reconcile.py) so it names the runs under comparison, then:
   ```cmd
   py -3 tooling\h24_reconcile.py
   ```
   It reads `benchmark_report.json` from each run (both the `{images:[…]}` and `{reports:[…]}` schemas),
   prints run counts, and writes `tooling/out/h24/flip_table.md`, `flip_table.csv`, `flips.txt`.
4. **Only flip-table deltas are regressions.** In H24 the only flips were `bench_docunet_08` +
   `bench_docunet_62` (AI-ON) and the 14 classical-fallback pages (AI-OFF).
5. **Reproduce one lost page alone**, then diff its `[OpenCvPageDetector]` / `[Gate]` / `[Detect]` lines
   between the two trees. If the detector lines are identical and only `pass` changed, the bug is in the
   gate, not in detection.
6. **Lane-path caveat:** `BenchmarkArtifacts.java` resolves `tooling\out\<runId>`. For a nested run folder pass
   the **relative path** as the run id (e.g. `h24/corpus_fixed`), not the bare basename — otherwise it fails
   with `FATAL: tooling\out\corpus_fixed\corpus_out not found` and no `benchmark_report.json` is written.

### H23 — pencil/handwriting lane (measured, feature NOT shipped)

The lane exists to answer one question on **real** data: does the opt-in handwriting stage meet its
gates? The answer recorded in H23 is **no** (see
[`docs/HANDWRITING_ENHANCEMENT.md`](../docs/HANDWRITING_ENHANCEMENT.md) §6), so the flag stays off by
default and the edit-screen affordance is only rendered when `handwritingEnhanceEnabled` is on.

```bat
REM provenance lane (artifacts already committed under tooling\out\h23\)
py -3 tooling\h23_fetch_real.py search
py -3 tooling\h23_fetch_real.py download --limit 38
py -3 tooling\h23_fetch_real.py sheet
py -3 tooling\h23_prepare_assets.py

REM measurement lane (boot docscan_dbg_api35 first; APKs must be installed)
"C:\Users\Kuzey\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell am instrument -w ^
  -e class com.docscanai.HandwritingEnhanceTest com.docscanai.test/androidx.test.runner.AndroidJUnitRunner
"C:\Users\Kuzey\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 pull ^
  /storage/emulated/0/Android/data/com.docscanai/files/h23 tooling\out\h23\deviceN

REM tables + sheets
py -3 tooling\h23_summary.py
py -3 tooling\h23_sheets.py
```

### H25 — handwriting attempt 2 (reuse-first), and warp-time attribution

The H25 run reused the H23 assets bit-for-bit (same 12 `pencil_real_XX.jpg`, same masks, same normal
corpus), so the only variable is the candidate. Reproduce with:

```bat
REM 1) measurement lane (boot docscan_dbg_api35 first; debug + androidTest APKs installed)
tooling\h25_lane.cmd

REM 2) tables, sheets, and the warp attribution (reads the newest h25_results.json)
py -3 tooling\h25_table.py
py -3 tooling\h25_sheets.py

REM 3) warp timing (needs a finished corpus_run.cmd run; DOCSCAN_RUN_DIR pins the evidence dir)
set "DOCSCAN_RUN_DIR=tooling\out\h25\corpus2"
tooling\corpus_run.cmd
py -3 tooling\h25_warp_timing.py tooling\out\h25\corpus2\corpus_out\ai_on
```

**Reading the output.** `h25_table.py` prints one pipe row per page and then the means; the gate is
mechanical — contrast ≥ +30 %, blank noise ≤ +10 %, non-stroke PSNR ≥ 30 dB, sharpness not down,
components not up, latency ≤ 120 ms median at 2000 px. `h25_warp_timing.py` answers "cold start or
per-page outlier?" — in H25 the answer was **per-page outlier** (max `bench_docunet_62` 664 ms,
detect only 98 ms), with cold start explaining only the elevated first page.

**Result of record (H25):** the candidate failed 4 of 6 gates (+5.27 % contrast, 27.75 dB PSNR,
+128 % components, 142.5 ms), so `Variant.FULL` stayed the production default and 0.3.6 / vc 9 was
not rebuilt. Evidence and the green/red verdict: `tooling/out/h25/SUMMARY.txt`.

Two traps worth knowing, both hit during H23:

* `adb pull` creates `deviceN\h23\` when the destination already exists — always pull into a fresh
  `deviceN`, and let `h23_summary.py` pick the newest `h23_results.json` instead of hard-coding a path.
* `python -c "..."` with a newline breaks under `cmd.exe`; use a script file for anything longer than
  one statement.

**The "very faint" augmentation tier.** [`h22_pencil_gen.py`](h22_pencil_gen.py) is no longer a
fixture source. It now takes the real pages as `--src` (the default), writes to
`E:\DocScanAI-cache\pencil\very_faint\` (never into the APK unless `--allow-assets`), names its
output `pencil_faint_XX.jpg`, and stamps every manifest entry with `"tier": "very faint"`,
`"synthetic": true`, `"real_data": false` and `derived_from {asset, sha256, source_page, licence,
licence_url}`. Report any number from it as "augmented from real page X". Its H22 stroke-ratio band
check is now opt-in (`--strict-band`) because a deliberately fainter tier is supposed to sit below it.

### H26 — the seeded `refine()` guard (`RefineSeedGuardTest`)

`refine(bitmap, seed)` used to crop a 1.15× box around the seed, re-detect inside it and accept
whatever 4-gon won **without comparing it with the seed** — so on a page whose border sits at (or
outside) the crop edge, an inner text block replaced a correct seed. That is the owner's "captured /
ORIGINAL page is a sub-rectangle" bug (`docs/RESULTS.md` H26, diagnosis in
`tooling/out/h25/CAPTURE_BUG_DIAGNOSIS.md`). The guard now validates the winner and falls back to the
seed, `ScanSessionViewModel.reEnhance()` passes `seed = null`, and `SeedMapping` maps the live quad
onto the still through the analysis/still aspect transform.

Run it (either lane works; the class is now part of `h7_tests.cmd` too):

```bat
cd /d C:\Users\Kuzey\Desktop\DocScanAI
rem focused, on an AVD that is already up:
tooling\h26_lane.cmd GREEN RefineSeedGuardTest LiveCapturePathTest
rem full lane (boots/builds/installs/provisions, then all 11 classes):
set "DOCSCAN_RUN_DIR=%CD%\tooling\out\h26\h7_final"
tooling\h7_tests.cmd
```

**How the trap is reproduced** (`trap_pageBorderOutsideCrop_seedIsKept`, no new fixtures): the
benchmark asset `testdocs/test_page.jpg` is pasted **full-bleed** into a 1000×1300 camera frame (so the
page's true border *is* the frame) and a strong dark inner block is drawn at a 0.25 margin with ruled
lines inside it. The seed is `BitmapUtils.fullFrameQuad`, i.e. the correct border. Inside `refine()`'s
1.15× crop the only 4-gon is that inner block, which is exactly the field situation "the page border is
outside the crop". Assertions: `IoU(refined, seed) >= 0.85` and `area ratio in [0.9, 1.1]`; three
chained passes must not shrink further. On the broken tree this read
`IoU=0.5293` (`tooling/out/h26/RED/RefineSeedGuardTest.txt`, verbatim):

```
java.lang.AssertionError: refine() must keep the seed: IoU(refined, seed)=0.5293 < 0.85 (refined=(47,107),(693,137),(688,1181),(36,1183) seed=(0,0),(1000,0),(1000,1300),(0,1300))
java.lang.AssertionError: a repeated seeded pass must not leave the original seed's quad: IoU=0.5293 < 0.85
java.lang.AssertionError: Edit -> ORIGINAL must not change the page geometry: border-band RMS=71.1450 > 8 grey levels (maxAbs=255.0000)
```

On the fixed tree every decision is logged, one line per `refine()` call:

```
D/DocScan: [OpenCvPageDetector] [Refine] seedIou=0.5293 area=0.5293 inset=0.1372 accept=false reason=iou
D/DocScan: [OpenCvPageDetector] [Refine] seedIou=0.9169 area=0.9174 inset=-1.0000 accept=true reason=ok
```

`reason` is one of `ok`, `iou`, `area_small`, `area_large`, `contained`, `degenerate`, `error`
(`seedDecision()` in
[`OpenCvQuadPageDetector.kt`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt:1004));
`inset=-1` means "not contained in the seed". Two notes for whoever touches this next:

* The test also checks the reprocess chain through the **production** `ScanSessionViewModel.reEnhance()`
  (`cornerIou >= 0.95`, `areaRatio in [0.95,1.05]`, no corner moved more than 1 % of the short side,
  warp output size ratio within ±10 %) because the geometry is what the user sees; the raw border-band
  RMS is kept as a diagnostic only. H26 wrote here that the second-stage UVDoc grid dewarp "is not
  bit-stable (same quad → 258,204 B and 288,331 B)"; **H27 disproved that**: the warp is bit-identical
  for identical input, and those two sizes are two different inputs (a re-warp of the stored raw
  capture vs the delivered reprocess). See §5 below.
* The import path was never broken and must stay that way: `importPath_detectionUnchanged` asserts the
  stored quad equals `detect()` exactly.

### H27 — warp determinism + B&W calibration

**Why.** H26 could only gate geometry because a pixel comparison could not tell "same page,
re-registered" from "different page region" while the grid dewarp might not be reproducible.

**What changed in production.**
[`ServiceLocator.dewarpGridSessionFactory`](../app/src/main/java/com/docscanai/core/di/ServiceLocator.kt)
is a dedicated ORT session for `UVDoc_grid.onnx` with `setDeterministicCompute(true)` (XNNPACK off);
`createDewarpGridModel()` loads through it. Measured cost: grid graph 87 → 94 ms, delivered warp
558 → 563 ms (+1.2 %). B&W mode got `BW_SOFTEN_SIGMA = 0.4` — a Gaussian anti-alias of the 1-bit
Sauvola output in
[`ClassicalEnhancer.binarize()`](../app/src/main/java/com/docscanai/imaging/ClassicalEnhancer.kt:340).

**Lanes.**

```bat
cd /d C:\Users\Kuzey\Desktop\DocScanAI
tooling\h27_boot.cmd
rem determinism: N runs x 4 session configs in-session, then a second process for the cross-session check
tooling\h27_lane.cmd DET1 WarpDeterminismTest
tooling\h27_lane.cmd DET2 WarpDeterminismTest
rem the Edit -> ORIGINAL pixel identity (part of the 11-class lane too)
tooling\h27_lane.cmd REFINE5 RefineSeedGuardTest
rem B&W: baseline, then the candidate, which also writes the before/after contact sheet
tooling\h27_bw.cmd BEFORE
tooling\h27_bw.cmd AFTER
rem release launch smoke (boots/tears down the AVD itself)
tooling\h27_smoke.cmd
```

**What the determinism test asserts.** Identical grid floats (SHA-256 over the `[1,2,H,W]` float
array), identical page RGBA and identical quality-92 JPEG bytes at N = 5 for each of four session
configurations (baseline, deterministic, intra-op = 1, intra-op = 1 + deterministic), **and**
equality with the record written by a previous process for the same page size and model length.
Pinning one thread was rejected: same bytes, 3.3× the latency.

**What the pixel identity asserts, and what it deliberately does not.** Two runs of the production
call over the same bitmap must give the same file SHA-256 and pixel MAE 0.0
(`REFINE_GUARD c=determinism …`), and the same holds for the stored raw capture that `reEnhance`
re-warps. Import vs Edit → ORIGINAL is **not** asserted to be pixel-equal: `reEnhance` warps
`page.rawFile` (a JPEG re-encode), the corner model re-detects a quad up to 0.76 % different, and the
warp is a pure function of (bitmap, quad, page size) — 34.1 grey levels of measured displacement,
which no low-pass or ink-overlap metric separates from the H26 defect class (calibration in
`tooling/out/h27/pix_metric.py`). The geometry assertions bound that displacement instead.

**B&W measurement.** `BwSharpnessTest` renders 3 real corpus pages at 675x900 in ORIGINAL, ENHANCED
and BW, and measures stroke Laplacian (ink pixels only), overshoot in the 2 px ring around ink,
the clipped-pixel share and the ink share, plus the ratios to ORIGINAL and ENHANCED; `AFTER` also
builds a 3× detail contact sheet against the `BEFORE` PNGs left in app-private storage. Shipped
result: stroke Laplacian −12.6 / −13.5 / −12.7 %, clipping 100 % → 90–97 %, overshoot still 0.000,
B&W still 5.1–7.4× ORIGINAL and 1.16–2.84× ENHANCED. A σ = 0.6 trial (−49…−52 %, B&W softer than
ENHANCED on one page) was rejected and is kept in `tooling/out/h27/BW_SIGMA06/`.

### H29 — open-book / facing-page sliver (`BookSpreadDetectionTest`, no production change)

**Why.** The owner reported that the import path over-detects a small fraction of the time: the quad
"detects more than the app itself" — on an open book it absorbs the facing page. The detector had no
test for this, so the failure could come and go between builds unnoticed.

**Fixtures.** `app/src/androidTest/assets/bookspread/` — 9 scenes × 2 canvases = 18 assets, generated
by [`h29_fixtures.py`](h29_fixtures.py): homography of the unit square for the GT quad, banded
rasterisation, and `sin(πv)` spine warping that is zero at the corners so the GT stays exact.
Variants: clean control, sliver-left thin, sliver-right thin, sliver-both large, gutter-only, curved
spine, strong perspective, uneven light, control-with-strong-perspective — each at 3024×4032
("still", the import path) and 1280×720 ("preview", the viewfinder path).
`book_spread_manifest.json` carries the GT page, the sliver polygon and the quad per asset.

```bat
cd /d C:\Users\Kuzey\Desktop\DocScanAI
rem regenerate, then self-verify: the manifest is checked against pixels, not trusted
python tooling\h29_fixtures.py
python tooling\h29_fixtures.py --verify
rem record candidate ONNX contracts before any integration code
python tooling\h29_inspect.py
rem run the lane (the tag is the run dir name under tooling\out\h27)
tooling\h27_boot.cmd
tooling\h27_lane.cmd H29 BookSpreadDetectionTest
rem artifact-only A/B of a candidate weight file: no code changes, only the bytes of m1c_detect.onnx
set "DOCSCAN_MODELS_DIR=%CD%\tooling\out\h29\models_fastvit"
tooling\h27_lane.cmd FASTVIT BookSpreadDetectionTest
set "DOCSCAN_MODELS_DIR=%CD%\..\dsmodels"
```

**What it scores**, per asset, against the real production detector (`ServiceLocator.pageDetector`):
detection rate, corner error as % of the image diagonal (8 corner orderings, best match), IoU against
the GT page, and `merged` — whether the returned quad covers ≥ 50 % of the GT facing-page sliver.

**Pinned baseline** (three separate instrumentation processes agree to the last digit): 18/18
detected, 7/12 slivers merged, mean corner error 3.9562 % of diagonal, mean IoU 0.8279. Constants:
`PIN_DETECT_FRACTION = 1.00`, `PIN_MAX_MERGED_SLIVERS = 7`, `PIN_MAX_MEAN_CORNER_PCT = 3.97`.
**Tighten them in the same commit that improves the detector** — a pin left at the old number stops
guarding the improvement it exists to protect.

**Candidate measured and rejected.** DocAligner FastViT-SA24 has an identical graph contract
(`[1,3,256,256]` → `[1,4,128,128]`), so it drops in with no code change: 11/12 slivers merged (worse),
2.9340 % corner error (better), 86.5 ms vs 33.0 ms, 83.08 MB vs 4.77 MB. The lane **fails** on it —
the gate working as designed. Full numbers and per-asset tables: `tooling/out/h29/HEADTOHEAD.md`.

**Do not call `detector.release()` in a test.** `release()` sets `released = true` on the shared
`ServiceLocator` detector, after which `resolveCornerModel()` returns null forever and every later
`detect()` silently measures `CLASSICAL_FALLBACK`. That is what produced "the same fixtures merged 7
vs 12 slivers" during this subtask; `measureAll()` now memoises a single pass instead.

---

## 2. Corpus + gallery workflow

```bat
cd /d C:\Users\Kuzey\Desktop\DocScanAI
set "DOCSCAN_RUN_DIR=C:\Users\Kuzey\Desktop\DocScanAI\tooling\out\h10_corpus"
tooling\corpus_run.cmd
```

Steps performed (`[n/10]` in the log):

1. Kill leftovers by **exact PID** (`_procquery.vbs` → `taskkill /PID <pid> /T /F`), never `/IM`.
2. Boot headless AVD, poll `sys.boot_completed` with a 10 s heartbeat up to 180 s.
3. `gradlew.bat assembleDebug assembleDebugAndroidTest --no-daemon` (600 s watchdog).
4. Install app + androidTest APKs (`-r -t`), discover the instrumentation component.
5. Provision models and verify the exact byte counts of **all five** files (see §4).
6. Run `corpus_ai_on` (780 s), capture `adb logcat -d -s DocScan` → `logcat_ai_on.txt`.
7. Run `corpus_ai_off` (600 s) → `logcat_ai_off.txt`.
8. `adb root` + `adb pull /storage/emulated/0/Android/data/com.docscanai/files/corpus_out`
   (an existing `corpus_out` is first **moved** to `archive\corpus_out_<ts>`, because
   `adb pull <dir> <existingDir>` nests).
   1. `BenchmarkArtifacts.java` → `benchmark\`, `overlay\`, `overlay_compare\`,
      `benchmark_report.json|csv`.
   2. `CreateGallery.java` → rebuilds the repo's `gallery\` (junction to `E:`).
   3. `BenchmarkGallery.java` → injects the benchmark section into `gallery\index.html`.
9. Classify: AI-ON must report `CORPUS_SESSIONS_AI_ON>=1`, AI-OFF must report
   `CORPUS_SESSIONS_AI_OFF==0`, no `Fatal signal` / `SIGSEGV` / `SIGABRT`, no
   `CORPUS_INVALID` / `NEURAL_RUN_INVALID`. Otherwise `INVALID` (not green).
10. Teardown: `adb emu kill`, ≤60 s poll, then kill only the emulator/qemu PIDs that were started.

Artifacts (default `tooling\out\corpus_<ts>\`, or `DOCSCAN_RUN_DIR`):

```
progress.txt                 timestamped step log (+ 10 s heartbeats during boot/build)
build.log, install_*.log, provision.log, pull.log, gallery*.log
instr_ai_on.txt, instr_ai_off.txt
logcat_ai_on.txt, logcat_ai_off.txt
corpus_out\ai_on\..., corpus_out\ai_off\..., corpus_report.json
archive\corpus_out_<ts>\     previous pull, if any
benchmark\ai_on\<name>\{input,raw,page,thumb,gt_scan}.jpg + docscan.txt
benchmark_report.json, benchmark_report.csv
overlay\{ai_on,ai_off}\<name>.png, overlay_compare\<name>.png
```

The browsable results are in `<repo>\gallery\index.html`.

### Dataset drop-folders

| Path | Contents |
|---|---|
| [`app/src/androidTest/assets/corpus/real/`](../app/src/androidTest/assets/corpus/real/) | real-world positive photos (auto-discovered, sorted) |
| [`app/src/androidTest/assets/corpus/benchmark/inputs/`](../app/src/androidTest/assets/corpus/benchmark/inputs/) | **required** benchmark page photos (DocUNet) |
| [`app/src/androidTest/assets/corpus/benchmark/gt_scan/`](../app/src/androidTest/assets/corpus/benchmark/gt_scan/) | optional flat scan of the same page, **same file name** → PSNR/SSIM |
| [`app/src/androidTest/assets/corpus/benchmark/gt_corners/`](../app/src/androidTest/assets/corpus/benchmark/gt_corners/) | optional `<name>.json` corner annotations → corner error + INSIDE/OUTSIDE audit |
| in-test generators | synthetic controlled pages + not-a-document negatives (fixed seed) |

Acquisition (one-off, needs network, JDK 21 only):

```bat
set "JAVA_HOME=C:\Program Files\Java\jdk-21"
"%JAVA_HOME%\bin\java.exe" tooling\corpus\FetchCorpus.java .          rem real photos
"%JAVA_HOME%\bin\java.exe" tooling\corpus\FetchBenchmark.java .       rem DocUNet benchmark
```

Both write provenance JSONL next to themselves and never fabricate an image. `FetchBenchmark`
also accepts `list` / `regen`. See [`corpus/PROVENANCE.md`](corpus/PROVENANCE.md).

---

## 3. Other lanes

### `h7_tests.cmd`

```bat
cd /d C:\Users\Kuzey\Desktop\DocScanAI
tooling\h7_tests.cmd
```

Boots `docscan_dbg_api35`, builds/installs, provisions models, clears logcat, then runs the twelve
classes (`DetectionRegressionTest`, `DewarpFidelityTest`, `NeuralPathReproTest`,
`NeuralPathXnnpackTest`, `PageViewerUiTest`, `DetectorLatchRuntimeTest`, `AutoCaptureGatingTest`,
`LiveCapturePathTest`, `LiveRoomNegativeTest`, `AutoCaptureDuplicateTest`, `RefineSeedGuardTest`,
`BookSpreadDetectionTest`)
**each in its own instrumentation process** with a 900 s budget each, and finally pulls
`last_crash.txt` + `logcat_all.txt`. Per-class output:
`<run>\<ClassName>.txt`; a class passes only when its output contains `OK (` and no
`FAILURES!!!` / `INSTRUMENTATION_FAILED` / `Process crashed`. Default run dir `tooling\out\h7_tests`,
override with `DOCSCAN_RUN_DIR`.

#### `LiveRoomNegativeTest` — the real-room acceptance gate (SUBTASK H21)

Replays the **16 real frames harvested from the H20 camera run** — `app/src/androidTest/assets/live_room/room_00.jpg`
… `room_15.jpg` (960×1280, ~160 KB each, 2.57 MB total; captured with `-camera-back virtualscene`
and no document in the room) — through the **real** `OpenCvQuadPageDetector` and the real
`PagePlausibility` gate, at the same 480 px working edge `FrameAnalyzer` uses. Every frame must fail
the gate (`pass=false`), must not be auto-capture eligible, and a 3-frame hold of every frame must
produce **0** captures. It was recorded **red** on 0.3.4 (`room_00` passed, 1 auto-capture) and is the
acceptance gate for the H21 fix. Evidence lines: `LIVE_ROOM frame=<name> detected=… pass=… prov=…
score=… text=… paper=… glyphs=… coh=… cov=… auto=…` plus the `[Gate] … lines=… span=… hfrac=…` line
that carries the text-line proof. To refresh the fixture set, re-run the virtual-scene room lane (see
§"virtual scene" below) and copy `files/scans/<session>/raw/*.jpg` out with
`adb exec-out run-as com.docscanai tar -cf - files/scans`.

#### `AutoCaptureDuplicateTest` — capture-safety rules (SUBTASK H21)

Drives the real `CaptureStateMachine` (zero cooldown, zero thumbnail) and proves: **30 identical
stable frames ⇒ exactly 1 capture**; a **moving scene never captures** (fingerprint alternates by
MAD 0.47 while the quad is constant); the **minimum hold time** is enforced on the frame clock
(100 ms ⇒ 0 captures with `HOLD_STEADY` visible, 400 ms ⇒ 1); the **post-capture debounce** blocks an
immediately following *different* page and then releases it; a changed page at the same quad still
captures; and the **manual shutter is never suppressed** by the duplicate guard.

#### `LiveCapturePathTest` — the live-capture chain (SUBTASK H20)

The only lane that does not start at `ScanPipeline`. It feeds **synthesised camera frames** (the
bundled page photograph `testdocs/test_page.jpg` perspective-warped onto a desk at the corpus's
`baseRect(0.10)` geometry; negatives are full-bleed framing shifts of the known scene photo) through
the real `OpenCvQuadPageDetector` → `PagePlausibility` → `AutoCaptureStability` →
`CaptureStateMachine` → `captureRequests` → `ScanPipeline` → saved files → `SessionStore` →
`PageReviewScreen` → `PageViewerContent`. It asserts: one fire per hold (never per frame), never on
room/clutter (30 frames), no re-fire after the page leaves, no fire while the quads jitter below the
IoU floor, and a manual shutter that bypasses stability and still saves. Every step logs
`LIVE_CAPTURE ...` under the `DocScan` tag — `adb logcat -d -s DocScan | findstr LIVE_CAPTURE`
reproduces the evidence table.

**Limitation:** the frames are composed by the test, so the real optics + CameraX binder hand-off
(`ImageAnalysis` → `FrameAnalyzer.analyze` → `BitmapUtils.imageProxyToBitmap`) is *not* exercised.
That link is verified only on physical hardware — see the virtual-scene recipe below for why the
emulator cannot stand in for it.

### Virtual-scene camera on the emulator — recipe + limitations (SUBTASK H20)

The emulator can render its built-in "virtual scene" room as a **real camera device**, which is the
closest thing to optics available without hardware. The recipe (verified on emulator
**37.2.12.0 / build 16428233**):

```bat
rem 1) stop anything of ours first (exact PIDs only - never a broad kill)
for /f "tokens=1" %%p in ('cscript //nologo tooling\_procquery.vbs "emulator.exe"') do taskkill /PID %%p /T /F
for /f "tokens=1" %%p in ('cscript //nologo tooling\_procquery.vbs "qemu-system-x86_64.exe"') do taskkill /PID %%p /T /F

rem 2) boot headless with the virtual scene as the BACK camera and a page as the wall poster
start "h20_emulator" /MIN "%ANDROID_HOME%\emulator\emulator.exe" -avd docscan_dbg_api35 ^
  -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect ^
  -camera-back virtualscene ^
  -virtualscene-poster wall=C:\Users\Kuzey\Desktop\DocScanAI\app\src\androidTest\assets\corpus\benchmark\gt_scan\docunet_01.jpg

rem 3) install the debug build, provision the models, grant the camera permission, launch
adb uninstall com.docscanai
adb install -r -t app\build\outputs\apk\debug\app-debug.apk
call tooling\_provision_models.cmd "%ADB%" "%DOCSCAN_MODELS_DIR%" ""
adb shell pm grant com.docscanai android.permission.CAMERA
adb shell am start -n com.docscanai/.MainActivity      rem Splash -> Camera automatically

rem 4) watch the real chain and grab the preview
adb shell am force-stop com.docscanai                 rem optional: stop the capture storm before pulling
adb logcat -d -v time -s DocScan > tooling\out\h20\a02_room_logcat.txt
adb exec-out screencap -p > tooling\out\h20\a01_boot_camera.png
```

`-virtualscene-poster <type>=<path>` accepts `wall`, `table` and `image` and can be repeated; the
path **must be absolute**. `start /MIN` keeps it off-screen; the virtual scene still renders under
`-gpu swiftshader_indirect`.

**H21 after-lane (the fixed build).** Same boot, then: install the debug APK, provision models, wipe
the logcat, launch, **turn Auto-capture ON** (tap the `Auto` switch; the app logs
`[AutoCapture] enabled=…` so the state is provable rather than assumed), and hold the unchanged room
for ≥ 120 s. Required result: every `[Gate]` line in that window has `pass=false`, **zero**
`[AutoCapture] … -> capture` lines and **zero** saved pages; then press the shutter once and confirm
one page is written under `files/scans/<session>/{raw,page,thumb}`. Measured on 0.3.5: **1254** gate
evaluations, **0** `pass=true`, **0** captures in 120 s, then 1 manual page (377 682 B).
**Limitation that still stands:** emulator 37.2.12 ignores `-virtualscene-poster`, so the virtual room
can never contain a page — this lane proves the *negative* only. The positive path is covered by
`LiveCapturePathTest` + `corpus_run.cmd`, never faked here.

**Measured limitations (H20 attempt A) — read before trusting this lane:**

1. **The poster override is silently ignored** on emulator 37.2.12: with a `.jpg` on `wall` and,
   separately, a `.png` on `wall` + `table` + `image`, the stock checkerboard poster stayed rendered
   in both launches. The page was never in view, so "poster in view" assertions cannot be made.
2. **The camera pose cannot be aimed.** `-no-window` provides no host pointer to drag the virtual
   camera, and the emulator console exposes no pose command (`adb emu help` lists only `rotate`).
   Without host-side mouse control the camera stays at its default pose.
3. **The default pose is a room** (wall + bookshelf + TV). That *is* useful as a negative: it is the
   one input that reproduced the owner-reported defect — the gate returned `pass=true`
   (`score≈0.75, cov=1.000, text≈0.72, paper≈0.26`) and auto-capture fired 18× / saved 35 room pages
   (`tooling/out/h20/a02_room_logcat.txt`). Treat a virtual-scene run as a **negative-input** probe,
   not as a page-capture proof.
4. The **manual shutter** path does work here, and it is the only way to prove the "shutter bypasses
   the gate" behaviour against a real CameraX `ImageCapture` (log: `manual shutter -> capture
   (page-ness gate bypassed)`). Note the shutter is only honoured in `DETECTING`/`LOCKED`, so turn
   auto-capture off with the on-screen **Auto** chip first, otherwise the tap usually lands while the
   machine is `CAPTURING` and is (correctly) ignored.
5. Real optics + the CameraX binder hand-off remain **physical-hardware only**.

### `e2e.cmd`

The original single-command gate: boots headless, builds, installs, provisions, captures logcat in
the background ([`capture_logcat.cmd`](capture_logcat.cmd)), runs CPU-EP then XNNPACK, retries once on
transient instrumentation errors, pulls crash reports + `dumpsys meminfo`, and always tears down.
Its report distinguishes `PASS` / `FAIL` / `INVALID` (no ONNX session ⇒ meaningless run) / `INFRA`.
**Set `set "DOCSCAN_AVD=docscan_dbg_api35"`** — its built-in default is the older `docscan_test`.

### `ai_off_check.cmd`

Proves the AI gate still works: models present, `neuralProcessingEnabled=false`, exactly one
instrumented test, then requires `classical-only page=` **and zero** `session EP=` lines.

### `subtask_f_check.cmd`

Runs `PageViewerUiTest` and `DetectorLatchRuntimeTest`, pulls the viewer screenshots, and verifies
that no `session EP=` line appears before the runtime AI flip and ≥1 after it. Reports `INVALID` when
models are missing.

### Viewer zoom detail (two decode tiers)

The full-screen viewer never holds a full-resolution page. `PageImageDecoder` exposes two tiers:

| Tier | Call | Long edge | When |
|---|---|---|---|
| preview | `decode(file, rotation, viewLongEdge)` | hard cap **2048 px** | immediately on open — the only thing the first frame waits for |
| detail | `decodeForDetail(file, rotation, targetLongEdge, maxPixels)` | `min(source, target, 4096, sqrt(maxPixels·aspect))` | once, asynchronously, when zoom crosses **1.8×** |

The detail target is `min(sourceLongEdge, 4 × fit long edge, MAX_DETAIL_LONG_EDGE = 4096)` and is
then clamped by the pixel budget. The budget is derived at runtime —
`maxPixels = min((Runtime.maxMemory() × 0.20) / 4, 12 MP)` (i.e. at most 20 % of the process heap,
never more than 12 MP ≈ 48 MB at ARGB_8888) — and logged once per page as
`[Viewer] detail target=<n> maxPixels=<n> budgetMB=<n>`. On arrival the bitmap is swapped in while
pan/scale are preserved (the swap does not reset zoom), the preview is recycled only after the
detail frame has been drawn, and any OOM/IO failure simply leaves the preview on screen.

---

### `check_16k.py`

```bat
python tooling\check_16k.py app\build\outputs\apk\debug\app-debug.apk
python tooling\check_16k.py app\build\outputs\apk\release\app-release.apk
```

Parses the real ELF program headers; `p_align >= 0x4000` prints `16KB-OK`. Both APKs currently
report 16/16 libraries compliant.

### Capture gating (auto-capture safety)

Auto-capture may fire only after a detected quad passes **two independent** checks. Both are
fail-closed: anything that cannot be evaluated is treated as “no document”.

**1. Content page-ness gate** — [`PagePlausibility.kt`](../app/src/main/java/com/docscanai/capture/PagePlausibility.kt:64)
scores the *content inside the quad* on the original frame (no new model; cheap OpenCV on a ≤480 px
copy). Signals, normalised to 0..1:

| Signal | What it measures | Threshold / scoring |
|---|---|---|
| coverage | quad area / frame area | full credit 0.20–0.85; zero below 0.10 or above 0.97 |
| aspect | mean side ratio vs A4/Letter 1.41 | Gaussian falloff, σ = 0.55 |
| angle | all four interior corner angles | **HARD rule: every angle within 55°–125°** ([`PagePlausibility.kt`](../app/src/main/java/com/docscanai/capture/PagePlausibility.kt:225)) |
| convexity | simple, non-self-intersecting quad | **HARD rule** |
| paper | rectified luminance brightness + low saturation + dominant bright histogram mode | weight 0.14 |
| text | horizontal projection profile of ink: row variance, peak count, periodic autocorrelation, text-band fraction | weight 0.48, **hard floor 0.30** |
| edge | Canny density in a sane band (not empty, not chaotic noise) | weight 0.06 |

Decision: `score >= 0.55` **and** `text >= 0.30` **and** convex **and** the 55°–125° angle rule
([`PagePlausibility.kt`](../app/src/main/java/com/docscanai/capture/PagePlausibility.kt:67)). A
rejected live quad becomes no detection ([`FrameAnalyzer.kt`](../app/src/main/java/com/docscanai/capture/FrameAnalyzer.kt:111));
a rejected still quad is downgraded to **NO DETECTION and no warp runs**
([`ScanPipeline.kt`](../app/src/main/java/com/docscanai/capture/ScanPipeline.kt:134)). Each
evaluation emits exactly one audit line, for example:

```
[Gate] id=still score=0.551 cov=1.000 asp=0.813 paper=0.932 text=0.115 edge=1.000 glyphs=7 angle=1.000 minAng=74 maxAng=118 convex=true selfInt=false pass=false
```

**2. Temporal stability** — [`AutoCaptureStability.kt`](../app/src/main/java/com/docscanai/capture/AutoCaptureStability.kt:78)
requires **3 consecutive gate-passing quads** whose IoU with the running reference is **≥ 0.85**.
A missing or gate-rejected frame resets the counter. [`CaptureStateMachine.kt`](../app/src/main/java/com/docscanai/capture/CaptureStateMachine.kt:113)
consults it before arming the auto-shutter.

**Indicator states** ([`CaptureStateMachine.kt`](../app/src/main/java/com/docscanai/capture/CaptureStateMachine.kt:48)):

| State | Meaning |
|---|---|
| `NONE` | no gate-passing document in view |
| `DOCUMENT_DETECTED` | a page passed the gate, but the 3-frame / IoU-0.85 window is not yet met |
| `HOLD_STEADY` | stability satisfied — the next frame may auto-capture |

**The manual shutter always works.** It bypasses the gate, the stability window and the cooldown:
explicit user intent wins ([`CaptureStateMachine.kt`](../app/src/main/java/com/docscanai/capture/CaptureStateMachine.kt:40)).
The deliberate cost of the gate is that a blank sheet (no periodic text rows) is not
auto-captured — the manual shutter covers exactly that case.

**Tune / verify.** Run `tooling\h7_tests.cmd`; the behaviour is proven by `AutoCaptureGatingTest`
(5/5 negatives rejected with zero auto-captures, ≥18/20 benchmark positives accepted, a 30-frame
clutter sequence never fires) and `AutoCaptureStabilityTest` (3 consecutive passes fire, 2+1 does
not, IoU 0.5 does not). Change a threshold only together with a fresh `corpus_run.cmd`; the
acceptance bar is **0/5 negatives while benchmark detection stays ≥ 19/20**.

### Adaptive grid dewarp (H15)

The 45×31 UVDoc grid is **not** applied at 100 % on every page. Before the single remap from the
original still, [`NeuralGridDewarp.kt`](../app/src/main/java/com/docscanai/imaging/NeuralGridDewarp.kt:465)
measures the grid-implied sampling map against the pure 4-corner homography and
[`WarpPolicy.kt`](../app/src/main/java/com/docscanai/imaging/WarpPolicy.kt:91) turns that residual
into a strength:

| Constant | Value | Meaning |
|---|---|---|
| `RESIDUAL_FLAT_MEAN` | **0.015** ([`WarpPolicy.kt`](../app/src/main/java/com/docscanai/imaging/WarpPolicy.kt:35)) | mean residual ≤ 1.5 % of the page diagonal ⇒ **strength 0** (homography only) |
| `RESIDUAL_FULL_MEAN` | **0.05** ([`WarpPolicy.kt`](../app/src/main/java/com/docscanai/imaging/WarpPolicy.kt:38)) | linear ramp 0 → 1 between 1.5 % and 5 % |
| `RESIDUAL_MAX_CLAMP` | **0.06** ([`WarpPolicy.kt`](../app/src/main/java/com/docscanai/imaging/WarpPolicy.kt:41)) | every residual vector is capped at 6 % of the diagonal before the strength is applied |

The strength interpolates the **sampling map** (`final = homography + strength · clamp(residual)`),
never two output images, and the pipeline still performs exactly **one** `Imgproc.remap` from the
original still on the CPU EP (`allowXnnpack=false`). Interpolation is `INTER_CUBIC`
([`NeuralGridDewarp.kt`](../app/src/main/java/com/docscanai/imaging/NeuralGridDewarp.kt:876)),
chosen by measurement (2190.63 vs 2130.39 Laplacian on the same composed map).

Per-page audit lines (tag `DocScan`, stage `[Warp]`):

```
[Warp] residual mean=0.0183 max=0.0541 p95=0.0311 curv=0.0009
[Warp] strength=0.09 mean=0.0183 max=0.0541
[Warp] residual suppressed (flat) mean=0.0090 max=0.0210   (when strength = 0)
[Warp] clamp vectors=1234 limit=0.0600                      (when the 6 % cap bites)
```

Measured distribution on the 0.3.3 corpus (`tooling/out/h15_warp/corpus_final`): 61 grid runs —
**30 strength 0**, **29 partial**, **2 full**; benchmark mean residual avg **0.0256**
(p95 avg 0.0510, max avg 0.0830); synthetic mean residual avg **0.0148**; all **7**
`rectified_skip` pages ran no grid at all (0 residual samples by design). Summary:
`py -3 tooling\h15_warp_summary.py tooling\out\h15_warp\corpus_final`.

The proof is `DewarpFidelityTest` (run by `h7_tests.cmd`): flat-page strength 0 + zero map movement,
AI stroke Laplacian 1.000× homography-only, curved-page strength 0.22 with +11 % straightness, and a
30 % corrupted grid clamped to exactly 6 % with a positive Jacobian (no fold).

### Geometry refinement — implemented, measured, gated OFF (H18)

Sub-pixel corner refinement sits behind the production constant `REFINE_ENABLED`, read through
`subpixelRefineEnabled()` ([`OpenCvQuadPageDetector.kt`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt:1201)).
When it is on, the detected quad is refined before warping
([hook at `OpenCvQuadPageDetector.kt:1058`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt:1058)):
for each side it samples the perpendicular luminance gradient in a narrow band, fits a robust edge
line (`fitEdgeLine`), and intersects adjacent lines to get a sub-pixel corner.

| Constant | Value | Meaning |
|---|---|---|
| `REFINE_ENABLED` | **`false`** ([`OpenCvQuadPageDetector.kt:1578`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt:1578)) | production toggle; `false` ⇒ detected quad is used unchanged |
| `REFINE_MAX_MOVE_FRAC` | **0.015** ([`OpenCvQuadPageDetector.kt:1581`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt:1581)) | each refined corner may move at most 1.5 % of the image diagonal |
| `REFINE_BAND_FRAC` | `0.02` ([`:1584`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt:1584)) | perpendicular search band, fraction of the diagonal |
| `REFINE_BAND_MAX` | `24.0` px ([`:1587`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt:1587)) | hard cap on that band |
| `REFINE_MIN_GRAD` | `6.0` ([`:1590`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt:1590)) | minimum gradient for an accepted edge sample |
| `REFINE_MAX_LINE_RMS` | `2.5` px ([`:1593`](../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt:1593)) | maximum robust RMS of an accepted edge-line fit |

After the clamp, the refined quad is re-checked with the **same** plausibility gate; on any failure
the original detection is kept. There is also a test-only override,
`setSubpixelRefineOverride(...)`, used only by `WarpGeometryBenchTest` (never by the corpus lane).

**Gating rule (H18).** Refinement is kept only if *all* of these hold on the corpus lane: 19-image
border RMS and/or text straightness improve ≥ 2 % relative, corner error ≤ **3.44 %**, benchmark
detection 19/20, negatives 0/5, `distorted_suspect` 0, sharpness ratio ≥ 1.00, native heap ≤ 320 MB,
warp ≤ 300 ms. One bounded mitigation (clamp 1.5 % → 0.75 %) was allowed before a revert.

**Result — reverted.** Refinement improved geometry (border RMS 10.02 → 9.14 px, −8.76 % relative;
sharpness +3.42 %; PSNR +1.92 %; SSIM +9.46 %) but corner error rose to **3.585 %** at the 1.5 %
clamp and **3.522 %** with the 0.75 % clamp, so `REFINE_ENABLED` stays `false` and 0.3.4 keeps the
0.3.3 corner model. Everything else held in both runs (19/20, 0/5 negatives, 0 distorted, heap
306.7–307.8 MB, warp 213–219 ms).

**To re-measure.** Flip `REFINE_ENABLED` to `true`, then run the corpus lane and/or the geometry bench:

```bat
tooling\corpus_run.cmd            rem corner error + guardrails (h15_warp_summary.py)
tooling\h16_bench.cmd             rem same-build A/B variants
py -3 tooling\h16_geometry.py <run>\geom_out\raw_refine_off --out table.csv --variant raw_refine_off
py -3 tooling\h16_compare.py <baseline.csv> table.csv
```

**Which test measures it.** `WarpGeometryBenchTest` (via `h16_bench.cmd` + `h16_geometry.py`) is the
geometry measurement; the corpus lane's `mean_corner_err_pct_diag` is the gate.

### Corner-refinement release gate — the H19 lane (0.3.4)

Lane name: **corner-refinement gate**. It is a *sequence*, not one script: corpus gate → at most one
mitigation → final geometry table → fidelity + 16 KB + signature + launch. Every step gets its own
`DOCSCAN_RUN_DIR` and writes a `progress.txt` heartbeat, so an interrupted run can be resumed instead
of repeated. H19's own output is under `tooling/out/h19/`.

```bat
rem 1) configuration A/B: REFINE_ENABLED=false vs true, one build, full corpus lane
set "DOCSCAN_RUN_DIR=tooling\out\h19\corpus_on"
tooling\corpus_run.cmd
py -3 tooling\h15_warp_summary.py tooling\out\h19\corpus_on

rem 2) only if corner error regressed: ONE mitigation, REFINE_MAX_MOVE_FRAC 0.015 -> 0.0075
rem    (edit OpenCvQuadPageDetector.kt:1581, repeat step 1, no third variant)

rem 3) final config geometry table (19 detected rows, bench_docunet_25 highlighted)
set "DOCSCAN_RUN_DIR=tooling\out\h19\geom"
tooling\h16_bench.cmd
py -3 tooling\h16_geometry.py tooling\out\h19\geom\geom_out\raw_refine_off ^
    --out tooling\out\h19\geometry_table_0.3.4.csv --variant 0.3.4_final_raw_refine_off

rem 4) fidelity + packaging checks
set "DOCSCAN_RUN_DIR=tooling\out\h19\h7_tests"
tooling\h7_tests.cmd
py -3 tooling\check_16k.py app\build\outputs\apk\release\app-release.apk
py -3 tooling\check_16k.py app\build\outputs\apk\debug\app-debug.apk
```

Two gotchas that cost a run each in H19:

- **`set` + `%VAR%` on the same line does not work in cmd** (the whole line is expanded before `set`
  runs). Put `set "DOCSCAN_RUN_DIR=..."` on its own line, or use the literal path.
- **A release-signed APK cannot install over the debug-signed build** the h7/geometry lanes leave
  behind (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Run `adb uninstall com.docscanai` first, then
  install the release APK, then `pm clear`, then launch — otherwise the launch evidence describes the
  debug build. `tooling/out/h19/h19_launch_check.cmd` does exactly that and is the launch proof
  lane for 0.3.4.

Gate result for 0.3.4: **REVERTED** (corner error 3.585 % / 3.522 % vs the 3.44 % gate), see
[`docs/RESULTS.md`](../docs/RESULTS.md) §H19.

### Line-evidence lane — H30 (M-LSD + merge rejection)

Lane name: **line evidence**. Two capabilities, one optional model, and a hard latency verdict.

```bat
rem 1) port + unit tests (numpy only: no cv2, no TensorFlow)
py -3 tooling\h30_mlsd_test.py                 rem 38 tests -> "OK"

rem 2) calibrate the offline corner replica against the DEVICE's published per-asset numbers
py -3 tooling\h30_resize_probe.py              rem -> tooling\out\h30\resize_probe.json

rem 3) score M-LSD 320/512 and the merge rule on the 18 fixtures
py -3 tooling\h30_score.py                     rem -> tooling\out\h30\scores.{json,md}

rem 4) on-device A/B + latency (needs the optional artifact provisioned, see §4)
call tooling\h7_tests.cmd                      rem 13 classes incl. BookSpreadDetectionTest
```

What each script is for:

| script | what it answers |
|---|---|
| [`h30_mlsd.py`](h30_mlsd.py) | the reference port: junctions/scores/displacement → segments → Hough merge → squares, plus the geometry the rule needs (`splitQuadByLine`, `lineSupport`, `lineThroughQuad`) |
| [`h30_mlsd_test.py`](h30_mlsd_test.py) | is the port right? rectangle → 4 lines/1 square, diagonal → 1/0, T → 3/0, empty → 0/0, noise → bounded+deterministic, plus the merge rule's split/choose cases |
| [`h30_corner.py`](h30_corner.py) | the shipped corner path replicated (preprocess, subpixel peak, ordering, plausibility) so the rule is measured against *real* quads |
| [`h30_merge.py`](h30_merge.py) | the rule itself — mirrored constant-for-constant by `MlsdMergeRepair.kt` |
| [`h30_score.py`](h30_score.py) | the variant matrix (corner / merge / fusion / M-LSD-only, 320 vs 512) with corner error, IoU, sliver coverage, area ratio, latency |
| [`h30_resize_probe.py`](h30_resize_probe.py) | which PIL filter reproduces Android's `createScaledBitmap` — the one thing that cannot be copied from the device |

Two things to know before re-running:

- **The offline numbers are a proxy, and say so.** `resize_probe.json` records the residual against
  the device's published table (best mode `box`: mean |Δ| 0.70 pp, worst asset 4.7 pp). The device is
  the authority; the proxy exists to iterate in seconds instead of tens of minutes.
- **M-LSD is optional and NOT in `ModelRegistry.models`.** Upstream ships four graphs inside one
  `resources.tar.gz`, so there is no single-file URL to pin. Put a local copy at
  `<models dir>\m2_mlsd.onnx` and `_provision_models.cmd` pushes it to
  `files/models/m2_mlsd/model.onnx`; without it the detector runs exactly as 0.3.8
  (`MlsdMode.OFF`). `BookSpreadDetectionTest.mlsdAbIsAttributableAndDoesNotCostDetection` **fails
  loudly** if the artifact is missing, so a silent fallback cannot look like a win.

Measured verdict (H30): quality **wins** (merged slivers 7/12 → 2/12, corner 3.9562 % → 3.5672 %,
IoU 0.8279 → 0.8442), cost **loses** (+73.68 ms p50 / +99.39 ms p95 on the live canvas vs a +30 ms
budget; +69.76 / +81.89 on the still vs +60 ms). Nothing ships; `MlsdMode.OFF` is the production
default and `productionModeMatchesTheShippedDecision` guards it.

---

## 4. Model provisioning (including the two-file grid model)

Models are **not** in `assets/`; they live in app-private storage at
`filesDir/models/<id>/<file>` and are pushed from `DOCSCAN_MODELS_DIR` (default `<repo>\..\dsmodels`):

| id | file(s) | bytes |
|---|---|---|
| `m1c_detect` | `model.onnx` | 4,767,987 |
| `m1_boundary` | `model.onnx` | 47,235,563 |
| `m2_dewarp` | `model.onnx` | 31,684,150 |
| `m2_dewarp_grid` | **`UVDoc_grid.onnx`** | 242,890 |
| `m2_dewarp_grid` | **`UVDoc_grid.onnx.data`** | 31,588,352 |

`m2_dewarp_grid` is a **graph + external-data sidecar** pair. ONNX Runtime resolves the sidecar by
relative path, so both files MUST be in the same directory; the downloader downloads both into
`filesDir/models/m2_dewarp_grid/`, verifies size + SHA-256 per file, and only then renames `.part` →
final.

[`_provision_models.cmd`](_provision_models.cmd) takes `%1=adb.exe`, `%2=models dir`,
`%3=serial flag` (e.g. `-s R5CX...` or empty). It is **binary-safe**: `adb push` to
`/data/local/tmp`, then the *device* shell redirects that file into
`run-as com.docscanai sh -c 'cat > files/models/...'`.

Caveats:

- `run-as` requires a **debuggable** APK. A release APK cannot be provisioned this way — use the
  in-app **Set up AI models → Download AI models** flow, or (emulator only, `docscan_dbg_api35`)
  `adb root` followed by `adb push` into `/data/data/com.docscanai/files/models/...`, then
  `chown` to the app uid and `restorecon -R`.
- With no models the pipeline silently uses the classical OpenCV path, so lanes that require neural
  evidence report **INVALID** rather than a false PASS.

---

## 5. Release signing

Never commit signing material; never print the password.

1. Keystore: `keystore/docscan-release.jks` (JKS, RSA **4096**, validity **10000 days**,
   alias **`docscan`**).
2. `keystore.properties` at the repo root:
   ```properties
   storeFile=keystore/docscan-release.jks
   storePassword=<generated>
   keyAlias=docscan
   keyPassword=<generated>
   ```
3. `.gitignore` contains `keystore.properties` and `keystore/` (verify with
   `git check-ignore -v keystore.properties` once the project is a git repo — it currently is not).
4. [`app/build.gradle.kts`](../app/build.gradle.kts) loads `keystore.properties` **if present** and
   adds a `release` `signingConfig`; if the file is absent it logs a warning and the release APK is
   produced **unsigned**, so a fresh clone still builds.

```bat
cd /d C:\Users\Kuzey\Desktop\DocScanAI
set "JAVA_HOME=C:\Program Files\Java\jdk-21"
set "ANDROID_HOME=C:\Users\Kuzey\AppData\Local\Android\Sdk"
gradlew.bat assembleDebug assembleRelease --no-daemon
"%ANDROID_HOME%\build-tools\36.0.0\apksigner.bat" verify --print-certs --verbose ^
    app\build\outputs\apk\release\app-release.apk
```

---

## 6. Operating conventions (mandatory)

- **Bounded timeouts.** Every long step has a finite budget; no infinite waits, no `timeout /t`
  under redirected stdin (the scripts use `ping -n` sleep helpers for that reason).
- **Exact-PID kills only.** [`_procquery.vbs`](_procquery.vbs) lists processes by name via WMI
  (`wmic` is gone on this Windows build); the scripts then `taskkill /PID <pid> /T /F` only those
  PIDs. No broad `/IM` kills except the legacy `e2e.cmd` stray-cleanup step.
- **Watchdog.** [`_exec_watchdog.vbs`](_exec_watchdog.vbs):
  `run <budgetSec> <logFile> <progressFile> <cmd...>` runs a child, prints a heartbeat every ~10 s to
  stdout and the progress file, and on expiry kills exactly that child's PID tree (exit code 124).
  `spawn <cmd...>` starts a detached child and returns.
- **Progress + heartbeats.** `corpus_run.cmd` / `h7_tests.cmd` write timestamped lines to
  `<run>\progress.txt`; boot polling logs a heartbeat every 10 s.
- **Interruption.** If a run is interrupted, assume the **user** did it: stop, leave the tree
  buildable, and report the exact state instead of retrying.
- **Destructive-command guard (DCG) avoidance.** No `rmdir /s` on tracked trees, no wildcard `del`,
  no `gradlew clean`, no `git reset --hard`, no AVD create/delete, no `sdkmanager`. Use explicit
  paths, `adb uninstall com.docscanai` for signature conflicts, and a fresh timestamped run folder
  per run. [`h9_prune.ps1`](h9_prune.ps1) is the only deleter and it refuses paths outside the
  `tooling\out` root, keep-listed names, and folders containing source/model files.
- **JDK launcher.** Host-side Java tools are run through [`_java.cmd`](_java.cmd) because the
  watchdog joins argv and the space in `C:\Program Files\...` would otherwise break.

---

## 7. Disk layout — junctions to `E:`

`C:` is small on this machine; large regenerable trees live on `E:` behind **directory junctions**.
Original `C:` paths still work (and Gradle/adb/emulator all use them unchanged).

| Path (original) | Junction target on `E:` |
|---|---|
| `<repo>\app\build` | `E:\DocScanAI-cache\app-build` |
| `<repo>\.gradle` | `E:\DocScanAI-cache\project-gradle` |
| `<repo>\tooling\out` | `E:\DocScanAI-cache\tooling-out` |
| `<repo>\gallery` | `E:\DocScanAI-cache\gallery` |
| `C:\Users\Kuzey\.gradle` | `E:\DocScanAI-cache\user-gradle` |
| `C:\Users\Kuzey\.android\avd` | `E:\DocScanAI-cache\avd` |
| `C:\Users\Kuzey\AppData\Local\Android\Sdk` | `E:\DocScanAI-cache\android-sdk` |

Verify with:

```bat
dir /a:l C:\Users\Kuzey\Desktop\DocScanAI
dir /a:l C:\Users\Kuzey\Desktop\DocScanAI\app
dir /a:l C:\Users\Kuzey\Desktop\DocScanAI\tooling
```

### Undo a relocation

Removing a junction removes only the link — never the data — but the data must be moved back first
or it stays on `E:`.

```bat
rem 1) stop anything using the tree
adb kill-server
rem 2) delete ONLY the junction (rmdir on a junction does not recurse into the target)
rmdir "C:\Users\Kuzey\Desktop\DocScanAI\app\build"
rem 3) move the real data back
robocopy "E:\DocScanAI-cache\app-build" "C:\Users\Kuzey\Desktop\DocScanAI\app\build" /E /MOVE
```

Repeat per row with that row's target. Do **not** use `rmdir /s` on these paths.

Supporting scripts: [`h9_inventory.ps1`](h9_inventory.ps1) (read-only per-child sizes),
[`h9_state.ps1`](h9_state.ps1) (size/count + junction target + free space), [`h9_move.ps1`](h9_move.ps1)
(robocopy → verify → junction; only when robocopy rc < 8), [`h9_move_safe.ps1`](h9_move_safe.ps1)
(copy → verify → rename → junction → delete duplicate; for trees with live handles, e.g. the SDK),
[`h9_link.ps1`](h9_link.ps1) (create + prove a junction), [`h9_prune.ps1`](h9_prune.ps1) (guarded
artifact pruning; `-DryRun` supported), [`h9_gallery_check.ps1`](h9_gallery_check.ps1) (proves every
relative `src`/`href` in `gallery\index.html` still resolves after the move).

---

## 8. Real device instead of the emulator (Galaxy S25 FE, arm64-v8a)

```bat
cd /d C:\Users\Kuzey\Desktop\DocScanAI
set "DOCSCAN_SERIAL=R5CX0000000"
tooling\corpus_run.cmd      rem or h7_tests.cmd / e2e.cmd
```

With `DOCSCAN_SERIAL` set the lane skips the emulator boot and never kills an emulator; every `adb`
call is scoped with `-s <serial>`. The device must be arm64-v8a, have USB debugging enabled, run
API 26+, and the APKs must be installed for that ABI (`arm64-v8a` is packaged).

**Known caveat:** the emulator is `x86_64`; ONNX Runtime and OpenCV ship different native kernels
per ABI, so a green emulator run is necessary but **not** sufficient. Camera behaviour, thermal
throttling and real sensor input can only be validated on the phone.

---

## 9. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `run-as: package not debuggable` | A **release** APK is installed. Provision through the in-app download, or `adb root` + `adb push` (emulator). |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | A debug and a release build share the package name. `adb uninstall com.docscanai` first. |
| `INVALID` on a neural lane | Models absent or hash-mismatched; check `_provision_models.cmd` output for the exact byte counts. |
| Boot timeout | Another emulator/qemu holds the port, or WHPX is unavailable. The lanes kill only their own stray PIDs; check `emulator -accel-check`. |
| `adb root` refused | You are on `docscan_test` (API 37). Use `docscan_dbg_api35`. |


### H31 lane — M-LSD cost budget + merge-trigger quality (`tooling/h31_*`)

| script | purpose |
|--------|---------|
| `h31_trigger_probe.py` | offline study of the cheap trigger features (which thresholds actually pay off) on the H30 artifacts; writes `tooling/out/h31/trigger_probe.md/.json` |
| `h31_lane.cmd <Tag> <Class...>` | instrumented lane (install, provision, run, logcat) writing to `tooling/out/h31/<Tag>/` — the H31 quality + cost evidence |
| `h31_ship.cmd` | **self-terminating** ship lane: signed release build → `aapt2 dump badging` → `apksigner verify --print-certs` → size + SHA-256 → `check_16k` → install + launch + screencap smoke → `tooling/out/h31/SHIP.txt`. Every wait is a bounded poll and the script ends in `exit /b`, so the terminal is released as soon as the work is done |

The classes behind this lane are [`MlsdCostBudgetTest`](../app/src/androidTest/java/com/docscanai/MlsdCostBudgetTest.kt)
(200-frame sequences, invocation rate, average/p95, clean sequence, still path, warm-up) and
[`MlsdTriggerTest`](../app/src/test/java/com/docscanai/ml/MlsdTriggerTest.kt) (JVM, 25 tests:
signature, hysteresis, motion, cadence, cache invalidation, the do-no-harm guards).

Evidence and the green `SUMMARY.txt` live in [`tooling/out/h31/`](out/h31/SUMMARY.txt).

### H32 lane — real book-spread page-pick (`tooling/h32_*`), 0.3.10

The H32 claim ("on a book spread the detector picks the page that is **fully seen**, never the
page that is cut off at the frame edge") is proven on **real photographs of real open books**, not
on the H29 synthetic renders. Reproduce the whole lane in this order:

```bat
rem 1) fetch + provenance (Wikimedia Commons, CC0/CC-BY/CC-BY-SA/PD only; NC rejected)
rem    writes tooling\out\h32\queries.json, commons_candidates.json, provenance_thumbs.json
rem    originals -> E:\DocScanAI-cache\bookspread_real\original\  (thumbs -> \thumb\)
python tooling\h32_fetch_real.py search
python tooling\h32_fetch_real.py thumbs --limit 120
python tooling\h32_fetch_real.py sheet          rem contact sheets for visual selection

rem 2) grid overlays + annotation (agent vision on the labelled 10%/2% grid), then finalize
python tooling\h32_grid.py                       rem NNN_grid.jpg per pool image
rem    ... annotate: tooling\out\h32\h32_selection.json (fraction-space GT quads) ...
python tooling\h32_finalize.py                   rem -> gt_final.json (included/excluded + reasons)

rem 3) bundle (<=1400 px long edge, <=15 MB) into androidTest assets with full provenance
python tooling\h32_bundle.py                     rem -> app\src\androidTest\assets\bookspread_real\

rem 4) offline policy A/B (device red quads + offline M-LSD; real + synthetic, all variants)
python tooling\h32_policy_ab.py --variant h32_v2_novalley
python tooling\h32_replay.py --set real --variant shipped --tag red

rem 5) device lanes (emulator: tooling\h27_boot.cmd first)
call tooling\h32_lane.cmd H32RED  RealBookSpreadDetectionTest    rem red baseline (pre-change)
call tooling\h32_lane.cmd H32GATE BookSpreadDetectionTest MlsdCostBudgetTest
call tooling\h32_lane.cmd H32GREEN RealBookSpreadDetectionTest   rem post-change gate

rem 6) full regression + corpus + ship
call tooling\h7_tests.cmd                        rem 13 classes incl. RealBookSpreadDetectionTest
call tooling\corpus_run.cmd                      rem then tooling\out\h32\_corpus_gate_h32.py <run>
call tooling\h32_ship.cmd                        rem build+badging+certs+SHA+16k+smoke, self-terminating
```

| artefact | what it is |
|----------|------------|
| [`h32_fetch_real.py`](h32_fetch_real.py) | Commons discovery/download/sheet; licence + SHA-256 provenance, NC rejected at fetch |
| [`h32_grid.py`](h32_grid.py) | labelled 10 %/2 % coordinate grid overlays (the annotation is reproducible) + GT verification overlays |
| [`h32_finalize.py`](h32_finalize.py) | `h32_selection.json` → `gt_final.json` (fraction-space GT, exclusions with reasons) |
| [`h32_bundle.py`](h32_bundle.py) | downscaled bundle + `real_bookspread_manifest.json` (source URL, licence, attribution, SHA-256 original + rendition) |
| [`h32_policy_ab.py`](h32_policy_ab.py) | offline policy A/B: device red quads + offline M-LSD; merged/wrong-page/mismatch per variant; accumulates into `policy_ab.json` |
| [`h32_replay.py`](h32_replay.py) | offline replay of the NN-path (corner + trigger + `MlsdMergeRepair`) on real + synthetic |
| [`h32_lane.cmd`](h32_lane.cmd) | instrumented device lane → `tooling/out/h32/<Tag>/` |
| [`h32_ship.cmd`](h32_ship.cmd) | self-terminating ship lane with a **cert-continuity** gate ([`h32_cert_check.py`](h32_cert_check.py), exit-code gated; batch `find`/`findstr` misread a matching cert in this environment) |
| [`out/h32/`](out/h32/) | `RED_BASELINE.md` (device red, verbatim per-image table), `policy_ab.json`, `gtcheck/`, `tavily_discovery.md`, `SHIP.txt`, `HEARTBEAT.txt`, `SUMMARY.txt` |

Test classes added by H32: [`RealBookSpreadDetectionTest`](../app/src/androidTest/java/com/docscanai/RealBookSpreadDetectionTest.kt)
(instrumented, the 13th class in `h7_tests.cmd`) and
[`MlsdMergeRepairH32Test`](../app/src/test/java/com/docscanai/ml/MlsdMergeRepairH32Test.kt)
(JVM, 11 tests: adaptive window, all-candidate folds, sibling-aware `borderIsPaper`, paper-prior
selection, `Fractional.keep` round-trip).

### H33 lane - oracle harness (`tooling/oracle/`), 0.3.11

- [`oracle/oracle_query.py`](oracle/oracle_query.py) + [`oracle/oracle_batch.py`](oracle/oracle_batch.py) + [`oracle/oracle_calibrate.py`](oracle/oracle_calibrate.py) - one image / folder -> strict-JSON pseudo-GT corners (TL,TR,BR,BL, 0-1) from the LM Studio VL oracle (Qwen3-VL-8B), with an offline `--selftest` and a calibration verdict rule (pseudo-GT usable if mean corner error <= 3% diag and IoU >= 0.85, else candidate-filter only).

### H35 lane - REAL in-app detector path on the owner-110 photos (0.3.11, nothing shipped)

- [`h35_bundle.py`](h35_bundle.py) -> `assets/owner110/` (111 downscaled copies <= 1400 px, 25.3 MB, raw-pixel frame = oracle frame) + `oracle_manifest.json`; [`h35_lane.cmd`](h35_lane.cmd) runs [`Owner110RealPathTest`](../app/src/androidTest/java/com/docscanai/Owner110RealPathTest.kt) (NN + classical + gate + M-LSD, real path) and pulls the evidence JSON; [`h35_report.py`](h35_report.py) -> the table + peak histogram + worst-10 (H34 definitions, comparable to the NN-only mirror); [`h35_analyze.py`](h35_analyze.py)/[`h35_framecut.py`](h35_framecut.py) = the named candidate-fix measurements. Real-path recall 91/111 vs H34's NN-only 10/111 -> **no recall defect** (classical fallback rescues 81); every named fix measured and rejected; nothing shipped.

### H36 lane - SAM 2.1 segmentation oracle + classical-path fixes (0.3.11, nothing shipped)

- [`h36_sam.py`](h36_sam.py) = the SAM 2.1 Hiera Large ORACLE (onnx-community ONNX in E:\DocScanAI-cache\sam\, URLs + SHA-256 pinned in the header; DirectML GPU with CPU fallback): validated on 18 synthetic + 16 real known-GT and REJECTED as pseudo-GT (synth 3.51 %/0.855 vs the 3 %/0.85 gate, real 16.98 %/0.517 vs 8 %/0.50) -> measurement/subset-selection only (bow + shadow_frac in `sam_gt.json`); [`h36_lane.cmd`](h36_lane.cmd) + [`h36_report.py`](h36_report.py) = one-fix-at-a-time owner-110 lanes (tags baseline/fixab/fixc/fixd2/fixe/fixf/final). Only fixes 1a+1b (paper-interior + frame-contact rank) survive; 1c/1d/2e/2f each measured and dropped. Nothing shipped.

### H38 lane - 5 new struggle photos + hue-agnostic paper fix (0.3.13 / versionCode 16)
- [`h38_assets.py`](h38_assets.py)/[`h38_gt5.py`](h38_gt5.py)/[`h38_graticule.py`](h38_graticule.py)/[`h38_patch_gt.py`](h38_patch_gt.py) = bundle the 5 new ``20261025_*`` photos + vision-read GT corners (SAM full-frame masks unusable on table=page brightness); [`h38_lane.cmd`](h38_lane.cmd) + [`h38_gate.py`](h38_gate.py) = the one lane + gate (new-5 all detected, det>=85, wrong<=20, merged<=25); [`h38_ship.cmd`](h38_ship.cmd) = ship lane. Shipped fixes: hue-agnostic paper (PagePlausibility/paperInteriorOf/pageLikeness) + detector reset button (CameraScreen -> OpenCvQuadPageDetector.reset -> MlsdTrigger.Schedule.reset -> CaptureStateMachine.reset). CURVE_CUE 2e re-measured and reverted (det 108 but wrong 24/merged 29). Lane: 5/5 new detected 0 wrong-page; aggregate 96/17/27 (merged 27 vs gate 25 - reported).
