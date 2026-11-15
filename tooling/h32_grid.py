#!/usr/bin/env python3
"""h32_grid.py - grid overlays for MANUAL ground-truth annotation of the real book-spread set.

Annotation discipline (recorded in real_bookspread_manifest.json):

  Ground truth is a VISUAL annotation made by the agent, independent of the detector under test.
  To make it reproducible, every pool image is rendered twice:

    NNN_grid.jpg    the image with a labelled coordinate grid (major lines every 10 % of width
                    and height, minor ticks every 2 %), so each page corner can be read to about
                    +/- 1 % of the frame;
    (later)         NNN_gt.jpg overlays the annotated quad so the annotation can be verified and
                    refined in one more pass.

  Coordinates are annotated as FRACTIONS of the image (0..1) in h32_selection.json and are
  resolved to pixels per rendition by h32_annotate.py finalize / h32_bundle.py. Fraction-space
  annotation makes the GT resolution-independent.

Usage:
  python tooling/h32_grid.py             # render grid overlays for the whole pool
  python tooling/h32_grid.py --gt        # render GT verification overlays from h32_selection.json
  python tooling/h32_grid.py --gt --batch 4   # 2x2 contact of GT overlays per view
"""

import argparse
import json
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
OUT_DIR = os.path.join(REPO, "tooling", "out", "h32")
THUMB_DIR = r"E:\DocScanAI-cache\bookspread_real\thumb"
GRID_DIR = os.path.join(OUT_DIR, "grid")
GTOV_DIR = os.path.join(OUT_DIR, "gtcheck")

MAJOR = 0.10
MINOR = 0.02
GRID_LONG_EDGE = 1100


def load_pool():
    with open(os.path.join(OUT_DIR, "pool_index.json"), encoding="utf-8") as fh:
        return json.load(fh)


def resize_long(im, long_edge):
    w, h = im.size
    scale = long_edge / float(max(w, h))
    if scale >= 1.0:
        return im, 1.0
    return im.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.LANCZOS), scale


def render_grid():
    os.makedirs(GRID_DIR, exist_ok=True)
    pool = load_pool()
    for item in pool:
        path = item["local_path"]
        if not os.path.exists(path):
            continue
        im = Image.open(path).convert("RGB")
        im, _ = resize_long(im, GRID_LONG_EDGE)
        w, h = im.size
        dr = ImageDraw.Draw(im)
        # minor ticks every 2 % along the top and left edges
        f = MINOR
        while f < 1.0:
            x = f * w
            y = f * h
            long_tick = abs(f % MAJOR) < 1e-6 or abs(f % MAJOR - MAJOR) < 1e-6
            ln = 14 if long_tick else 6
            dr.line([(x, 0), (x, ln)], fill=(255, 60, 60), width=1)
            dr.line([(0, y), (ln, y)], fill=(255, 60, 60), width=1)
            f += MINOR
        # major lines every 10 % with labels
        k = 0
        while k * MAJOR <= 1.0001:
            fx = k * MAJOR
            x = fx * w
            y = fx * h
            dr.line([(x, 0), (x, h)], fill=(255, 60, 60), width=1)
            dr.line([(0, y), (w, y)], fill=(255, 60, 60), width=1)
            dr.text((x + 2, 2), "%d" % round(fx * 100), fill=(255, 255, 0))
            dr.text((2, y + 2), "%d" % round(fx * 100), fill=(255, 255, 0))
            k += 1
        dr.text((w - 220, h - 18), "#%d %s" % (item["pool_index"], item["title"].replace("File:", "")[:38]),
                fill=(255, 255, 255))
        out = os.path.join(GRID_DIR, "%03d_grid.jpg" % item["pool_index"])
        im.save(out, quality=90)
    print("grid overlays -> %s" % GRID_DIR)
    return 0


def render_gt(batch=0):
    """Overlay the annotated GT quads (fractions -> pixels) for verification."""
    os.makedirs(GTOV_DIR, exist_ok=True)
    sel_path = os.path.join(OUT_DIR, "h32_selection.json")
    if not os.path.exists(sel_path):
        print("h32_selection.json missing")
        return 2
    with open(sel_path, encoding="utf-8") as fh:
        sels = json.load(fh)
    pool = {p["pool_index"]: p for p in load_pool()}

    tiles = []
    for sel in sels:
        if sel.get("exclude"):
            continue
        idx = sel["pool_index"]
        p = pool.get(idx)
        if p is None or not os.path.exists(p["local_path"]):
            continue
        gt = sel.get("gt")
        quad = None
        if isinstance(gt, dict):
            quad = gt.get("target_quad_frac") or gt.get("target_quad")
        if quad is None:
            continue
        im = Image.open(p["local_path"]).convert("RGB")
        im, _ = resize_long(im, GRID_LONG_EDGE)
        w, h = im.size
        dr = ImageDraw.Draw(im)

        def px(q):
            return [(float(x) * w, float(y) * h) for x, y in q]

        # fraction-space quads have coordinates in 0..1; pixel-space on the thumb has > 1 values.
        def is_frac(q):
            return all(0.0 <= float(v) <= 1.5 for pt in q for v in pt)

        use_frac = is_frac(quad)
        target = [(x, y) for x, y in quad]

        def draw_poly(q, color, width):
            pts = [(float(x) * w, float(y) * h) for x, y in q] if use_frac else [(float(x), float(y)) for x, y in q]
            dr.polygon(pts, outline=color, width=width)
            for i, (x, y) in enumerate(pts):
                dr.text((x + 3, y + 3), "p%d" % i, fill=color)

        draw_poly(target, (0, 220, 0), 3)
        fq = gt.get("facing_quad_frac") if isinstance(gt, dict) else sel.get("facing_quad_frac")
        if fq:
            draw_poly(fq, (255, 140, 0), 3)
        alt = gt.get("alt_quad_frac") if isinstance(gt, dict) else None
        if alt:
            draw_poly(alt, (0, 160, 255), 2)
        dr.text((8, 8), "#%d %s | facing=%s" % (idx, p["title"].replace("File:", "")[:32],
                                                sel.get("facing_side")), fill=(255, 255, 255))
        out = os.path.join(GTOV_DIR, "%03d_gt.jpg" % idx)
        im.save(out, quality=90)
        tiles.append(out)

    if batch and tiles:
        cols = 2
        cw, ch = 620, 480
        for page_start in range(0, len(tiles), batch):
            chunk = tiles[page_start:page_start + batch]
            rows = (len(chunk) + cols - 1) // cols
            sheet = Image.new("RGB", (cols * cw, rows * ch), "white")
            for i, t in enumerate(chunk):
                th = Image.open(t)
                th.thumbnail((cw, ch))
                sheet.paste(th, ((i % cols) * cw, (i // cols) * ch))
            sheet.save(os.path.join(GTOV_DIR, "gtcheck_%02d.jpg" % (page_start // batch + 1)), quality=88)
    print("GT overlays -> %s (%d images)" % (GTOV_DIR, len(tiles)))
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gt", action="store_true", help="render GT verification overlays")
    ap.add_argument("--batch", type=int, default=0, help="contact-sheet batching for --gt")
    args = ap.parse_args()
    if args.gt:
        return render_gt(args.batch)
    return render_grid()


if __name__ == "__main__":
    sys.exit(main())
