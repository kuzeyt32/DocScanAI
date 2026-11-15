#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H38 — gate + report over the owner110 lane evidence (EXACT H35/H36/H37 definitions).

Consumes tooling/out/h38/<Tag>/owner110_realpath.json (pulled by tooling/h38_lane.cmd) and
tooling/out/h35/pseudo_gt_asset.json (oracle manifest incl. the 5 new 20261025_* photos,
whose GT corners come from the H36 SAM oracle via tooling/h38_assets.py).

Scoring definitions kept EXACTLY tooling/h36_report.py so numbers are comparable:
  iou            IoU(our quad, oracle quad)
  corner_pct     mean corner error, % of frame diag, 8 orderings
  wrong_page     IoU < 0.35 AND our quad frame-touches while the oracle quad does not
  merged         covers_oracle >= 0.80 AND area_ratio >= 1.50

H38 GATE (the subtask contract):
  1. all 5 new photos (20261025_*) detected (correct-page or merged OK; wrong-page NOT);
  2. aggregate detected >= 85, wrong-page <= 20, merged <= 25.

Outputs: tooling/out/h38/report_<Tag>.json + report_<Tag>.md; exit 0 only when the gate passes.

Usage:
  python tooling\\h38_gate.py --tag h38ship
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import h30_score as S  # noqa: E402  (iou_of / align_measure / polygon area)

OUT = os.path.join(HERE, "out", "h38")
GT_PATH = os.path.join(HERE, "out", "h35", "pseudo_gt_asset.json")

WRONG_PAGE_IOU = 0.35
MERGED_COVER = 0.80
MERGED_AREA_RATIO = 1.50

NEW_PREFIX = "20261025_"
GATE_DET_MIN = 85
GATE_WRONG_MAX = 20
GATE_MERGED_MAX = 25


def hb(msg):
    os.makedirs(OUT, exist_ok=True)
    line = "[%s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg)
    with open(os.path.join(OUT, "HEARTBEAT.txt"), "a", encoding="utf-8") as fh:
        fh.write(line + "\n")
    print(line, flush=True)


def quad_list(q):
    if not q:
        return None
    return [[float(p[0]), float(p[1])] for p in q["pts"]]


def f(v, n=4):
    return "-" if v is None or (isinstance(v, float) and math.isnan(v)) else ("%%.%df" % n) % v


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
    for r in ev.get("rows", []):
        quad = quad_list(r.get("quad"))
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
        correct_page = bool(r["detected"] and not wrong_page and not merged)

        rows.append({
            "image": r["image"],
            "is_new": r["image"].startswith(NEW_PREFIX),
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
            "correct_page": correct_page,
            "luma_mean": r.get("luma_mean"),
            "luma_std": r.get("luma_std"),
            "ms": r.get("ms"),
            "mlsd_invoked": bool(r.get("mlsd_invoked")),
            "mlsd_log": r.get("mlsd_log", ""),
            "cue_log": r.get("cue_log", ""),
            "corner_log": r.get("corner_log", ""),
            "gate_log": r.get("gate_log", ""),
            "peaks": r.get("peaks"),
        })

    det = [r for r in rows if r["detected"]]
    merged_rows = [r for r in rows if r["merged"]]
    wrong_rows = [r for r in rows if r["wrong_page"]]
    new_rows = [r for r in rows if r["is_new"]]
    new_detected = [r for r in new_rows if r["detected"]]
    new_wrong = [r for r in new_rows if r["wrong_page"]]

    summary = {
        "tag": args.tag,
        "images": len(rows),
        "detected": len(det),
        "detected_corner_model": sum(1 for r in det if r["prov"] == "CORNER_MODEL"),
        "detected_classical": sum(1 for r in det if r["prov"] == "CLASSICAL_FALLBACK"),
        "no_detection": sum(1 for r in rows if not r["detected"]),
        "wrong_page": len(wrong_rows),
        "merged": len(merged_rows),
        "new_total": len(new_rows),
        "new_detected": len(new_detected),
        "new_wrong_page": len(new_wrong),
        "new_merged": sum(1 for r in new_rows if r["merged"]),
        "new_correct_page": sum(1 for r in new_rows if r["correct_page"]),
    }

    # --- the H38 gate ------------------------------------------------------------------
    gate_new_ok = len(new_rows) == 5 and len(new_detected) == 5 and len(new_wrong) == 0
    gate_agg = (len(det) >= GATE_DET_MIN and len(wrong_rows) <= GATE_WRONG_MAX
                and len(merged_rows) <= GATE_MERGED_MAX)
    gate = {
        "new_all_detected_not_wrong": gate_new_ok,
        "new_requirement": "5/5 detected (correct-page or merged OK, wrong-page NOT)",
        "new_result": "%d/5 detected, %d wrong-page, %d merged" % (
            len(new_detected), len(new_wrong), summary["new_merged"]),
        "aggregate_ok": gate_agg,
        "aggregate_requirement": "detected >= %d, wrong-page <= %d, merged <= %d" % (
            GATE_DET_MIN, GATE_WRONG_MAX, GATE_MERGED_MAX),
        "aggregate_result": "detected %d, wrong-page %d, merged %d" % (
            len(det), len(wrong_rows), len(merged_rows)),
    }
    passed = bool(gate_new_ok and gate_agg)
    gate["passed"] = passed

    report = {"summary": summary, "gate": gate, "rows": rows}
    out_json = os.path.join(OUT, "report_%s.json" % args.tag)
    with open(out_json, "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=1)

    lines = []
    lines.append("# H38 — owner photos + 5 new struggle photos (tag=%s)" % args.tag)
    lines.append("")
    lines.append("| metric                     | value                                              |")
    lines.append("|----------------------------|----------------------------------------------------|")
    lines.append("| images                     | %-50s |" % summary["images"])
    lines.append("| detected (real pipeline)   | %-50s |" % summary["detected"])
    lines.append("|   CORNER_MODEL             | %-50s |" % summary["detected_corner_model"])
    lines.append("|   CLASSICAL_FALLBACK       | %-50s |" % summary["detected_classical"])
    lines.append("| no-detection               | %-50s |" % summary["no_detection"])
    lines.append("| wrong-page                 | %-50s |" % summary["wrong_page"])
    lines.append("| merged                     | %-50s |" % summary["merged"])
    lines.append("| new-5 detected             | %-50s |" % gate["new_result"])
    lines.append("| new-5 correct-page         | %-50s |" % summary["new_correct_page"])
    lines.append("")
    lines.append("## H38 gate: %s" % ("PASS" if passed else "FAIL"))
    lines.append("")
    lines.append("- new-5: %s -> %s" % (gate["new_requirement"], gate["new_result"]))
    lines.append("- aggregate: %s -> %s" % (gate["aggregate_requirement"], gate["aggregate_result"]))
    lines.append("")
    lines.append("## The 5 new photos")
    lines.append("")
    lines.append("| image | detected | class | iou | corner%% | prov | mlsd reason |")
    lines.append("|-------|----------|-------|-----|---------|------|-------------|")
    for r in new_rows:
        cls = ("correct-page" if r["correct_page"] else
               ("merged" if r["merged"] else ("wrong-page" if r["wrong_page"] else "NO-Detection")))
        reason = (r.get("mlsd_log", "") or "-").split("reason=")[-1].split()[0] if "reason=" in (r.get("mlsd_log") or "") else "-"
        lines.append("| %s | %s | %s | %s | %s | %s | %s |" % (
            r["image"], "Y" if r["detected"] else "n", cls,
            f(r["iou"], 3), f(r["corner_pct"], 1), r["prov"] or "-", reason))
    lines.append("")
    lines.append("## Wrong-page rows (%d)" % len(wrong_rows))
    lines.append("")
    lines.append("| image | iou | area_ratio | prov |")
    lines.append("|-------|-----|-----------|------|")
    for r in sorted(wrong_rows, key=lambda r: -(r["area_ratio"] or 0)):
        lines.append("| %s | %s | %.2f | %s |" % (
            r["image"], f(r["iou"], 3), r["area_ratio"], r["prov"]))
    lines.append("")
    lines.append("## Merged rows (%d)" % len(merged_rows))
    lines.append("")
    lines.append("| image | iou | area_ratio | prov |")
    lines.append("|-------|-----|-----------|------|")
    for r in sorted(merged_rows, key=lambda r: -(r["area_ratio"] or 0)):
        lines.append("| %s | %s | %.2f | %s |" % (
            r["image"], f(r["iou"], 3), r["area_ratio"], r["prov"]))

    out_md = os.path.join(OUT, "report_%s.md" % args.tag)
    with open(out_md, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")

    hb("h38_gate tag=%s GATE=%s (new %s; aggregate %s)" % (
        args.tag, "PASS" if passed else "FAIL", gate["new_result"], gate["aggregate_result"]))
    print("\n".join(lines))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
