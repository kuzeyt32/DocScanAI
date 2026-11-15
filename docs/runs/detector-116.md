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
| correct | 63 | 84 | +21 |
| merged | 17 | 5 | -12 |
| wrong-page | 17 | 15 | -2 |
| no-detection | 19 | 12 | -7 |
| **detected** | 97 | 104 | +7 |

### By provenance (detected photos only)

| provenance | 0.3.15 | 0.3.16 |
|---|---|---|
| CLASSICAL_FALLBACK | 64 | 25 |
| CORNER_MODEL | 33 | 79 |

Classes are a **strict partition**: every photo appears once, and a photo that is
both merged and wrong-page is counted as `wrong-page`. The H38 reports quote the two
flags independently (merged 27, wrong-page 17, 10 photos carry both), which is why
the `merged` row here reads 17 - the union of bad quads is the same 34 in both
tables (17 + 17).

### Photos whose decision changed (46)

| photo | 0.3.15 | 0.3.16 | IoU vs oracle | class |
|---|---|---|---|---|
| `20261020_195404.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.834 | correct |
| `20261020_200042.jpg` | UNKNOWN / detected=False | CORNER_MODEL / detected=True | 0.293 | correct |
| `20261024_185245.jpg` | UNKNOWN / detected=False | CORNER_MODEL / detected=True | 0.484 | correct |
| `20261024_185249.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.603 | correct |
| `20261024_185255.jpg` | UNKNOWN / detected=False | CORNER_MODEL / detected=True | 0.269 | correct |
| `20261024_185341.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.889 | correct |
| `20261024_185345.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.901 | correct |
| `20261024_185351.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.000 | correct |
| `20261024_185354.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.206 | correct |
| `20261024_185355.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.679 | correct |
| `20261024_185359.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.435 | correct |
| `20261024_185405.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.204 | correct |
| `20261024_185421.jpg` | UNKNOWN / detected=False | CORNER_MODEL / detected=True | 0.595 | correct |
| `20261024_185449.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.757 | correct |
| `20261024_185450.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.364 | correct |
| `20261024_185453.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.000 | correct |
| `20261024_185454.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.000 | correct |
| `20261024_185456.jpg` | UNKNOWN / detected=False | CORNER_MODEL / detected=True | 0.000 | correct |
| `20261024_185509.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.872 | correct |
| `20261024_185518.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.634 | correct |
| `20261024_185529.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.335 | correct |
| `20261024_185534.jpg` | UNKNOWN / detected=False | CORNER_MODEL / detected=True | 0.511 | correct |
| `20261024_185600.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.735 | correct |
| `20261024_185608.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.662 | correct |
| `20261024_185610.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.476 | correct |
| `20261024_185736.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.612 | correct |
| `20261024_185758.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.874 | correct |
| `20261024_185809.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.463 | correct |
| `20261024_185811.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.798 | correct |
| `20261024_190138.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.000 | correct |
| `20261024_190141.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.497 | correct |
| `20261024_190143.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.166 | correct |
| `20261024_190146.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.351 | correct |
| `20261024_190149.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.000 | correct |
| `20261024_190151.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.406 | correct |
| `20261024_190154.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.134 | correct |
| `20261024_190212.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.844 | correct |
| `20261024_190213.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.256 | wrong-page |
| `20261024_190215.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.044 | correct |
| `20261024_190234.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.016 | correct |
| `20261024_190237.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.000 | wrong-page |
| `20261024_190250.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.050 | correct |
| `20261024_190304.jpg` | UNKNOWN / detected=False | CORNER_MODEL / detected=True | 0.350 | correct |
| `20261025_130821.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.702 | correct |
| `20261025_131201.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.813 | correct |
| `20261025_131249.jpg` | CLASSICAL_FALLBACK / detected=True | CORNER_MODEL / detected=True | 0.365 | correct |

## Per-photo measurements (0.3.16)

`iou` is against the pseudo-GT oracle quad; `corner %` is mean corner error as a
percentage of the image diagonal. Both are lower bounds (oracle corners are hand-
marked, ~5-10 % of the diagonal off on curved pages).

| photo | class | prov | gate | iou | corner % | area ratio | mlsd | ms |
|---|---|---|---|---|---|---|---|---|
| `20261018_210910.jpg` | correct | CORNER_MODEL | pass | 0.603 | 8.7 | 1.20 | yes | 252 |
| `20261018_213738.jpg` | correct | CORNER_MODEL | pass | 0.616 | 6.2 | 1.48 | yes | 209 |
| `20261020_195348.jpg` | correct | CORNER_MODEL | pass | 0.523 | 12.6 | 0.55 | yes | 200 |
| `20261020_195404.jpg` | correct | CORNER_MODEL | pass | 0.834 | 3.6 | 0.96 | yes | 93 |
| `20261020_200014.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 221 |
| `20261020_200042.jpg` | correct | CORNER_MODEL | pass | 0.293 | 14.5 | 1.85 | yes | 107 |
| `20261024_185245.jpg` | correct | CORNER_MODEL | pass | 0.484 | 12.9 | 0.56 | yes | 123 |
| `20261024_185249.jpg` | correct | CORNER_MODEL | pass | 0.603 | 6.3 | 0.67 | yes | 154 |
| `20261024_185255.jpg` | correct | CORNER_MODEL | pass | 0.269 | 17.5 | 1.18 | yes | 93 |
| `20261024_185303.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.460 | 12.1 | 1.32 | yes | 263 |
| `20261024_185307.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 170 |
| `20261024_185309.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 183 |
| `20261024_185315.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 185 |
| `20261024_185319.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.548 | 13.7 | 1.10 | yes | 257 |
| `20261024_185321.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.454 | 13.1 | 1.49 | yes | 236 |
| `20261024_185336.jpg` | correct | CORNER_MODEL | pass | 0.866 | 3.6 | 1.04 | yes | 83 |
| `20261024_185340.jpg` | correct | CORNER_MODEL | pass | 0.865 | 3.2 | 0.92 | yes | 85 |
| `20261024_185341.jpg` | correct | CORNER_MODEL | pass | 0.889 | 2.0 | 0.91 | yes | 93 |
| `20261024_185345.jpg` | correct | CORNER_MODEL | fail | 0.901 | 2.1 | 0.99 | yes | 80 |
| `20261024_185346.jpg` | merged | CLASSICAL_FALLBACK | pass | 0.479 | 15.5 | 2.05 | yes | 232 |
| `20261024_185351.jpg` | correct | CORNER_MODEL | pass | 0.000 | 56.8 | 282951.94 | yes | 100 |
| `20261024_185353.jpg` | correct | CORNER_MODEL | pass | 0.554 | 10.3 | 0.71 | yes | 123 |
| `20261024_185354.jpg` | correct | CORNER_MODEL | pass | 0.206 | 13.7 | 1.20 | yes | 99 |
| `20261024_185355.jpg` | correct | CORNER_MODEL | pass | 0.679 | 7.6 | 0.85 | yes | 88 |
| `20261024_185359.jpg` | correct | CORNER_MODEL | pass | 0.435 | 11.5 | 0.52 | yes | 81 |
| `20261024_185404.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.261 | 17.9 | 0.26 | yes | 263 |
| `20261024_185405.jpg` | correct | CORNER_MODEL | pass | 0.204 | 25.8 | 0.20 | yes | 96 |
| `20261024_185421.jpg` | correct | CORNER_MODEL | pass | 0.595 | 7.5 | 0.99 | yes | 88 |
| `20261024_185422.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.608 | 12.7 | 0.99 | yes | 233 |
| `20261024_185423.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.289 | 16.9 | 1.98 | yes | 229 |
| `20261024_185425.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 172 |
| `20261024_185426.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 163 |
| `20261024_185449.jpg` | correct | CORNER_MODEL | pass | 0.757 | 5.9 | 1.22 | yes | 83 |
| `20261024_185450.jpg` | correct | CORNER_MODEL | pass | 0.364 | 22.8 | 0.36 | yes | 84 |
| `20261024_185452.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.189 | 21.4 | 5.29 | yes | 219 |
| `20261024_185453.jpg` | correct | CORNER_MODEL | pass | 0.000 | 33.9 | 0.69 | yes | 139 |
| `20261024_185454.jpg` | correct | CORNER_MODEL | pass | 0.000 | 29.9 | 0.52 | yes | 141 |
| `20261024_185456.jpg` | correct | CORNER_MODEL | pass | 0.000 | 35.8 | 1.21 | yes | 125 |
| `20261024_185458.jpg` | correct | CORNER_MODEL | pass | 0.802 | 5.2 | 1.07 | yes | 86 |
| `20261024_185503.jpg` | correct | CORNER_MODEL | pass | 0.873 | 3.2 | 1.12 | yes | 84 |
| `20261024_185504.jpg` | correct | CORNER_MODEL | pass | 0.757 | 6.4 | 1.08 | yes | 87 |
| `20261024_185505.jpg` | correct | CORNER_MODEL | pass | 0.831 | 3.9 | 0.87 | yes | 77 |
| `20261024_185506.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 176 |
| `20261024_185509(0).jpg` | merged | CLASSICAL_FALLBACK | pass | 0.424 | 15.8 | 1.92 | yes | 223 |
| `20261024_185509.jpg` | correct | CORNER_MODEL | pass | 0.872 | 3.0 | 1.07 | yes | 221 |
| `20261024_185511.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 167 |
| `20261024_185518.jpg` | correct | CORNER_MODEL | pass | 0.634 | 7.2 | 0.68 | yes | 203 |
| `20261024_185522.jpg` | merged | CLASSICAL_FALLBACK | pass | 0.370 | 16.9 | 2.43 | yes | 225 |
| `20261024_185525.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.304 | 20.6 | 2.10 | yes | 234 |
| `20261024_185529.jpg` | correct | CORNER_MODEL | pass | 0.335 | 17.6 | 0.43 | yes | 228 |
| `20261024_185532.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 177 |
| `20261024_185534.jpg` | correct | CORNER_MODEL | pass | 0.511 | 12.4 | 0.62 | yes | 246 |
| `20261024_185537.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.373 | 19.8 | 1.29 | yes | 228 |
| `20261024_185543.jpg` | correct | CORNER_MODEL | pass | 0.689 | 6.2 | 0.79 | yes | 207 |
| `20261024_185550.jpg` | correct | CORNER_MODEL | pass | 0.513 | 10.2 | 1.29 | yes | 80 |
| `20261024_185552.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.212 | 26.0 | 4.65 | yes | 252 |
| `20261024_185555.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.194 | 22.0 | 5.15 | yes | 241 |
| `20261024_185600.jpg` | correct | CORNER_MODEL | pass | 0.735 | 5.8 | 1.11 | yes | 84 |
| `20261024_185602.jpg` | correct | CORNER_MODEL | pass | 0.800 | 4.4 | 1.06 | yes | 80 |
| `20261024_185608.jpg` | correct | CORNER_MODEL | pass | 0.662 | 7.2 | 0.67 | yes | 78 |
| `20261024_185610.jpg` | correct | CORNER_MODEL | pass | 0.476 | 15.0 | 0.86 | yes | 83 |
| `20261024_185736.jpg` | correct | CORNER_MODEL | pass | 0.612 | 8.3 | 1.40 | yes | 77 |
| `20261024_185741.jpg` | correct | CORNER_MODEL | pass | 0.393 | 12.8 | 0.41 | yes | 85 |
| `20261024_185744.jpg` | correct | CORNER_MODEL | pass | 0.549 | 9.7 | 0.55 | yes | 80 |
| `20261024_185745.jpg` | correct | CORNER_MODEL | pass | 0.574 | 9.0 | 0.66 | yes | 213 |
| `20261024_185746.jpg` | correct | CLASSICAL_FALLBACK | fail | 0.609 | 10.4 | 1.36 | yes | 240 |
| `20261024_185748.jpg` | correct | CORNER_MODEL | pass | 0.694 | 6.8 | 0.69 | yes | 88 |
| `20261024_185750.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.251 | 20.5 | 0.26 | yes | 240 |
| `20261024_185752.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.175 | 25.7 | 5.70 | yes | 276 |
| `20261024_185753.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.128 | 24.9 | 2.01 | yes | 252 |
| `20261024_185758.jpg` | correct | CORNER_MODEL | pass | 0.874 | 3.9 | 1.06 | yes | 112 |
| `20261024_185800.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 175 |
| `20261024_185806.jpg` | correct | CORNER_MODEL | pass | 0.846 | 3.6 | 0.94 | yes | 89 |
| `20261024_185808.jpg` | correct | CORNER_MODEL | pass | 0.633 | 9.6 | 1.12 | yes | 89 |
| `20261024_185809.jpg` | correct | CORNER_MODEL | pass | 0.463 | 10.0 | 0.49 | yes | 84 |
| `20261024_185811.jpg` | correct | CORNER_MODEL | pass | 0.798 | 4.9 | 0.90 | yes | 82 |
| `20261024_190133.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.205 | 20.4 | 4.87 | yes | 306 |
| `20261024_190136.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 170 |
| `20261024_190138.jpg` | correct | CORNER_MODEL | pass | 0.000 | 20.2 | 319960.97 | yes | 95 |
| `20261024_190141.jpg` | correct | CORNER_MODEL | pass | 0.497 | 10.1 | 0.50 | yes | 249 |
| `20261024_190143.jpg` | correct | CORNER_MODEL | pass | 0.166 | 16.6 | 1.06 | yes | 166 |
| `20261024_190145.jpg` | correct | CLASSICAL_FALLBACK | pass | 0.300 | 16.6 | 0.81 | yes | 253 |
| `20261024_190146.jpg` | correct | CORNER_MODEL | pass | 0.351 | 12.8 | 0.80 | no | 181 |
| `20261024_190149.jpg` | correct | CORNER_MODEL | pass | 0.000 | 18.6 | 218318.57 | yes | 92 |
| `20261024_190151.jpg` | correct | CORNER_MODEL | pass | 0.406 | 9.4 | 1.74 | yes | 81 |
| `20261024_190152.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.009 | 41.3 | 117.47 | yes | 232 |
| `20261024_190154.jpg` | correct | CORNER_MODEL | fail | 0.134 | 47.1 | 0.16 | yes | 101 |
| `20261024_190157.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.057 | 38.1 | 17.63 | yes | 232 |
| `20261024_190206.jpg` | wrong-page | CORNER_MODEL | pass | 0.190 | 19.9 | 4.08 | yes | 81 |
| `20261024_190210.jpg` | correct | CORNER_MODEL | pass | 0.462 | 11.4 | 0.46 | yes | 82 |
| `20261024_190212.jpg` | correct | CORNER_MODEL | pass | 0.844 | 4.5 | 0.97 | yes | 107 |
| `20261024_190213.jpg` | wrong-page | CORNER_MODEL | pass | 0.256 | 17.6 | 1.26 | no | 25 |
| `20261024_190215.jpg` | correct | CORNER_MODEL | pass | 0.044 | 26.6 | 0.68 | yes | 87 |
| `20261024_190216.jpg` | correct | CORNER_MODEL | pass | 0.498 | 11.9 | 0.60 | yes | 89 |
| `20261024_190222.jpg` | wrong-page | CLASSICAL_FALLBACK | pass | 0.100 | 33.2 | 10.01 | yes | 254 |
| `20261024_190224.jpg` | merged | CLASSICAL_FALLBACK | pass | 0.399 | 10.9 | 2.14 | yes | 230 |
| `20261024_190226.jpg` | no-detection | UNKNOWN | fail | — | — | 0.00 | no | 175 |
| `20261024_190232.jpg` | wrong-page | CORNER_MODEL | pass | 0.231 | 18.0 | 2.62 | yes | 88 |
| `20261024_190233.jpg` | merged | CLASSICAL_FALLBACK | pass | 0.366 | 20.3 | 2.49 | no | 191 |
| `20261024_190234.jpg` | correct | CORNER_MODEL | pass | 0.016 | 29.4 | 1.08 | yes | 83 |
| `20261024_190235.jpg` | correct | CORNER_MODEL | pass | 0.512 | 16.5 | 0.75 | yes | 86 |
| `20261024_190237.jpg` | wrong-page | CORNER_MODEL | pass | 0.000 | 21.5 | 242739.20 | yes | 110 |
| `20261024_190248.jpg` | correct | CORNER_MODEL | pass | 0.798 | 3.2 | 1.09 | yes | 86 |
| `20261024_190249.jpg` | correct | CORNER_MODEL | pass | 0.210 | 18.4 | 1.71 | yes | 80 |
| `20261024_190250.jpg` | correct | CORNER_MODEL | pass | 0.050 | 21.6 | 10.93 | yes | 124 |
| `20261024_190254.jpg` | correct | CORNER_MODEL | pass | 0.544 | 8.9 | 0.60 | yes | 95 |
| `20261024_190256.jpg` | correct | CORNER_MODEL | pass | 0.000 | 51.5 | 318356.56 | yes | 82 |
| `20261024_190259.jpg` | correct | CORNER_MODEL | pass | 0.413 | 9.0 | 1.71 | yes | 83 |
| `20261024_190300.jpg` | correct | CORNER_MODEL | pass | 0.136 | 20.8 | 2.19 | yes | 86 |
| `20261024_190301.jpg` | correct | CORNER_MODEL | pass | 0.258 | 13.8 | 0.90 | yes | 90 |
| `20261024_190304.jpg` | correct | CORNER_MODEL | pass | 0.350 | 18.3 | 0.35 | yes | 223 |
| `20261025_130818.jpg` | correct | CORNER_MODEL | pass | 0.599 | 9.2 | 1.23 | yes | 92 |
| `20261025_130821.jpg` | correct | CORNER_MODEL | pass | 0.702 | 5.2 | 1.01 | yes | 80 |
| `20261025_131201.jpg` | correct | CORNER_MODEL | pass | 0.813 | 4.0 | 0.83 | yes | 78 |
| `20261025_131246.jpg` | correct | CORNER_MODEL | pass | 0.840 | 2.7 | 0.86 | no | 24 |
| `20261025_131249.jpg` | correct | CORNER_MODEL | pass | 0.365 | 13.9 | 0.37 | yes | 154 |

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
| `20261024_185800.jpg` | no-detection |
| `20261024_190136.jpg` | no-detection |
| `20261024_190226.jpg` | no-detection |
| `20261024_190304.jpg` | no-detection |

Per-channel heat peaks, quad and gate scalars live in the trace dump
(`tooling/out/h39/h40all/<photo>/trace.json`, regenerable).
