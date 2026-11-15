#!/usr/bin/env python3
"""h16_compare.py - compare H16 geometry variant CSVs.

Usage:
  py -3 tooling/h16_compare.py <dir-with-*.csv> [--baseline raw_refine_off]
"""
import csv
import glob
import os
import statistics as st
import sys

KEYS_LOWER = ("border_rms_px", "text_rms_px", "text_angle_std_deg")
KEYS_HIGHER = ("sharp_crops", "psnr", "ssim")


def load(path):
    rows = list(csv.DictReader(open(path, encoding="utf-8")))
    return [r for r in rows if r.get("detected") == "1" and r.get("name", "").startswith("bench_")]


def mean(rows, key):
    vals = []
    for r in rows:
        try:
            v = float(r.get(key, ""))
        except ValueError:
            continue
        if v >= 0:
            vals.append(v)
    return st.fmean(vals) if vals else -1.0


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    d = argv[1]
    baseline = "raw_refine_off"
    if "--baseline" in argv:
        baseline = argv[argv.index("--baseline") + 1]
    files = sorted(glob.glob(os.path.join(d, "*.csv")))
    data = {}
    for f in files:
        name = os.path.splitext(os.path.basename(f))[0]
        rows = load(f)
        if rows:
            data[name] = rows
    if not data:
        print("no variant CSVs with detected rows")
        return 2
    base = data.get(baseline, next(iter(data.values())))

    print("variant,det," + ",".join(KEYS_LOWER + KEYS_HIGHER))
    for name, rows in data.items():
        print(name + "," + str(len(rows)) + "," + ",".join(
            "%.5f" % mean(rows, k) for k in KEYS_LOWER + KEYS_HIGHER))

    print("\n--- relative deltas vs %s (negative is better for border/text) ---" % baseline)
    for name, rows in data.items():
        if name == baseline:
            continue
        parts = []
        for k in KEYS_LOWER:
            b, v = mean(base, k), mean(rows, k)
            parts.append("%s %+.2f%%" % (k, (v - b) / b * 100.0 if b else 0.0))
        for k in KEYS_HIGHER:
            b, v = mean(base, k), mean(rows, k)
            parts.append("%s %+.2f%%" % (k, (v - b) / b * 100.0 if b else 0.0))
        print(name + ": " + "  ".join(parts))

    print("\n--- per-image, %s ---" % baseline)
    print("%-18s %5s %9s %9s %9s" % ("image", "out", "borderRms", "textRms", "sharp"))
    for r in base:
        print("%-18s %4sx%-4s %9.3f %9.4f %9.1f" % (
            r["name"].replace("bench_docunet_", "d"), r["out_w"], r["out_h"],
            float(r["border_rms_px"]), float(r["text_rms_px"]), float(r["sharp_crops"])))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
