#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H34 — vision cross-check helpers (contact sheet + quad overlays).

  sheet    one contact sheet of every corpus_owner photo (thumbnail + filename),
           so the deliberate hard cases (page edge ~ table edge, second page
           showing) can be spotted WITHOUT opening all 110 files.
  overlay  annotated copies of chosen photos: oracle pseudo-GT quad in GREEN,
           shipped-detector quad in RED (measurement run) -> tooling/out/h34/vision/.

corpus_owner/ is NEVER written; all output goes to tooling/out/h34/vision/.

Usage:
  python tooling\\h34_vision.py --mode sheet
  python tooling\\h34_vision.py --mode overlay --names a.jpg,b.jpg
  python tooling\\h34_vision.py --mode auto8 --names hard1.jpg,...   (sheet picks + names)
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
OUT = os.path.join(HERE, "out", "h34")
VISION_DIR = os.path.join(OUT, "vision")
CORPUS_DIR = os.path.join(REPO, "corpus_owner")
PSEUDO_GT = os.path.join(OUT, "pseudo_gt_owner110.json")

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp"}
THUMB_W = 200
COLS = 10


def list_images():
    return sorted(n for n in os.listdir(CORPUS_DIR)
                  if os.path.splitext(n)[1].lower() in IMAGE_EXTS)


def oracle_corners(rec):
    if not rec:
        return None
    if rec.get("corners"):
        return rec["corners"]
    return rec.get("corners_lenient")


def corners_to_px(corners, w, h):
    return [[corners[k][0] * w, corners[k][1] * h] for k in ("tl", "tr", "br", "bl")]


def load_pgt():
    with open(PSEUDO_GT, encoding="utf-8") as fh:
        pgt = json.load(fh)
    return {os.path.basename(r.get("image", "")): r for r in pgt["items"]}


def load_measure():
    path = os.path.join(OUT, "owner110_measure.json")
    if not os.path.isfile(path):
        return {}
    with open(path, encoding="utf-8") as fh:
        m = json.load(fh)
    return {r["image"]: r for r in m.get("rows", [])}


def mode_sheet():
    names = list_images()
    n = len(names)
    rows_n = int(math.ceil(n / float(COLS)))
    th = int(THUMB_W * 3 / 4)  # 4:3-ish cell; portrait shots letterbox in the cell
    cell_h = th + 16
    sheet = Image.new("RGB", (COLS * THUMB_W, rows_n * cell_h), (24, 24, 24))
    d = ImageDraw.Draw(sheet)
    for i, name in enumerate(names):
        cx, cy = (i % COLS) * THUMB_W, (i // COLS) * cell_h
        try:
            with Image.open(os.path.join(CORPUS_DIR, name)) as im:
                im = im.convert("RGB")
                im.thumbnail((THUMB_W - 4, th))
                sheet.paste(im, (cx + 2, cy + 2))
        except OSError:
            pass
        d.text((cx + 3, cy + th + 2), "%d %s" % (i, name[:22]), fill=(220, 220, 90))
    os.makedirs(VISION_DIR, exist_ok=True)
    out_path = os.path.join(VISION_DIR, "contact_sheet.jpg")
    sheet.save(out_path, quality=88)
    print("wrote %s  (%d thumbs)" % (out_path, n))
    return 0


def mode_overlay(names):
    pgt = load_pgt()
    meas = load_measure()
    os.makedirs(VISION_DIR, exist_ok=True)
    for name in names:
        rec = pgt.get(name)
        oc = oracle_corners(rec)
        mrow = meas.get(name, {})
        path = os.path.join(CORPUS_DIR, name)
        with Image.open(path) as im:
            im = im.convert("RGB")
            w, h = im.size
            d = ImageDraw.Draw(im)
            if oc and rec and rec.get("width"):
                oq = corners_to_px(oc, rec["width"], rec["height"])
                if (rec["width"], rec["height"]) != (w, h):
                    sx, sy = w / rec["width"], h / rec["height"]
                    oq = [[x * sx, y * sy] for x, y in oq]
                pts = [tuple(p) for p in oq] + [tuple(oq[0])]
                d.line(pts, fill=(0, 230, 0), width=8)
            ours = mrow.get("our_quad")
            if ours:
                pts = [tuple(p) for p in ours] + [tuple(ours[0])]
                d.line(pts, fill=(230, 0, 0), width=8)
            label = "%s  oracle=%s iou=%s corner=%s%% %s" % (
                name[:24], rec.get("corner_format") if rec else "-",
                mrow.get("iou"), mrow.get("corner_pct"), mrow.get("category", ""))
            d.rectangle([0, 0, len(label) * 7 + 12, 22], fill=(0, 0, 0))
            d.text((6, 5), label, fill=(255, 255, 0))
            out_path = os.path.join(VISION_DIR, "overlay_%s" % name)
            im.save(out_path, quality=90)
            print("wrote %s" % out_path)
    return 0


def main():
    ap = argparse.ArgumentParser(description="H34 vision cross-check helpers")
    ap.add_argument("--mode", required=True, choices=["sheet", "overlay", "auto8"])
    ap.add_argument("--names", help="comma-separated image basenames")
    args = ap.parse_args()
    if args.mode == "sheet":
        return mode_sheet()
    names = [n.strip() for n in (args.names or "").split(",") if n.strip()]
    return mode_overlay(names)


if __name__ == "__main__":
    sys.exit(main())
