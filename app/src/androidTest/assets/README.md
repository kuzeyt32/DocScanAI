# Test assets

The instrumented tests replay **real photographs** through the production pipeline. Those
photographs are the project owner's own pictures of documents, desks, rooms and book spreads, so
they are **not** published in this repository — only the manifests that describe them are.

## What is tracked

| File | Contents |
|---|---|
| `owner110/owner110_manifest.json` | 116 entries: image name, asset size, pixel size, downscale factor |
| `owner110/oracle_manifest.json` | pseudo ground-truth quads (`tl/tr/br/bl` in asset pixels) for the same 116 photos |
| `bookspread/book_spread_manifest.json` | synthetic control/sliver/gutter/spine cases with their expected quads |
| `bookspread_real/real_bookspread_manifest.json` | real book-spread photos: labels (merged / wrong-page / correct-page) + quads |
| `pencil/pencil_manifest.json` | pencil / faint-handwriting pages and their reference metrics |

No image bytes are included, so a fresh clone builds and unit-tests fine; the instrumented lanes
that need pixel data skip the missing assets (they assert on their presence, so expect a clear
failure rather than a silent pass).

## Reproducing the corpora

Everything that produced them is in `tooling/`:

| Asset set | Builder |
|---|---|
| `owner110` (downscaled copies of `corpus_owner/`, max long edge 1400, JPEG q92) | `tooling/h35_bundle.py`, geometry in `tooling/h34_oracle_run.py` |
| `corpus/` (benchmark + synthetic GT) | `tooling/corpus_run.cmd`, `tooling/h29_fixtures.py` |
| `bookspread/` (synthetic) | `tooling/h29_fixtures.py` |
| `bookspread_real/` | `tooling/h32_fetch_real.py` + `tooling/h32_bundle.py` |
| `pencil/` | `tooling/h22_pencil_gen.py`, `tooling/h23_fetch_real.py` |

Put your own JPEGs in the matching folder with the names listed in the manifest and the lanes run
unchanged. The oracle quads would have to be regenerated for a different photo set
(`tooling/h34_oracle_run.py`, or hand-marked and patched with `tooling/h38_patch_gt.py`).

## Why the manifests are enough to be useful

They carry the *decisions* the tests check (which photo is a merged spread, which corner set is
correct, what the expected quad is), so the measurement scripts under `tooling/` and the evidence
in `docs/evidence/` remain readable and re-runnable on any corpus you supply.
