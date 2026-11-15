#!/usr/bin/env python3
"""SUBTASK H23 - contact sheets for the real-pencil measurement lane.

Reads the JPEG pairs written by HandwritingEnhanceTest into
``tooling/out/h23/<pull>/pairs/<asset>_{in,full,clahe}.jpg`` and writes:

  * ``tooling/out/h23/sheets/<asset>_row.jpg``  - input | FULL | CLAHE with labels
  * ``tooling/out/h23/sheets/<asset>_zoom.jpg`` - a 40 % centre crop, 2x upscaled, same order
  * ``tooling/out/h23/sheets/overview_all_pages.jpg`` - every page, input above FULL

Usage:  py -3 tooling\\h23_sheets.py [pull-dir-name]
Default pull dir: the newest directory under tooling/out/h23 that contains a ``pairs`` folder.
"""
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
H23 = os.path.join(ROOT, "tooling", "out", "h23")
OUT = os.path.join(H23, "sheets")

ROW_HEIGHT = 700
LABEL_H = 26


def newest_pull():
    best, best_mtime = None, -1.0
    for name in os.listdir(H23):
        pairs = os.path.join(H23, name, "pairs")
        if not os.path.isdir(pairs):
            continue
        mtime = os.path.getmtime(pairs)
        if mtime > best_mtime:
            best, best_mtime = name, mtime
    return best


def load(path, height):
    im = Image.open(path).convert("RGB")
    scale = height / im.height
    return im.resize((max(1, int(im.width * scale)), height), Image.LANCZOS)


def row(paths_labels, height, out_path):
    tiles = [(load(p, height), lab) for p, lab in paths_labels]
    width = sum(t.width for t, _ in tiles) + 8 * (len(tiles) - 1)
    sheet = Image.new("RGB", (width, height + LABEL_H), "white")
    draw = ImageDraw.Draw(sheet)
    x = 0
    for tile, label in tiles:
        sheet.paste(tile, (x, LABEL_H))
        draw.text((x + 4, 6), label, fill="black")
        x += tile.width + 8
    sheet.save(out_path, quality=90)
    return out_path


def zoom(paths_labels, out_path, factor=2.0):
    """40 % centre crop, upscaled, so a reader can see whether a stroke got deeper."""
    tiles = []
    for path, label in paths_labels:
        im = Image.open(path).convert("RGB")
        w, h = im.size
        cw, ch = int(w * 0.40), int(h * 0.22)
        left = int(w * 0.20)
        top = int(h * 0.15)
        crop = im.crop((left, top, left + cw, top + ch))
        crop = crop.resize((int(cw * factor), int(ch * factor)), Image.LANCZOS)
        tiles.append((crop, label))
    width = sum(t.width for t, _ in tiles) + 8 * (len(tiles) - 1)
    height = max(t.height for t, _ in tiles)
    sheet = Image.new("RGB", (width, height + LABEL_H), "white")
    draw = ImageDraw.Draw(sheet)
    x = 0
    for tile, label in tiles:
        sheet.paste(tile, (x, LABEL_H))
        draw.text((x + 4, 6), label, fill="black")
        x += tile.width + 8
    sheet.save(out_path, quality=92)
    return out_path


def overview(pages, out_path):
    thumbs = []
    for asset in pages:
        for kind in ("in", "full"):
            path = os.path.join(PAIRS, "%s_%s.jpg" % (asset, kind))
            if os.path.exists(path):
                thumbs.append((load(path, 320), "%s %s" % (asset.replace("pencil_real_", "#").replace(".jpg", ""), kind)))
    cols = 6
    rows = (len(thumbs) + cols - 1) // cols
    tile_w = max(t.width for t, _ in thumbs)
    cell_w, cell_h = tile_w + 10, 320 + LABEL_H + 8
    sheet = Image.new("RGB", (cols * cell_w, rows * cell_h), "white")
    draw = ImageDraw.Draw(sheet)
    for i, (tile, label) in enumerate(thumbs):
        cx = (i % cols) * cell_w + 5
        cy = (i // cols) * cell_h + LABEL_H + 4
        sheet.paste(tile, (cx, cy))
        draw.text((cx, cy - LABEL_H + 2), label, fill="black")
    sheet.save(out_path, quality=88)
    return out_path


if __name__ == "__main__":
    pull = sys.argv[1] if len(sys.argv) > 1 else newest_pull()
    PAIRS = os.path.join(H23, pull, "pairs")
    if not os.path.isdir(PAIRS):
        raise SystemExit("no pairs directory: %s" % PAIRS)
    os.makedirs(OUT, exist_ok=True)
    assets = sorted({f.split("_in.jpg")[0] for f in os.listdir(PAIRS) if f.endswith("_in.jpg")})
    print("pull   : %s" % pull)
    print("pages  : %d" % len(assets))
    for asset in assets:
        pairs = [
            (os.path.join(PAIRS, "%s_in.jpg" % asset), "%s input" % asset),
            (os.path.join(PAIRS, "%s_full.jpg" % asset), "%s FULL" % asset),
            (os.path.join(PAIRS, "%s_clahe.jpg" % asset), "%s CLAHE" % asset),
        ]
        if all(os.path.exists(p) for p, _ in pairs):
            print(row(pairs, ROW_HEIGHT, os.path.join(OUT, "%s_row.jpg" % asset)))
            print(zoom(pairs, os.path.join(OUT, "%s_zoom.jpg" % asset)))
    print(overview(assets, os.path.join(OUT, "overview_all_pages.jpg")))
