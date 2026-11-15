# Corner signatures - what the returned quad is actually touching

Owner report: *"it detects the box inside the page ... one of the 4 points usually goes to one of
the corners of the box, so usually just 1 or 2 corners is detected as the page."*

This page tests that description corner by corner, on the **0.3.16 build**, using the 116-photo
owner corpus. Numbers come from `tooling/h45_corners.py` (lane `h44trace`, JSON-only trace of the
real detector). No photo data is published - only measurements.

## How a corner is judged

For every returned corner, against the hand-marked page quad:

| label | meaning |
|---|---|
| `page` | within 10 % of the page side of a real page corner |
| `inset` | at least 6 % *inside* the page and far from every page corner - i.e. on a box corner |
| `outside` | at least 6 % *outside* the page |
| `edge` | on the page boundary, but not on a corner |

## Results (94 scorable photos)

| signature | photos | share |
|---|---|---|
| page (all 4 corners on the page) | 19 | 20 % |
| **pure inner box** (all 4 corners inset, quad <= 85 % of the page) | **1** | 1 % |
| shrunk | 3 | 3 % |
| over-extended (quad > 15 % larger than the page) | 34 | 36 % |
| mislocated (quad covers < 75 % of the page) | 37 | 39 % |

**7 photos** match the owner's description: 1-2 corners on page corners, the remaining 2-3 corners
inside the page. **0 photos** have exactly 2 page corners + 2 box corners.

So the "box inside the page" failure is real but rare in the still path, and it is **not** the
dominant failure. The dominant failure is the opposite: the quad **overshoots** the page (34) or
lands on a different region altogether (37). The single clearest box-lock is
`20261024_185404`, and it came from **`CLASSICAL_FALLBACK`**, not the corner model.

## The 4 quads that sit entirely inside the page

| photo | provenance | quad area vs page | what the overlay shows |
|---|---|---|---|
| `20261024_185404` | CLASSICAL_FALLBACK | 0.26 | the **printed frame around the article**, inset inside the paper - a genuine inner-box lock, from the classical path |
| `20261024_185405` | CORNER_MODEL | 0.20 | the **right-hand page** of an open two-page spread; the annotation covers the whole spread |
| `20261024_185450` | CORNER_MODEL | 0.36 | one page of a **spiral-bound** spread; the annotation covers the whole spread |
| `20261024_190141` | CORNER_MODEL | 0.50 | the **white sheet lying on** the green workbook; the annotation covers the green cover |

Two of these are arguably *correct* detector behaviour in a document scanner (one leaf of a spread,
the loose sheet on top) against an annotation that says "the whole spread / the cover". One is a
genuine frame lock, and it is classical.

## Oracle damage (why earlier numbers were wrong)

`tooling/oracle_quality.py` found **9 photos** whose annotation cannot be scored:

| photo | problem |
|---|---|
| `20261024_185351`, `190138`, `190149`, `190237`, `190256` | placeholder - all four corners collapsed onto the frame corner |
| `20261024_190152` | degenerate - quad is 0.67 % of the frame |
| `20261024_190154`, `190157`, `190222` | duplicated corner - one side has zero length |

Against a zero-area oracle, IoU is 0 by construction and `merged` (`area_ratio >= 1.5`) is
infinite, so the old reports produced **fabricated** wrong-page/merged flags on those rows. Four of
the owner's twelve green-workbook photos are in this set, which is why they looked like detector
failures.

`wrong-page` and `merged` are now computed only on scoreable rows, and `docs/runs/detector-116.md`
reports the strict metric alongside the gate label.

## Strict detector quality (IoU >= 0.5 against the oracle)

| build | quads that are the page | share | mean IoU |
|---|---|---|---|
| 0.3.15 (`h40relax`) | 40 / 88 scoreable detections | 45 % | 0.459 |
| **0.3.16 (`h44crop`)** | **44 / 95** | **46 %** | **0.480** |

The gate's `correct` label means *detected and not flagged*, not *quad is the page*. It should not
be quoted as accuracy: on 0.3.16 it reads 88, while 44 quads actually match the page.

## Reproduce

```bat
set DOCSCAN_TRACE_JSON_ONLY=1
tooling\h39_dump.cmd h44trace all
python tooling\h45_corners.py h44trace
python tooling\h45_overlay.py h44trace      :: overlays, local only, not committed
```
