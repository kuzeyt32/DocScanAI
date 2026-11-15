"""SUBTASK H25 step 4a - warp-time attribution on the AI-ON corpus run.

Reads ``corpus_report_ai_on.csv`` (one row per processed image, in processing order)
and answers one question: is the 669 ms worst-case warp time a cold start or a
per-page outlier?

The CSV rows are written in processing order, so row 1 is the first page of the
first session and its session-relative position is 0 (cold).
"""
import csv
import os
import statistics
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def pct(values, p):
    if not values:
        return float("nan")
    ordered = sorted(values)
    k = (len(ordered) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(ordered) - 1)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (k - lo)


def stats(rows, label):
    vals = [r["warp_ms"] for r in rows]
    print("%-22s n=%3d min=%4d median=%6.1f p95=%6.1f max=%5d mean=%6.1f" % (
        label, len(vals), min(vals), statistics.median(vals), pct(vals, 0.95),
        max(vals), statistics.mean(vals)))


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        ROOT, "tooling", "out", "h25", "corpus2", "corpus_out", "ai_on",
        "corpus_report_ai_on.csv")
    rows = []
    with open(path, newline="", encoding="utf-8") as fh:
        for i, r in enumerate(csv.DictReader(fh)):
            r["i"] = i
            r["warp_ms"] = int(r["warp_ms"] or 0)
            r["detect_ms"] = int(r["detect_ms"] or 0)
            r["total_ms"] = int(r["total_ms"] or 0)
            r["max_out"] = max(int(r["out_w"] or 0), int(r["out_h"] or 0))
            rows.append(r)
    print("source:", os.path.relpath(path, ROOT))
    print()
    stats(rows, "all pages")
    stats(rows[1:], "excluding first page")
    print()
    print("first page           : %-28s warp=%4d detect=%3d total=%4d" % (
        rows[0]["image"], rows[0]["warp_ms"], rows[0]["detect_ms"], rows[0]["total_ms"]))
    print()
    print("slowest 6 pages (processing order):")
    print("  idx  warp detect total  out_px  path             image")
    for r in sorted(rows, key=lambda r: -r["warp_ms"])[:6]:
        print("  %3d  %4d  %4d  %5d  %6d  %-16s %s" % (
            r["i"], r["warp_ms"], r["detect_ms"], r["total_ms"], r["max_out"],
            r["warp_path"], r["image"]))
    print()
    cold = rows[0]["warp_ms"]
    warm = [r["warp_ms"] for r in rows[1:]]
    over = [r for r in rows if r["warp_ms"] >= 400]
    print("pages >= 400 ms      : %d (%s)" % (
        len(over), ", ".join("%s@%d" % (r["image"], r["warp_ms"]) for r in over)))
    print("first page is max    : %s" % ("YES" if cold == max(r["warp_ms"] for r in rows) else "no"))
    print("first page vs warm   : %d ms vs median %d ms (%.2fx)" % (
        cold, statistics.median(warm), cold / max(1.0, statistics.median(warm))))
    # size effect: warp time on the largest quarter vs the smallest quarter
    by_size = sorted(rows, key=lambda r: r["max_out"])
    q = max(1, len(by_size) // 4)
    small = [r["warp_ms"] for r in by_size[:q]]
    large = [r["warp_ms"] for r in by_size[-q:]]
    print("smallest 25%% output  : median %d ms (max_px %d..%d)" % (
        statistics.median(small), by_size[0]["max_out"], by_size[q - 1]["max_out"]))
    print("largest 25%% output   : median %d ms (max_px %d..%d)" % (
        statistics.median(large), by_size[-q]["max_out"], by_size[-1]["max_out"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
