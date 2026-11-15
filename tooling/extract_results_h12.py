#!/usr/bin/env python3
"""H12 evidence extractor v2.

Builds every per-image table in docs/RESULTS.md directly from the corpus
report artifacts so no number is hand-typed. Source of truth:
  tooling/out/h11/corpus/corpus_out/corpus_report.json            (AI-ON report)
  .../ai_off/corpus_report_ai_off.json                            (AI-OFF report)

Outputs:
  tooling/out/h12_final/rows.json           - joined per-image rows
  tooling/out/h12_final/rows.csv            - same, flat
  tooling/out/h12_final/aggregates.json     - headline numbers
  tooling/out/h12_final/results_tables.md   - markdown tables for RESULTS.md
"""
import csv
import json
import os
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RUN = os.path.join(ROOT, "tooling", "out", "h11", "corpus", "corpus_out")
ON_JSON = os.path.join(RUN, "corpus_report.json")
OFF_JSON = os.path.join(RUN, "ai_off", "corpus_report_ai_off.json")
BASELINE_JSON = os.path.join(ROOT, "tooling", "out", "h4_final", "corpus_out", "corpus_report.json")
OUT_DIR = os.path.join(ROOT, "tooling", "out", "h12_final")
NA = "n/a"


def load(path):
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def jpeg_size(path):
    """Minimal baseline/progressive JPEG SOF parser (no external deps)."""
    try:
        with open(path, "rb") as fh:
            data = fh.read(200000)
    except OSError:
        return None
    if len(data) < 4 or data[0:2] != b"\xff\xd8":
        return None
    i = 2
    while i < len(data) - 9:
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
            h, w = struct.unpack(">HH", data[i + 5:i + 9])
            return w, h
        if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
            i += 2
            continue
        seg_len = struct.unpack(">H", data[i + 2:i + 4])[0]
        i += 2 + seg_len
    return None


def fmt(value, digits=3):
    if value is None:
        return NA
    if isinstance(value, bool):
        return "Y" if value else "N"
    if isinstance(value, (int,)) and not isinstance(value, bool):
        return str(value)
    if isinstance(value, float):
        return ("%." + str(digits) + "f") % value
    return str(value)


def pct(fraction):
    """Report corner error stored as a fraction of the diagonal -> percent.

    The harness writes -100 as a sentinel when no corner GT exists (or the
    image was not detected); those must not enter any average.
    """
    if fraction is None or fraction < 0:
        return None
    return round(fraction * 100.0, 2)


def pick(report, ai_mode):
    """Accept both file shapes: {"reports":[..]} and a single flat report."""
    reports = report.get("reports")
    if not reports:
        if report.get("images") is not None:
            return report
        raise SystemExit("report has neither 'reports' nor 'images'")
    for rep in reports:
        if str(rep.get("ai_mode", "")).upper() == ai_mode.upper():
            return rep
    # fall back to the single report if the mode label differs in case
    if len(reports) == 1:
        return reports[0]
    raise SystemExit("no report for ai_mode=%s (have %s)"
                     % (ai_mode, [r.get("ai_mode") for r in reports]))


def verdict(row):
    """Explicit rule used for the per-image verdict column."""
    if row["group"] == "negatives":
        return "PASS (correctly not a page)" if not row["detected"] else "POOR (false positive)"
    if not row["detected"]:
        # An already-rectified / full-frame page needs no quad: passing the
        # frame through is the correct outcome, not a miss.
        if row["full_frame_fallback"] and row["corner_err_pct"] is not None and row["corner_err_pct"] <= 1.0:
            return "GOOD (correct full-frame pass-through)"
        return "POOR (no detection -> fallback warp)"
    if row["placement"] == "OUTSIDE":
        return "POOR (quad outside GT)"
    if row["distorted_suspect"]:
        return "POOR (distorted_suspect)"
    err = row["corner_err_pct"]
    if err is not None and err > 5.0:
        return "POOR (corner error > 5%)"
    if err is not None and err > 2.0:
        return "ACCEPTABLE (corner error 2-5%)"
    if row["placement"] == "MIXED":
        return "ACCEPTABLE (mixed corner placement)"
    if row["sharp_on"] is not None and row["in_sharp"] is not None and row["sharp_on"] <= row["in_sharp"]:
        return "ACCEPTABLE (no sharpness gain)"
    return "GOOD"


def detection_source(row):
    if not row["detected"]:
        return "none"
    if row["warp_path"] == "neural":
        return "corner model (neural grid)"
    if row["warp_path"] == "classical":
        return "classical (OpenCV)"
    if row["warp_path"]:
        return row["warp_path"]
    return NA


def build():
    on = pick(load(ON_JSON), "ON")
    off = pick(load(OFF_JSON), "OFF")
    off_by_name = {i["name"]: i for i in off["images"]}

    rows = []
    for rec in on["images"]:
        name = rec["name"]
        dims = jpeg_size(os.path.join(RUN, "ai_on", "images", name, "input.jpg"))
        off_rec = off_by_name.get(name, {})
        sharp_on = rec.get("out_sharpness")
        sharp_off = off_rec.get("out_sharpness")
        ratio = round(sharp_on / sharp_off, 3) if (sharp_on and sharp_off) else None
        row = {
            "id": name,
            "group": rec.get("group", NA),
            "category": rec.get("category", NA),
            "src_w": dims[0] if dims else None,
            "src_h": dims[1] if dims else None,
            "detected": bool(rec.get("detected_found")),
            "full_frame_fallback": bool(rec.get("full_frame_fallback")),
            "near_full_frame": bool(rec.get("near_full_frame")),
            "warp_path": rec.get("warp_path"),
            "out_w": rec.get("out_w"),
            "out_h": rec.get("out_h"),
            "corner_err_pct": pct(rec.get("corner_err_pct_diag")),
            "has_corner_gt": bool(rec.get("has_corner_gt")),
            "placement": rec.get("quad_placement") or NA,
            "sharp_on": sharp_on,
            "sharp_off": sharp_off,
            "sharp_ratio_on_off": ratio,
            "in_sharp": rec.get("in_sharpness"),
            "sharp_gain_on": round(sharp_on / rec["in_sharpness"], 3) if (sharp_on and rec.get("in_sharpness")) else None,
            "gt_sharp": rec.get("gt_sharpness"),
            "sharp_ratio_gt": rec.get("sharp_ratio_gt"),
            "distorted_suspect": bool(rec.get("distorted_suspect")),
            "quad_area_frac": rec.get("quad_area_frac"),
            "detect_ms": rec.get("detect_ms"),
            "warp_ms": rec.get("warp_ms"),
            "total_ms": rec.get("total_ms"),
            "gt_psnr": rec.get("gt_psnr"),
            "gt_ssim": rec.get("gt_ssim"),
            "note": rec.get("note") or "",
        }
        row["det_source"] = detection_source(row)
        row["verdict"] = verdict(row)
        rows.append(row)
    return on, off, rows


def aggregates(on, off, rows):
    def grp(g):
        return [r for r in rows if r["group"] == g]

    def mean(seq, key):
        vals = [r[key] for r in seq if isinstance(r.get(key), (int, float))]
        return round(sum(vals) / len(vals), 3) if vals else None

    bench = grp("benchmark")
    synth = grp("synthetic")
    neg = grp("negatives")
    pos = [r for r in rows if r["group"] != "negatives"]
    errs = [r["corner_err_pct"] for r in pos if r["corner_err_pct"] is not None]
    s = on["summary"]
    so = off["summary"]
    return {
        "source_run": os.path.relpath(RUN, ROOT).replace("\\", "/"),
        "images_total": len(rows),
        "groups": {"benchmark": len(bench), "synthetic": len(synth), "negatives": len(neg)},
        "benchmark_detected": sum(1 for r in bench if r["detected"]),
        "synthetic_detected": sum(1 for r in synth if r["detected"]),
        "negatives_detected_fp": sum(1 for r in neg if r["detected"]),
        "positive_detected": sum(1 for r in pos if r["detected"]),
        "mean_corner_err_pct": round(sum(errs) / len(errs), 2) if errs else None,
        "corner_err_n": len(errs),
        "placement_inside": s.get("quad_placement_inside"),
        "placement_outside": s.get("quad_placement_outside"),
        "placement_mixed": s.get("quad_placement_mixed"),
        "distorted_suspect": s.get("distorted_suspect"),
        "warp_neural": s.get("warp_neural"),
        "warp_classical": s.get("warp_classical"),
        "warp_skipped_no_detection": s.get("warp_skipped_no_detection"),
        "mean_sharp_on": mean(rows, "sharp_on"),
        "mean_sharp_off": mean(rows, "sharp_off"),
        "mean_detect_ms": mean(rows, "detect_ms"),
        "mean_warp_ms": mean(rows, "warp_ms"),
        "mean_total_ms": mean(rows, "total_ms"),
        "median_total_ms": sorted([r["total_ms"] for r in rows if r["total_ms"]])[len(rows) // 2],
        "ai_on_sessions": s.get("sessions_created"),
        "ai_off_sessions": so.get("sessions_created"),
        "native_signal_on": s.get("native_signal"),
        "native_signal_off": so.get("native_signal"),
        "corpus_elapsed_ms_on": s.get("elapsed_ms"),
        "corpus_elapsed_ms_off": so.get("elapsed_ms"),
        "bench_psnr": s.get("group_benchmark_mean_psnr"),
        "bench_ssim": s.get("group_benchmark_mean_ssim"),
        "synth_psnr": s.get("group_synthetic_mean_psnr"),
        "synth_ssim": s.get("group_synthetic_mean_ssim"),
        "ai_off_bench_detected": so.get("group_benchmark_detected"),
        "ai_on_bench_detected": s.get("group_benchmark_detected"),
    }


def md_table(rows, compact=False):
    head = ("| id | detected | corner err % | placement | dewarp | AI-ON sharp | AI-OFF sharp | verdict |\n"
            "|---|---|---|---|---|---|---|---|\n")
    out = [head]
    for r in rows:
        out.append("| `%s` | %s | %s | %s | %s | %s | %s | %s |\n" % (
            r["id"],
            "Y" if r["detected"] else "N",
            (fmt(r["corner_err_pct"], 2) + "%") if r["corner_err_pct"] is not None else NA,
            r["placement"] if r["has_corner_gt"] else "no GT",
            r["warp_path"] or NA,
            fmt(r["sharp_on"], 0),
            fmt(r["sharp_off"], 0),
            r["verdict"],
        ))
    return "".join(out)


def worst10(rows):
    # A correct full-frame pass-through is not a quality failure, so it is
    # excluded from the worst-list ranking.
    pos = [r for r in rows
           if r["group"] != "negatives" and not r["verdict"].startswith("GOOD (correct full-frame")]
    neg = [r for r in rows if r["group"] == "negatives"]

    def score(r):
        # higher tuple == worse: genuine miss first, then large corner error,
        # then the smallest sharpness gain.
        err = r["corner_err_pct"] if r["corner_err_pct"] is not None else 100.0
        gain = r["sharp_gain_on"] if r["sharp_gain_on"] is not None else 0.0
        return (1 if r["detected"] else 2, err, -min(gain, 10.0))

    ranked = sorted(pos, key=score, reverse=True)[:10]
    fp = [r for r in neg if r["detected"]]
    return ranked, fp


def diagnosis(row):
    if not row["detected"]:
        return "detection: no quad found, fell back to full-frame warp"
    if row["placement"] == "OUTSIDE":
        return "detection: quad placed outside the GT page"
    if row["placement"] == "MIXED":
        return "detection: mixed corner placement (part in, part out)"
    err = row["corner_err_pct"]
    if err is not None and err > 5.0:
        return "detection: corner error %.2f%% of diagonal" % err
    if row["sharp_gain_on"] is not None and row["sharp_gain_on"] < 1.0:
        return "dewarp/source: output not sharper than input (gain %.2f)" % row["sharp_gain_on"]
    # gt_psnr == -1 is the harness sentinel for "no GT scan exists".
    if row["gt_psnr"] is not None and 0 <= row["gt_psnr"] < 10.0:
        return "source quality: low PSNR vs GT scan (%.2f dB)" % row["gt_psnr"]
    return "dewarp: measurable loss vs GT scan despite detection"


def run_metrics(on_path, off_path):
    """Same metric set for any historical run, used for the baseline column."""
    doc_on = load(on_path)
    on = pick(doc_on, "ON")
    try:
        off = pick(load(off_path), "OFF")
    except Exception:  # noqa: BLE001
        off = {"summary": {}, "images": []}

    imgs = on["images"]
    neg = [i for i in imgs if i.get("group") == "negatives"]
    pos = [i for i in imgs if i.get("group") != "negatives"]
    errs = [i["corner_err_pct_diag"] * 100.0 for i in pos
            if isinstance(i.get("corner_err_pct_diag"), (int, float)) and i["corner_err_pct_diag"] >= 0]
    on_sharp = [i["out_sharpness"] for i in imgs if isinstance(i.get("out_sharpness"), (int, float))]
    off_sharp = [i["out_sharpness"] for i in off.get("images", []) if isinstance(i.get("out_sharpness"), (int, float))]
    warp = [i["warp_ms"] for i in imgs if isinstance(i.get("warp_ms"), (int, float))]
    det = [i["detect_ms"] for i in imgs if isinstance(i.get("detect_ms"), (int, float))]
    s = on["summary"]
    return {
        "path": os.path.relpath(on_path, ROOT).replace("\\", "/"),
        "benchmark_detected": s.get("group_benchmark_detected"),
        "benchmark_count": s.get("group_benchmark_count"),
        "mean_corner_err_pct": round(sum(errs) / len(errs), 2) if errs else None,
        "corner_err_n": len(errs),
        "inside": s.get("quad_placement_inside"),
        "outside": s.get("quad_placement_outside"),
        "mixed": s.get("quad_placement_mixed"),
        "distorted_suspect": s.get("distorted_suspect"),
        "negative_fp": sum(1 for i in neg if i.get("detected_found")),
        "negative_count": len(neg),
        "mean_sharp_on": round(sum(on_sharp) / len(on_sharp), 1) if on_sharp else None,
        "mean_sharp_off": round(sum(off_sharp) / len(off_sharp), 1) if off_sharp else None,
        "mean_warp_ms": round(sum(warp) / len(warp), 1) if warp else None,
        "mean_detect_ms": round(sum(det) / len(det), 1) if det else None,
        "sessions_on": s.get("sessions_created"),
        "sessions_off": off.get("summary", {}).get("sessions_created"),
        "bench_psnr": s.get("group_benchmark_mean_psnr"),
        "bench_ssim": s.get("group_benchmark_mean_ssim"),
        "elapsed_ms": s.get("elapsed_ms"),
    }


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    on, off, rows = build()
    agg = aggregates(on, off, rows)
    base = run_metrics(BASELINE_JSON,
                       os.path.join(os.path.dirname(BASELINE_JSON), "ai_off", "corpus_report_ai_off.json"))
    with open(os.path.join(OUT_DIR, "baseline_h4.json"), "w", encoding="utf-8") as fh:
        json.dump(base, fh, indent=2)
    with open(os.path.join(OUT_DIR, "rows.json"), "w", encoding="utf-8") as fh:
        json.dump(rows, fh, indent=2)
    with open(os.path.join(OUT_DIR, "aggregates.json"), "w", encoding="utf-8") as fh:
        json.dump(agg, fh, indent=2)
    keys = ["id", "group", "category", "src_w", "src_h", "detected", "det_source", "corner_err_pct",
            "has_corner_gt", "placement", "warp_path", "out_w", "out_h", "sharp_on", "sharp_off",
            "sharp_ratio_on_off", "in_sharp", "sharp_gain_on", "gt_sharp", "sharp_ratio_gt",
            "distorted_suspect", "quad_area_frac", "detect_ms", "warp_ms", "total_ms",
            "gt_psnr", "gt_ssim", "note", "verdict"]
    with open(os.path.join(OUT_DIR, "rows.csv"), "w", newline="", encoding="utf-8") as fh:
        wr = csv.DictWriter(fh, fieldnames=keys, extrasaction="ignore")
        wr.writeheader()
        wr.writerows(rows)

    top, fp = worst10(rows)
    parts = []
    parts.append("### Benchmark\n\n" + md_table([r for r in rows if r["group"] == "benchmark"]))
    parts.append("\n### Synthetic\n\n" + md_table([r for r in rows if r["group"] == "synthetic"]))
    parts.append("\n### Negatives\n\n" + md_table([r for r in rows if r["group"] == "negatives"]))
    parts.append("\n### Worst 10\n\n| id | group | rank metric | diagnosis |\n|---|---|---|---|\n")
    for r in top:
        parts.append("| `%s` | %s | detect=%s err=%s%% gain=%.2f | %s |\n" % (
            r["id"], r["group"], "Y" if r["detected"] else "N",
            fmt(r["corner_err_pct"], 2), (r["sharp_gain_on"] or 0), diagnosis(r)))
    with open(os.path.join(OUT_DIR, "results_tables.md"), "w", encoding="utf-8") as fh:
        fh.write("".join(parts))

    # Individual fragments so RESULTS.md can interleave prose between tables.
    worst = ["| id | group | rank metric | diagnosis |\n|---|---|---|---|\n"]
    for r in top:
        worst.append("| `%s` | %s | detect=%s err=%s%% gain=%.2f | %s |\n" % (
            r["id"], r["group"], "Y" if r["detected"] else "N",
            fmt(r["corner_err_pct"], 2), (r["sharp_gain_on"] or 0), diagnosis(r)))
    for name, text in {
        "table_benchmark.md": md_table([r for r in rows if r["group"] == "benchmark"]),
        "table_synthetic.md": md_table([r for r in rows if r["group"] == "synthetic"]),
        "table_negatives.md": md_table([r for r in rows if r["group"] == "negatives"]),
        "table_worst10.md": "".join(worst),
    }.items():
        with open(os.path.join(OUT_DIR, name), "w", encoding="utf-8") as fh:
            fh.write(text)

    print(json.dumps(agg, indent=2))
    print("\nrows:", len(rows))
    print("false positives among negatives:", len(fp), [r["id"] for r in fp])
    print("worst10 ids:", [r["id"] for r in top])
    print("missing src dims:", sum(1 for r in rows if r["src_w"] is None))


if __name__ == "__main__":
    sys.exit(main())
