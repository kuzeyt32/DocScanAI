# Faint pencil / handwriting enhancement — H23 research note and measurement record

**Status: implemented, measured on real data twice (H23 amplitude, H25 shape), NOT shipped. The flag
is OFF by default and the UI affordance is dark.**

| | |
|---|---|
| Candidate | `HandwritingEnhancer` (`app/src/main/java/com/docscanai/imaging/HandwritingEnhancer.kt`), called from `ScanPipeline` stage 3b. Production default remains `Variant.FULL`; `Variant.RIDGE` is measurement-only |
| Data | 12 **real** public-domain pencil pages (Wikimedia Commons / DPLA), bundled in `app/src/androidTest/assets/pencil/` with per-file provenance |
| Verdict | **REVERT, twice** — H23: no amplitude variant met the contrast target (§6.4); H25: the Frangi/Hessian shape discriminant improved contrast only +5.27 % and broke PSNR/components/latency (§10.4) |
| Shipped baseline | `versionCode 9` / `versionName 0.3.6` — unchanged by H25; no 0.3.7 was produced |
| Evidence | H23: `tooling/out/h23/` — `device4/h23_results.json`, `device4/pairs/`, `sheets/`, `h23_summary.json`, `queries.json`, `real_pool_contact_sheet.jpg`. H25: `tooling/out/h25/` — `device_202601Pa_2106/h25_results.json`, `device_202601Pa_2106/pairs/`, `sheets/`, `SUMMARY.txt` |

> **Why this document starts with a negative result.** H22 was stopped because it measured the
> enhancement on data we generated. H23 re-ran the question on data the internet already contains.
> The answer changed from "the gates are met by construction" to "the gates are not met on real
> pencil pages", and that answer is the deliverable.

---

## 1. Scope and evidence taxonomy

Every claim below carries one of these tags:

| Tag | Meaning |
|---|---|
| `[LIT]` | standard, published technique; cited by name only where the source is unambiguous |
| `[PUB]` | a published number that is being quoted, not reproduced here |
| `[OURS]` | measured in this repository, on the hardware and data named next to it |
| `[UNVERIFIED]` | plausible but not measured here — never used to justify a parameter |

The document covers the *pencil/light-handwriting* mode only. It does not re-argue the warping,
detection or auto-capture work, which have their own notes and their own regression lanes.

---

## 2. The stages every document-enhancement pipeline has, and why

### 2.1 Illumination flattening (`[LIT]`)

A phone photo of a sheet is a product of reflectance and illumination. Dividing by a heavily
blurred copy of the image estimates the illumination and removes the gradient, so a stroke at the
top of the page and the same stroke at the bottom land at the same tone. This is the standard
flat-field/background-division step (Gonzalez & Woods, *Digital Image Processing*, background
estimation and division) and it is also what [`ClassicalEnhancer`](../app/src/main/java/com/docscanai/imaging/ClassicalEnhancer.kt:1)
already does with `·255/B`.

**What matters for us is the normalisation constant.** `·255/B` pushes paper to 255 and therefore
changes the page's brightness; that is fine for a "make it white" mode but it destroys any
fidelity metric on non-stroke pixels. Anchor the divisor to the *page's own paper level* instead and
the gain becomes exactly 1.0 on paper. `[OURS]` §6.3 shows what happens when this is done wrong.

### 2.2 Local contrast / CLAHE (`[LIT]`)

Contrast Limited Adaptive Histogram Equalisation (Zuiderveld 1994) equalises per tile with a clip
limit that caps the slope. It is the standard tool for faint text, and it works because it spends
each tile's dynamic range on whatever occupies that tile. On a page that is 90 % blank paper, most
tiles contain no strokes at all, so the only thing left to equalise is paper texture — which is
exactly the failure mode measured in §6.4: `+199 %` stroke contrast with `+134 %` blank noise on the
worst page and a 3.5× increase in disconnected components.

### 2.3 Stroke-band sharpening (`[LIT]`)

Unsharp masking with a coring/dead-zone term (Polesel, Ramponi & Reeves 2000) adds back a
high-pass band only where its magnitude exceeds a threshold, so flat paper grain is not amplified.
The H22 note proposed a fixed tone LUT on top of this. `[OURS]` that combination measured worse
than doing nothing (§6.2, revision 1).

### 2.4 Gentle white balance (`[LIT]`)

Grey-world (Buchsbaum 1980) assumes the scene averages to grey. Applied fully it paints an aged,
yellowed page blue-grey, which is wrong. Clamped to ±3 % it removes a cast without inventing
colour; below a 2 % channel deviation it is skipped entirely.

### 2.5 Why aggressive binarisation is not the answer (`[LIT]` + `[OURS]`)

Sauvola & Pietikäinen (2000) is the standard adaptive binarisation for documents, and it is in
[`ClassicalEnhancer`](../app/src/main/java/com/docscanai/imaging/ClassicalEnhancer.kt:1) for the BW
mode. Binarisation is *the wrong operation for faint pencil*: a faint mark is a partial deposit whose
depth is comparable to the paper's own texture (`[OURS]` 20.2 levels mean mark depth vs 13.5 levels
mean blank texture on the bundled pages). A threshold has to place pixels on one side or the other,
so it either drops the faint half of the stroke or promotes texture into "ink". The measured
`components_delta_pct` in §6.4 is the quantitative version of that sentence.

---

## 3. What the app's existing modes do to a pencil page (`[OURS]`)

| Mode | What it does | Effect on a faint pencil page |
|---|---|---|
| `original` | no enhancement; raw JPEG is always saved too | nothing is lost, nothing is gained |
| `enhanced` | illumination divide + grey-world + auto-levels + CLAHE + gamma + unsharp (see `ClassicalEnhancer`) | raises contrast, but auto-levels and the `·255/B` normalisation move the paper level, and the unsharp stage is not stroke-aware |
| `bw` | Sauvola binarisation after the same chain | drops faint strokes or turns paper texture into speckle (§2.5) |
| handwriting (this note) | flat-field + stroke-band boost, applied **after** the chosen mode | measured below; not shipped |

The raw capture is never passed through any of these stages; `ScanPipeline` saves the raw JPEG from
the unmodified `source` bitmap in every mode.

---

## 4. The implemented candidate

```text
source bitmap
  → (existing) detect → bounded 2000 px working image → warp → Enhancer(mode)
  → 3b HandwritingEnhancer  (only when the per-page flag is on AND mode != ORIGINAL)
  → save page JPEG + thumbnail                    (raw JPEG saved separately, untouched)
```

### 4.1 `Variant.FULL` — flat-field + difference-of-Gaussians stroke band

| Step | Operation | Parameters |
|---|---|---|
| flat-field | `out = ch · P / clamp(B, P/1.22, min(255, 1.06·P))`, `B` = Gaussian background of grey at 512 px long edge | kernel `min(sw,sh)/6` (odd), `P` = **median of the paper population** (pixels ≥ 0.80 · p92) |
| band | `band = blur(L, σf) − blur(L, σc)`, positive on thin dark structures | `σf = max(0.8, 0.0009·longEdge)` ≈ 1.3 px at 1400; `σc = max(3.0, 0.0032·longEdge)` ≈ 4.5 px |
| knee | `w = clamp((band − 3.0) / 4.0, 0, 1)` — paper texture below 3 levels is untouched | `KNEE_LOW 3.0`, `KNEE_HIGH 7.0` |
| ink guard | `f = clamp((85 − depth) / 40, 0, 1)` with `depth = blur(L,σc) − L` | `INK_LOW 45`, `INK_HIGH 85` |
| boost | `L' = L − 1.00 · max(band,0) · w · f` | `STROKE_BOOST 1.00` |
| white balance | grey-world, clamped | `[0.97, 1.03]`, skip if deviation < 2 % |

Design intent: paper stays bit-identical (weight 0 below the knee), black ink and the archival
mount stay bit-identical (guard 0 above `INK_HIGH`), and only the faint band in between is deepened.

### 4.2 `Variant.CLAHE_ONLY` — the single permitted alternative

Same flat-field, then CLAHE on Lab L (`clip 2.5`, `12×12` tiles), then the same white balance. This
is the textbook local-contrast answer and it is measured, not assumed.

### 4.3 The one-tap affordance and the flag

`HandwritingEnhancer.measure()` runs on a 512 px copy of every warped page and reports
`Suggestion(suggested, depth, coverage, paperLevel)`:

* paper region = `background ≥ 0.70 · paperMedian`;
* faint marks = `background − grey ≥ 8`, **eroded 3×3** so isolated grain cannot fake a stroke;
* suggested when `paper ≥ 60`, coverage in `0.05 % … 30 %`, mean depth in `9 … 70` levels.

`[OURS]` This detector fired on **11 of 12** real pencil pages (`suggested_depth` 16–26 levels) and
did not fire on page 00, whose marks are only 11.4 levels deep. It is a faint-mark detector, not a
handwriting classifier, and on its own it is the most reusable piece of H23.

Persistence: `PageEnhanceSettings.handwriting` and `ScannedPage.handwritingSuggested` are stored
through `SessionStore`; the global `SettingsRepository.handwritingEnhanceEnabled` flag defaults to
**false**, and because the measurement failed, `PageEditScreen` only renders the control when that
flag is on. With the shipped defaults the feature is unreachable.

---

## 5. Dataset — real pages, with provenance

**Rule applied: real, already-collected internet data was preferred over generated data for every
number in this document.** No synthetic page contributed to any gate decision.

### 5.1 What was searched, in order

1. **Hugging Face datasets API** — `?search=handwriting`, `pencil`, `handwritten`,
   `scanned%20documents` (raw JSON kept in `tooling/out/h23/`). Result: many handwriting datasets,
   but the pencil-page candidates are either synthetic renders, licence-restricted, or require
   accepting terms. No permissively licensed pencil-page set suitable for bundling was found.
2. **Zenodo / OpenML / academic sets** — the well-known handwritten benchmarks (IAM, NIST SD 19/6)
   are registration-gated and were excluded by policy, as the brief requires. They were not crawled
   exhaustively; the exclusion is a policy decision, not a finding.
3. **Wikimedia Commons** — 10 recorded queries through
   [`tooling/h23_fetch_real.py`](../tooling/h23_fetch_real.py:1), all logged to
   `tooling/out/h23/queries.json`:
   `pencil handwriting`, `handwritten in pencil`, `pencil written letter`, `pencil notebook page`,
   `pencil manuscript page`, `pencil exam paper`, `pencil school exercise book`,
   `faint pencil writing`, `pencil notes page`, `pencil draft manuscript`.
   323 unique candidates survived the size/mime/licence filters; licence mix: Public domain 230,
   No restrictions 50, CC BY 4.0 17, CC BY-SA 4.0 13, CC BY 3.0 5, CC0 3, other 5.
4. Pool inspection: `tooling/out/h23/real_pool_contact_sheet.jpg` (38 downloaded pages, 14.95 MB,
   cached at `E:\DocScanAI-cache\pencil\original\`). Most were ink letters or drawings — a
   handwriting search returns ink, not graphite — so the pool was narrowed by *title* to material
   whose catalogue record says "pencil manuscript", and then by *local stroke statistics*.

The decisive find is the DPLA upload of **Kate Chopin's original pencil manuscripts** ("Ti Frere" and
"Jacques"), which is unambiguously pencil-on-paper, public domain, and available at a usable size.

### 5.2 Bundled subset — 12 pages, 2,158,060 bytes

File, paper level, and the original's SHA-256 prefix (full manifest, including the Commons page URL,
direct file URL, licence URL and attribution, is `app/src/androidTest/assets/pencil/pencil_manifest.json`):

| Asset | Paper level | Mark depth (levels) | Blank texture (levels) | Suggested | SHA-256 (orig., 10) |
|---|---:|---:|---:|---|---|
| `pencil_real_00.jpg` | 173 | 11.4 | 7.2 | no | `3b5bb70c29` |
| `pencil_real_01.jpg` | 188 | 17.1 | 16.9 | yes | `56cdf57181` |
| `pencil_real_02.jpg` | 194 | 15.2 | 17.5 | yes | `e7a51d7fc8` |
| `pencil_real_03.jpg` | 199 | 20.8 | 14.4 | yes | `feee121347` |
| `pencil_real_04.jpg` | 204 | 22.4 | 12.2 | yes | `052ffd55fa` |
| `pencil_real_05.jpg` | 203 | 23.0 | 11.9 | yes | `d88e0ebe63` |
| `pencil_real_06.jpg` | 204 | 23.0 | 12.0 | yes | `7afe25605c` |
| `pencil_real_07.jpg` | 196 | 24.0 | 11.3 | yes | `186d60dcc9` |
| `pencil_real_08.jpg` | 202 | 21.3 | 13.5 | yes | `74b5fe482b` |
| `pencil_real_09.jpg` | 204 | 21.9 | 12.0 | yes | `634a8cde7a` |
| `pencil_real_10.jpg` | 199 | 26.1 | 13.1 | yes | `33b9d18529` |
| `pencil_real_11.jpg` | 188 | 16.2 | 20.0 | yes | `1c5287de9c` |

All twelve are **Public domain** (licence URL `http://creativecommons.org/publicdomain/mark/1.0/` in
the manifest), downscaled to ≤1400 px long edge at JPEG q85 and re-hashed (`asset_sha256`), with the
original file's `original_sha256` preserved. Nothing non-redistributable was bundled.

Two selection safeguards are worth recording because they were bugs first:
`[OURS]` a global-percentile contrast test picked low-contrast photographs of **ink** letters as
"pencil"; the rule now requires pencil in the catalogue title *and* a local-background stroke
statistic. `[OURS]` two uploads of the same physical page were both selected (their grey signatures
had a mean absolute difference of 0.54); duplicates are now rejected below `DUPLICATE_MAD = 3.0`.

### 5.3 The augmented tier, and how it is labelled

[`tooling/h22_pencil_gen.py`](../tooling/h22_pencil_gen.py:1) survives **only** as a "very faint"
stress tier applied *on top of* the real pages. Its output goes to
`E:\DocScanAI-cache\pencil\very_faint\` (never into the APK: writing into the bundled assets now
requires `--allow-assets`), its files are named `pencil_faint_XX.jpg`, and every manifest entry
carries `"tier": "very faint"`, `"synthetic": true`, `"real_data": false` plus
`derived_from {asset, sha256, source_page, licence, licence_url}`. Any number produced from it must
be written as "augmented from real page X", never as a real-data measurement.

`[OURS]` 12 pages were generated: mean stroke ratio 0.331 (min 0.205, max 0.500), 1,032,997 bytes
total. Two pages fall outside H22's 22–48 % band; that contract is now opt-in (`--strict-band`)
because a deliberately fainter tier is *supposed* to sit below it.

---

## 6. Measurement

### 6.1 Method (`[OURS]`)

Lane: `app/src/androidTest/java/com/docscanai/HandwritingEnhanceTest.kt`, run on the emulator
`docscan_dbg_api35` (x86_64 system image). It calls the **production** class that `ScanPipeline`
calls, on both variants.

Masks are computed **once on the input** and reused for every output; if the masks were recomputed
on the enhanced image, a contrast "gain" could be manufactured by the mask selecting different
pixels. Because the pages are archival scans with black mounts and torn edges, the masks are
deliberately conservative:

* **paper region** — `background ≥ 0.70 · paperP92`, eroded 9×9 twice (≈8 px), so mount boundaries
  and torn edges never enter a statistic;
* **stroke mask** — inside the paper region, `6 ≤ background − grey ≤ 70`: a *mark band* that
  excludes black ink (deeper than 70) and paper grain (shallower than 6);
* **blank mask** — inside the paper region, the stroke mask dilated 15×15 and removed; no amplitude
  threshold, so grain is measured over a fixed population.

Metric definitions (all against fixed masks, all in 0–255 levels):

| Metric | Definition |
|---|---|
| stroke contrast | `depth = mean(background_input − grey | stroke)`; gain = `depth_out/depth_in − 1` — the **input** background is the fixed reference, so the number answers only "did the marks get deeper?" |
| blank noise | `std(grey | blank)` relative to the input |
| non-stroke PSNR | PSNR between input and output over the blank mask (≥ 30 dB ⇒ RMS error ≤ 8.07 levels) |
| sharpness | variance of the Laplacian over the stroke mask, ratio out/in |
| continuity | connected components (area ≥ 12) of the stroke map under the same fixed masks |
| latency | `enhance()` at 2000 px long edge, 3 timed runs after a warm-up |

Gates: contrast **≥ +30 %**, noise **≤ +10 %**, PSNR **≥ 30 dB**, sharpness **≥ 1.00×**,
components **≤ 1.00×**, latency median **and** max **≤ 120 ms**.

### 6.2 What the first two designs did wrong (`[OURS]`)

**Revision 1 — mean-preserving flatten + paper-anchored tone LUT + cored unsharp.** Every gate
failed: contrast −3.5 %, non-stroke PSNR 22.8 dB, stroke sharpness 0.26×. Two anchor bugs, both
found by looking at the pairs rather than the numbers:

* the flatten normalised paper to `mean(grey)`. On a page with a 20–30 % black mount the mean sits
  ~25 % below the paper level, so the whole page was darkened;
* the tone LUT took `mean(L)` as "paper" — 128 on a page whose paper was at 203 — so every real
  paper tone fell into the identity branch and the pencil was never touched at all.

**Revision 2 — single-Gaussian high-pass (`D = blur(L,σ) − L`) + smoothed-detail boost.** Validated
the masks (contrast −35 %, PSNR 26.6 dB, sharpness 1.12×, noise −44 %) and exposed the real problem:
this paper's *texture* is a low-frequency 13.5-level signal that survives a σ≈1.3 blur, so the stage
subtracted ~6 levels of "detail" from every pixel — it was quietly smoothing the page, which is why
"blank noise" looked *better* while PSNR fell to 26.6 dB.

**Revision 3 — difference-of-Gaussians band-pass (Marr–Hildreth style) + paper-median anchor** is the
candidate reported below. The DoG rejects slow blobs and pixel grain by construction.

### 6.3 Input population — why this data is hard (`[OURS]`)

| Input statistic (12 pages) | Mean |
|---|---:|
| paper level | 196.2 |
| mark depth (stroke band 6…70) | **20.2 levels** |
| blank paper texture | **13.5 levels** |
| stroke coverage of the paper region | 10.3 % |
| pages where the faint-mark detector fires | 11 / 12 |

Blank texture is **67 % of the mark amplitude**. Any operator that is amplitude-gated enough to
protect the texture is also gated above the faintest marks; any operator that reaches the faintest
marks reaches the texture too.

### 6.4 Results — revision 3, final (`[OURS]`)

Per page (`F` = FULL, `C` = CLAHE-only; contrast/noise in %, PSNR in dB, sharpness as a ratio,
`comps` = connected components in the output stroke map):

| Asset | Paper | Depth | Texture | Sug. | F con | F noise | F PSNR | F sharp | F comps | C con | C noise | C PSNR | C sharp |
|---|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `pencil_real_00.jpg` | 173 | 11.4 | 7.2 | no | +10.2 | +26.0 | 25.7 | 1.10 | 1327 | +268.6 | +134.1 | 22.2 | 3.88 |
| `pencil_real_01.jpg` | 188 | 17.1 | 16.9 | yes | +19.1 | −28.3 | 28.6 | 1.01 | 769 | +229.7 | −2.7 | 26.9 | 3.81 |
| `pencil_real_02.jpg` | 194 | 15.2 | 17.5 | yes | +35.2 | −28.0 | 27.4 | 0.97 | 704 | +271.7 | −6.9 | 26.1 | 4.72 |
| `pencil_real_03.jpg` | 199 | 20.8 | 14.4 | yes | −0.6 | −50.1 | 27.2 | 1.00 | 465 | +195.6 | −20.6 | 24.9 | 2.82 |
| `pencil_real_04.jpg` | 204 | 22.4 | 12.2 | yes | −7.5 | −49.7 | 27.9 | 1.01 | 442 | +171.6 | −13.6 | 24.6 | 2.36 |
| `pencil_real_05.jpg` | 203 | 23.0 | 11.9 | yes | −4.5 | −48.4 | 28.0 | 1.01 | 458 | +166.7 | −12.8 | 24.2 | 2.19 |
| `pencil_real_06.jpg` | 204 | 23.0 | 12.0 | yes | −5.3 | −50.2 | 27.8 | 1.02 | 442 | +174.9 | −15.6 | 24.6 | 2.36 |
| `pencil_real_07.jpg` | 196 | 24.0 | 11.3 | yes | −11.4 | −39.5 | 29.3 | 1.07 | 568 | +155.0 | +2.3 | 25.1 | 2.10 |
| `pencil_real_08.jpg` | 202 | 21.3 | 13.5 | yes | −4.1 | −57.2 | 26.9 | 1.00 | 327 | +185.0 | −23.7 | 24.9 | 2.70 |
| `pencil_real_09.jpg` | 204 | 21.9 | 12.0 | yes | −6.5 | −52.9 | 27.7 | 1.01 | 417 | +175.9 | −15.5 | 24.4 | 2.41 |
| `pencil_real_10.jpg` | 199 | 26.1 | 13.1 | yes | −5.1 | −45.0 | 29.0 | 1.02 | 507 | +154.2 | −6.2 | 24.8 | 1.79 |
| `pencil_real_11.jpg` | 188 | 16.2 | 20.0 | yes | +6.9 | −31.6 | 27.6 | 1.02 | 559 | +238.4 | −10.4 | 24.9 | 3.33 |

Means and the mechanical verdict:

| Gate | Target | FULL | CLAHE-only |
|---|---|---:|---:|
| stroke contrast gain | ≥ +30 % | **+2.20 %** ✗ | +198.94 % ✓ |
| blank noise | ≤ +10 % | −37.92 % ✓ | +0.71 % ✓ (worst page **+134 %** ✗) |
| non-stroke PSNR | ≥ 30 dB | **27.75 dB** ✗ | **24.80 dB** ✗ |
| stroke sharpness | ≥ 1.00× | 1.02× ✓ | 2.87× ✓ |
| connected components | ≤ 1.00× | **2.29×** ✗ | **3.47×** ✗ |
| latency median / max @2000 px | ≤ 120 ms | **205 / 217 ms** ✗ | 111 / 140 ms ✗ |
| **verdict** | all of the above | **REVERT** | **REVERT** |

Revision comparison (same harness from revision 2 onward):

| Revision | Contrast | Noise | PSNR | Sharpness | Comps | Latency |
|---|---:|---:|---:|---:|---:|---:|
| 1 — tone LUT + unsharp | −3.5 % | −8.9 % | 22.8 dB | 0.26× | 0.87× | 126 / 155 ms |
| 2 — single-Gaussian detail | −35.3 % | −44.4 % | 26.6 dB | 1.12× | 1.68× | 207 / 246 ms |
| 3 — DoG band + paper median | +2.2 % | −37.9 % | 27.75 dB | 1.02× | 2.29× | 205 / 217 ms |
| CLAHE-only | +199 % | +0.7 % | 24.8 dB | 2.87× | 3.47× | 111 / 140 ms |

**Normal corpus pages** (12 pages, FULL): PSNR vs input ≥ 30 dB on all 12, i.e. the stage leaves
pages without faint marks measurably alone — the one gate the design was built to pass, and it does.

The visual check agrees with the table: in
`tooling/out/h23/sheets/pencil_real_05.jpg_zoom.jpg`, FULL is visually indistinguishable from the
input, while CLAHE makes the pencil plainly darker and simultaneously prints a cross-hatch of paper
texture across the blank areas.

### 6.5 Reading of the result

* **CLAHE is the only thing that actually makes faint pencil legible**, confirming the literature
  and the H22 expectation — but it pays +134 % blank noise on the worst page, 24.8 dB non-stroke
  PSNR and 3.5× more disconnected components. It is not shippable under these gates.
* **The DoG candidate is safe but too weak.** Where it does act, it removes texture (noise −38 %)
  rather than deepening marks, because the pass band that contains a 2–3 px stroke also contains
  this paper's 3–10 px texture blobs.
* **Latency fails independently of quality.** 205 ms median at 2000 px on this emulator is 1.7× the
  budget; only the CLAHE variant comes close (111/140 ms), and it fails everything else.

---

## 7. Established practice vs what we measured

| Claim | Source | `[OURS]` status |
|---|---|---|
| Dividing by a blurred background removes illumination gradients | `[LIT]` | reproduced; the *normalisation constant* is the part that decides whether fidelity survives |
| CLAHE lifts faint local contrast | `[LIT]` | reproduced: +199 % stroke contrast |
| CLAHE amplifies noise in near-empty tiles | `[LIT]` | reproduced and quantified: +134 % blank noise worst case, 3.5× components |
| Coring/dead-zone unsharp protects flat areas | `[LIT]` | reproduced only when the dead zone sits **above** the paper's texture amplitude (13.5 levels here), which then also blocks the faintest marks |
| Difference-of-Gaussians isolates stroke-scale structure | `[LIT]` | partially: it removes texture rather than amplifying marks at this texture/mark ratio |
| A +30 % contrast gain with ≤10 % noise growth is achievable for *faint pencil on aged paper* | `[UNVERIFIED]` in the literature we could use | **not achieved**; three designs measured, best honest contrast gain +2.2 % |
| Binarisation is unsuitable for faint graphite | `[LIT]` | consistent with `components_delta_pct` growth in every variant |

---

## 8. Residual risks and what would have to change

1. **The bundled pages are 1200 px Commons thumbnails**, i.e. already resampled, with baked JPEG
   artefacts. A full-sensor capture would have a different texture/mark ratio; the numbers here
   should not be extrapolated to a 12 MP capture without re-running the lane.
2. **Glossy or coated paper** would add specular structure that the DoG would treat as strokes.
3. **Coloured ink/pencil** (blue, red) is outside the tested population; the L-channel path assumes
   marks are darker than paper in luminance.
4. **Pencil lighter than the detector floor**: page 00 (11.4 levels) was correctly *not* suggested,
   so the affordance would not offer help exactly where help is wanted most.
5. **Timing is emulator-only.** No ARM hardware was measured. The 120 ms budget is not met even on
   the emulator for the FULL variant.
6. **A shape-based discriminator is the obvious next step**: strokes are long and thin while stains
   are blobs, so a Hessian/ridge (Frangi-style) response, or a stroke-width-consistency test, would
   separate them in a way that amplitude and scale cannot. That is a larger build with its own
   latency cost, and it must be re-measured on this same 12-page set before anything ships.

**Ship decision: none.** `versionCode 9 / 0.3.6` was not produced. The flag defaults to off, the
edit-screen control is only rendered when the flag is explicitly enabled, and RAW capture is
untouched in all cases.

---

## 9. Reproduction

```bat
REM 1. provenance lane (already run; artifacts are committed under tooling/out/h23/)
py -3 tooling\h23_fetch_real.py search
py -3 tooling\h23_fetch_real.py download --limit 38
py -3 tooling\h23_fetch_real.py sheet
py -3 tooling\h23_prepare_assets.py

REM 2. measurement lane (emulator must be booted and the APKs installed)
gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t app\build\outputs\apk\debug\app-debug.apk
adb install -r -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w -e class com.docscanai.HandwritingEnhanceTest ^
    com.docscanai.test/androidx.test.runner.AndroidJUnitRunner
adb pull /storage/emulated/0/Android/data/com.docscanai/files/h23 tooling\out\h23\deviceN

REM 3. tables + contact sheets
py -3 tooling\h23_summary.py
py -3 tooling\h23_sheets.py

REM 4. optional stress tier (never evidence, never in the APK)
py -3 tooling\h22_pencil_gen.py --pages 12
```

Note: `adb pull` creates a nested directory when the destination already exists; always pull into a
fresh name (`deviceN`) or read the newest `h23_results.json` via `h23_summary.py`.

---

## 10. Attempt 2 — reuse-first (H25)

H23 ended with a hard statement: a 20.2-level pencil mark sitting on 13.5-level texture cannot be
separated by any amplitude or scale operator, because the operator that lifts the mark lifts the
texture by the same factor. Attempt 2 was therefore opened as a **reuse hunt** — prefer published,
licence-clean work over another hand-rolled filter — and closed with exactly one implemented
candidate, measured once, on the same 12 pages, with the same masks and the same metrics.

### 10.1 What already exists, and whether we may use it

Licence was checked by fetching the licence file itself, not the README badge. "NC" means
non-commercial: this app ships commercially, so an NC asset cannot be bundled or downloaded at
runtime.

| Work | Task it actually solves | Licence (verified) | Weights / ONNX | Size | Usable here? |
|---|---|---|---|---|---|
| [DocShadow-ONNX-TensorRT](https://github.com/fabio-sim/DocShadow-ONNX-TensorRT) (Sim, port of CVH-Research DocShadow) | document **shadow/illumination** removal | MIT ([LICENSE](https://github.com/fabio-sim/DocShadow-ONNX-TensorRT/blob/main/LICENSE): "Copyright (c) 2023 Nick Chen / Fabio Milentiansen Sim") | yes: release `v1.0.0` assets `docshadow_sd7k.onnx`, `docshadow_jung.onnx`, `docshadow_kligler.onnx` | **120,041,674 B** each | **No.** Wrong problem (illumination, not mark depth) and wrong cost (§10.2) |
| [DocShadow-SD7K](https://github.com/CXH-Research/DocShadow-SD7K) (ICCV 2023) | shadow removal + SD7K dataset | MIT | training repo, archived read-only | — | No (same reason; dataset is shadows) |
| [DocEnTr](https://github.com/dali92002/DocEnTR) | document **binarisation** / enhancement, transformer | **CC BY-NC 4.0** ([LICENSE](https://github.com/dali92002/DocEnTR/blob/main/LICENSE)) | PyTorch checkpoints only | ~100 MB class | **No — NC licence** |
| [DE-GAN](https://github.com/dali92002/DE-GAN) | document de-warping / enhancement | GPL-3.0 **plus** README "only allowed for academic research use" | PyTorch | ~50 MB class | **No — NC + copyleft** |
| [CV-Reimplementation/TraditionalDocumentShadowRemoval](https://github.com/CV-Reimplementation/TraditionalDocumentShadowRemoval) | classic background-estimation shadow removal | MIT | none (methods only) | — | Already the basis of our flat-field stage |
| [onnx/models](https://github.com/onnx/models) | general vision zoo | Apache-2.0 | many | — | No document mark/text-depth model exists in it |

**Conclusion of the licence sweep:** there is no licence-clean, ONNX-ready model that does *faint
pencil depth*. The only permissively-licensed document models are shadow removers, and shadow
removal is precisely the part our flat-field stage already does (H23 measured it as a wash:
+2.20 % contrast).

### 10.2 Could the existing ONNX Runtime stack run a learned model inside budget?

We already ship ONNX Runtime 1.22.0 with the CPU EP only — no NNAPI/XNNPACK path is pinned for
scanning, and adding a new EP would be a new dependency. DocShadow runs at **256 × 256 tiles**
(`--img_size 256 256`). A 2000 × 2600 page is ~80 tiles. A CPU-EP U-Net-class forward pass at 256²
is ~15–40 ms per tile on emulator-class hardware, i.e. **1.2–3.2 s per page**, before post-process.
That is 10–25× over the 120 ms budget, and it would add 120 MB of assets to a 125 MB APK. A GPU/NNAPI
delegate would be a new pin and a new failure surface in the scan path — both out of scope for a
feature that is still dark.

### 10.3 Licence-free shape discriminants: which can separate a 20-level ridge from 13.5-level texture?

| Discriminant | Published basis | Distinguishes line vs. blob? | Practical cost | Decision |
|---|---|---|---|---|
| Frangi / Hessian ridge (vesselness) | Frangi et al. 1998, `[LIT]` | **Yes** — `R_B = |λ1|/|λ2|` is ≈0 for a line, ≈1 for a blob; `S = |λ2|` rejects weak grain | 3 Sobel passes on a bounded copy; all 8-bit after the gate | **Implemented** (the one candidate) |
| Stroke-width transform / stroke-width consistency | STT is the SWT literature's inverse; needs a binarised stroke map first | Yes, in principle | Needs a threshold that the paper texture already defeats | Rejected: chicken-and-egg on exactly the faint images that matter |
| Morphological path openings | Heijmans/Bazin; `[LIT]` | Yes for thin elongated sets | Not in OpenCV; a faithful implementation is multi-scale (a new dependency or several hundred lines) | Rejected for this attempt (cost, not concept) |
| Phase congruency / log-Gabor | Kovesi; `[LIT]` | Partly — responds to *all* coherent edges, including paper fibre and JPEG blocking | 4–6 scales of separable convolution | Rejected: no better than DoG on texture, several times the cost |

So the one candidate that is genuinely *shape*-based, cheap, and implementable without new
dependencies is the Frangi/Hessian ridge gate. That is what shipped behind the dark flag.

### 10.4 The candidate and its measured result

Implementation (`Variant.RIDGE`, `ridgeStrokeBoost`): the H23 stroke band is computed exactly as
before, but its increment is multiplied by
`w = ramp(|λ1|/|λ2|; 0.15..0.55) · ramp(|λ2|; 6..14 levels) · [λ2 > 0]`, evaluated on a
σ²-normalised Hessian of the smoothed L channel at a **bounded 1000 px** working copy, then resized
back. Knee, ink guard and BOOST (0.65) are unchanged; one parameter set, measured once.

12 real public-domain pencil pages, identical masks and metrics as H23
(`tooling/out/h25/device_202601Pa_2106/h25_results.json`):

| # | page | paper | mark depth (in) | suggested | contrast % | noise % | PSNR dB | sharp × | comps in→out | comps % |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `pencil_real_00` | 173.3 | 11.37 | no | +10.70 | +26.55 | 25.66 | 1.17 | 221→1322 | **+498.19** |
| 2 | `pencil_real_01` | 187.6 | 17.08 | yes | +20.71 | −27.59 | 28.66 | 1.17 | 359→764 | +112.81 |
| 3 | `pencil_real_02` | 194.1 | 15.20 | yes | **+36.17** | −27.90 | 27.40 | 1.10 | 343→706 | +105.83 |
| 4 | `pencil_real_03` | 198.9 | 20.77 | yes | +2.33 | −49.97 | 27.17 | 1.25 | 257→459 | +78.60 |
| 5 | `pencil_real_04` | 204.1 | 22.43 | yes | −3.75 | −49.42 | 27.93 | 1.32 | 238→447 | +87.82 |
| 6 | `pencil_real_05` | 202.7 | 23.04 | yes | +0.68 | −48.20 | 27.97 | 1.43 | 244→452 | +85.25 |
| 7 | `pencil_real_06` | 203.9 | 23.02 | yes | −1.43 | −50.10 | 27.75 | 1.35 | 211→449 | +112.80 |
| 8 | `pencil_real_07` | 196.1 | 24.00 | yes | −7.25 | −38.87 | 29.34 | 1.38 | 189→567 | +200.00 |
| 9 | `pencil_real_08` | 202.5 | 21.29 | yes | −0.91 | −57.05 | 26.91 | 1.28 | 263→327 | +24.33 |
| 10 | `pencil_real_09` | 204.3 | 21.93 | yes | −2.85 | −52.54 | 27.71 | 1.32 | 228→409 | +79.39 |
| 11 | `pencil_real_10` | 199.1 | 26.08 | yes | −0.14 | −44.71 | 28.95 | 1.38 | 231→496 | +114.72 |
| 12 | `pencil_real_11` | 187.7 | 16.21 | yes | +8.93 | −30.56 | 27.58 | 1.22 | 401→565 | +40.90 |
| | **mean** | | | **11/12 fired** | **+5.27** | **−37.53** | **27.75** | **1.28** | | **+128.39** |

Mechanical verdict against the H25 gate:

| Gate | Target | Measured | Result |
|---|---|---|---|
| stroke-to-background contrast | ≥ +30 % relative | **+5.27 % mean** (best +36.17 %, 5 of 12 pages negative) | **FAIL** |
| blank-region noise | ≤ +10 % | −37.53 % mean (one page +26.55 %) | PASS |
| non-stroke PSNR | ≥ 30 dB | **27.75 dB mean** (max 29.34) | **FAIL** |
| stroke sharpness | must not drop | 1.28× mean | PASS |
| component continuity | must not grow | **+128.39 % mean** (worst +498 %) | **FAIL** |
| latency @2000 px | ≤ 120 ms median | **142.5 ms median, 154 ms p95, 161 ms max** (36 samples) | **FAIL** |

Normal-paper corpus pages, same lane, to prove the stage would not silently damage ordinary scans:
mean non-stroke PSNR **25.41 dB**, worst `a_woman_fills_out_a_bear_information_management.jpg`
**20.05 dB**; `receiptswiss.jpg` components +68.27 %; mean contrast gain **−31.09 %**.

### 10.5 Why it failed, precisely

1. **The gate is present but the band is not the constraint.** Contrast gain (+5.27 %) is roughly
   what the un-gated FULL chain achieves (+2.20 %). Restricting the boost to elongated structures
   does not create more ink to boost: the increment is still `band · weight`, and on these pages
   `band` *is* the whole signal — the marks are only 20 levels deep, so a knee of 3..7 levels plus a
   boost of 0.65 can deepen a core by a few levels at most. The shape gate improves *specificity*,
   not *amplitude*.
2. **Specificity did not actually improve.** Components grew +128 % on the pencil pages and +68 % on
   a printed receipt: `R_B` on a 1200 px Commons thumbnail with baked JPEG blocking is not ≈0.1 for
   pencil, because the stroke is 2–3 px wide and the Hessian at σ≈2 px sees the JPEG ringing as part
   of the structure. The discriminator was designed for clean strokes and measured on resampled ones.
3. **The 1000 px Hessian costs more than it saves.** Resizing to 1000 px, three Sobels, an
   eigenvalue expansion and a resize back costs ~40 ms on top of the 8-bit band/guard stages, hence
   142.5 ms median — over the 120 ms budget before any of the quality gates are even considered.
4. **PSNR and components move together.** Both fail for the same reason: the stage changes pixels in
   the non-stroke region on pages whose paper level is high (204-level paper leaves little headroom
   before clipping), so "blank" samples move and the fixed-level component counter fragments.

The picture is honest and consistent: attempt 2 is a **better-targeted operator that produces no
more contrast than attempt 1**, and it pays for that with latency and continuity.

### 10.6 What would actually be required

* **Resolution, not another filter.** These are 1200 px thumbnails: a pencil stroke is 2–3 px and the
  Hessian cannot separate it from JPEG ringing. The same shape test on a 12 MP capture (stroke
  10–15 px) is a *different measurement* and would have to be re-run on full-resolution pencil scans;
  the bundled corpus cannot answer it.
* **A learned model, if one is wanted, must be trained or licensed for faint-graphite depth.** No
  such licence-clean checkpoint exists today (§10.1). DocShadow is the closest permissive artefact
  and solves illumination; running it costs 1.2–3.2 s/page at 256² tiles on the CPU EP and 120 MB
  of assets.
* **Or a genuinely local operator.** Path openings (multi-scale, not in OpenCV) or a stroke-width
  filter applied to a locally-adaptive binarisation would have to be implemented from the published
  papers — a build of its own, with the same 12-page gate.
* **Until one of those lands, the feature stays dark.** Shipping +5.27 % contrast with +128 %
  fragmented components and 27.75 dB PSNR would be a worse scan, not a better one.

**Ship decision: none.** `0.3.6 / versionCode 9` is unchanged. `Variant.FULL` remains the production
default, `Variant.RIDGE` stays in the source as the measured, non-default candidate, and with the
flag off no new code executes.

### 10.7 Reproduction (H25 lane)

```bat
gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
tooling\h25_lane.cmd                          REM instrument HandwritingEnhanceTest, pull pairs + json
py -3 tooling\h25_table.py                    REM per-page table, means, gate list
py -3 tooling\h25_sheets.py                   REM contact sheets -> tooling\out\h25\sheets\
```

The lane measures `Variant.RIDGE` only and never writes into the h23 folder; both lanes share the
same mask and metric code so the two attempts are directly comparable.
