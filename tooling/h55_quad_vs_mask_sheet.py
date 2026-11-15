#!/usr/bin/env python3
"""
H55 - visual diff of detector quad vs target page mask.

For each photo: the mask outline in green, the detector quad in red, and the quad's area filled
translucently. This is the instrument for the UNDER/OVER classes, where a number tells you the
quad is too small but not WHICH part of the page it missed.

Usage:
  python tooling\\h55_quad_vs_mask_sheet.py --tag h53spread --verdict UNDER
  python tooling\\h55_quad_vs_mask_sheet.py --tag h53spread --only 192342,192347
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

MASKS = Path("tooling/out/h48/masks")
TRACES = Path("tooling/out/h39")
OUT = Path("tooling/out/h55")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="h53spread")
    ap.add_argument("--set", default="holdout42")
    ap.add_argument("--score", default="tooling/out/h50/spread.json")
    ap.add_argument("--verdict", default="", help="only these verdicts, comma separated")
    ap.add_argument("--only", default="", help="only these stems (substring match)")
    ap.add_argument("--cols", type=int, default=2)
    ap.add_argument("--rows", type=int, default=2)
    ap.add_argument("--width", type=int, default=720)
    args = ap.parse_args()

    root = Path("app/src/androidTest/assets") / args.set
    score_p = Path(args.score)
    scores = {}
    if score_p.exists():
        scores = {r["image"][:-4]: r for r in json.loads(score_p.read_text(encoding="utf-8"))}

    want = {v.strip().upper() for v in args.verdict.split(",") if v.strip()}
    only = [s.strip() for s in args.only.split(",") if s.strip()]

    stems = []
    for npy in sorted(MASKS.glob("*.npy")):
        stem = npy.stem
        if want and scores.get(stem, {}).get("verdict") not in want:
            continue
        if only and not any(o in stem for o in only):
            continue
        if (TRACES / args.tag / stem / "trace.json").exists():
            stems.append(stem)
    if not stems:
        print("nothing selected")
        return 1

    per = args.cols * args.rows
    OUT.mkdir(parents=True, exist_ok=True)
    for s in range(0, len(stems), per):
        tiles = []
        for stem in stems[s:s + per]:
            t = json.loads((TRACES / args.tag / stem / "trace.json").read_text(encoding="utf-8"))
            mask = np.load(MASKS / f"{stem}.npy").astype(bool)
            img = cv2.imread(str(root / f"{stem}.jpg"), cv2.IMREAD_COLOR)
            if img is None:
                continue
            if img.shape[:2] != mask.shape:
                img = cv2.resize(img, (mask.shape[1], mask.shape[0]))

            overlay = img.copy()
            overlay[mask] = (0.72 * overlay[mask] + 0.28 * np.array([0, 200, 0])).astype(np.uint8)
            img = overlay
            cnts, _ = cv2.findContours(mask.astype(np.uint8), cv2.RETR_EXTERNAL,
                                       cv2.CHAIN_APPROX_SIMPLE)
            cv2.drawContours(img, cnts, -1, (0, 255, 0), 2)

            if t.get("quad"):
                W, H = float(t["image_w"]), float(t["image_h"])
                hh, ww = img.shape[:2]
                q = np.array([[d["x"] / W * ww, d["y"] / H * hh] for d in t["quad"]], np.int32)
                fill = img.copy()
                cv2.fillPoly(fill, [q], (0, 0, 190))
                cv2.addWeighted(fill, 0.22, img, 0.78, 0, img)
                cv2.polylines(img, [q], True, (0, 0, 255), 3)

            r = scores.get(stem, {})
            label = (f"{stem}  {r.get('verdict', '?')} iou={r.get('iou', 0):.2f} "
                     f"rec={r.get('recall', 0):.2f} prec={r.get('precision', 0):.2f}")
            h, w = img.shape[:2]
            scale = args.width / w
            tile = cv2.resize(img, (args.width, int(h * scale)), interpolation=cv2.INTER_AREA)
            cv2.rectangle(tile, (0, 0), (args.width, 30), (0, 0, 0), -1)
            cv2.putText(tile, label, (8, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.5,
                        (255, 255, 255), 1, cv2.LINE_AA)
            tiles.append(tile)

        if not tiles:
            continue
        th = max(t.shape[0] for t in tiles)
        tw = max(t.shape[1] for t in tiles)
        sheet = np.full((args.rows * th, args.cols * tw, 3), 32, np.uint8)
        for k, t in enumerate(tiles):
            r, c = divmod(k, args.cols)
            sheet[r * th:r * th + t.shape[0], c * tw:c * tw + t.shape[1]] = t
        outp = OUT / f"{args.tag}_{args.verdict or 'all'}_{s // per + 1}.png"
        cv2.imwrite(str(outp), sheet)
        print(f"{len(tiles)} tiles -> {outp}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
