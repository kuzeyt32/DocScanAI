#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H38 — extend the owner110 bundle with the 5 new owner struggle photos + SAM pseudo-GT.

Reads (NEVER writes) corpus_owner/20261025_13*.jpg and produces downscaled copies plus
manifest/oracle entries in the SAME schema tooling/h35_bundle.py established:

  app/src/androidTest/assets/owner110/<name>.jpg            downscaled, long edge <= 1400 px
  app/src/androidTest/assets/owner110/owner110_manifest.json  (items appended, idempotent)
  app/src/androidTest/assets/owner110/oracle_manifest.json     (items appended)
  tooling/out/h35/owner110_manifest.json                       (mirror)
  tooling/out/h35/pseudo_gt_asset.json                         (items appended)
  tooling/out/h34/pseudo_gt_owner110.json                      (raw-normalised items appended)
  tooling/out/h38/sam_gt_new5.json                             (SAM evidence for the 5)

Ground truth for the new photos comes from the H36 SAM oracle (measurement only, never
shipped): sam2.1-hiera-large ONNX from E:\\DocScanAI-cache\\sam, full-frame box prompt ->
largest paper mask -> min-area-rect + per-corner refinement (tooling/h36_sam.py protocol).
Normalized corners are scale-invariant, so the SAME corners serve the raw frame
(pseudo_gt_owner110.json) and the bundled asset frame (oracle_manifest.json).

Coordinate frame contract (identical to h35_bundle.py):
  RAW JPEG pixels, NO EXIF transpose (matches BitmapFactory.decodeStream and PIL without
  ImageOps.exif_transpose). asset = raw * src_scale.

Usage:
  python tooling\\h38_assets.py
"""

from __future__ import annotations

import glob
import json
import math
import os
import sys
import time

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)

OUT38 = os.path.join(HERE, "out", "h38")
CORPUS = os.path.join(REPO, "corpus_owner")
ASSET_DIR = os.path.join(REPO, "app", "src", "androidTest", "assets", "owner110")
MANIFEST = os.path.join(ASSET_DIR, "owner110_manifest.json")
MANIFEST_MIRROR = os.path.join(HERE, "out", "h35", "owner110_manifest.json")
ORACLE = os.path.join(ASSET_DIR, "oracle_manifest.json")
GT_ASSET = os.path.join(HERE, "out", "h35", "pseudo_gt_asset.json")
GT_RAW = os.path.join(HERE, "out", "h34", "pseudo_gt_owner110.json")

NEW_GLOB = "20261025_*.jpg"
MAX_LONG_EDGE = 1400
JPEG_QUALITY = 92
CORNER_KEYS = ("tl", "tr", "br", "bl")


def hb(msg):
    os.makedirs(OUT38, exist_ok=True)
    line = "[%s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg)
    with open(os.path.join(OUT38, "HEARTBEAT.txt"), "a", encoding="utf-8") as fh:
        fh.write(line + "\n")
    print(line, flush=True)


def load_json(path, default):
    if not os.path.isfile(path):
        return default
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def save_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(obj, fh, indent=1)


def main():
    os.makedirs(OUT38, exist_ok=True)
    names = sorted(os.path.basename(p) for p in glob.glob(os.path.join(CORPUS, NEW_GLOB)))
    if not names:
        hb("ERROR no %s in corpus_owner/" % NEW_GLOB)
        return 2
    hb("new photos: %s" % ", ".join(names))

    manifest = load_json(MANIFEST, None)
    if manifest is None:
        hb("ERROR missing %s (run h35_bundle.py first)" % MANIFEST)
        return 2
    oracle = load_json(ORACLE, {"dataset": "owner110", "items": []})
    gt_asset = load_json(GT_ASSET, {"items": []})
    gt_raw = load_json(GT_RAW, {"items": []})

    have = {it["image"] for it in manifest.get("items", [])}
    have_oracle = {it["image"] for it in oracle.get("items", [])}
    have_gt_asset = {it["image"] for it in gt_asset.get("items", [])}
    have_gt_raw = {os.path.basename(it.get("image", "")) for it in gt_raw.get("items", [])}

    # One SAM oracle instance for all 5 (the 888 MB vision encoder loads once).
    import h36_sam as SAM

    hb("loading SAM oracle (%s)..." % SAM.SAM_DIR)
    oracle_sam = SAM.SamOracle(resize="squash")
    hb("SAM providers: %s" % (oracle_sam.providers,))

    evidence = []
    for name in names:
        src = os.path.join(CORPUS, name)
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
        size = os.path.getsize(dst)

        quad, meta, _wh = SAM.run_one(oracle_sam, dst, None)
        if quad is None:
            hb("ERROR %s: SAM produced NO MASK - cannot score this photo" % name)
            evidence.append({"image": name, "ok": False, "meta": meta})
            continue

        # Normalized corners in the RAW frame == in the asset frame (uniform scale).
        norm = {k: [round(float(quad[i][0]) / aw, 6), round(float(quad[i][1]) / ah, 6)]
                for i, k in enumerate(CORNER_KEYS)}
        px = {k: [round(float(quad[i][0]), 1), round(float(quad[i][1]), 1)]
              for i, k in enumerate(CORNER_KEYS)}

        if name not in have:
            manifest["items"].append({
                "image": name,
                "width": aw, "height": ah,
                "diagonal": round(math.hypot(aw, ah), 3),
                "asset_bytes": size,
                "src_width": sw, "src_height": sh,
                "src_scale": round(scale, 6),
                "oracle_format": "xy",
            })
        if name not in have_oracle:
            oracle["items"].append({
                "image": name, "width": aw, "height": ah,
                "src_scale": round(scale, 6), "corners_px": px,
            })
        if name not in have_gt_asset:
            gt_asset["items"].append({
                "image": name, "width": aw, "height": ah,
                "src_scale": round(scale, 6), "corners_px": px,
            })
        if name not in have_gt_raw:
            gt_raw["items"].append({
                "image": os.path.join(CORPUS, name),
                "width": sw, "height": sh,
                "target_page": "the fully visible page",
                "server": "sam2.1-hiera-large", "model": "sam2.1-hiera-large",
                "corners": norm, "raw": "", "ok": True,
                "corners_lenient": norm, "corner_format": "xy",
            })

        evidence.append({
            "image": name, "ok": True, "raw": [sw, sh], "asset": [aw, ah],
            "corners_px": px, "corners_norm": norm, "meta": meta,
        })
        hb("%s raw=%dx%d asset=%dx%d SAM quad tl=%s tr=%s br=%s bl=%s bow=%.4f" % (
            name, sw, sh, aw, ah, px["tl"], px["tr"], px["br"], px["bl"],
            meta.get("bow", -1)))

    manifest["count"] = len(manifest["items"])
    save_json(MANIFEST, manifest)
    save_json(MANIFEST_MIRROR, manifest)
    oracle["count"] = len(oracle["items"])
    save_json(ORACLE, oracle)
    gt_asset["count"] = len(gt_asset["items"])
    save_json(GT_ASSET, gt_asset)
    save_json(GT_RAW, gt_raw)

    save_json(os.path.join(OUT38, "sam_gt_new5.json"), {
        "dataset": "owner110-new5",
        "oracle": "sam2.1-hiera-large (onnx-community ONNX), measurement-only",
        "frame": "raw-jpeg-pixels-of-the-bundled-asset",
        "resize_mode": "squash",
        "items": evidence,
    })
    ok = sum(1 for e in evidence if e.get("ok"))
    hb("h38_assets DONE ok=%d/%d -> manifests + oracle entries appended" % (ok, len(names)))
    return 0 if ok == len(names) else 1


if __name__ == "__main__":
    sys.exit(main())
