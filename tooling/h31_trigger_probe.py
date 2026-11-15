#!/usr/bin/env python3
"""
SUBTASK H31 — offline design of the M-LSD trigger, from the H30 measurement.

H30 measured the quality win of M-LSD merge repair (merged slivers 7/12 -> 2/12 on device)
and its cost (+73.68 ms p50 on a preview frame).  H31 does not re-litigate the win; it has to
stop paying for M-LSD on frames where it cannot help.  The trigger therefore has to answer one
question per frame, from data that is already on hand:

    "Does this quad look like it absorbed the facing page?"

The answer must be computable from the *corner-model quad alone* (a few float comparisons), before
any M-LSD work, so that a clean page costs zero.

This script does not run the model.  It reads the H30 offline artifacts —

  tooling/out/h30/scores.json           corner quads + per-variant scores for all 18 assets
  app/src/androidTest/assets/bookspread/book_spread_manifest.json   canvas sizes

— and asks, for a grid of candidate thresholds, what the trigger would have decided and what the
18-asset aggregate would therefore have been (M-LSD path where it fires, 0.3.8 corner path where it
does not).  The device remains the authority; this only chooses the constants so that the
instrumented run has something defensible to measure.

Outputs (both under tooling/out/h31/):
  trigger_probe.md   human table: separability + every candidate policy, with its prediction
  trigger_probe.json machine-readable policy results (consumed by the SUMMARY)

Usage:  python tooling/h31_trigger_probe.py [--scores PATH] [--manifest PATH] [--out DIR]
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_SCORES = os.path.join(ROOT, "tooling", "out", "h30", "scores.json")
DEFAULT_MANIFEST = os.path.join(
    ROOT, "app", "src", "androidTest", "assets", "bookspread", "book_spread_manifest.json"
)
DEFAULT_OUT = os.path.join(ROOT, "tooling", "out", "h31")

# ---------------------------------------------------------------------------------------------
# Threshold grid.  The chosen values are reported by the policy table; the Kotlin side pins the
# winner (MlsdTrigger.Policy.DEFAULT) and the instrumentation test asserts the device agrees.
# ---------------------------------------------------------------------------------------------
BORDER_MARGIN_GRID = [0.01, 0.02, 0.03, 0.04, 0.05, 0.06, 0.08, 0.10, 0.12]
AREA_FRAC_GRID = [0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80]

# The variant each policy falls back to / upgrades to.  merge_320 is "corner quad + M-LSD merge
# repair", i.e. exactly what the detector does in MlsdMode.REPAIR.
FAST = "corner"
MLSD = "merge_320"


def polygon_area(pts):
    s = 0.0
    n = len(pts)
    for i in range(n):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % n]
        s += x1 * y2 - x2 * y1
    return abs(s) / 2.0


def border_margin(quad, width, height):
    """
    Distance from the closest quad corner to the nearest frame border, as a fraction of the
    frame's SHORT side.  0 = the corner sits on the border (the merged-spread failure signature:
    the corner model ran the bottom/top edge into the frame), 0.5 = dead centre.
    """
    short = float(min(width, height))
    if short <= 0:
        return 0.0
    best = float("inf")
    for x, y in quad:
        d = min(x, y, width - x, height - y)
        if d < best:
            best = d
    return max(0.0, best) / short


def load(scores_path, manifest_path):
    with open(scores_path, "r", encoding="utf-8") as f:
        scores = json.load(f)
    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest = json.load(f)

    dims = {}
    for item in manifest["items"]:
        name = item["name"]
        for canvas in ("still", "preview"):
            shot = item[canvas]
            dims[(name, canvas)] = (shot["width"], shot["height"])

    rows = []
    for asset in scores["per_asset"]:
        key = (asset["name"], asset["canvas"])
        width, height = dims[key]
        quad = asset["corner"]
        fast = asset["scores"][FAST]
        mlsd = asset["scores"][MLSD]
        merge_info = asset.get("merge_info", {}).get(MLSD, {})
        rows.append(
            {
                "name": asset["name"],
                "canvas": asset["canvas"],
                "variant": asset["variant"],
                "sliver_fraction": asset["sliver_fraction"],
                "width": width,
                "height": height,
                "quad": quad,
                # trigger features — all free, pure geometry
                "border_margin": border_margin(quad, width, height),
                "area_frac": fast["area_frac"],
                # outcomes
                "fast_corner_pct": fast["corner_pct"],
                "fast_iou": fast["iou"],
                "fast_merged": bool(fast["merged"]),
                "fast_detected": bool(fast["detected"]),
                "mlsd_corner_pct": mlsd["corner_pct"],
                "mlsd_iou": mlsd["iou"],
                "mlsd_merged": bool(mlsd["merged"]),
                "mlsd_detected": bool(mlsd["detected"]),
                "mlsd_applied": bool(merge_info.get("applied", 0)),
                "mlsd_reason": merge_info.get("reason", "-"),
                "is_sliver": asset["sliver_fraction"] > 0.0,
            }
        )
    return rows


def predict(rows, border_max, area_min):
    """
    Applies one policy: M-LSD runs when border_margin <= border_max OR area_frac >= area_min.
    (None disables a term.)  Returns the aggregate the 18 assets would score.
    """
    used = []
    detected = 0
    merged = 0
    sliver_total = 0
    corners = []
    ious = []
    for r in rows:
        hit_border = border_max is not None and r["border_margin"] <= border_max
        hit_area = area_min is not None and r["area_frac"] >= area_min
        fired = hit_border or hit_area
        pick = r if fired else r
        prefix = "mlsd_" if fired else "fast_"
        if fired:
            used.append(r)
        if pick[prefix + "detected"]:
            detected += 1
            corners.append(pick[prefix + "corner_pct"])
            ious.append(pick[prefix + "iou"])
        if r["is_sliver"]:
            sliver_total += 1
            if pick[prefix + "merged"]:
                merged += 1
    return {
        "border_max": border_max,
        "area_min": area_min,
        "invocations": len(used),
        "invocation_assets": ["%s/%s" % (r["name"], r["canvas"]) for r in used],
        "detected": detected,
        "merged": merged,
        "sliver_total": sliver_total,
        "mean_corner_pct": sum(corners) / len(corners) if corners else float("nan"),
        "mean_iou": sum(ious) / len(ious) if ious else float("nan"),
        "unnecessary": [
            "%s/%s" % (r["name"], r["canvas"])
            for r in used
            if abs(r["mlsd_corner_pct"] - r["fast_corner_pct"]) < 1e-9
            and r["mlsd_merged"] == r["fast_merged"]
        ],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scores", default=DEFAULT_SCORES)
    ap.add_argument("--manifest", default=DEFAULT_MANIFEST)
    ap.add_argument("--out", default=DEFAULT_OUT)
    args = ap.parse_args()

    rows = load(args.scores, args.manifest)
    os.makedirs(args.out, exist_ok=True)

    # Reference points: 0.3.8 fast path only, and "M-LSD on everything" (the H30 BOTH mode).
    ref_fast = predict(rows, None, None)
    ref_all = predict(rows, 1.0, 0.0)

    lines = []
    lines.append("# H31 — M-LSD trigger probe (offline, from the H30 measurement)")
    lines.append("")
    lines.append("Source: `tooling/out/h30/scores.json` (corner quads + variant scores, 18 assets).")
    lines.append("Model: M-LSD 320 merge repair (`merge_320`) vs the 0.3.8 corner path (`corner`).")
    lines.append("")
    lines.append("## Per-asset trigger features and both outcomes")
    lines.append("")
    lines.append(
        "| asset | canvas | sliver | border margin | area frac | corner % | merged | "
        "merge % | merged | rule |"
    )
    lines.append("|---|---|---|---|---|---|---|---|---|---|")
    for r in sorted(rows, key=lambda r: (r["name"], r["canvas"])):
        lines.append(
            "| %s | %s | %.2f | %.4f | %.4f | %.4f | %s | %.4f | %s | %s |"
            % (
                r["name"],
                r["canvas"],
                r["sliver_fraction"],
                r["border_margin"],
                r["area_frac"],
                r["fast_corner_pct"],
                "YES" if r["fast_merged"] else "-",
                r["mlsd_corner_pct"],
                "YES" if r["mlsd_merged"] else "-",
                r["mlsd_reason"],
            )
        )

    merged_rows = [r for r in rows if r["fast_merged"]]
    clean_rows = [r for r in rows if not r["fast_merged"]]
    lines.append("")
    lines.append("## Separability of the two cheap features")
    lines.append("")
    lines.append(
        "| population | n | border margin min..max | area frac min..max |"
    )
    lines.append("|---|---|---|---|")
    for label, pop in (("merged (needs M-LSD)", merged_rows), ("not merged (must not pay)", clean_rows)):
        bm = [r["border_margin"] for r in pop]
        af = [r["area_frac"] for r in pop]
        lines.append(
            "| %s | %d | %.4f .. %.4f | %.4f .. %.4f |"
            % (label, len(pop), min(bm), max(bm), min(af), max(af))
        )

    def grid_row(pred, note=""):
        return (
            "| %s | %s | %d/18 | %d/%d | %.4f | %.4f | %s |"
            % (
                "-" if pred["border_max"] is None else "%.2f" % pred["border_max"],
                "-" if pred["area_min"] is None else "%.2f" % pred["area_min"],
                pred["invocations"],
                pred["merged"],
                pred["sliver_total"],
                pred["mean_corner_pct"],
                pred["mean_iou"],
                note,
            )
        )

    lines.append("")
    lines.append("## Every candidate policy (predicted on the 18 assets)")
    lines.append("")
    lines.append("| border<= | area>= | M-LSD runs | merged slivers | mean corner % | mean IoU | note |")
    lines.append("|---|---|---|---|---|---|---|")
    lines.append(grid_row(ref_fast, "reference: 0.3.8 fast path only (H30 OFF)"))
    lines.append(grid_row(ref_all, "reference: M-LSD on everything (H30 BOTH)"))
    for bm in BORDER_MARGIN_GRID:
        for af in AREA_FRAC_GRID:
            p = predict(rows, bm, af)
            note = ""
            if p["merged"] <= 2:
                note = "meets merged<=2"
            lines.append(grid_row(p, note))
        lines.append(grid_row(predict(rows, bm, None), "border only"))
    for af in AREA_FRAC_GRID:
        lines.append(grid_row(predict(rows, None, af), "area only"))

    # The winner: fewest invocations that still meets merged <= 2 and mean corner <= 3.5672.
    best = None
    for bm in BORDER_MARGIN_GRID:
        for af in AREA_FRAC_GRID:
            p = predict(rows, bm, af)
            if p["merged"] > 2:
                continue
            if p["mean_corner_pct"] > 3.5672:
                continue
            if p["detected"] < 18:
                continue
            key = (p["invocations"], p["mean_corner_pct"])
            if best is None or key < best[0]:
                best = (key, p)
    if best is None:
        for bm in BORDER_MARGIN_GRID:
            p = predict(rows, bm, None)
            if p["merged"] <= 2 and (best is None or p["invocations"] < best[1]["invocations"]):
                best = ((p["invocations"], p["mean_corner_pct"]), p)

    lines.append("")
    lines.append("## Chosen policy")
    lines.append("")
    if best is None:
        lines.append("**No candidate policy meets the acceptance floors offline.**")
        chosen = None
    else:
        p = best[1]
        chosen = p
        lines.append(
            "`border_margin <= %s` OR `area_frac >= %s` -> M-LSD runs on **%d of 18** assets, "
            "predicted merged **%d/%d**, mean corner **%.4f %%**, mean IoU **%.4f**."
            % (
                "-" if p["border_max"] is None else "%.2f" % p["border_max"],
                "-" if p["area_min"] is None else "%.2f" % p["area_min"],
                p["invocations"],
                p["merged"],
                p["sliver_total"],
                p["mean_corner_pct"],
                p["mean_iou"],
            )
        )
        lines.append("")
        lines.append("Assets that pay: " + ", ".join(p["invocation_assets"]))
        lines.append("")
        if p["unnecessary"]:
            lines.append(
                "Assets that pay without changing the answer (the cost of the trigger being "
                "generous): " + ", ".join(p["unnecessary"])
            )
        else:
            lines.append("Every invocation changes the quad: the trigger pays only where it acts.")

    with open(os.path.join(args.out, "trigger_probe.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    payload = {
        "fast_only": ref_fast,
        "mlsd_everything": ref_all,
        "chosen": chosen,
        "assets": rows,
    }
    with open(os.path.join(args.out, "trigger_probe.json"), "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)

    print("\n".join(lines[-14:]))
    if chosen is None:
        print("TRIGGER_PROBE FAIL: no policy met the floors", file=sys.stderr)
        return 1
    print(
        "TRIGGER_PROBE OK border<=%s area>=%s invocations=%d merged=%d/%d corner=%.4f iou=%.4f"
        % (
            chosen["border_max"],
            chosen["area_min"],
            chosen["invocations"],
            chosen["merged"],
            chosen["sliver_total"],
            chosen["mean_corner_pct"],
            chosen["mean_iou"],
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
