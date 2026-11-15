"""Spread (side by side) vs stack (page behind), in plain IMAGE coordinates.

The earlier attempt projected the centre shift onto the mask's min-area-rect axes and got the
axis assignment wrong (it labelled every pair "side by side"). These photos are all roughly
upright, so the raw image-space shift is unambiguous and needs no axis bookkeeping:

   side by side (a real spread):  the two leaves differ mostly HORIZONTALLY   |dx| >> |dy|
   page behind (the stack):       they differ mostly VERTICALLY               |dy| >> |dx|

Prints every sheet pair so the rule can be checked against the photos by eye.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, "tooling")
import cv2
import numpy as np
from PIL import Image
from h36_sam import SamOracle
import h48_pointgrid as H

ROOT = Path("app/src/androidTest/assets/holdout42")
OUT = Path("tooling/out/h51")

oracle = SamOracle()
rows = []
names = sorted(p.name for p in ROOT.glob("*.jpg"))
for i, name in enumerate(names, 1):
    stem = name[:-4]
    pil = Image.open(ROOT / name)
    w, h = pil.size
    gray = np.asarray(pil.convert("L"), dtype=np.float32)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    edges = cv2.Canny(blurred.astype(np.uint8), 40, 120) > 0
    edges = cv2.dilate(edges.astype(np.uint8), np.ones((3, 3), np.uint8)).astype(bool)
    emb, wh = oracle.encode(pil)
    cands = []
    H._seed_candidates(oracle, emb, wh, w, h, 6, gray, edges, cands)
    page = H._page_candidates(cands)
    page.sort(key=lambda c: -c["area"])

    groups = H._group(page)
    sheets = []
    for idx in groups:
        m = np.zeros((h, w), bool)
        for j in idx:
            m |= page[j]["mask"]
        st = H._describe(m, gray, edges)
        if st.get("area"):
            st["members"] = len(idx)
            st["trunc"] = H._truncated(m)
            sheets.append(st)
    sheets.sort(key=lambda s: -s["area"])

    rec = {"stem": stem, "pairs": []}
    if sheets:
        b = sheets[0]
        bys, bxs = np.nonzero(b["mask"])
        for s in sheets[1:5]:
            oys, oxs = np.nonzero(s["mask"])
            dx = float(oxs.mean() - bxs.mean()) / w
            dy = float(oys.mean() - bys.mean()) / h
            rec["pairs"].append({
                "ratio": round(float(s["frac"]) / max(1e-9, float(b["frac"])), 3),
                "dx": round(dx, 3), "dy": round(dy, 3),
                "horizontal": bool(abs(dx) > abs(dy)),
                "trunc": bool(s["trunc"]),
            })
    rows.append(rec)
    ps = " ".join(f"| r={p['ratio']:.2f} dx={p['dx']:+.2f} dy={p['dy']:+.2f} "
                  f"horiz={int(p['horizontal'])} t={int(p['trunc'])}" for p in rec["pairs"])
    print(f"[{i}/{len(names)}] {stem}: {ps}", flush=True)

OUT.mkdir(parents=True, exist_ok=True)
(OUT / "pairs.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")


def rule(fn):
    return sum(1 for r in rows if any(fn(p) for p in r["pairs"]))


print("\nphotos where a second sheet joins the target:")
print(f"  ratio>=0.25                            : {rule(lambda p: p['ratio'] >= 0.25)}")
print(f"  ... horizontal                         : {rule(lambda p: p['ratio'] >= 0.25 and p['horizontal'])}")
print(f"  ... horizontal and not truncated       : "
      f"{rule(lambda p: p['ratio'] >= 0.25 and p['horizontal'] and not p['trunc'])}")
print(f"  ... not truncated                      : {rule(lambda p: p['ratio'] >= 0.25 and not p['trunc'])}")
