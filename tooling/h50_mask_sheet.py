#!/usr/bin/env python3
"""
H50b - contact sheet of the page masks, so they can be checked before they are trusted.

A quad-vs-mask score is only worth reading if the mask is the page. This tints each saved mask
and outlines it, 6 photos per sheet at 880 px, so a mask that grabbed the table or half a leaf
is obvious at a glance.

Usage: python tooling\\h50_mask_sheet.py [--set holdout42] [--cols 2] [--rows 3]
Output: tooling/out/h50/mask_<set>_<n>.png  (local only; private photos)
"""
from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np

MASKS = Path("tooling/out/h48/masks")
OUT = Path("tooling/out/h50")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", default="holdout42")
    ap.add_argument("--cols", type=int, default=2)
    ap.add_argument("--rows", type=int, default=3)
    ap.add_argument("--width", type=int, default=880)
    ap.add_argument("--only", default="", help="comma-separated stems")
    args = ap.parse_args()

    root = Path("app/src/androidTest/assets") / args.set
    names = sorted(p.stem for p in MASKS.glob("*.npy"))
    if args.only:
        wanted = {s.strip() for s in args.only.split(",") if s.strip()}
        names = [n for n in names if n in wanted]
    if not names:
        print(f"no masks under {MASKS}")
        return 1
    per = args.cols * args.rows
    OUT.mkdir(parents=True, exist_ok=True)

    for s in range(0, len(names), per):
        tiles = []
        for stem in names[s:s + per]:
            img = cv2.imread(str(root / f"{stem}.jpg"), cv2.IMREAD_COLOR)
            m = np.load(MASKS / f"{stem}.npy")
            if img is None or m.shape != img.shape[:2]:
                continue
            tint = img.copy()
            tint[m] = (0.55 * tint[m] + 0.45 * np.array([0, 200, 0])).astype(np.uint8)
            cnts, _ = cv2.findContours(m.astype(np.uint8), cv2.RETR_EXTERNAL,
                                       cv2.CHAIN_APPROX_SIMPLE)
            cv2.drawContours(tint, cnts, -1, (0, 255, 0), 3)
            h, w = tint.shape[:2]
            sc = args.width / w
            tile = cv2.resize(tint, (args.width, int(h * sc)), interpolation=cv2.INTER_AREA)
            cv2.rectangle(tile, (0, 0), (args.width, 30), (0, 0, 0), -1)
            cv2.putText(tile, f"{s + len(tiles) + 1}. {stem}", (8, 22),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 1, cv2.LINE_AA)
            tiles.append(tile)

        if not tiles:
            continue
        th = max(t.shape[0] for t in tiles)
        tw = max(t.shape[1] for t in tiles)
        sheet = np.full((args.rows * th, args.cols * tw, 3), 32, np.uint8)
        for k, t in enumerate(tiles):
            r, c = divmod(k, args.cols)
            sheet[r * th:r * th + t.shape[0], c * tw:c * tw + t.shape[1]] = t
        out = OUT / f"mask_{args.set}_{s // per + 1}.png"
        cv2.imwrite(str(out), sheet)
        print(f"{len(tiles)} tiles -> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
