# The quad ceiling: is a four-corner detector actually the limit?

**Short answer: no.** A quadrilateral can represent these page masks almost perfectly (median
ceiling **0.98**). The remaining loss is choosing the *wrong* quad, not the fact that a quad has
four corners.

This was measured because the opposite was a reasonable hypothesis: a curved page is not a
quadrilateral, so either the quad covers all the paper and spills onto the background, or it stays
inside and misses the curved edges. If true, the score would be charging the detector for a shape
it cannot represent, and the fix would be polygonal crops instead of four corners.

It is not true on this data. Instruments: `h66_ceiling.py`, `h67_oneleaf.py`,
`h67b_oneleaf_owner.py`, `h68*_extend*.py`.

## 1. Ceiling per photo

`h66_ceiling.py` hill-climbs all 8 coordinates of a quad to maximise IoU against the SAM page
mask, from three different starting quads, and reports the best it finds. That is an upper bound
on what any 4-corner detector could achieve *on that photo*.

holdout42, 42 photos:

| | value |
|---|---|
| ceiling, median | **0.98** |
| ceiling, minimum | 0.63 |
| photos where NO quad can exceed 0.90 | **2 / 42** |
| photos that could reach IoU >= 0.85 | **40 / 42** |
| photos the detector actually reaches | **23 / 42** |

So 17 photos are lost to quad *choice*, and only 2 to quad *shape*. The two shape-limited photos
are `20261115_192444` (the sticker-sheet ambiguity - which object is "the page") and
`20261115_192445` (also a referee case, see `0.3.18-what-remains.md`).

**This answers the "more detailed crops" question:** polygonal or curved crops are not needed to
cover the page. They may still be worth having for the *warp* (straightening curved text), but they
would not raise coverage, because coverage is already achievable with four corners.

## 2. Where the lost 17 actually go: one-leaf locks

`h67_oneleaf.py` classifies a photo as a one-leaf lock when all of:

* precision >= 0.95 (the quad is entirely on paper),
* the target is 1.4-2.8x the quad's area,
* the uncovered region is ONE connected blob at least 0.45x the quad (the other leaf), rather than
  a ring around the quad (which would mean imprecise corners).

holdout42: **6 photos** - `192340`, `192342`, `192347`, `192354_0`, `192356`, `192442`.
Using the mask as an oracle, extending each to include the other blob would score:

| photo | now | if extended |
|---|---|---|
| `192347` | 0.48 | **0.94** |
| `192342` | 0.45 | **0.91** |
| `192340` | 0.43 | **0.91** |
| `192356` | 0.65 | 0.96 |
| `192442` | 0.64 | 0.92 |
| `192354_0` | 0.57 | 0.90 |

owner110: **9 of 94 photos (10 %) show the same signature** (`h67b`), so this is a general
detector weakness, not a quirk of the 42 new photos. `192442` is also the sticker-panel case, so
one rule would address both failure modes.

**Correction to an earlier claim.** `0.3.18-what-remains.md` said only 1 of 42 photos was a
one-leaf lock. That was wrong: `h61b_leaf.py` required `pointgrid.spread == True`, and that flag
means "two SAM masks were merged". When SAM returns the whole open spread as a single mask - its
usual behaviour - the flag is False even though the target is a spread. The test therefore
excluded exactly the cases it was looking for. `h67` replaces it with the geometric test above,
which does not depend on how the mask was assembled.

## 3. Can a pixel-only rule find those extensions? Two attempts

### v1 - luma walk (`h68_extend_rule.py`) - NEGATIVE

Walk outward from each edge while the luma stays close to the quad's interior, bridge a short dark
run (a gutter), stop on non-paper.

Result: fired on 29 photos, helped 9, **hurt 19**. Same root cause as three earlier bugs: a white
page on a white table has no luma step, so the walk runs straight off the paper. Not shippable.

### v2 - require printed content + refuse frame-cut regions (`h68b_extend_ink.py`)

Two additions, both grounded in measurements already in this repo:

1. **Ink test.** Only add a region that sits on image edges. When the SAM masks were being
   repaired, ink density separated page from desk with a measured 4x gap (desk <= 0.013, printed
   page >= 0.056). A facing page is printed; a table is not.
2. **Frame guard.** Refuse an extension that pushes the quad into the picture border when it was
   clear of it before. This is the owner's "<25 % visible" rule: a sheet cut by the frame is only
   partly seen, so it is not part of the target. It removes exactly the three photos v2 initially
   damaged (`192327`, `192333`, `192334`) - the next page of a stack, not a facing leaf.

holdout42 at ink threshold 0.08:

| | before | after |
|---|---|---|
| passing (IoU >= 0.85) | 23 | **25** |
| mean IoU | 0.757 | **0.808** |
| fired / helped / hurt | - | **6 / 6 / 0** |

owner110 (scored against its own oracle, the same geometry as the published figure):

| | before | after |
|---|---|---|
| passing (IoU >= 0.5) | 54 | 54-55 |
| mean IoU | 0.537 | 0.540-0.546 |
| fired / helped / hurt | - | 7 / 5 / 1 |

No photo that was **already passing** gets worse on either set. The single owner110 casualty
(`20261024_190146`) moves 0.35 -> 0.24, i.e. it was already failing.

## 4. Why this is NOT shipped

Three reasons, in order of weight:

1. **It would destroy the only clean test set.** `ink_min = 0.08` was chosen by scanning
   holdout42. Once a threshold is fitted to the holdout, holdout42 stops being held out and I lose
   the ability to measure any future change honestly. The gain is +2 photos; the cost is my
   measuring instrument.
2. **The gain is small and the margin thin.** +2 of 42, from a rule that fires on 6. It is not
   obviously better than noise at that sample size.
3. **Unseen-data risk.** The rule fires whenever printed matter continues outside the quad. On a
   page lying on a magazine or a newspaper it would merge the two, and no photo in either set
   tests that.

The prototype is kept, with its numbers, so this is a decision that can be revisited rather than a
dead end. To try it: the rule is `extend()` in `tooling/h68b_extend_ink.py`; the only tuned
constant is `INK_MIN = 0.08`.

## 5. What would actually move the number

The ceiling says 17 photos of headroom and the extension rule recovers 2 of them. The gap is
therefore in the **corner model's choice of quad**, not in post-processing. Concretely:

* **A spread-aware page model.** The current model is a document corner detector
  (DocAligner LCNet100) and behaves as if a document is one page; on an open spread it picks a
  leaf. This is the single highest-value change and it needs a model that either sees both leaves
  or predicts a page *region* rather than four corners.
* **Then re-measure.** `h66_ceiling.py` gives the target: 40 of 42 are reachable.

Everything here was measured by replaying photos through the real detector on an emulator. The
absolute IoU values depend on the SAM mask quality; the ceilings and the before/after pairs are the
reliable parts.
