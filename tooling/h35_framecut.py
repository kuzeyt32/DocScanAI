#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H35 — measure ONE named candidate rule (STEP 2d) without shipping it: does a
frame-cut / frame-hug rejection on CLASSICAL_FALLBACK quads trade wrong-page+merged
away cleanly, or does it cost good detections?

Named reason: all 27 wrong-page and 34 merged rows are CLASSICAL_FALLBACK (the NN
path is 29/29 clean), and the wrong_page definition is literally "our quad frame-hugs
while the target page is interior". A rule "reject a classical quad that frame-hugs"
is therefore the single simplest hypothesis to test. This measures its exact trade on
the real-path rows so a future subtask has a before/after number, and so the decision
NOT to ship it now is itself measured (not hand-waved).

Usage:  python tooling\\h35_framecut.py
"""

from __future__ import annotations

import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out", "h35")

with open(os.path.join(OUT, "report_baseline.json"), "r", encoding="utf-8") as fh:
    rows = json.load(fh)["rows"]


def touches(pts, w, h, frac=0.02):
    if not pts:
        return False
    tx, ty = w * frac, h * frac
    for x, y in pts:
        if x <= tx or x >= w - tx or y <= ty or y >= h - ty:
            return True
    return False


det = [r for r in rows if r["detected"]]
cls = [r for r in det if r["prov"] == "CLASSICAL_FALLBACK"]
nn = [r for r in det if r["prov"] == "CORNER_MODEL"]

print("== frame-hug signature by provenance ==")
for name, grp in (("CORNER_MODEL", nn), ("CLASSICAL_FALLBACK", cls)):
    hug = [r for r in grp if r["our_frame_touch"]]
    print("  %-18s n=%-3d frame_hug=%-3d  (wrong=%d merged=%d)"
          % (name, len(grp), len(hug),
             sum(1 for r in grp if r["wrong_page"]),
             sum(1 for r in grp if r["merged"])))

print()
print("== candidate rule: 'reject CLASSICAL_FALLBACK when our_frame_touch' ==")
# Confusion of the rule against the "clean" label (not wrong_page and not merged).
tp = fp = tn = fn = 0
rescued_wrong = rescued_merged = 0
lost_clean = 0
for r in det:
    clean = not r["wrong_page"] and not r["merged"]
    reject = r["prov"] == "CLASSICAL_FALLBACK" and r["our_frame_touch"]
    if reject and not clean:
        tp += 1
        rescued_wrong += 1 if r["wrong_page"] else 0
        rescued_merged += 1 if r["merged"] else 0
    elif reject and clean:
        fp += 1
        lost_clean += 1
    elif not reject and clean:
        tn += 1
    else:
        fn += 1
print("  rejects that WERE wrong/merged (good rejection): %d  [wrong=%d merged=%d]"
      % (tp, rescued_wrong, rescued_merged))
print("  rejects that were CLEAN (cost = lost good detections): %d" % lost_clean)
print("  kept clean: %d" % tn)
print("  kept but wrong/merged (rule missed them): %d" % fn)
print("  -> net: removes %d bad, but discards %d good classical detections"
      % (tp, lost_clean))
print("  -> new detection count = %d (was %d), new wrong/merged = %d/%d (was %d/%d)"
      % (len(det) - tp - lost_clean, len(det), fn, fn, sum(1 for r in det if r['wrong_page']),
         sum(1 for r in det if r['merged'])))

# Same rule but ONLY when the frame-hug is on 2+ corners AND the quad is near-full-frame
# (a table-edge grab usually hugs one whole side). Test the stricter variant too.
print()
print("== stricter variant: reject CLASSICAL frame-hug quads with area_ratio >= 0.7 ==")
tp2 = lost2 = tn2 = fn2 = 0
for r in det:
    clean = not r["wrong_page"] and not r["merged"]
    reject = (r["prov"] == "CLASSICAL_FALLBACK" and r["our_frame_touch"]
              and (r.get("area_ratio") or 0) >= 0.7)
    if reject and not clean:
        tp2 += 1
    elif reject and clean:
        lost2 += 1
    elif not reject and clean:
        tn2 += 1
    else:
        fn2 += 1
print("  good rejections=%d  lost clean=%d  kept clean=%d  missed bad=%d"
      % (tp2, lost2, tn2, fn2))
