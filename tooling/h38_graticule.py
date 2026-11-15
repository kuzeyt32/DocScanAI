#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H38 — draw a labelled 5%% graticule on the 5 new assets so the GT corners can be
read off visually (vision-assistant annotation, cross-checked against SAM proposals)."""
from __future__ import annotations

import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
ASSETS = os.path.join(REPO, "app", "src", "androidTest", "assets", "owner110")
OUT = os.path.join(HERE, "out", "h38", "graticule")
NAMES = ["20261025_130818.jpg", "20261025_130821.jpg", "20261025_131201.jpg",
         "20261025_131246.jpg", "20261025_131249.jpg"]

W_OUT = 1000  # output width; keeps 5% steps readable


def main():
    os.makedirs(OUT, exist_ok=True)
    for name in NAMES:
        im = Image.open(os.path.join(ASSETS, name)).convert("RGB")
        w, h = im.size
        s = W_OUT / float(w)
        sm = im.resize((W_OUT, int(h * s)))
        d = ImageDraw.Draw(sm)
        for i in range(1, 20):
            x = W_OUT * i / 20.0
            y = sm.size[1] * i / 20.0
            col = (255, 255, 0) if i % 2 == 0 else (0, 255, 255)
            d.line([(x, 0), (x, sm.size[1])], fill=col, width=1)
            d.line([(0, y), (W_OUT, y)], fill=col, width=1)
        for i in range(0, 21, 2):
            x = W_OUT * i / 20.0
            y = sm.size[1] * i / 20.0
            d.text((x + 2, 2), "%d%%" % (i * 5), fill=(255, 255, 0))
            d.text((2, y + 2), "%d%%" % (i * 5), fill=(255, 255, 0))
        sm.save(os.path.join(OUT, name))
        print(name, sm.size)
    return 0


if __name__ == "__main__":
    sys.exit(main())
