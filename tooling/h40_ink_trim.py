#!/usr/bin/env python3
"""
H40 — INK TRIM: can the INK map itself shrink a merged quad back onto the page?

Motivation (measured in `h40_quad_ink_eval.py`): the INK *score* cannot separate a correct quad
from a merged one, because any textured region scores ~0.85-0.95. But the INK *spatial
distribution* is still informative: on a merged quad (page + desk) the page is a dense ink block
sitting inside a mostly empty outer band. This probe asks whether trimming the quad to the ink
mass, with a conservative guard, recovers the page without hurting correct quads.

Method, per photo (offline, oracle quads available only for scoring):

  1. rectify the DETECTED quad generously (long edge 480, 4 % out-of-quad bleed so a too-small
     quad is not clipped);
  2. build the gate's INK map on the interior;
  3. row/column ink profiles -> the text-mass bbox (threshold = fraction of the profile median
     + an absolute floor);
  4. only trim a side when the band being removed is wide (>= MIN_CUT of that side) AND carries
     almost no ink (<= MAX_BAND_INK) — otherwise keep the original edge;
  5. map the trimmed box back through the inverse homography, score IoU against the oracle quad.

Nothing here touches the app; it is a measurement of whether an ink-based trim is worth porting.

Usage:
    python tooling/h40_ink_trim.py <owner110_realpath.json>
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from h40_ink_probe import ASSET_DIR, ORACLE, ink_baseline  # noqa: E402

BLEED = 0.04          # fraction of the quad expanded outward before rectification
RECT_LONG = 480
MIN_CUT = 0.12        # a side is only trimmed if >= 12 % of it is removed
MAX_BAND_INK = 0.020  # ...and the removed band is <= 2 % ink
CANDIDATE_CUTS = (0.12, 0.18, 0.25)


def quad_iou(a, b) -> float:
    """IoU of two convex quads (both 4x2 float arrays)."""
    a = np.asarray(a, np.float32)
    b = np.asarray(b, np.float32)
    inter, _ = cv2.intersectConvexConvex(a, b)
    if inter <= 0:
        return 0.0
    area_a = abs(cv2.contourArea(a))
    area_b = abs(cv2.contourArea(b))
    union = area_a + area_b - inter
    return float(inter / union) if union > 0 else 0.0


def spread_quad(quad: np.ndarray, bleed: float) -> np.ndarray:
    c = quad.mean(axis=0)
    return c + (quad - c) * (1.0 + bleed)


def rect_matrix(box_quad: np.ndarray, w: int, h: int):
    dst = np.array([[0, 0], [w - 1, 0], [w - 1, h - 1], [0, h - 1]], np.float32)
    return cv2.getPerspectiveTransform(box_quad.astype(np.float32), dst)


def trim_quad(img: np.ndarray, quad: np.ndarray, cut: float = MIN_CUT,
              max_band_ink: float = MAX_BAND_INK, bleed: float = BLEED) -> np.ndarray:
    """Return a possibly-trimmed quad in source coordinates."""
    outer = spread_quad(quad, bleed)
    w = h = RECT_LONG
    m = rect_matrix(outer, w, h)
    rect = cv2.warpPerspective(img, m, (w, h), flags=cv2.INTER_LINEAR,
                               borderMode=cv2.BORDER_REPLICATE)
    gray = cv2.cvtColor(rect, cv2.COLOR_BGR2GRAY)
    ink = ink_baseline(gray)
    cols = (ink > 0).mean(axis=0)
    rows = (ink > 0).mean(axis=1)

    def bounds(profile: np.ndarray) -> tuple[int, int]:
        n = len(profile)
        lo, hi = 0, n - 1
        limit = int(round(n * cut))
        # left
        acc = 0.0
        for i in range(limit):
            acc += profile[i]
            if acc / (i + 1) > max_band_ink * 0.5 or profile[i] > max_band_ink:
                break
        else:
            i = limit
        lo = i
        acc = 0.0
        for j in range(n - 1, n - 1 - limit, -1):
            acc += profile[j]
            if acc / (n - j) > max_band_ink * 0.5 or profile[j] > max_band_ink:
                break
        else:
            j = n - 1 - limit
        hi = j
        if hi - lo < n * 0.4:      # trimming ate the page: reject the trim entirely
            return 0, n - 1
        return lo, hi

    x0, x1 = bounds(cols)
    y0, y1 = bounds(rows)
    if (x0, x1, y0, y1) == (0, w - 1, 0, h - 1):
        return quad
    box = np.array([[x0, y0], [x1, y0], [x1, y1], [x0, y1]], np.float64)
    inv = np.linalg.inv(m)
    pts = cv2.perspectiveTransform(box.reshape(-1, 1, 2), inv).reshape(-1, 2)
    return pts


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    src = Path(argv[1]).resolve()
    data = json.loads(src.read_text(encoding="utf-8"))
    labels_path = src.parent.parent / "report_h38fixd.json"
    labels = {}
    if labels_path.exists():
        for r in json.loads(labels_path.read_text(encoding="utf-8"))["rows"]:
            labels[r["image"]] = r
    oracle = {i["image"]: np.array([i["corners_px"][k] for k in ("tl", "tr", "br", "bl")], np.float64)
              for i in json.loads(ORACLE.read_text(encoding="utf-8"))["items"]}

    out = src.parent / "h40_ink_trim"
    out.mkdir(parents=True, exist_ok=True)

    rows = []
    for r in data["rows"]:
        quad = (r.get("quad") or {}).get("pts")
        if not quad or r["image"] not in oracle:
            continue
        img = cv2.imread(str(ASSET_DIR / r["image"]), cv2.IMREAD_COLOR)
        if img is None:
            continue
        q = np.array(quad, np.float64)
        o = oracle[r["image"]]
        entry = {"image": r["image"], "prov": r.get("prov"), "iou0": quad_iou(q, o)}
        lab = labels.get(r["image"], {})
        entry["bad"] = bool(lab.get("merged") or lab.get("wrong_page"))
        for cut in CANDIDATE_CUTS:
            t = trim_quad(img, q, cut=cut)
            entry[f"iou_{cut}"] = quad_iou(t, o)
            entry[f"trimmed_{cut}"] = bool(not np.allclose(t, q, atol=1.0))
        rows.append(entry)

    (out / "trim.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")

    lines = ["# H40 — ink trim sweep", "",
             "`iou0` = the shipped quad vs oracle. Each column = the same quad after the ink trim",
             "at that minimum-cut fraction. `touched` = how many quads the guard actually changed.",
             "",
             "| cut | mean IoU (all) | mean IoU (bad) | bad improved | bad worsened | good improved | good worsened | touched |",
             "|---|---|---|---|---|---|---|---|"]
    bad = [r for r in rows if r["bad"]]
    good = [r for r in rows if not r["bad"]]
    for cut in CANDIDATE_CUTS:
        key = f"iou_{cut}"
        imp_bad = sum(1 for r in bad if r[key] > r["iou0"] + 0.02)
        wor_bad = sum(1 for r in bad if r[key] < r["iou0"] - 0.02)
        imp_good = sum(1 for r in good if r[key] > r["iou0"] + 0.02)
        wor_good = sum(1 for r in good if r[key] < r["iou0"] - 0.02)
        touched = sum(1 for r in rows if r[f"trimmed_{cut}"])
        lines.append(
            f"| {cut:.2f} | {np.mean([r[key] for r in rows]):.3f} | "
            f"{np.mean([r[key] for r in bad]):.3f} | {imp_bad} | {wor_bad} | "
            f"{imp_good} | {wor_good} | {touched} |"
        )
    lines += ["", f"baseline mean IoU: all {np.mean([r['iou0'] for r in rows]):.3f}, "
                  f"bad {np.mean([r['iou0'] for r in bad]):.3f}, "
                  f"good {np.mean([r['iou0'] for r in good]):.3f}", ""]
    (out / "SUMMARY.md").write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines))
    print(f"-> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
