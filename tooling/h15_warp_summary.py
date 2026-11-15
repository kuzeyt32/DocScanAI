#!/usr/bin/env python3
"""
h15_warp_summary.py - aggregate the SUBTASK H15 adaptive-warp evidence.

Reads the collected corpus artifacts (no device access):

  <run>/corpus_out/ai_on/images/<id>/docscan.txt   (per-image DocScan logcat)
  <run>/corpus_out/ai_on/corpus_report_ai_on.json
  <run>/corpus_out/ai_off/corpus_report_ai_off.json
  <run>/logcat_ai_on.txt

and prints / writes:

  * residual distribution (mean / max / p95 / curvature) per group:
      benchmark      - 20 DocUNet photographs
      synthetic      - controlled renders
      rectified_skip - pages the F2 gate refused to grid-dewarp (no residual by design)
  * the chosen strength distribution (0.0 / partial / 1.0)
  * the guardrail metrics needed for the before/after table
  * the AI-ON / AI-OFF sharpness ratio, warp latency and native-heap high-water

Usage:
  py -3 tooling/h15_warp_summary.py tooling/out/h15_warp/corpus
"""
import json
import os
import re
import statistics
import sys

RESIDUAL_RE = re.compile(
    r"\[Warp\] residual mean=([0-9.]+) max=([0-9.]+) p95=([0-9.]+) curv=([0-9.]+)")
STRENGTH_RE = re.compile(
    r"\[Warp\] strength=([0-9.]+)(?: \(flat\))? mean=([0-9.]+) max=([0-9.]+)")
HEAP_RE = re.compile(r"native=([0-9.]+)MB")


def classify(name, text):
    if name.startswith("bench_"):
        return "benchmark"
    if "stage2 skipped (already rectified" in text:
        return "rectified_skip"
    if name.startswith("syn_rectified_full") or name.startswith("syn_light_brightness") \
            or name.startswith("syn_light_vignette"):
        return "rectified_skip"
    return "synthetic"


def parse_images(run):
    root = os.path.join(run, "corpus_out", "ai_on", "images")
    rows = []
    if not os.path.isdir(root):
        return rows
    for name in sorted(os.listdir(root)):
        p = os.path.join(root, name, "docscan.txt")
        if not os.path.isfile(p):
            continue
        text = open(p, "r", encoding="utf-8", errors="replace").read()
        res = RESIDUAL_RE.findall(text)
        st = STRENGTH_RE.findall(text)
        row = {
            "name": name,
            "group": classify(name, text),
            "grid_ran": "stage2 grid dewarp applied" in text,
            "residual": None,
            "strength": None,
            "clamped": "clamp vectors=" in text,
        }
        if res:
            m = res[-1]
            row["residual"] = {
                "mean": float(m[0]), "max": float(m[1]),
                "p95": float(m[2]), "curv": float(m[3]),
            }
        if st:
            m = st[-1]
            row["strength"] = float(m[0])
        elif "[Warp] strength=0.00 (flat)" in text:
            row["strength"] = 0.0
        rows.append(row)
    return rows


def group_stats(rows):
    out = {}
    for g in ("benchmark", "synthetic", "rectified_skip"):
        rs = [r for r in rows if r["group"] == g]
        with_res = [r for r in rs if r["residual"]]
        entry = {
            "pages": len(rs),
            "grid_runs": len(with_res),
            "strength_zero": sum(1 for r in with_res if r["strength"] == 0.0),
            "strength_full": sum(1 for r in with_res if r["strength"] == 1.0),
            "strength_partial": sum(1 for r in with_res
                                    if r["strength"] is not None and 0.0 < r["strength"] < 1.0),
            "clamped_pages": sum(1 for r in with_res if r["clamped"]),
        }
        for key in ("mean", "max", "p95", "curv"):
            vals = [r["residual"][key] for r in with_res]
            entry[key] = {
                "avg": round(statistics.fmean(vals), 5) if vals else None,
                "max": round(max(vals), 5) if vals else None,
                "min": round(min(vals), 5) if vals else None,
            }
        out[g] = entry
    return out


def load_json(path):
    try:
        with open(path, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except Exception:
        return None


def record_mean(rep, key):
    if not rep:
        return None
    vals = [im.get(key) for im in rep.get("images", [])
            if isinstance(im.get(key), (int, float)) and im.get(key) >= 0]
    return round(statistics.fmean(vals), 4) if vals else None


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    run = argv[1]
    rows = parse_images(run)
    groups = group_stats(rows)

    on = load_json(os.path.join(run, "corpus_out", "ai_on", "corpus_report_ai_on.json"))
    off = load_json(os.path.join(run, "corpus_out", "ai_off", "corpus_report_ai_off.json"))

    def s(rep, key):
        return rep.get("summary", {}).get(key) if rep else None

    sharp_on = s(on, "mean_out_sharpness")
    sharp_off = s(off, "mean_out_sharpness")
    ratio = round(sharp_on / sharp_off, 4) if sharp_on and sharp_off else None

    negatives = []
    if on:
        negatives = [im for im in on.get("images", []) if im.get("kind") == "negative"]
    neg_fp = sum(1 for im in negatives if im.get("detected_found"))

    heap = None
    lc = os.path.join(run, "logcat_ai_on.txt")
    if os.path.isfile(lc):
        vals = [float(v) for v in HEAP_RE.findall(
            open(lc, "r", encoding="utf-8", errors="replace").read())]
        heap = max(vals) if vals else None

    summary = {
        "run": run,
        "residual_groups": groups,
        "strength_distribution_all": {
            "grid_runs": sum(1 for r in rows if r["residual"]),
            "zero": sum(1 for r in rows if r["residual"] and r["strength"] == 0.0),
            "full": sum(1 for r in rows if r["residual"] and r["strength"] == 1.0),
            "partial": sum(1 for r in rows if r["residual"]
                           and r["strength"] is not None and 0.0 < r["strength"] < 1.0),
        },
        "guardrails": {
            "benchmark_detected": s(on, "group_benchmark_detected"),
            "benchmark_count": s(on, "group_benchmark_count"),
            "mean_corner_err_pct_diag": s(on, "mean_corner_err_pct_diag"),
            "placement_inside": s(on, "quad_placement_inside"),
            "placement_outside": s(on, "quad_placement_outside"),
            "placement_mixed": s(on, "quad_placement_mixed"),
            "distorted_suspect": s(on, "distorted_suspect"),
            "negatives": len(negatives),
            "negatives_false_positive": neg_fp,
            "warp_ms_mean": record_mean(on, "warp_ms"),
            "native_heap_max_mb": heap,
            "mean_gt_psnr": s(on, "mean_gt_psnr"),
            "mean_gt_ssim": s(on, "mean_gt_ssim"),
            "mean_out_sharpness_on": sharp_on,
            "mean_out_sharpness_off": sharp_off,
            "sharpness_ratio_on_off": ratio,
            "ai_off_benchmark_detected": s(off, "group_benchmark_detected"),
            "ai_off_benchmark_count": s(off, "group_benchmark_count"),
        },
    }

    out = os.path.join(run, "warp_summary.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(summary, fh, indent=2)
        fh.write("\n")

    print(json.dumps(summary, indent=2))
    print("\nwrote", out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
