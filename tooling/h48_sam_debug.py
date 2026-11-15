#!/usr/bin/env python3
"""
H48b - why did SAM return the whole frame on the holdout?

The holdout photos are framed tightly on the book, so the standard "box prompt = whole frame"
gives SAM a box covering the entire scene and it segments the scene. The owner110 photos had
table margin around the book, which is why the same protocol worked there.

This prints, for a few photos:
  * the 3 SAM candidates for a full-frame box (area fraction + paper score);
  * the same for a centre box (60 % of the frame);
  * a model-free reference: Otsu-bright largest component's box and area.

Usage: python tooling\\h48_sam_debug.py --set holdout42 --images 20261115_192319,20261115_192403
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from h36_sam import SamOracle, paper_score  # noqa: E402


def otsu_box(gray: np.ndarray) -> tuple[tuple[int, int, int, int], float, bool]:
    """Bright region vs dark table. Returns box, area fraction, touches-frame."""
    h, w = gray.shape
    blur = cv2.GaussianBlur(gray, (0, 0), 3)
    _, th = cv2.threshold(blur, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    th = cv2.morphologyEx(th, cv2.MORPH_CLOSE, np.ones((15, 15), np.uint8))
    th = cv2.morphologyEx(th, cv2.MORPH_OPEN, np.ones((9, 9), np.uint8))
    n, lab, stats, _ = cv2.connectedComponentsWithStats(th, 8)
    if n <= 1:
        return (0, 0, w, h), 1.0, True
    k = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    x, y, bw, bh, area = stats[k]
    touch = x <= 2 or y <= 2 or x + bw >= w - 2 or y + bh >= h - 2
    return (int(x), int(y), int(x + bw), int(y + bh)), area / (w * h), touch


def candidates(oracle: SamOracle, pil: Image.Image, box) -> list[dict]:
    emb, wh = oracle.encode(pil)
    dec = oracle.decode_box(emb, box, wh)
    logits = dec[0]
    gray = np.asarray(pil.convert("L"), dtype=np.float32)
    out = []
    for k in range(logits.shape[0]):
        m = oracle._mask_to_image(logits[k] > 0.0, wh)
        out.append({"k": k, "area": round(float(m.sum()) / (wh[0] * wh[1]), 4),
                    "paper": round(float(paper_score(m, gray)), 3)})
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", default="holdout42")
    ap.add_argument("--images", required=True)
    ap.add_argument("--limit", type=int, default=4)
    args = ap.parse_args()

    root = Path("app/src/androidTest/assets") / args.set
    names = [n.strip() for n in args.images.split(",") if n.strip()][:args.limit]
    oracle = SamOracle()
    print(f"providers: {oracle.providers}\n")

    for name in names:
        path = root / (name if name.endswith(".jpg") else name + ".jpg")
        pil = Image.open(path)
        w, h = pil.size
        g = np.asarray(pil.convert("L"))
        full = (0.0, 0.0, float(w), float(h))
        cx, cy = 0.2 * w, 0.2 * h
        centre = (cx, cy, 0.8 * w, 0.8 * h)
        obox, oarea, otouch = otsu_box(g)
        print(f"== {name}  {w}x{h}")
        print(f"   otsu bright: box {obox} area {oarea:.3f} touches_frame={otouch}")
        print(f"   full-frame box candidates: {candidates(oracle, pil, full)}")
        print(f"   centre box   candidates: {candidates(oracle, pil, centre)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
