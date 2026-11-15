#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H38 — dump the 3 SAM mask candidates for the new-5 photos (diagnostic)."""
from __future__ import annotations

import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import h36_sam as SAM  # noqa: E402

ASSETS = os.path.join(REPO, "app", "src", "androidTest", "assets", "owner110")
OUT = os.path.join(HERE, "out", "h38", "mask_cands")
NAMES = ["20261025_130818.jpg", "20261025_130821.jpg", "20261025_131201.jpg",
         "20261025_131246.jpg", "20261025_131249.jpg"]


def main():
    os.makedirs(OUT, exist_ok=True)
    oracle = SAM.SamOracle(resize="squash")
    for name in NAMES:
        path = os.path.join(ASSETS, name)
        pil = Image.open(path)
        emb, wh = oracle.encode(pil)
        dec = oracle.decode_box(emb, (0.0, 0.0, float(pil.size[0]), float(pil.size[1])), wh)
        gray = np.asarray(pil.convert("L"), dtype=np.float32)
        frame = float(gray.shape[0] * gray.shape[1])
        base = os.path.splitext(name)[0]
        logits = dec[0] if dec.ndim == 4 else dec  # [3, 256, 256]
        for k in range(3):
            m256 = logits[k] > 0.0
            m = oracle._mask_to_image(m256, wh)
            area = float(m.sum()) / frame
            ps = SAM.paper_score(m, gray)
            overlay = np.asarray(pil.convert("RGB"), dtype=np.float32).copy()
            overlay[m] = overlay[m] * 0.4 + np.array([255, 0, 0], dtype=np.float32) * 0.6
            Image.fromarray(overlay.astype(np.uint8)).resize(
                (489, 367)).save(os.path.join(OUT, "%s_k%d_a%.3f_p%.2f.jpg" % (base, k, area, ps)))
            print("%s k=%d area=%.4f paper=%.3f" % (name, k, area, ps))
    return 0


if __name__ == "__main__":
    sys.exit(main())
