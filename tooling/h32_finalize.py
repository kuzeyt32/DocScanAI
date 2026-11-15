#!/usr/bin/env python3
"""h32_finalize.py - resolve the visual annotation to gt_final.json (fraction-space quads).

This is STEP 3 of the annotation pipeline:

    h32_grid.py            -> NNN_grid.jpg (labelled 10 %/2 % coordinate grid)
    (agent vision)         -> h32_selection.json  (per-image GT as FRACTIONS of the frame,
                                plus scene label, facing side, and exclusion reasons)
    h32_finalize.py (this)-> gt_final.json        (typed records the bundler consumes)

Ground truth is a VISUAL annotation by the agent, independent of the detector under test.
Fractions are resolution-independent, so the same annotation is valid for the pool rendition
and for the bundled rendition; the manifest records the annotation method.

Usage:
  python tooling/h32_finalize.py
"""

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
OUT_DIR = os.path.join(REPO, "tooling", "out", "h32")


def main():
    sel_path = os.path.join(OUT_DIR, "h32_selection.json")
    pool_path = os.path.join(OUT_DIR, "pool_index.json")
    prov_path = os.path.join(OUT_DIR, "provenance_thumbs.json")
    if not os.path.exists(sel_path):
        print("h32_selection.json missing")
        return 2
    with open(sel_path, encoding="utf-8") as fh:
        sels = json.load(fh)
    pool = {}
    if os.path.exists(pool_path):
        with open(pool_path, encoding="utf-8") as fh:
            pool = {p["pool_index"]: p for p in json.load(fh)}
    prov = {}
    if os.path.exists(prov_path):
        with open(prov_path, encoding="utf-8") as fh:
            prov = json.load(fh)

    final = []
    excluded = []
    for sel in sels:
        idx = sel["pool_index"]
        if sel.get("exclude"):
            excluded.append({"pool_index": idx, "reason": sel.get("reason", "unspecified")})
            continue
        p = pool.get(idx)
        if p is None:
            excluded.append({"pool_index": idx, "reason": "not in pool_index.json"})
            continue
        gt = sel.get("gt") or {}
        quad = gt.get("target_quad_frac") or gt.get("target_quad")
        if not quad or len(quad) != 4:
            excluded.append({"pool_index": idx, "reason": "no 4-point target quad in selection"})
            continue

        title = p["title"]
        rec = prov.get(title, {})
        final.append({
            "pool_index": idx,
            "title": title,
            "local_path": p["local_path"],
            "width": p.get("width") or rec.get("width", 0),
            "height": p.get("height") or rec.get("height", 0),
            "diagonal": float(((p.get("width") or rec.get("width", 0)) ** 2 +
                               (p.get("height") or rec.get("height", 0)) ** 2) ** 0.5),
            "quad_space": "frac",
            "gt_target_quad": [[float(x), float(y)] for x, y in quad],
            "facing_quad_visible": (
                [[float(x), float(y)] for x, y in gt["facing_quad_frac"]]
                if gt.get("facing_quad_frac") else None
            ),
            "alt_quad_visible": (
                [[float(x), float(y)] for x, y in gt["alt_quad_frac"]]
                if gt.get("alt_quad_frac") else None
            ),
            "facing_side": sel.get("facing_side"),
            "scene": sel.get("scene", "real_spread"),
            "target_rule": sel.get("target_rule", ""),
            "annotation_method": "visual_fraction_annotation",
            "note": sel.get("note", ""),
        })

    out = {
        "annotation_method": (
            "Visual annotation by the agent on labelled grid overlays (tooling/h32_grid.py), "
            "independent of the detector under test. Quads are fractions of the frame so the "
            "annotation is resolution-independent. Every included image was inspected with "
            "vision; the target page is the page that is FULLY seen in the frame, and the "
            "facing page is the one cut off at the frame edge (recorded as facing_quad_visible "
            "where it can be delimited). Exclusions carry an explicit reason."
        ),
        "counts": {"included": len(final), "excluded": len(excluded)},
        "items": final,
        "excluded": excluded,
    }
    out_path = os.path.join(OUT_DIR, "gt_final.json")
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(out, fh, indent=2)
    print("gt_final.json: %d included, %d excluded" % (len(final), len(excluded)))
    for e in excluded:
        print("  excluded #%d: %s" % (e["pool_index"], e["reason"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
