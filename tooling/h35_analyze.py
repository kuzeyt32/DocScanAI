#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H35 — targeted diagnostics on the real-path report (one-off, kept for evidence).

Checks, against tooling/out/h35/report_baseline.json:
  1. the named H34 wrong-page case (20261024_190249, table-edge coincidence);
  2. the peak-probe cross-check vs the production `corner detect` log line
     (the probe must reproduce the production peak statistic);
  3. the profile of the residual no-detections (peaks / provenance / luma);
  4. how many photos a 0.20 peak gate could ADDITIONALLY pass (the named
     measurement for the "lower the gate to 0.2" candidate).
"""

from __future__ import annotations

import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out", "h35")

with open(os.path.join(OUT, "report_baseline.json"), "r", encoding="utf-8") as fh:
    rows = json.load(fh)["rows"]

by = {r["image"]: r for r in rows}

print("== 1) H34's named wrong-page case: 20261024_190249 ==")
r = by.get("20261024_190249.jpg")
if r:
    for k in ("detected", "gate_pass", "prov", "iou", "corner_pct", "covers_oracle",
              "area_ratio", "our_frame_touch", "oracle_frame_touch", "wrong_page",
              "merged", "category", "peaks", "peak_min"):
        print("  %-18s %s" % (k, r.get(k)))
    print("  corner_log: %s" % (r.get("corner_log") or "-"))

print()
print("== 2) peak-probe cross-check (production 'channel c peak=V' vs probe) ==")
pat = re.compile(r"channel (\d) peak=([0-9.]+)")
n_checked = 0
n_match = 0
mismatch = []
for r in rows:
    m = pat.search(r.get("corner_log") or "")
    if not m:
        continue
    c = int(m.group(1))
    v = float(m.group(2))
    peaks = r.get("peaks")
    if not peaks:
        continue
    n_checked += 1
    if abs(peaks[c] - v) <= 0.005:
        n_match += 1
    else:
        mismatch.append((r["image"], c, v, peaks[c]))
print("  checked=%d match=%d mismatch=%d" % (n_checked, n_match, len(mismatch)))
for m in mismatch[:10]:
    print("  MISMATCH %s channel %d production=%.4f probe=%.4f" % m)

print()
print("== 3) residual no-detections (real pipeline) ==")
nd = [r for r in rows if not r["detected"]]
print("  count=%d" % len(nd))
for r in nd:
    pk = r.get("peaks")
    pmin = ("%.4f" % min(pk)) if pk else "-"
    print("  %-26s peak_min=%-7s luma=%5.1f/%4.1f peaks=%s" % (
        r["image"], pmin, r.get("luma_mean") or 0.0, r.get("luma_std") or 0.0,
        ",".join("%.3f" % v for v in pk) if pk else "-"))

print()
print("== 4) 'lower the peak gate to 0.2' named measurement ==")
nn_blocked = [r for r in rows if not r["detected"] and r.get("peaks")]
rescuable_02 = [r for r in nn_blocked if 0.20 <= min(r["peaks"]) < 0.30]
rescuable_03 = [r for r in nn_blocked if 0.30 <= min(r["peaks"])]
print("  NN-gated (peaks recorded, below gate): %d" % len([r for r in nn_blocked if min(r["peaks"]) < 0.30]))
print("  of those, min-peak in [0.20, 0.30) -> a 0.20 gate could pass: %d" % len(rescuable_02))
print("  of those, min-peak >= 0.30 but still no quad (sanity/plausibility): %d" % len(rescuable_03))
for r in rescuable_02:
    print("    %s peaks=%s" % (r["image"], ",".join("%.3f" % v for v in r["peaks"])))

print()
print("== 5) provenance x outcome cross-tab ==")
from collections import Counter
tab = Counter()
for r in rows:
    tab[(r["prov"], r["detected"], r["wrong_page"], r["merged"])] += 1
for k in sorted(tab, key=lambda k: (-tab[k], k)):
    print("  prov=%-18s detected=%-5s wrong=%-5s merged=%-5s -> %d"
          % (k[0], k[1], k[2], k[3], tab[k]))
