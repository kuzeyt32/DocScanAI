#!/usr/bin/env python3
"""
H65 - is a detected quad a PAGE, or a PANEL lying on a page?

The failure this targets: `20261115_192442` locks a white sticker sheet lying on a green workbook
cover. The quad is a perfectly good rectangle with precision 1.00 - it is simply the wrong
rectangle, because a larger sheet continues outside it.

Signal (production-shaped: pixels only, no segmentation):
  sample a thin ring just OUTSIDE each quad edge and compare it with the quad's interior.
    * a real page edge has table / shadow / background outside -> a clear step
    * a panel lying on a page has more SHEET outside           -> little or no step

Reported per quad:
  ring_luma   - median luma of the ring, relative to the interior
  ring_ink    - edge density in the ring (more sheet is usually printed, though margins are not)
  enclosed    - True when the ring looks like continuation of paper rather than background

This is a probe, not a shipped rule. It exists so the "panel vs page" question can be answered
with numbers before anything is added to the detector.

Usage: python tooling\\h65_enclosure.py --tag h64fast --score tooling/out/h50/fast.json
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np


def ring_points(quad: np.ndarray, w: int, h: int, off: float):
    """Points on a thin band `off` px outside each edge (quad corners in TL,TR,BR,BL order)."""
    pts = []
    for i in range(4):
        a, b = quad[i], quad[(i + 1) % 4]
        ex, ey = float(b[0] - a[0]), float(b[1] - a[1])
        ln = float(np.hypot(ex, ey))
        if ln < 1e-6:
            continue
        nx, ny = ey / ln, -ex / ln
        for t in np.linspace(0.15, 0.85, 14):
            bx, by = a[0] + ex * t, a[1] + ey * t
            x, y = bx + nx * off, by + ny * off
            xi, yi = int(round(x)), int(round(y))
            if 0 <= yi < h and 0 <= xi < w:
                pts.append((xi, yi))
    return pts


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="h64fast")
    ap.add_argument("--set", default="holdout42")
    ap.add_argument("--score", default="tooling/out/h50/fast.json")
    args = ap.parse_args()

    root = Path("app/src/androidTest/assets") / args.set
    traces = Path("tooling/out/h39") / args.tag
    scores = {r["image"][:-4]: r
              for r in json.loads(Path(args.score).read_text(encoding="utf-8"))}

    print(f"{'photo':22} {'verdict':6} {'rec':>5} {'prec':>5} {'ring/int':>9} {'ringInk':>8} "
          f"{'enclosed':>9}")
    rows = []
    for tp in sorted(traces.glob("*/trace.json")):
        stem = tp.parent.name
        t = json.loads(tp.read_text(encoding="utf-8"))
        if not t.get("quad"):
            continue
        img = cv2.imread(str(root / f"{stem}.jpg"), cv2.IMREAD_GRAYSCALE)
        if img is None:
            continue
        h, w = img.shape
        W, H = float(t["image_w"]), float(t["image_h"])
        quad = np.array([[d["x"] / W * w, d["y"] / H * h] for d in t["quad"]], np.float64)

        gray = cv2.GaussianBlur(img.astype(np.float32), (5, 5), 0)
        edges = cv2.Canny(gray.astype(np.uint8), 40, 120) > 0

        inner = np.zeros((h, w), np.uint8)
        cx, cy = quad[:, 0].mean(), quad[:, 1].mean()
        shrunk = np.stack([cx + (quad[:, 0] - cx) * 0.75,
                           cy + (quad[:, 1] - cy) * 0.75], 1)
        cv2.fillPoly(inner, [np.round(shrunk).astype(np.int32)], 1)
        inner = inner.astype(bool)
        if inner.sum() < 400:
            continue

        off = 0.022 * min(w, h)
        pts = ring_points(quad, w, h, off)
        if len(pts) < 40:
            continue
        ys = [p[1] for p in pts]
        xs = [p[0] for p in pts]
        ring_luma = float(np.median(gray[ys, xs]))
        ring_ink = float(edges[ys, xs].mean())
        in_luma = float(np.median(gray[inner]))
        ratio = ring_luma / max(1.0, in_luma)

        # Enclosed = the ring is nearly as bright as the paper and not much darker.
        enclosed = bool(ratio > 0.82)
        s = scores.get(stem, {})
        rows.append({"stem": stem, "ratio": round(ratio, 3), "ring_ink": round(ring_ink, 3),
                     "enclosed": enclosed, "verdict": s.get("verdict"),
                     "recall": s.get("recall"), "precision": s.get("precision")})
        print(f"{stem:22} {str(s.get('verdict')):6} {s.get('recall') or 0:5.2f} "
              f"{s.get('precision') or 0:5.2f} {ratio:9.3f} {ring_ink:8.3f} "
              f"{str(enclosed):>9}")

    out = Path("tooling/out/h65")
    out.mkdir(parents=True, exist_ok=True)
    (out / "enclosure.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")

    enc = [r for r in rows if r["enclosed"]]
    print(f"\n{len(enc)} of {len(rows)} quads have a paper-like ring (candidate panels):")
    for r in enc:
        print(f"   {r['stem']:22} {r['verdict']:6} ratio={r['ratio']:.3f} "
              f"ink={r['ring_ink']:.3f}")
    print("\nA ring ratio threshold near 0.82 is a STARTING POINT, not a validated cut: the counts")
    print("per verdict above are what must be checked before any rule is built on it.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
