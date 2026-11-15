#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H38 — draw the SAM GT quads on the bundled new-5 assets for a visual sanity check."""
from __future__ import annotations

import json
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
EV = os.path.join(HERE, "out", "h38", "sam_gt_new5.json")
ASSETS = os.path.join(REPO, "app", "src", "androidTest", "assets", "owner110")
OUT = os.path.join(HERE, "out", "h38", "sam_overlay")


def main():
    os.makedirs(OUT, exist_ok=True)
    with open(EV, "r", encoding="utf-8") as fh:
        ev = json.load(fh)
    for e in ev["items"]:
        if not e.get("ok"):
            print("skip", e["image"])
            continue
        name = e["image"]
        im = Image.open(os.path.join(ASSETS, name)).convert("RGB")
        sc = 0.35
        w, h = im.size
        sm = im.resize((int(w * sc), int(h * sc)))
        d = ImageDraw.Draw(sm)
        c = e["corners_px"]
        pts = [(c["tl"][0] * sc, c["tl"][1] * sc), (c["tr"][0] * sc, c["tr"][1] * sc),
               (c["br"][0] * sc, c["br"][1] * sc), (c["bl"][0] * sc, c["bl"][1] * sc)]
        d.line(pts + [pts[0]], fill=(255, 0, 0), width=4)
        for p in pts:
            d.ellipse([p[0] - 6, p[1] - 6, p[0] + 6, p[1] + 6], fill=(255, 255, 0))
        sm.save(os.path.join(OUT, name))
        print(name, "overlay", sm.size)
    return 0


if __name__ == "__main__":
    sys.exit(main())
