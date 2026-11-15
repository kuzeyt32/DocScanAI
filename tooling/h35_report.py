#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H35 — real-path owner-110 report: table + peak histogram + worst-10 categories.

Consumes the evidence JSON produced by Owner110RealPathTest
(Android/data/com.docscanai/files/h35_owner110/owner110_realpath.json, pulled by
tooling/h35_lane.cmd into tooling/out/h35/<Tag>/owner110_realpath.json) and the H34
oracle pseudo-GT (via the bundled asset-space copy in tooling/out/h35/pseudo_gt_asset.json).

Scoring definitions are kept EXACTLY those of tooling/h34_measure.py so the numbers are
directly comparable to H34's NN-only mirror (10/111 detected):
  iou            IoU(our quad, oracle quad)                     [h30_score.iou_of]
  corner_pct     mean corner error, % of frame diag, 8 orderings [h30_score.align_measure]
  wrong_page     IoU < 0.35 AND our quad frame-touches while the oracle quad does not
  merged         covers_oracle >= 0.80 AND area_ratio >= 1.50
  categories     heuristics identical to h34_measure.classify

Outputs (tooling/out/h35/):
  report_<Tag>.json    all rows + summary + peak histogram + worst10
  report_<Tag>.md      the human table (the one quoted in docs/RESULTS.md)

Usage:
  python tooling\\h35_report.py --tag baseline
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import h30_score as S  # noqa: E402  (iou_of / align_measure / polygon area)

OUT = os.path.join(HERE, "out", "h35")

WRONG_PAGE_IOU = 0.35        # h32/h34 constant, reused verbatim
MERGED_COVER = 0.80          # ours must contain the oracle page ...
MERGED_AREA_RATIO = 1.50     # ... and extend well beyond it (both pages)
CORNER_PEAK_MIN = 0.30       # the shipped NN gate (OpenCvQuadPageDetector)


def hb(msg):
    os.makedirs(OUT, exist_ok=True)
    line = "[%s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg)
    with open(os.path.join(OUT, "HEARTBEAT.txt"), "a", encoding="utf-8") as fh:
        fh.write(line + "\n")
    print(line, flush=True)


def classify(row):
    if not row["detected"]:
        return "other"
    if row["merged"]:
        return "second-page-showing"
    if row["wrong_page"]:
        return "table-edge-coincidence" if row.get("area_ratio", 1.0) >= 0.7 else "second-page-showing"
    if row["luma_mean"] < 60.0 or row["luma_std"] > 70.0:
        return "lighting"
    return "other"


def quad_list(q):
    if not q:
        return None
    return [[float(p[0]), float(p[1])] for p in q["pts"]]


def peak_hist(min_peaks):
    """Bucket counts of min(per-image peak vector) around the 0.30 gate."""
    edges = [0.0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.50, 0.70, 1.01]
    counts = [0] * (len(edges) - 1)
    for v in min_peaks:
        for i in range(len(edges) - 1):
            if edges[i] <= v < edges[i + 1]:
                counts[i] += 1
                break
    return {
        "buckets": [
            {"lo": edges[i], "hi": edges[i + 1], "count": counts[i]}
            for i in range(len(counts))
        ],
        "below_gate": sum(1 for v in min_peaks if v < CORNER_PEAK_MIN),
        "at_or_above_gate": sum(1 for v in min_peaks if v >= CORNER_PEAK_MIN),
        "min": round(min(min_peaks), 4) if min_peaks else None,
        "max": round(max(min_peaks), 4) if min_peaks else None,
        "mean": round(sum(min_peaks) / len(min_peaks), 4) if min_peaks else None,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="baseline")
    args = ap.parse_args()

    run_dir = os.path.join(OUT, args.tag)
    ev_path = os.path.join(run_dir, "owner110_realpath.json")
    if not os.path.isfile(ev_path):
        hb("ERROR missing %s" % ev_path)
        return 2
    with open(ev_path, "r", encoding="utf-8") as fh:
        ev = json.load(fh)

    gt_path = os.path.join(OUT, "pseudo_gt_asset.json")
    gt_by_name = {}
    if os.path.isfile(gt_path):
        with open(gt_path, "r", encoding="utf-8") as fh:
            for it in json.load(fh).get("items", []):
                gt_by_name[it["image"]] = it

    rows = []
    min_peaks = []
    for r in ev.get("rows", []):
        w, h = int(r["width"]), int(r["height"])
        quad = quad_list(r.get("quad"))
        gt = gt_by_name.get(r["image"], {})
        gt_quad = None
        if gt.get("corners_px"):
            gt_quad = [[gt["corners_px"][k][0], gt["corners_px"][k][1]]
                       for k in ("tl", "tr", "br", "bl")]

        iou = r.get("iou")
        corner_pct = r.get("corner_pct")
        covers = float(r.get("covers_oracle") or 0.0)
        area_ratio = float(r.get("area_ratio") or 0.0)
        our_touch = bool(r.get("our_frame_touch"))
        oracle_touch = bool(r.get("oracle_frame_touch"))

        wrong_page = bool(
            r["detected"] and iou is not None and not math.isnan(iou)
            and iou < WRONG_PAGE_IOU and our_touch and not oracle_touch
        )
        merged = bool(
            r["detected"] and covers >= MERGED_COVER and area_ratio >= MERGED_AREA_RATIO
        )

        peaks = r.get("peaks")
        peak_min = min(peaks) if peaks else None
        if peak_min is not None:
            min_peaks.append(float(peak_min))

        row = {
            "image": r["image"],
            "detected": bool(r["detected"]),
            "gate_pass": bool(r.get("gate_pass")),
            "prov": r.get("prov"),
            "iou": iou,
            "corner_pct": corner_pct,
            "covers_oracle": covers,
            "area_ratio": area_ratio,
            "our_frame_touch": our_touch,
            "oracle_frame_touch": oracle_touch,
            "wrong_page": wrong_page,
            "merged": merged,
            "luma_mean": r.get("luma_mean"),
            "luma_std": r.get("luma_std"),
            "ms": r.get("ms"),
            "mlsd_invoked": bool(r.get("mlsd_invoked")),
            "peaks": peaks,
            "peak_min": peak_min,
            "peak_gate_miss": bool(peak_min is not None and peak_min < CORNER_PEAK_MIN),
            "corner_log": r.get("corner_log", ""),
        }
        row["category"] = classify(row)
        rows.append(row)

    det = [r for r in rows if r["detected"]]
    ious = [r["iou"] for r in det if r["iou"] is not None and not math.isnan(r["iou"])]
    corners = [r["corner_pct"] for r in det
               if r["corner_pct"] is not None and not math.isnan(r["corner_pct"])]

    summary = {
        "tag": args.tag,
        "images": len(rows),
        "detected": len(det),
        "detected_corner_model": sum(1 for r in det if r["prov"] == "CORNER_MODEL"),
        "detected_classical": sum(1 for r in det if r["prov"] == "CLASSICAL_FALLBACK"),
        "gate_rejected": sum(1 for r in det if not r["gate_pass"]),
        "no_detection": sum(1 for r in rows if not r["detected"]),
        "wrong_page": sum(1 for r in rows if r["wrong_page"]),
        "merged": sum(1 for r in rows if r["merged"]),
        "mlsd_invoked": sum(1 for r in rows if r["mlsd_invoked"]),
        "mean_iou": round(sum(ious) / len(ious), 4) if ious else None,
        "mean_corner_pct": round(sum(corners) / len(corners), 4) if corners else None,
        "peak_hist": peak_hist(min_peaks),
        "category_counts": {},
    }
    cats = {}
    for r in rows:
        cats[r["category"]] = cats.get(r["category"], 0) + 1
    summary["category_counts"] = cats

    # Worst 10 among DETECTED by lowest IoU (the H34 convention), with the heuristic category.
    worst = sorted(
        [r for r in det if r["iou"] is not None and not math.isnan(r["iou"])],
        key=lambda r: r["iou"],
    )[:10]

    report = {"summary": summary, "worst10": worst, "rows": rows}
    out_json = os.path.join(OUT, "report_%s.json" % args.tag)
    with open(out_json, "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=1)

    def f(v, n=4):
        return "-" if v is None or (isinstance(v, float) and math.isnan(v)) else ("%%.%df" % n) % v

    lines = []
    lines.append("# H35 — REAL in-app detector path on the owner photos (tag=%s)" % args.tag)
    lines.append("")
    lines.append("| metric                     | value                                              |")
    lines.append("|----------------------------|----------------------------------------------------|")
    lines.append("| images                     | %-50s |" % summary["images"])
    lines.append("| detected (real pipeline)   | %-50s |" % summary["detected"])
    lines.append("|   CORNER_MODEL             | %-50s |" % summary["detected_corner_model"])
    lines.append("|   CLASSICAL_FALLBACK       | %-50s |" % summary["detected_classical"])
    lines.append("| gate-rejected (detected)   | %-50s |" % summary["gate_rejected"])
    lines.append("| no-detection               | %-50s |" % summary["no_detection"])
    lines.append("| wrong-page                 | %-50s |" % summary["wrong_page"])
    lines.append("| merged                     | %-50s |" % summary["merged"])
    lines.append("| M-LSD invoked              | %-50s |" % summary["mlsd_invoked"])
    lines.append("| mean IoU (detected)        | %-50s |" % f(summary["mean_iou"]))
    lines.append("| mean corner %% diag         | %-50s |" % f(summary["mean_corner_pct"]))
    lines.append("| probe512 (graph 512 input) | %-50s |" % ev.get("probe512", "-"))
    lines.append("")
    lines.append("H34 NN-only mirror for comparison: detected 10/111, wrong-page 1, merged 0, "
                 "no-detection 101.")
    lines.append("")
    lines.append("## Peak distribution (min of the 4 corner peaks per photo; gate = %.2f)"
                 % CORNER_PEAK_MIN)
    lines.append("")
    lines.append("| bucket      | count |")
    lines.append("|-------------|-------|")
    for b in summary["peak_hist"]["buckets"]:
        lines.append("| %.2f-%.2f  | %-5s |" % (b["lo"], b["hi"], b["count"]))
    lines.append("")
    lines.append("below gate: %d, at/above gate: %d, min=%s mean=%s max=%s" % (
        summary["peak_hist"]["below_gate"],
        summary["peak_hist"]["at_or_above_gate"],
        f(summary["peak_hist"]["min"]), f(summary["peak_hist"]["mean"]),
        f(summary["peak_hist"]["max"])))
    lines.append("")
    lines.append("## Worst 10 (detected, lowest IoU; oracle-noisy => lower bounds)")
    lines.append("")
    lines.append("| # | image | iou | corner%% | prov | category |")
    lines.append("|---|-------|-----|---------|------|----------|")
    for i, r in enumerate(worst, 1):
        lines.append("| %d | %s | %s | %s | %s | %s |" % (
            i, r["image"], f(r["iou"], 3), f(r["corner_pct"], 2), r["prov"], r["category"]))
    lines.append("")
    lines.append("category counts: %s" % summary["category_counts"])
    out_md = os.path.join(OUT, "report_%s.md" % args.tag)
    with open(out_md, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")

    hb("report tag=%s detected=%d/%d (NN=%d classical=%d) wrong=%d merged=%d none=%d"
       % (args.tag, summary["detected"], summary["images"],
          summary["detected_corner_model"], summary["detected_classical"],
          summary["wrong_page"], summary["merged"], summary["no_detection"]))
    hb("peak histogram: below_gate=%d at_or_above=%d"
       % (summary["peak_hist"]["below_gate"], summary["peak_hist"]["at_or_above_gate"]))
    hb("wrote %s and %s" % (out_json, out_md))
    return 0


if __name__ == "__main__":
    sys.exit(main())
