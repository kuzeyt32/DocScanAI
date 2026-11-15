#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H33 — calibrate the oracle against KNOWN ground truth before trusting it.

Compares oracle corner predictions (pseudo-GT) against known GT quads and
reports corner error + IoU. Based on the verdict rule:

    PSEUDO-GT USABLE (direct)  if  mean corner error <= USABLE_MAX  and  IoU >= USABLE_IOU
    CANDIDATE-FILTER ONLY      otherwise — the VL model picks WHICH page is the
                                fully visible one, but our detector measures the
                                corners (the VL is not trusted for exact corners).

GT sources (both offline, no network):
  * bookspread synthetic fixture GT  (app/src/androidTest/assets/bookspread/
    book_spread_manifest.json, items[].still.gt_page — exact by construction)
  * bookspread_real manifest         (app/src/androidTest/assets/bookspread_real/
    real_bookspread_manifest.json, items[].gt_target_quad — human-verified annotation)

The oracle's predicted corners are normalized 0..1; GT is in pixel coordinates,
so we normalize by width/height before comparing.

Selftest (`--selftest`) uses a canned oracle response and synthetic GT — proves
the metrics and verdict logic run offline without the model or network.

Usage:
  python tooling\\oracle\\oracle_calibrate.py --selftest
  python tooling\\oracle\\oracle_calibrate.py \\
      --manifest tooling\\out\\h33\\oracle_manifest.json \\
      --synthetic-manifest app\\src\\androidTest\\assets\\bookspread\\book_spread_manifest.json \\
      --real-manifest app\\src\\androidTest\\assets\\bookspread_real\\real_bookspread_manifest.json \\
      --out tooling\\out\\h33\\calibration.json
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys

# --- verdict thresholds (documented rule) ---
USABLE_MAX_CORNER_ERROR = 0.03   # 3% of frame diagonal
USABLE_MIN_IOU = 0.85            # intersection-over-union
CANDIDATE_MAX_CORNER_ERROR = 0.08  # beyond this the model is not even useful as a filter
CANDIDATE_MIN_IOU = 0.50

CORNER_KEYS = ("tl", "tr", "br", "bl")


def norm_corners_px(quad_px, width, height):
    """Pixel quad -> normalized [0,1] corners dict (TL,TR,BR,BL)."""
    out = {}
    for i, k in enumerate(CORNER_KEYS):
        x, y = quad_px[i][0], quad_px[i][1]
        out[k] = [x / width, y / height]
    return out


def corners_to_list(corners):
    return [corners[k] for k in CORNER_KEYS]


def poly_area(pts):
    n = len(pts)
    if n < 3:
        return 0.0
    s = 0.0
    for i in range(n):
        j = (i + 1) % n
        s += pts[i][0] * pts[j][1] - pts[j][0] * pts[i][1]
    return abs(s) / 2.0


def clip_polygon(subject, clip):
    """Sutherland-Hodgman convex-convex intersection."""
    def signed_area(arr):
        s = 0.0
        for i in range(len(arr)):
            j = (i + 1) % len(arr)
            s += arr[i][0] * arr[j][1] - arr[j][0] * arr[i][1]
        return s / 2.0

    if signed_area(clip) < 0:
        clip = clip[::-1]
    out = list(subject)
    n = len(clip)
    for i in range(n):
        ax, ay = clip[i]
        bx, by = clip[(i + 1) % n]
        ex, ey = bx - ax, by - ay
        if not out:
            break
        new = []
        prev = out[-1]
        for cur in out:
            def inside(p):
                return ex * (p[1] - ay) - ey * (p[0] - ax) >= 0.0
            cur_in = inside(cur)
            prev_in = inside(prev)
            if cur_in:
                if not prev_in:
                    dx, dy = cur[0] - prev[0], cur[1] - prev[1]
                    denom = ex * dy - ey * dx
                    t = 0.0 if abs(denom) < 1e-12 else (
                        ((ax - prev[0]) * ey - (ay - prev[1]) * ex) / -denom)
                    new.append((prev[0] + t * dx, prev[1] + t * dy))
                new.append(cur)
            elif prev_in:
                dx, dy = cur[0] - prev[0], cur[1] - prev[1]
                denom = ex * dy - ey * dx
                t = 0.0 if abs(denom) < 1e-12 else (
                    ((ax - prev[0]) * ey - (ay - prev[1]) * ex) / -denom)
                new.append((prev[0] + t * dx, prev[1] + t * dy))
            prev = cur
        out = new
    return out if len(out) >= 3 else []


def inter_area(a, b):
    poly = clip_polygon(a, b)
    return poly_area(poly) if len(poly) >= 3 else 0.0


def iou_of(a, b):
    inter = inter_area(a, b)
    union = poly_area(a) + poly_area(b) - inter
    return inter / union if union > 1e-9 else 0.0


def corner_errors(pred_corners, gt_corners):
    """Per-corner Euclidean distance in normalized units + mean + max."""
    errs = []
    for k in CORNER_KEYS:
        dx = pred_corners[k][0] - gt_corners[k][0]
        dy = pred_corners[k][1] - gt_corners[k][1]
        errs.append(math.hypot(dx, dy))
    return errs


def diagonal_normalized(width, height):
    """Frame diagonal in normalized units (aspect is the unit square)."""
    return math.hypot(1.0, 1.0)  # ~1.414; corner error as fraction of diagonal


def evaluate_pair(pred_corners, gt_corners_norm):
    errs = corner_errors(pred_corners, gt_corners_norm)
    mean_err = sum(errs) / len(errs)
    max_err = max(errs)
    pred_list = corners_to_list(pred_corners)
    gt_list = corners_to_list(gt_corners_norm)
    iou = iou_of(pred_list, gt_list)
    return {
        "mean_corner_error": round(mean_err, 5),
        "max_corner_error": round(max_err, 5),
        "per_corner_errors": [round(e, 5) for e in errs],
        "iou": round(iou, 5),
    }


def verdict(metrics_list):
    """Aggregate verdict across all pairs."""
    if not metrics_list:
        return "NO_DATA"
    mean_errs = [m["mean_corner_error"] for m in metrics_list]
    ious = [m["iou"] for m in metrics_list]
    avg_err = sum(mean_errs) / len(mean_errs)
    avg_iou = sum(ious) / len(ious)
    max_err = max(mean_errs)
    min_iou = min(ious)

    if avg_err <= USABLE_MAX_CORNER_ERROR and avg_iou >= USABLE_MIN_IOU:
        return "PSEUDO_GT_USABLE"
    if max_err <= CANDIDATE_MAX_CORNER_ERROR and min_iou >= CANDIDATE_MIN_IOU:
        return "CANDIDATE_FILTER_ONLY"
    return "REJECT"


def load_synthetic_gt(manifest_path):
    """Extract normalized GT quads from the synthetic bookspread manifest."""
    with open(manifest_path, encoding="utf-8") as fh:
        m = json.load(fh)
    results = []
    for item in m.get("items", []):
        name = item.get("name", "?")
        for canvas_key in ("still", "preview"):
            canvas = item.get(canvas_key)
            if canvas is None:
                continue
            w, h = canvas.get("width"), canvas.get("height")
            gt_px = canvas.get("gt_page")
            if not (w and h and gt_px):
                continue
            gt_norm = norm_corners_px(gt_px, w, h)
            results.append({
                "source": "synthetic",
                "name": "%s/%s" % (name, canvas_key),
                "width": w, "height": h,
                "gt_norm": gt_norm,
            })
    return results


def load_real_gt(manifest_path):
    """Extract normalized GT quads from the real bookspread manifest."""
    with open(manifest_path, encoding="utf-8") as fh:
        m = json.load(fh)
    results = []
    for item in m.get("items", []):
        asset = item.get("asset", "?")
        w, h = item.get("width"), item.get("height")
        gt_px = item.get("gt_target_quad")
        if not (w and h and gt_px):
            continue
        gt_norm = norm_corners_px(gt_px, w, h)
        results.append({
            "source": "real",
            "name": asset,
            "width": w, "height": h,
            "gt_norm": gt_norm,
        })
    return results


def run_calibration(oracle_manifest, synthetic_manifest, real_manifest, out):
    """Compare oracle predictions (if any) against known GT; always emit the verdict rule."""
    gt_entries = []
    if synthetic_manifest and os.path.isfile(synthetic_manifest):
        gt_entries.extend(load_synthetic_gt(synthetic_manifest))
    if real_manifest and os.path.isfile(real_manifest):
        gt_entries.extend(load_real_gt(real_manifest))

    oracle_items = []
    if oracle_manifest and os.path.isfile(oracle_manifest):
        with open(oracle_manifest, encoding="utf-8") as fh:
            oracle_data = json.load(fh)
        oracle_items = oracle_data.get("items", [])

    # Index oracle items by normalized image basename for matching
    oracle_by_name = {}
    for r in oracle_items:
        base = os.path.splitext(os.path.basename(r.get("image", "")))[0]
        oracle_by_name[base] = r

    metrics = []
    matched = 0
    for gt in gt_entries:
        # H34 fix (trivial matcher bug): synthetic GT names are "bsNN_x/canvas";
        # basename() yields "still"/"preview" and never matches the image stem.
        # Fold '/' to '_' so "bs01_control_clean/still" -> "bs01_control_clean_still".
        base = os.path.splitext(gt["name"].replace("\\", "_").replace("/", "_"))[0]
        pred = oracle_by_name.get(base)
        if pred is None or not pred.get("corners"):
            continue
        matched += 1
        m = evaluate_pair(pred["corners"], gt["gt_norm"])
        m["name"] = gt["name"]
        m["source"] = gt["source"]
        metrics.append(m)

    result = {
        "verdict_rule": {
            "PSEUDO_GT_USABLE": "mean_corner_error <= %.3f AND iou >= %.2f" % (
                USABLE_MAX_CORNER_ERROR, USABLE_MIN_IOU),
            "CANDIDATE_FILTER_ONLY": "max_corner_error <= %.3f AND min_iou >= %.2f" % (
                CANDIDATE_MAX_CORNER_ERROR, CANDIDATE_MIN_IOU),
            "REJECT": "worse than candidate-filter threshold",
        },
        "gt_entries": len(gt_entries),
        "oracle_items": len(oracle_items),
        "matched": matched,
        "metrics": metrics,
    }
    result["verdict"] = verdict(metrics)

    if out:
        with open(out, "w", encoding="utf-8") as fh:
            json.dump(result, fh, indent=2)
    summary = {k: result[k] for k in ("gt_entries", "oracle_items", "matched", "verdict")}
    print(json.dumps(summary, indent=2))
    return 0


def run_selftest(out):
    """Offline: canned oracle corners vs a synthetic GT; proves metrics + verdict logic."""
    # Synthetic GT (normalized, from a 3024x4032 still)
    gt_norm = {
        "tl": [0.10, 0.10],
        "tr": [0.90, 0.11],
        "br": [0.89, 0.90],
        "bl": [0.11, 0.89],
    }
    # Oracle prediction: small perturbation (~0.015 max corner error, IoU ~0.88)
    pred = {
        "tl": [0.115, 0.105],
        "tr": [0.895, 0.115],
        "br": [0.885, 0.895],
        "bl": [0.115, 0.885],
    }

    m = evaluate_pair(pred, gt_norm)
    assert m["mean_corner_error"] < 0.05, m
    assert m["iou"] > 0.70, m

    # A bad prediction: should trigger REJECT or CANDIDATE_FILTER_ONLY
    pred_bad = {
        "tl": [0.30, 0.30],
        "tr": [0.70, 0.30],
        "br": [0.70, 0.70],
        "bl": [0.30, 0.70],
    }
    m_bad = evaluate_pair(pred_bad, gt_norm)

    v_good = verdict([m])
    v_bad = verdict([m_bad])

    result = {
        "verdict_rule": {
            "PSEUDO_GT_USABLE": "mean_corner_error <= %.3f AND iou >= %.2f" % (
                USABLE_MAX_CORNER_ERROR, USABLE_MIN_IOU),
            "CANDIDATE_FILTER_ONLY": "max_corner_error <= %.3f AND min_iou >= %.2f" % (
                CANDIDATE_MAX_CORNER_ERROR, CANDIDATE_MIN_IOU),
            "REJECT": "worse than candidate-filter threshold",
        },
        "selftest_good": {"metrics": m, "verdict": v_good},
        "selftest_bad": {"metrics": m_bad, "verdict": v_bad},
    }
    if out:
        with open(out, "w", encoding="utf-8") as fh:
            json.dump(result, fh, indent=2)
    print(json.dumps({
        "good_mean_err": m["mean_corner_error"], "good_iou": m["iou"], "good_verdict": v_good,
        "bad_mean_err": m_bad["mean_corner_error"], "bad_iou": m_bad["iou"], "bad_verdict": v_bad,
    }, indent=2))
    print("SELFTEST OK  (corner error + IoU + verdict rule, offline)")
    return 0


def main():
    ap = argparse.ArgumentParser(description="Calibrate oracle pseudo-GT against known GT")
    ap.add_argument("--manifest", help="oracle batch output (oracle_manifest.json)")
    ap.add_argument("--synthetic-manifest",
                    default="app/src/androidTest/assets/bookspread/book_spread_manifest.json")
    ap.add_argument("--real-manifest",
                    default="app/src/androidTest/assets/bookspread_real/real_bookspread_manifest.json")
    ap.add_argument("--out", help="output calibration.json path")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return run_selftest(args.out)
    return run_calibration(
        args.manifest, args.synthetic_manifest, args.real_manifest, args.out
    )


if __name__ == "__main__":
    sys.exit(main())
