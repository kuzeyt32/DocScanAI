# Detector lane - all 116 owner photos

Machine output of the real in-app detector path (`Owner110RealPathTest`) over the private
owner corpus. **The photos are not in this repository**; these are the measurements they
produced, published so the numbers can be checked without a phone.

Regenerate: `tooling\h38_lane.cmd <tag> Owner110RealPathTest`, then
`python tooling\h38_gate.py --tag <tag>`. Rebuild this page:
`python tooling\h42_publish_runs.py`.

## Summary

| class | 0.3.13 (`h38fixd`) | 0.3.14 (`h40relax`) | change |
|---|---|---|---|
| correct | 62 | 63 | +1 |
| merged | 17 | 17 | 0 |
| wrong-page | 17 | 17 | 0 |
| no-detection | 20 | 19 | -1 |
| **detected** | 96 | 97 | +1 |

### By provenance (detected photos only)

| provenance | 0.3.13 | 0.3.14 |
|---|---|---|
| CLASSICAL_FALLBACK | 65 | 64 |
| CORNER_MODEL | 31 | 33 |

Classes are a **strict partition**: every photo appears once, and a photo that is
both merged and wrong-page is counted as `wrong-page`. The H38 reports quote the two
flags independently (merged 27, wrong-page 17, 10 photos carry both), which is why
the `merged` row here reads 17 - the union of bad quads is the same 34 in both
tables (17 + 17).

### Photos whose decision changed (2)

| photo | 0.3.13 | 0.3.14 | IoU vs oracle | class |
|---|---|---|---|---|
| `20261024_185543.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.552 | correct |
| `20261024_185748.jpg` | UNKNOWN / detected=False | CORNER_MODEL / detected=True | 0.576 | correct |

## Per-photo measurements (0.3.14)

`iou` is against the pseudo-GT oracle quad; `corner %` is mean corner error as a
percentage of the image diagonal. Both are lower bounds (oracle corners are hand-
marked, ~5-10 % of the diagonal off on curved pages).

| photo | class | prov | gate | iou | corner % | area ratio | mlsd | ms |
|---|---|---|---|---|---|---|---|---|
| `20261018_210910.jpg` | correct | CORNER_MODEL | pass | 0.602 | 8.8 | 1.21 | yes | 213 |
| `20261018_213738.jpg` | correct | CORNER_MODEL | pass | 0.603 | 6.5 | 1.50 | yes | 149 |
| `20261020_195348.jpg` | correct | CORNER_MODEL | pass | 0.508 | 12.9 | 0.52 | yes | 113 |
| `20261020_195404.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.691 | 9.8 | 0.94 | yes | 124 |
| `20261020_200014.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 43 |
| `20261020_200042.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 38 |
| `20261024_185245.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 52 |
| `20261024_185249.jpg` | merged | CLASSICAL_FALLBACK | pass | 0.429 | 14.4 | 2.25 | yes | 116 |
| `20261024_185255.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 47 |
| `20261024_185303.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.460 | 12.1 | 1.32 | yes | 115 |
| `20261024_185307.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 39 |
| `20261024_185309.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 39 |
| `20261024_185315.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 42 |
| `20261024_185319.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.548 | 13.7 | 1.10 | yes | 123 |
| `20261024_185321.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.454 | 13.1 | 1.49 | yes | 112 |
| `20261024_185336.jpg` | correct | CORNER_MODEL | pass | 0.868 | 3.6 | 1.04 | yes | 100 |
| `20261024_185340.jpg` | correct | CORNER_MODEL | pass | 0.867 | 3.1 | 0.93 | yes | 79 |
| `20261024_185341.jpg` | merged | CLASSICAL_FALLBACK | pass | 0.560 | 11.2 | 1.73 | yes | 101 |
| `20261024_185345.jpg` | merged | CLASSICAL_FALLBACK | fail | 0.472 | 14.7 | 2.07 | yes | 132 |
| `20261024_185346.jpg` | merged | CLASSICAL_FALLBACK | pass | 0.479 | 15.5 | 2.05 | yes | 89 |
| `20261024_185351.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.000 | 64.4 | 966131.44 | yes | 92 |
| `20261024_185353.jpg` | correct | CORNER_MODEL | pass | 0.553 | 10.3 | 0.71 | yes | 88 |
| `20261024_185354.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.080 | 29.8 | 3.95 | yes | 94 |
| `20261024_185355.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.441 | 11.8 | 0.46 | yes | 94 |
| `20261024_185359.jpg` | merged | CLASSICAL_FALLBACK | pass | 0.479 | 12.2 | 1.69 | yes | 90 |
| `20261024_185404.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.261 | 17.9 | 0.26 | yes | 112 |
| `20261024_185405.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.501 | 14.7 | 0.50 | no | 52 |
| `20261024_185421.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 38 |
| `20261024_185422.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.608 | 12.7 | 0.99 | yes | 109 |
| `20261024_185423.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.289 | 16.9 | 1.98 | yes | 108 |
| `20261024_185425.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 41 |
| `20261024_185426.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 39 |
| `20261024_185449.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.544 | 8.4 | 0.58 | yes | 110 |
| `20261024_185450.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.502 | 16.7 | 0.50 | yes | 101 |
| `20261024_185452.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.189 | 21.4 | 5.29 | yes | 96 |
| `20261024_185453.jpg` | merged | CLASSICAL_FALLBACK | pass | 0.301 | 26.0 | 3.29 | yes | 110 |
| `20261024_185454.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.192 | 25.6 | 1.78 | yes | 109 |
| `20261024_185456.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 39 |
| `20261024_185458.jpg` | correct | CORNER_MODEL | pass | 0.541 | 12.7 | 0.58 | yes | 83 |
| `20261024_185503.jpg` | correct | CORNER_MODEL | pass | 0.774 | 5.2 | 1.26 | yes | 88 |
| `20261024_185504.jpg` | correct | CORNER_MODEL | pass | 0.756 | 6.5 | 1.07 | yes | 88 |
| `20261024_185505.jpg` | correct | CORNER_MODEL | pass | 0.831 | 3.9 | 0.88 | yes | 77 |
| `20261024_185506.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 37 |
| `20261024_185509(0).jpg` | merged | CLASSICAL_FALLBACK | pass | 0.424 | 15.8 | 1.92 | yes | 102 |
| `20261024_185509.jpg` | merged | CLASSICAL_FALLBACK | pass | 0.396 | 17.1 | 2.44 | yes | 103 |
| `20261024_185511.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 41 |
| `20261024_185518.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.336 | 21.3 | 2.97 | yes | 108 |
| `20261024_185522.jpg` | merged | CLASSICAL_FALLBACK | pass | 0.370 | 16.9 | 2.43 | yes | 109 |
| `20261024_185525.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.304 | 20.6 | 2.10 | yes | 89 |
| `20261024_185529.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.689 | 8.3 | 1.29 | yes | 99 |
| `20261024_185532.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 40 |
| `20261024_185534.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 43 |
| `20261024_185537.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.373 | 19.8 | 1.29 | yes | 95 |
| `20261024_185543.jpg` | correct | CORNER_MODEL | pass | 0.552 | 10.3 | 0.64 | yes | 84 |
| `20261024_185550.jpg` | correct | CORNER_MODEL | pass | 0.510 | 10.3 | 1.29 | yes | 81 |
| `20261024_185552.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.212 | 26.0 | 4.65 | yes | 88 |
| `20261024_185555.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.194 | 22.0 | 5.15 | yes | 96 |
| `20261024_185600.jpg` | merged | CLASSICAL_FALLBACK | pass | 0.562 | 10.3 | 1.61 | yes | 94 |
| `20261024_185602.jpg` | correct | CORNER_MODEL | pass | 0.804 | 4.3 | 1.06 | yes | 74 |
| `20261024_185608.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.724 | 9.3 | 1.38 | yes | 90 |
| `20261024_185610.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.483 | 19.0 | 0.78 | yes | 111 |
| `20261024_185736.jpg` | correct | CLASSICAL_FALLBACK | fail | 0.571 | 11.5 | 1.36 | yes | 90 |
| `20261024_185741.jpg` | correct | CORNER_MODEL | pass | 0.393 | 12.9 | 0.41 | yes | 78 |
| `20261024_185744.jpg` | correct | CORNER_MODEL | pass | 0.571 | 9.4 | 0.57 | yes | 85 |
| `20261024_185745.jpg` | correct | CORNER_MODEL | pass | 0.718 | 5.5 | 1.11 | yes | 74 |
| `20261024_185746.jpg` | correct | CLASSICAL_FALLBACK | fail | 0.609 | 10.4 | 1.36 | yes | 98 |
| `20261024_185748.jpg` | correct | CORNER_MODEL | pass | 0.576 | 9.2 | 0.58 | yes | 79 |
| `20261024_185750.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.251 | 20.5 | 0.26 | yes | 104 |
| `20261024_185752.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.175 | 25.7 | 5.70 | yes | 101 |
| `20261024_185753.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.128 | 24.9 | 2.01 | yes | 116 |
| `20261024_185758.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.386 | 13.3 | 0.39 | yes | 97 |
| `20261024_185800.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 38 |
| `20261024_185806.jpg` | correct | CORNER_MODEL | pass | 0.800 | 3.8 | 0.87 | yes | 94 |
| `20261024_185808.jpg` | correct | CORNER_MODEL | pass | 0.625 | 9.7 | 1.14 | yes | 76 |
| `20261024_185809.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.595 | 11.1 | 1.41 | yes | 90 |
| `20261024_185811.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.637 | 11.4 | 1.09 | yes | 89 |
| `20261024_190133.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.205 | 20.4 | 4.87 | yes | 90 |
| `20261024_190136.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 38 |
| `20261024_190138.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.000 | 20.2 | 321890.49 | yes | 108 |
| `20261024_190141.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.717 | 7.2 | 1.10 | yes | 92 |
| `20261024_190143.jpg` | merged | CLASSICAL_FALLBACK | pass | 0.363 | 14.1 | 2.48 | yes | 112 |
| `20261024_190145.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.300 | 16.6 | 0.81 | yes | 97 |
| `20261024_190146.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.202 | 20.9 | 2.15 | yes | 99 |
| `20261024_190149.jpg` | wrong-page | CLASSICAL_FALLBACK | fail | 0.000 | 28.2 | 746799.48 | yes | 93 |
| `20261024_190151.jpg` | wrong-page | CLASSICAL_FALLBACK | fail | 0.000 | 41.1 | 3.12 | no | 41 |
| `20261024_190152.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.009 | 41.3 | 117.47 | yes | 92 |
| `20261024_190154.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.495 | 28.5 | 0.94 | yes | 90 |
| `20261024_190157.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.057 | 38.1 | 17.63 | yes | 88 |
| `20261024_190206.jpg` | correct | CORNER_MODEL | pass | 0.109 | 22.0 | 1.92 | yes | 84 |
| `20261024_190210.jpg` | correct | CORNER_MODEL | pass | 0.462 | 11.4 | 0.46 | yes | 87 |
| `20261024_190212.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.754 | 6.0 | 1.26 | yes | 129 |
| `20261024_190213.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.246 | 18.8 | 1.15 | yes | 100 |
| `20261024_190215.jpg` | merged | CLASSICAL_FALLBACK | pass | 0.400 | 20.6 | 2.50 | yes | 88 |
| `20261024_190216.jpg` | correct | CORNER_MODEL | pass | 0.496 | 11.9 | 0.59 | yes | 90 |
| `20261024_190222.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.100 | 33.2 | 10.01 | yes | 91 |
| `20261024_190224.jpg` | merged | CLASSICAL_FALLBACK | pass | 0.399 | 10.9 | 2.14 | yes | 95 |
| `20261024_190226.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 39 |
| `20261024_190232.jpg` | correct | CORNER_MODEL | pass | 0.041 | 25.9 | 1.19 | yes | 76 |
| `20261024_190233.jpg` | merged | CLASSICAL_FALLBACK | pass | 0.366 | 20.3 | 2.49 | no | 42 |
| `20261024_190234.jpg` | correct | CLASSICAL_FALLBACK | fail | 0.128 | 29.8 | 2.63 | yes | 94 |
| `20261024_190235.jpg` | correct | CORNER_MODEL | pass | 0.500 | 14.4 | 0.86 | yes | 101 |
| `20261024_190237.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.000 | 17.8 | 470111.49 | yes | 90 |
| `20261024_190248.jpg` | correct | CORNER_MODEL | pass | 0.805 | 3.1 | 1.10 | yes | 77 |
| `20261024_190249.jpg` | correct | CORNER_MODEL | pass | 0.210 | 18.4 | 1.71 | yes | 74 |
| `20261024_190250.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.026 | 35.2 | 37.95 | yes | 89 |
| `20261024_190254.jpg` | correct | CORNER_MODEL | pass | 0.554 | 8.8 | 0.60 | yes | 86 |
| `20261024_190256.jpg` | correct | CORNER_MODEL | pass | 0.000 | 51.4 | 318461.69 | yes | 91 |
| `20261024_190259.jpg` | correct | CORNER_MODEL | pass | 0.414 | 9.0 | 1.69 | yes | 87 |
| `20261024_190300.jpg` | correct | CORNER_MODEL | pass | 0.135 | 21.0 | 2.21 | yes | 86 |
| `20261024_190301.jpg` | correct | CORNER_MODEL | pass | 0.257 | 13.9 | 0.90 | yes | 77 |
| `20261024_190304.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 42 |
| `20261025_130818.jpg` | correct | CORNER_MODEL | pass | 0.603 | 9.0 | 1.20 | yes | 125 |
| `20261025_130821.jpg` | merged | CLASSICAL_FALLBACK | pass | 0.488 | 16.0 | 1.75 | yes | 121 |
| `20261025_131201.jpg` | merged | CLASSICAL_FALLBACK | pass | 0.459 | 14.6 | 2.18 | yes | 105 |
| `20261025_131246.jpg` | correct | CORNER_MODEL | pass | 0.840 | 2.7 | 0.87 | no | 19 |
| `20261025_131249.jpg` | merged | CLASSICAL_FALLBACK | pass | 0.555 | 9.9 | 1.71 | yes | 110 |

## Corner-model blind spots (from the H40 trace)

`DOCSCAN_TRACE_JSON_ONLY=1` re-ran all 116 photos through the detector
with the corner heat maps recorded. Of the photos that ended with no quad,
these are the ones where the model had no confident corner at all

| photo | gate |
|---|---|
| `20261020_200014.jpg` | no-detection |
| `20261020_200042.jpg` | no-detection |
| `20261024_185245.jpg` | no-detection |
| `20261024_185255.jpg` | no-detection |
| `20261024_185307.jpg` | no-detection |
| `20261024_185309.jpg` | no-detection |
| `20261024_185315.jpg` | no-detection |
| `20261024_185421.jpg` | no-detection |
| `20261024_185425.jpg` | no-detection |
| `20261024_185426.jpg` | no-detection |
| `20261024_185456.jpg` | no-detection |
| `20261024_185506.jpg` | no-detection |
| `20261024_185511.jpg` | no-detection |
| `20261024_185532.jpg` | no-detection |
| `20261024_185534.jpg` | no-detection |
| `20261024_185748.jpg` | no-detection |
| `20261024_185800.jpg` | no-detection |
| `20261024_190136.jpg` | no-detection |
| `20261024_190226.jpg` | no-detection |
| `20261024_190304.jpg` | no-detection |

Per-channel heat peaks, quad and gate scalars live in the trace dump
(`tooling/out/h39/h40all/<photo>/trace.json`, regenerable).
