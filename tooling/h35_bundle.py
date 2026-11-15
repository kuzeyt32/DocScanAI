#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H35 — bundle the owner-110 photos as instrumented-test assets + oracle manifest.

Reads (NEVER writes) corpus_owner/, and produces:

  app/src/androidTest/assets/owner110/<name>.jpg   downscaled copies, long edge <= 1400 px
  app/src/androidTest/assets/owner110/owner110_manifest.json
  tooling/out/h35/pseudo_gt_asset.json              oracle pseudo-GT in ASSET pixel space
  tooling/out/h35/HEARTBEAT.txt

Coordinate frame contract (CRITICAL):
  The oracle pseudo-GT (tooling/out/h34/pseudo_gt_owner110.json) records normalised corners
  in the RAW (un-transposed) JPEG pixel frame: the recorded width/height are the raw ones
  (e.g. 4000x3000) even when EXIF orientation is 6. Android's BitmapFactory.decodeStream also
  returns RAW pixels (no EXIF transpose), and so does PIL without ImageOps.exif_transpose.
  Both the bundled assets and the oracle corners are therefore expressed in the SAME raw
  pixel frame; only the scale differs (asset = raw * s), and the manifest stores the scale.

Usage:
  python tooling\\h35_bundle.py
"""

from __future__ import annotations

import glob
import json
import os
import sys
import time

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)

OUT = os.path.join(HERE, "out", "h35")
CORPUS = os.path.join(REPO, "corpus_owner")
ASSET_DIR = os.path.join(REPO, "app", "src", "androidTest", "assets", "owner110")
PSEUDO_GT = os.path.join(HERE, "out", "h34", "pseudo_gt_owner110.json")

MAX_LONG_EDGE = 1400
JPEG_QUALITY = 92
CORNER_KEYS = ("tl", "tr", "br", "bl")


def hb(msg):
    os.makedirs(OUT, exist_ok=True)
    line = "[%s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg)
    with open(os.path.join(OUT, "HEARTBEAT.txt"), "a", encoding="utf-8") as fh:
        fh.write(line + "\n")
    print(line, flush=True)


def oracle_corners(rec):
    """Normalised (x,y) per corner as a tl/tr/br/bl dict.

    Handles BOTH stored shapes:
      - dict {tl:[x,y], tr, br, bl}           (strict parse + the stored lenient dict)
      - list [[x,y], ...] in TL,TR,BR,BL order (h34_oracle_run.parse_corners_lenient fallback)
    """
    def norm(c):
        if isinstance(c, dict):
            return {k: [float(c[k][0]), float(c[k][1])] for k in CORNER_KEYS}
        if isinstance(c, (list, tuple)) and len(c) == 4:
            return {k: [float(c[i][0]), float(c[i][1])] for i, k in enumerate(CORNER_KEYS)}
        return None

    if rec.get("corners"):
        c = norm(rec["corners"])
        if c:
            return c, rec.get("corner_format", "xy")
    if rec.get("corners_lenient"):
        c = norm(rec["corners_lenient"])
        if c:
            return c, rec.get("corner_format")
    import h34_oracle_run as HR  # local import: only needed for legacy raw-only rows
    raw_c, raw_fmt = HR.parse_corners_lenient(rec.get("raw"))
    return norm(raw_c), (raw_fmt or rec.get("corner_format"))


def main():
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(ASSET_DIR, exist_ok=True)

    with open(PSEUDO_GT, "r", encoding="utf-8") as fh:
        gt = json.load(fh)

    by_name = {}
    for rec in gt.get("items", []):
        name = os.path.basename(rec["image"])
        by_name[name] = rec

    names = sorted(
        os.path.basename(p)
        for p in glob.glob(os.path.join(CORPUS, "*.jpg"))
    )
    if not names:
        hb("ERROR no jpgs in corpus_owner/")
        return 2

    manifest_items = []
    gt_items = []
    total_bytes = 0
    for name in names:
        src = os.path.join(CORPUS, name)
        rec = by_name.get(name)
        if rec is None:
            hb("SKIP %s (no oracle record)" % name)
            continue
        with Image.open(src) as im:
            im = im.convert("RGB")
            sw, sh = im.size
            scale = 1.0
            if max(sw, sh) > MAX_LONG_EDGE:
                scale = float(MAX_LONG_EDGE) / float(max(sw, sh))
            aw = max(1, int(round(sw * scale)))
            ah = max(1, int(round(sh * scale)))
            out = im.resize((aw, ah), Image.LANCZOS)
            dst = os.path.join(ASSET_DIR, name)
            out.save(dst, "JPEG", quality=JPEG_QUALITY)

        corners, fmt = oracle_corners(rec)
        if not corners:
            hb("SKIP %s (no oracle corners)" % name)
            continue
        px = {k: [round(corners[k][0] * aw, 2), round(corners[k][1] * ah, 2)]
              for k in CORNER_KEYS}

        with Image.open(dst) as chk:
            aw2, ah2 = chk.size
        size = os.path.getsize(dst)
        total_bytes += size
        manifest_items.append({
            "image": name,
            "width": aw2,
            "height": ah2,
            "diagonal": round((aw2 ** 2 + ah2 ** 2) ** 0.5, 3),
            "asset_bytes": size,
            "src_width": sw,
            "src_height": sh,
            "src_scale": round(scale, 6),
            "oracle_format": fmt,
        })
        gt_items.append({
            "image": name,
            "width": aw2,
            "height": ah2,
            "src_scale": round(scale, 6),
            "oracle_format": fmt,
            "corners_px": px,
        })
        hb("bundled %s raw=%dx%d asset=%dx%d scale=%.4f bytes=%d" %
           (name, sw, sh, aw2, ah2, scale, size))

    manifest = {
        "dataset": "owner110",
        "note": ("Downscaled copies of corpus_owner/ for the H35 real-path replay. "
                 "Pixel frame = RAW JPEG frame (no EXIF transpose), matching the oracle "
                 "pseudo-GT and BitmapFactory.decodeStream. Originals never modified."),
        "max_long_edge": MAX_LONG_EDGE,
        "jpeg_quality": JPEG_QUALITY,
        "count": len(manifest_items),
        "total_bytes": total_bytes,
        "items": manifest_items,
    }
    with open(os.path.join(ASSET_DIR, "owner110_manifest.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=1)
    with open(os.path.join(OUT, "owner110_manifest.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=1)

    gt_out = {
        "dataset": "owner110",
        "source": os.path.relpath(PSEUDO_GT, REPO).replace("\\", "/"),
        "frame": "raw-jpeg-pixels-of-the-bundled-asset",
        "note": ("Oracle pseudo-GT (Qwen3-VL-8B) re-expressed in the bundled asset pixel "
                 "space. Oracle corners are ~10 % of the diagonal off on real photos - every "
                 "IoU / corner-error derived from them is a LOWER BOUND of ours-vs-truth."),
        "count": len(gt_items),
        "items": gt_items,
    }
    with open(os.path.join(OUT, "pseudo_gt_asset.json"), "w", encoding="utf-8") as fh:
        json.dump(gt_out, fh, indent=1)
    # The instrumentation test reads the SAME contract from assets.
    with open(os.path.join(ASSET_DIR, "oracle_manifest.json"), "w", encoding="utf-8") as fh:
        json.dump(gt_out, fh, indent=1)

    hb("bundle done items=%d total_bytes=%d (%.1f MB) -> %s"
       % (len(manifest_items), total_bytes, total_bytes / 1e6,
          os.path.relpath(ASSET_DIR, REPO)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
