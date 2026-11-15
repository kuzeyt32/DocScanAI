#!/usr/bin/env python3
"""
H48 - is the oracle quad the PAGE or the whole SPREAD, and is the page fully in frame?

The owner's target for a two-page photo is "the page near the centre that is fully shown",
not the spread union. `h36_sam.maybe_split_spread` already keeps the fully-visible half, but
it only fires when the mask's min-area-rect aspect is >= 1.85 AND a gutter dip is present.
On an angled / foreshortened spread neither condition may hold, and the oracle silently
becomes the whole spread - which would score a correct single-page detection as wrong.

This check needs no model: it reads the photo and the oracle quad and asks two questions.

  1. Does a dark gutter band run through the INSIDE of the oracle quad?
     If yes, the quad covers both leaves -> the oracle contradicts the owner's target.
  2. Does any quad corner sit on (or past) the frame edge?
     If yes, that page is truncated: no quad can place a corner that is not in the picture.

Output: tooling/out/h48/spread_check.json + a one-line-per-photo table on stdout.

Usage: python tooling\\h48_spread_check.py --set holdout42
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

ASSETS = Path("app/src/androidTest/assets")
OUT = Path("tooling/out/h48")

EDGE_TOL = 0.02      # corner within 2 % of the shorter frame side counts as on the edge
GUTTER_DIP = 0.80    # gutter luminance < 80 % of the page median = a real gutter


def order_quad(q: np.ndarray) -> np.ndarray:
    """TL, TR, BR, BL by centroid angle, rotated to start at min(x + y)."""
    c = q.mean(axis=0)
    ang = np.arctan2(q[:, 1] - c[1], q[:, 0] - c[0])
    q = q[np.argsort(ang)]
    s = q[:, 0] + q[:, 1]
    k = int(np.argmin(s))
    return np.roll(q, -k, axis=0)


def sample_profile(gray: np.ndarray, quad: np.ndarray, bins: int = 40) -> np.ndarray:
    """Median luminance per bin along the quad's long axis, sampled inside the quad."""
    tl, tr, br, bl = quad
    long_axis = 0 if np.linalg.norm(tr - tl) >= np.linalg.norm(bl - tl) else 1
    prof = []
    for b in range(bins):
        t = (b + 0.5) / bins
        if long_axis == 0:
            p0 = tl + (bl - tl) * t
            p1 = tr + (br - tr) * t
        else:
            p0 = tl + (tr - tl) * t
            p1 = bl + (br - bl) * t
        vals = []
        for s in np.linspace(0.06, 0.94, 24):
            x, y = p0 + (p1 - p0) * s
            xi, yi = int(round(x)), int(round(y))
            if 0 <= yi < gray.shape[0] and 0 <= xi < gray.shape[1]:
                vals.append(gray[yi, xi])
        prof.append(float(np.median(vals)) if vals else np.nan)
    return np.array(prof, dtype=np.float64)


def analyse_photo(img: np.ndarray, quad_px: dict) -> dict:
    h, w = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).astype(np.float64)
    quad = order_quad(np.array([quad_px[c] for c in ("tl", "tr", "br", "bl")], np.float64))

    pts = quad.astype(np.float32)
    (cx, cy), (rw, rh), _ = cv2.minAreaRect(pts)
    long_e, short_e = max(rw, rh), max(1e-6, min(rw, rh))
    aspect = long_e / short_e

    prof = sample_profile(gray, quad)
    ok = ~np.isnan(prof)
    med = float(np.nanmedian(prof))
    # gutter = dip strictly inside the quad, not at its own edges
    lo, hi = int(0.25 * len(prof)), int(0.75 * len(prof))
    window = prof[lo:hi]
    k = lo + int(np.nanargmin(window)) if not np.all(np.isnan(window)) else lo
    dip = float(prof[k]) if not np.isnan(prof[k]) else med
    gutter = bool(med > 0 and dip < GUTTER_DIP * med)

    tol = EDGE_TOL * min(w, h)
    edge = []
    for name, (x, y) in zip(("tl", "tr", "br", "bl"), quad):
        d = min(x, y, w - 1 - x, h - 1 - y)
        if d <= tol:
            edge.append(name)

    return {
        "width": w, "height": h,
        "aspect": round(float(aspect), 3),
        "quad": [[round(float(x), 1), round(float(y), 1)] for x, y in quad],
        "gutter_inside": gutter,
        "gutter_depth": round(dip / med, 3) if med else None,
        "edge_corners": edge,
        "samples_valid": int(ok.sum()),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", default="holdout42")
    args = ap.parse_args()

    root = ASSETS / args.set
    oracle = json.loads((root / "oracle_manifest.json").read_text(encoding="utf-8"))
    rows = []
    print(f"{'photo':<22}{'aspect':>7}{'gutter':>8}{'depth':>7}  edge corners")
    for item in oracle["items"]:
        img = cv2.imread(str(root / item["image"]), cv2.IMREAD_COLOR)
        if img is None:
            continue
        a = analyse_photo(img, item["corners_px"])
        a["image"] = item["image"]
        rows.append(a)
        print(f"{item['image'][:-4]:<22}{a['aspect']:>7.2f}"
              f"{('YES' if a['gutter_inside'] else '-'):>8}"
              f"{a['gutter_depth']:>7.2f}  {','.join(a['edge_corners']) or '-'}")

    spread = [r for r in rows if r["gutter_inside"]]
    trunc = [r for r in rows if r["edge_corners"]]
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "spread_check.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")

    print(f"\n{len(rows)} photos")
    print(f"{len(spread)} oracle quads still cover BOTH leaves (gutter inside) -> "
          f"contradict the one-fully-shown-page target")
    for r in spread:
        print(f"  {r['image']} (aspect {r['aspect']:.2f}, gutter {r['gutter_depth']:.2f})")
    print(f"{len(trunc)} photos have a corner on the frame edge -> page truncated")
    for r in trunc:
        print(f"  {r['image']}: {','.join(r['edge_corners'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
