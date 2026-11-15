#!/usr/bin/env python3
"""
H48c - test the oracle fix on the holdout without touching the published owner110 oracle.

Diagnosis: `h36_sam.mask_contour` returns the boundary of the WHOLE boolean mask, including
disconnected specks. `min_area_rect` then wraps the union of those specks, which on the tightly
framed holdout photos spans the entire frame - so every oracle quad came back as the image border
even though the chosen mask itself was a plausible page (area_frac 0.32).

Fix under test: keep the LARGEST CONNECTED COMPONENT of the chosen mask (a page is one sheet)
before taking the contour. Opt-in, so the owner110 oracle that all published numbers rest on is
unchanged.

Writes an overlay per photo so the fix can be eyeballed, and prints component stats.

Usage: python tooling\\h48_sam_fix.py --set holdout42 --images a,b,c
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from h36_sam import SamOracle, maybe_split_spread, quad_from_mask  # noqa: E402

OUT = Path("tooling/out/h48")


def largest_component(mask: np.ndarray) -> tuple[np.ndarray, list[dict]]:
    n, lab, stats, _ = cv2.connectedComponentsWithStats(mask.astype(np.uint8), 8)
    comps = []
    for k in range(1, n):
        x, y, w, h, a = stats[k]
        comps.append({"k": k, "area": int(a), "box": [int(x), int(y), int(w), int(h)]})
    if not comps:
        return mask, []
    comps.sort(key=lambda c: -c["area"])
    biggest = comps[0]
    return lab == biggest["k"], comps


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", default="holdout42")
    ap.add_argument("--images", required=True)
    ap.add_argument("--limit", type=int, default=4)
    args = ap.parse_args()

    root = Path("app/src/androidTest/assets") / args.set
    names = [n.strip() for n in args.images.split(",") if n.strip()][:args.limit]
    oracle = SamOracle()
    OUT.mkdir(parents=True, exist_ok=True)

    for name in names:
        stem = name if name.endswith(".jpg") else name + ".jpg"
        path = root / stem
        pil = Image.open(path)
        w, h = pil.size
        emb, wh = oracle.encode(pil)
        dec = oracle.decode_box(emb, (0.0, 0.0, float(w), float(h)), wh)
        mask, mmeta = oracle.best_mask(dec, wh, pil)
        gray = np.asarray(pil.convert("L"), dtype=np.float32)
        split_mask, smeta = maybe_split_spread(mask, gray, wh)
        big, comps = largest_component(split_mask)
        quad_old, _ = quad_from_mask(split_mask, pil)
        quad_new, qmeta = quad_from_mask(big, pil)

        img = cv2.imread(str(path), cv2.IMREAD_COLOR)
        for q, col in ((quad_old, (0, 0, 255)), (quad_new, (0, 220, 0))):
            if q is None:
                continue
            pts = np.array([[int(x), int(y)] for x, y in q], np.int32)
            cv2.polylines(img, [pts], True, col, 4)
        cv2.putText(img, "red=old  green=component", (12, 34), cv2.FONT_HERSHEY_SIMPLEX,
                    0.8, (255, 255, 255), 2, cv2.LINE_AA)
        dst = OUT / f"fix_{stem[:-4]}.png"
        cv2.imwrite(str(dst), img)

        print(f"== {stem}")
        print(f"   mask area {mmeta['area_frac']} split={smeta.get('split')} "
              f"side={smeta.get('side')}")
        print(f"   components: {len(comps)}, top: {comps[:3]}")
        for tag, q in (("old", quad_old), ("new", quad_new)):
            if q is None:
                print(f"   {tag}: none")
                continue
            xs, ys = q[:, 0], q[:, 1]
            print(f"   {tag}: x {xs.min():.0f}..{xs.max():.0f}  y {ys.min():.0f}..{ys.max():.0f} "
                  f"(frame {w}x{h})")
        print(f"   -> {dst}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
