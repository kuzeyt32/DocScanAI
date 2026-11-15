# Holdout 42 - strict visual audit

42 fresh photos (never used for tuning), detector build 0.3.16.

| verdict | photos |
|---|---|
| LOCK | 26 |
| PART | 5 |
| INNER | 2 |
| SPREAD | 5 |
| BAD | 4 |

**26 / 42 (62 %) locked the page on all four edges.**

Excluding the 5 photos where both leaves are fully visible and the target leaf is undefined: **26 / 37 ordinary photos are clean (70 %)**.

## Gate behaviour on this set

* gate failed on 5 photos: `20261115_192342`, `20261115_192347`, `20261115_192354_0`, `20261115_192441`, `20261115_192445`
* of the 6 hard failures (BAD + INNER), the gate flagged 5
* gate precision on this set: every flag was a real hard failure
* missed: `20261115_192442` (INNER, CORNER_MODEL)

## Per photo

| photo | provenance | gate | verdict | note |
|---|---|---|---|---|
| `20261115_192319` | CLASSICAL_FALLBACK | pass | PART | quad includes table at top-left; page's own edges sit inside it |
| `20261115_192321` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192323` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192327` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192328` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192333` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192334` | CLASSICAL_FALLBACK | pass | LOCK | marginal: bottom edge a few px past the page |
| `20261115_192337` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192337_0` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192340` | CORNER_MODEL | pass | LOCK | marginal: top/right edge ~1 % over |
| `20261115_192342` | CORNER_MODEL | **FAIL** | BAD | degenerate: one corner pulled to a diagonal across the page |
| `20261115_192343` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192343_0` | CORNER_MODEL | pass | SPREAD | both leaves fully visible; quad = union |
| `20261115_192347` | CORNER_MODEL | **FAIL** | BAD | degenerate diagonal + spread union |
| `20261115_192348` | CORNER_MODEL | pass | SPREAD | both leaves fully visible; quad = union |
| `20261115_192348_0` | CORNER_MODEL | pass | SPREAD | both leaves fully visible; quad = union |
| `20261115_192350` | CLASSICAL_FALLBACK | pass | SPREAD | narrow strip across both leaves |
| `20261115_192351` | CLASSICAL_FALLBACK | pass | SPREAD | wide quad across both leaves |
| `20261115_192352` | CLASSICAL_FALLBACK | pass | PART | missing the page's top edge, overhanging the bottom onto the table |
| `20261115_192354` | CLASSICAL_FALLBACK | pass | PART | quad is nearly the whole frame |
| `20261115_192354_0` | CLASSICAL_FALLBACK | **FAIL** | BAD | degenerate: triangle, half the page and background |
| `20261115_192356` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192357` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192359` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192359_0` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192402` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192403` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192406` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192407` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192410` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192411` | CORNER_MODEL | pass | PART | right edge stops ~3 % short of the gutter |
| `20261115_192416` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192417` | CORNER_MODEL | pass | LOCK | marginal: right edge ~1.5 % short of the gutter |
| `20261115_192441` | CORNER_MODEL | **FAIL** | BAD | degenerate: triangle across a green workbook cover |
| `20261115_192442` | CORNER_MODEL | pass | INNER | locked the white sticker panel lying on the green cover |
| `20261115_192444` | CORNER_MODEL | pass | LOCK | locked the white page; green cover behind it is not the page |
| `20261115_192445` | CORNER_MODEL | **FAIL** | INNER | locked the white sticker panel inside the green cover |
| `20261115_192450` | CLASSICAL_FALLBACK | pass | PART | overhangs left/bottom onto the table by ~3-4 % |
| `20261115_192454` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192455` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192457` | CORNER_MODEL | pass | LOCK |  |
| `20261115_192458` | CORNER_MODEL | pass | LOCK |  |
