#!/usr/bin/env python3
"""
H61 - can a leaf-sized quad be EXTENDED across a visible gutter?

11 holdout photos still fall short. A subset of them look like "the quad captured one leaf of a
two-leaf target", which a rule could fix: find the gutter just outside the quad, mirror/extend
the quad across it, and keep the union if the result is still page-shaped.

Whether that is safe depends on a question pixels can answer: is there really a gutter (a dark
valley, then more printed paper) just outside the short side, or is the short side just an
imprecise corner in the middle of a page?

This prototype measures, for each short photo:
  * the luminance profile moving OUTWARD from the quad's short side,
  * whether it dips then rises again (gutter signature),
  * how much EXTRA page mask lies beyond that side (what extending would gain).

It does not modify the detector. It answers whether the rule is worth writing.

Usage: python tooling\\h61_extend.py
"""
from __future__ import annotations

import json
from pathlib import Path

import cv2
import numpy as np

ROOT = Path("app/src/androidTest/assets/holdout42")
MASKS = Path("tooling/out/h48/masks")
TRACES = Path("tooling/out/h39/h59norepair")
SCORE = Path("tooling/out/h50/norepair.json")


def main() -> int:
    rows = json.load(open(SCORE, encoding="utf-8"))
    rows = [r for r in rows if r["verdict"] in ("UNDER", "PART")]
    print(f"{len(rows)} short photos\n")
    print(f"{'photo':22} {'rec':>5} {'side':7} {'gain%':>6} {'dip':>6} {'rise':>6} {'gutter':>7}")

    worth = []
    for r in sorted(rows, key=lambda r: r["recall"]):
        stem = r["image"][:-4]
        tp = TRACES / stem / "trace.json"
        mp = MASKS / f"{stem}.npy"
        if not tp.exists() or not mp.exists():
            continue
        t = json.loads(tp.read_text(encoding="utf-8"))
        if not t.get("quad"):
            continue
        mask = np.load(mp).astype(bool)
        img = cv2.imread(str(ROOT / f"{stem}.jpg"), cv2.IMREAD_GRAYSCALE)
        if img is None:
            continue
        if img.shape != mask.shape:
            img = cv2.resize(img, (mask.shape[1], mask.shape[0]))
        h, w = img.shape
        gray = img.astype(np.float64)

        W, H = float(t["image_w"]), float(t["image_h"])
        q = np.array([[d["x"] / W * w, d["y"] / H * h] for d in t["quad"]], np.float64)
        qm = np.zeros((h, w), np.uint8)
        cv2.fillPoly(qm, [np.round(q).astype(np.int32)], 1)
        qm = qm.astype(bool)

        ys, xs = np.nonzero(mask)
        qys, qxs = np.nonzero(qm)
        gaps = {
            "left": qxs.min() - xs.min(),
            "right": xs.max() - qxs.max(),
            "top": qys.min() - ys.min(),
            "bottom": ys.max() - qys.max(),
        }
        side = max(gaps, key=gaps.get)

        # What fraction of the page lies beyond that side? That is the ceiling on the gain.
        if side == "left":
            beyond = mask & (np.arange(w)[None, :] < qxs.min())
        elif side == "right":
            beyond = mask & (np.arange(w)[None, :] > qxs.max())
        elif side == "top":
            beyond = mask & (np.arange(h)[:, None] < qys.min())
        else:
            beyond = mask & (np.arange(h)[:, None] > qys.max())
        gain = 100.0 * int(beyond.sum()) / max(1, int(mask.sum()))

        # Luminance as we walk outward from the short side, inside the page's span.
        patch = max(4, int(0.012 * min(w, h)))
        prof = []
        for k in range(1, 13):
            off = k * patch
            if side in ("left", "right"):
                x = qxs.min() - off if side == "left" else qxs.max() + off
                if not (0 <= x < w):
                    break
                seg = gray[max(0, qys.min()):min(h, qys.max()), x]
            else:
                y = qys.min() - off if side == "top" else qys.max() + off
                if not (0 <= y < h):
                    break
                seg = gray[y, max(0, qxs.min()):min(w, qxs.max())]
            prof.append(float(np.median(seg)) if seg.size else np.nan)

        prof = [v for v in prof if not np.isnan(v)]
        if len(prof) < 5:
            continue
        inner = prof[0]
        dip = inner - min(prof)
        # A gutter: dark valley, then paper again (rise after the minimum).
        kmin = int(np.argmin(prof))
        rise = (max(prof[kmin:]) - prof[kmin]) if kmin < len(prof) - 1 else 0.0
        gutter = dip > 25 and rise > 25
        if gutter and gain >= 15:
            worth.append(stem)
        print(f"{stem:22} {r['recall']:5.2f} {side:7} {gain:6.1f} {dip:6.1f} {rise:6.1f} "
              f"{'YES' if gutter else '-':>7}")

    print(f"\n{len(worth)} photos look extendable (gutter signature AND >=15 % of the page beyond):")
    for s in worth:
        print(f"   {s}")
    print("\nNote: a gutter signature alone is not enough - a printed panel inside a page can also")
    print("show a dark border. Any rule must also check that what lies beyond is PRINTED paper,")
    print("not a blank margin, or it will reproduce exactly the over-cover bug 0.3.17 fixed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
