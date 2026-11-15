"""Is the quad's inside edge ON the gutter, or short of it?

Two very different problems hide in the UNDER class:

  A. the quad ends AT the gutter - it captured one leaf of a two-leaf target.
     Fixable by a rule: extend across the gutter when the facing leaf is visible.
  B. the quad ends INSIDE the paper - imprecise corners.
     Not fixable by a rule; needs corner accuracy.

Discriminator: for the side of the quad that is short of the page, is there a strong luminance
dip (gutter shadow) just outside it? A gutter is a dark valley; the middle of a printed page is
not.
"""
import json

import cv2
import numpy as np

from pathlib import Path

ROOT = Path("app/src/androidTest/assets/holdout42")
MASKS = Path("tooling/out/h48/masks")
TRACES = Path("tooling/out/h39/h59norepair")

rows = json.load(open(r"tooling/out/h50/norepair.json", encoding="utf-8"))
rows = [r for r in rows if r["verdict"] in ("UNDER", "PART")]

print(f"{'photo':22} {'verdict':6} {'rec':>5} {'short side':11} {'gapPx':>6} {'dip':>6} "
      f"{'gutter?':>8}")
for r in sorted(rows, key=lambda r: r["iou"]):
    stem = r["image"][:-4]
    t = json.load(open(TRACES / stem / "trace.json", encoding="utf-8"))
    if not t.get("quad"):
        continue
    mask = np.load(MASKS / f"{stem}.npy").astype(bool)
    gray = cv2.imread(str(ROOT / f"{stem}.jpg"), cv2.IMREAD_GRAYSCALE).astype(np.float32)
    if gray.shape != mask.shape:
        gray = cv2.resize(gray, (mask.shape[1], mask.shape[0]))
    h, w = gray.shape

    W, H = float(t["image_w"]), float(t["image_h"])
    q = np.array([[d["x"] / W * w, d["y"] / H * h] for d in t["quad"]], np.float64)

    ys, xs = np.nonzero(mask)
    qys, qxs = [], []
    m = np.zeros((h, w), np.uint8)
    cv2.fillPoly(m, [np.round(q).astype(np.int32)], 1)
    qys, qxs = np.nonzero(m)

    # Which page side is short: compare quad bbox against page bbox in image axes.
    page_l, page_r, page_t, page_b = xs.min(), xs.max(), ys.min(), ys.max()
    q_l, q_r, q_t, q_b = qxs.min(), qxs.max(), qys.min(), qys.max()
    gaps = {"left": q_l - page_l, "right": page_r - q_r,
            "top": q_t - page_t, "bottom": page_b - q_b}
    side = max(gaps, key=gaps.get)
    gap = int(gaps[side])

    # Luminance profile just outside the short side: is there a dark valley?
    band = max(6, int(0.02 * min(w, h)))
    prof = []
    for k in range(1, 8):
        if side in ("left", "right"):
            x = int(q_l - k * band) if side == "left" else int(q_r + k * band)
            if not (0 <= x < w):
                continue
            seg = gray[max(0, q_t):min(h, q_b), x]
        else:
            y = int(q_t - k * band) if side == "top" else int(q_b + k * band)
            if not (0 <= y < h):
                continue
            seg = gray[y, max(0, q_l):min(w, q_r)]
        if seg.size:
            prof.append(float(np.median(seg)))
    if len(prof) >= 3:
        interior = float(np.median(gray[m.astype(bool)]))
        dip = interior - min(prof)
    else:
        dip = 0.0

    print(f"{stem:22} {r['verdict']:6} {r['recall']:5.2f} {side:11} {gap:6d} {dip:6.1f} "
          f"{'YES' if dip > 25 else '-':>8}")
