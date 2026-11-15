# H40 - the "AI ink map is bad" hypothesis: tested three ways

Owner report that started this: the curved-page attempt was rejected because "the ink map of the
AI was not good enough". There was no artifact to look at, and no measurement saying which part
of the pipeline produced a bad map. This folder is that artifact plus three tests of the claim.

## Setup

| Piece | What it does |
|---|---|
| `tooling/h39_dump.cmd` | replays bundled stills through the **real** detector (`DetectorTraceTest`) with `TraceDump` on; pulls the maps off the device |
| `DOCSCAN_TRACE_JSON_ONLY=1` | same lane, scalars only (corner-heat peaks, quad, gate) - the whole 116-photo set in one 12 s run |
| `tooling/h40_ink_probe.py` | re-implements the gate's rectification + INK extraction and runs it on the **detected quad and the oracle quad of the same photo** |
| `tooling/h40_ink_report.py` | builds `h39-ink/index.html` (local-only, embeds the photos) |
| `tooling/h40_quad_ink_eval.py` | the same comparison over all 116 photos, using the H38 real-path quads/labels |
| `tooling/h40_ink_trim.py` | tests whether the INK map can *fix* a merged quad by trimming it |

## Result 1 - the INK map is not the AI, and it is not the problem

The AI (DocAligner LCNet100) produces **four corner heat maps** (`heat_c0..c3`). The INK map is
`adaptiveThreshold(MEAN_C, block 25, C 10)` on the rectified page, inside
`PagePlausibility.analyseRectifiedInterior` - the gate's own text evidence.

Same photo, oracle quad vs detected quad, 34 bad quads out of 116 photos:
**mean text-score change = -0.183**. The *wrong* region scores *higher* than the correct page.
`text` sits at 0.85-0.95 for any textured region, correct or not, so it cannot reject a merged quad.

Example (`20261025_131201`, merged): the green `final_quad` in
`h39-ink/img/20261025_131201_card.jpg` (local build) is page + desk; the INK map is a clean,
dense text mask of page **and** desk edge - nothing is "bad" about it, it simply describes the
wrong area.

## Result 2 - no INK-side feature separates bad quads from good ones

116 photos, 34 bad / 62 good ([`quad-vs-ink/SUMMARY.md`](quad-vs-ink/SUMMARY.md)):

| feature | median bad | median good | AUC |
|---|---|---|---|
| `border_ink` (ink in the outer 12 % band) | 0.093 | 0.089 | 0.462 |
| `ink_ratio` (centre vs border ink) | 0.576 | 0.556 | 0.529 |
| paper-mode fraction of the border band | 0.546 | 0.580 | 0.431 |
| paper histogram mode | 143.0 | 154.5 | 0.306 |
| `text` | 0.848 | 0.874 | 0.454 |

All ~0.5 or inverted: a margin/paper guard built on these would reject good photos at the same
rate as bad ones. (The one *strong* signal is geometric, not ink: quads with >= 3 corners pinned to
the frame border are 14 bad / 3 good - but the ranker already penalises frame contact,
`W_RANK_FRAME`, and H35/H38 measured that hardening it loses more detections than it saves.)

## Result 3 - a better INK map does not change any decision

* **Curvature flat-field** (`divide(roi, GaussianBlur(roi, sigma=21))` before thresholding): text
  score changes by -0.03...0.03 on all 21 quad/photo pairs - no gain.
* **Sauvola**: collapses (glyph count 200+ -> 2), i.e. the current adaptive threshold is already
  the right local operator at this scale.
* **INK trim** (shrink the quad to the text-mass bounding box when the removed band is <= 2 % ink,
  swept at 0.12/0.18/0.25 minimum cuts, [`ink-trim`](ink-trim/SUMMARY.md)): mean IoU 0.422 -> 0.426
  overall, **1** bad quad improved, **1** worsened. Noise, not a fix.

## Result 4 - what the trace data DID yield: one weak corner channel

This is the one finding that contradicted the original hypothesis in a useful way. Of the 20
no-detection photos, **19** have two or more corner channels at ~0.00-0.10 (the model is blind
there - nothing to recover). Exactly **one**, `20261024_185748`, has three confident corners and
one weak one: peaks `0.26 / 0.36 / 0.73 / 0.51`. The per-channel floor of 0.30 discarded a page the
model had actually found, and its quad scores IoU 0.57 against the oracle.

Fix (`OpenCvQuadPageDetector.postprocessCornerHeat`): read all four peaks first, then apply
`CORNER_PEAK_MIN_RELAXED = 0.22` instead of `0.30` **only when the other three are >= 0.35**
(`CORNER_PEAK_STRONG`). A blind photo cannot reach that condition, so this is not a global
threshold drop.

Device lane, 116 owner photos (`tooling/h38_lane.cmd h40relax Owner110RealPathTest`):

| metric | before | after |
|---|---|---|
| detected | 96 | **97** |
| CORNER_MODEL | 31 | **33** |
| no-detection | 20 | **19** |
| wrong-page | 17 | 17 |
| merged | 27 | 27 |

Two photos changed, both improved: `20261024_185748` (no-detection -> correct page, IoU 0.576) and
`20261024_185543` (classical -> corner model, still correct, IoU 0.552). Nothing got worse.

On the re-traced card (`h39-ink/img/20261024_185748_card.jpg`, local build) the recovered page's
own INK map now matches the oracle's (text 0.92 vs 0.93) - the ink was never the problem, the quad
was missing.

## What this means for the next attempt

The failure is upstream of the ink map, in **which quad the classical fallback picks** (all 34 bad
quads were `CLASSICAL_FALLBACK`; all 31 `CORNER_MODEL` quads were correct), and in the **corner
model being blind** on 19 of the 20 no-detection photos (two or more channels at ~0.00-0.10).

The next lever is therefore **candidate selection**, not thresholds: a merged candidate (page +
table union) is score-accepted before ranking and wins on area. The H38 note records the measured
attempts (raising `MIN_SCORE` down to 0.45 lets wrong quads in; hardening the frame-contact rank
weight costs detections). A page-segmentation cue, or a rank term that can express "this region
has a paper margin on three sides and continues past the frame on the fourth", is the untested
direction - and `h40_quad_ink_eval.py` / `h40_ink_trim.py` are the harness to measure it before
shipping.

## Regenerate

> **The generated images are not published.** The cards and the oracle panels under
> `h39-ink/` embed crops of the owner's own photographs, so `.gitignore` excludes
> `docs/evidence/h39-ink/img/` and `index.html`; only these generators and the measured
> summaries are in the repository. Run the commands below to build them locally.

```bat
tooling\h39_dump.cmd h39demo                                  :: 12 sampled photos, full maps
set DOCSCAN_TRACE_JSON_ONLY=1 && tooling\h39_dump.cmd h40all all   :: 116 photos, scalars only
python tooling\h40_ink_probe.py  tooling\out\h39\h39demo
python tooling\h40_ink_report.py tooling\out\h39\h39demo tooling\out\h40_ink_probe --out docs\evidence\h39-ink
python tooling\h40_quad_ink_eval.py tooling\out\h38\h38fixd\owner110_realpath.json
python tooling\h40_ink_trim.py      tooling\out\h38\h38fixd\owner110_realpath.json
```

The photo corpora themselves (`app/src/androidTest/assets/**/*.jpg`) are likewise local-only; see
[`app/src/androidTest/assets/README.md`](../../app/src/androidTest/assets/README.md) for how each
set is rebuilt and what its manifest contains.
