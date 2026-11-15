#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H38 — write the vision-read GT corners for the 5 new photos into the oracle manifests.

Corners were read off the 5% graticules in tooling/out/h38/graticule/ (Zoo vision annotation,
the same oracle class the owner accepted in earlier subtasks; SAM's full-frame protocol could
not isolate these pages — its masks selected the table/background, see sam_gt_new5.json).

Target = the photographed book page (photos 1-3) / the open book spread (photos 4-5, where the
whole spread is the fully visible subject). Coordinates are NORMALIZED tl/tr/br/bl in the RAW
JPEG frame == bundled asset frame (uniform scale, no EXIF transpose).

Patches (idempotent):
  app/src/androidTest/assets/owner110/oracle_manifest.json   corners_px per item
  tooling/out/h35/pseudo_gt_asset.json                       corners_px per item
  tooling/out/h34/pseudo_gt_owner110.json                    corners / corners_lenient
  tooling/out/h38/sam_gt_new5.json                            vision GT evidence
and draws verification overlays to tooling/out/h38/gt_overlay/.

Usage:
  python tooling\\h38_patch_gt.py
"""

from __future__ import annotations

import json
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
OUT38 = os.path.join(HERE, "out", "h38")
ASSETS = os.path.join(REPO, "app", "src", "androidTest", "assets", "owner110")
ORACLE = os.path.join(ASSETS, "oracle_manifest.json")
GT_ASSET = os.path.join(HERE, "out", "h35", "pseudo_gt_asset.json")
GT_RAW = os.path.join(HERE, "out", "h34", "pseudo_gt_owner110.json")
CORPUS = os.path.join(REPO, "corpus_owner")

# Vision-read normalized corners (x, y) in the asset frame, tl/tr/br/bl.
VISION_GT = {
    "20261025_130818.jpg": {  # single curved sheet, tilted on a light table
        "tl": [0.155, 0.375], "tr": [0.715, 0.245], "br": [0.790, 0.620], "bl": [0.260, 0.950],
    },
    "20261025_130821.jpg": {  # one book page (6.3), curved toward the spine
        "tl": [0.210, 0.135], "tr": [0.760, 0.065], "br": [0.745, 0.700], "bl": [0.185, 0.635],
    },
    "20261025_131201.jpg": {  # one book page (Chapter 11)
        "tl": [0.135, 0.100], "tr": [0.700, 0.085], "br": [0.695, 0.685], "bl": [0.125, 0.700],
    },
    "20261025_131246.jpg": {  # whole open book spread (both pages fully visible)
        "tl": [0.245, 0.085], "tr": [0.635, 0.075], "br": [0.625, 0.875], "bl": [0.255, 0.885],
    },
    "20261025_131249.jpg": {  # whole open book spread (both pages fully visible)
        "tl": [0.205, 0.235], "tr": [0.770, 0.230], "br": [0.755, 0.700], "bl": [0.160, 0.720],
    },
}
CORNER_KEYS = ("tl", "tr", "br", "bl")


def main():
    os.makedirs(OUT38, exist_ok=True)
    evidence = []
    for name, norm in VISION_GT.items():
        im = Image.open(os.path.join(ASSETS, name))
        w, h = im.size
        px = {k: [round(norm[k][0] * w, 1), round(norm[k][1] * h, 1)] for k in CORNER_KEYS}

        for mpath in (ORACLE, GT_ASSET):
            with open(mpath, "r", encoding="utf-8") as fh:
                doc = json.load(fh)
            hit = False
            for it in doc.get("items", []):
                if it.get("image") == name:
                    it["corners_px"] = px
                    hit = True
            if not hit:
                doc.setdefault("items", []).append({
                    "image": name, "width": w, "height": h,
                    "src_scale": round(1400.0 / max(Image.open(os.path.join(CORPUS, name)).size), 6),
                    "corners_px": px,
                })
            doc["count"] = len(doc.get("items", []))
            with open(mpath, "w", encoding="utf-8") as fh:
                json.dump(doc, fh, indent=1)

        with open(GT_RAW, "r", encoding="utf-8") as fh:
            doc = json.load(fh)
        hit = False
        for it in doc.get("items", []):
            if os.path.basename(it.get("image", "")) == name:
                it["corners"] = norm
                it["corners_lenient"] = norm
                it["model"] = "zoo-vision-graticule"
                hit = True
        if not hit:
            src = Image.open(os.path.join(CORPUS, name))
            sw, sh = src.size
            doc.setdefault("items", []).append({
                "image": os.path.join(CORPUS, name), "width": sw, "height": sh,
                "target_page": "the fully visible page",
                "server": "local", "model": "zoo-vision-graticule",
                "corners": norm, "raw": "", "ok": True,
                "corners_lenient": norm, "corner_format": "xy",
            })
        with open(GT_RAW, "w", encoding="utf-8") as fh:
            json.dump(doc, fh, indent=1)

        # verification overlay
        sm = im.convert("RGB").resize((489, int(h * 489.0 / w)))
        d = ImageDraw.Draw(sm)
        s = 489.0 / w
        pts = [(norm[k][0] * w * s, norm[k][1] * h * s) for k in CORNER_KEYS]
        d.line(pts + [pts[0]], fill=(255, 0, 0), width=3)
        for p in pts:
            d.ellipse([p[0] - 5, p[1] - 5, p[0] + 5, p[1] + 5], fill=(255, 255, 0))
        os.makedirs(os.path.join(OUT38, "gt_overlay"), exist_ok=True)
        sm.save(os.path.join(OUT38, "gt_overlay", name))

        evidence.append({"image": name, "ok": True, "source": "zoo-vision-graticule",
                         "asset": [w, h], "corners_norm": norm, "corners_px": px})
        print("%s -> tl=%s tr=%s br=%s bl=%s" % (name, px["tl"], px["tr"], px["br"], px["bl"]))

    with open(os.path.join(OUT38, "sam_gt_new5.json"), "w", encoding="utf-8") as fh:
        json.dump({
            "dataset": "owner110-new5",
            "oracle": "zoo-vision graticule annotation (SAM full-frame masks unusable here)",
            "frame": "raw-jpeg-pixels-of-the-bundled-asset",
            "items": evidence,
        }, fh, indent=1)
    print("patched 3 manifests + sam_gt_new5.json; overlays in gt_overlay/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
