#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H36 — owner-110 report: table + failure-class counts, EXACT H35/H34 definitions.

Consumes the evidence JSON produced by Owner110RealPathTest
(tooling/out/h36/<Tag>/owner110_realpath.json, pulled by tooling/h36_lane.cmd) and the H34
oracle pseudo-GT (tooling/out/h35/pseudo_gt_asset.json — the oracle did not change).

Scoring definitions are kept EXACTLY those of tooling/h34_measure.py / h35_report.py so the
numbers are directly comparable to the H35 baseline (91/111 detected, 27 wrong-page, 34 merged):
  iou            IoU(our quad, oracle quad)
  corner_pct     mean corner error, % of frame diag, 8 orderings
  wrong_page     IoU < 0.35 AND our quad frame-touches while the oracle quad does not
  merged         covers_oracle >= 0.80 AND area_ratio >= 1.50

SUBTASK H36 adds one analysis section: the M-LSD decision (`mlsd_log`) and the classical
candidate ranking (`cue_log`) per row, so the merged/wrong-page cause is visible per photo.

Outputs (tooling/out/h36/): report_<Tag>.json + report_<Tag>.md

Usage:
  python tooling\\h36_report.py --tag baseline
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
import sys
sys.path.insert(0, HERE)

import h30_score as S  # noqa: E402  (iou_of / align_measure / polygon area)

OUT = os.path.join(HERE, "out", "h36")
GT_PATH = os.path.join(HERE, "out", "h35", "pseudo_gt_asset.json")

WRONG_PAGE_IOU = 0.35
MERGED_COVER = 0.80
MERGED_AREA_RATIO = 1.50
CORNER_PEAK_MIN = 0.30


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


def parse_mlsd(text):
    """`segments=… reason=… trigger=…` -> dict; '-' / absent -> {}."""
    out = {}
    for kv in (text or "").split():
        if "=" in kv:
            k, v = kv.split("=", 1)
            out[k] = v
    return out


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

    gt_by_name = {}
    if os.path.isfile(GT_PATH):
        with open(GT_PATH, "r", encoding="utf-8") as fh:
            for it in json.load(fh).get("items", []):
                gt_by_name[it["image"]] = it

    rows = []
    min_peaks = []
    for r in ev.get("rows", []):
        quad = quad_list(r.get("quad"))
        gt = gt_by_name.get(r["image"], {})
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
            "mlsd_log": r.get("mlsd_log", ""),
            "cue_log": r.get("cue_log", ""),
            "mlsd_reason": parse_mlsd(r.get("mlsd_log", "")).get("reason", "-"),
            "mlsd_trigger": parse_mlsd(r.get("mlsd_log", "")).get("trigger", "-"),
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

    merged_rows = [r for r in rows if r["merged"]]
    wrong_rows = [r for r in rows if r["wrong_page"]]

    def reasons_of(group):
        out = {}
        for r in group:
            out[r["mlsd_reason"]] = out.get(r["mlsd_reason"], 0) + 1
        return out

    summary = {
        "tag": args.tag,
        "images": len(rows),
        "detected": len(det),
        "detected_corner_model": sum(1 for r in det if r["prov"] == "CORNER_MODEL"),
        "detected_classical": sum(1 for r in det if r["prov"] == "CLASSICAL_FALLBACK"),
        "gate_rejected": sum(1 for r in det if not r["gate_pass"]),
        "no_detection": sum(1 for r in rows if not r["detected"]),
        "wrong_page": len(wrong_rows),
        "merged": len(merged_rows),
        "mlsd_invoked": sum(1 for r in rows if r["mlsd_invoked"]),
        "mean_iou": round(sum(ious) / len(ious), 4) if ious else None,
        "mean_corner_pct": round(sum(corners) / len(corners), 4) if corners else None,
        "merged_reasons": reasons_of(merged_rows),
        "wrong_page_reasons": reasons_of(wrong_rows),
        "category_counts": {},
    }
    cats = {}
    for r in rows:
        cats[r["category"]] = cats.get(r["category"], 0) + 1
    summary["category_counts"] = cats

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
    lines.append("# H36 — REAL in-app detector path on the owner photos (tag=%s)" % args.tag)
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
    lines.append("")
    lines.append("H35 baseline: detected 91/111, wrong-page 27, merged 34, no-detection 20.")
    lines.append("")
    lines.append("## M-LSD decision on the failure classes")
    lines.append("")
    lines.append("merged (%d): %s" % (len(merged_rows), summary["merged_reasons"]))
    lines.append("wrong-page (%d): %s" % (len(wrong_rows), summary["wrong_page_reasons"]))
    lines.append("")
    lines.append("## Merged rows")
    lines.append("")
    lines.append("| image | iou | area_ratio | prov | mlsd_invoked | mlsd reason |")
    lines.append("|-------|-----|-----------|------|--------------|-------------|")
    for r in sorted(merged_rows, key=lambda r: -(r["area_ratio"] or 0)):
        lines.append("| %s | %s | %.2f | %s | %s | %s |" % (
            r["image"], f(r["iou"], 3), r["area_ratio"], r["prov"],
            "Y" if r["mlsd_invoked"] else "n", r["mlsd_reason"]))
    lines.append("")
    lines.append("## Wrong-page rows")
    lines.append("")
    lines.append("| image | iou | area_ratio | prov | mlsd_invoked | mlsd reason |")
    lines.append("|-------|-----|-----------|------|--------------|-------------|")
    for r in sorted(wrong_rows, key=lambda r: -(r["area_ratio"] or 0)):
        lines.append("| %s | %s | %.2f | %s | %s | %s |" % (
            r["image"], f(r["iou"], 3), r["area_ratio"], r["prov"],
            "Y" if r["mlsd_invoked"] else "n", r["mlsd_reason"]))
    lines.append("")
    lines.append("## Worst 10 (detected, lowest IoU; oracle-noisy => lower bounds)")
    lines.append("")
    lines.append("| # | image | iou | corner%% | prov | category |")
    lines.append("|---|-------|-----|---------|------|----------|")
    for i, r in enumerate(worst, 1):
        lines.append("| %d | %s | %s | %s | %s | %s |" % (
            i, r["image"], f(r["iou"], 3), f(r["corner_pct"], 1),
            r["prov"], r["category"]))
    lines.append("")

    out_md = os.path.join(OUT, "report_%s.md" % args.tag)
    with open(out_md, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    hb("report_%s.md written (detected=%d wrong=%d merged=%d)" % (
        args.tag, summary["detected"], summary["wrong_page"], summary["merged"]))
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
