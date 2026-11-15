#!/usr/bin/env python3
"""
h24_reconcile.py - SUBTASK H24 per-image detection reconciliation.

Settles the H21 (19/20) vs H23 (17/20 AI-ON, 0/20 AI-OFF) contradiction by reading the
per-image reports of every run and printing ONE table with identical definitions.

Runs (oldest -> newest):
  h14            tooling/out/h14_final/corpus/corpus_out/benchmark_report.json
  h18            tooling/out/h18_refine/corpus_on/corpus_out/benchmark_report.json
  paz_1058       tooling/out/corpus_Paz18102026_10581939/benchmark_report.json
  paz_1556       tooling/out/corpus_Paz18102026_15560151/benchmark_report.json
  h24_fixed      tooling/out/h24/corpus_fixed/benchmark_report.json

All of them define "detected" as `[Detect] found=true` in the per-image logcat
(CorpusRealLifeTest.kt), i.e. the pipeline accepted a detector quad after the page-ness gate.
The difference was never the definition — it was WHERE the text-line proof was applied.

Usage:  py -3 tooling/h24_reconcile.py
Output: tooling/out/h24/flip_table.md, flip_table.csv, flips.txt
"""
import csv
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tooling", "out", "h24")

RUNS = [
    ("h14", "tooling/out/h14_final/corpus/corpus_out/benchmark_report.json"),
    ("h18", "tooling/out/h18_refine/corpus_on/corpus_out/benchmark_report.json"),
    ("paz_1058", "tooling/out/corpus_Paz18102026_10581939/benchmark_report.json"),
    ("paz_1556", "tooling/out/corpus_Paz18102026_15560151/benchmark_report.json"),
    ("h24_fixed", "tooling/out/h24/corpus_fixed/benchmark_report.json"),
]


def load_run(path):
    """Returns {ai_on: {name: row}, ai_off: {name: row}} for either report schema."""
    with open(os.path.join(ROOT, path), "r", encoding="utf-8") as f:
        doc = json.load(f)
    out = {"ai_on": {}, "ai_off": {}}
    if "reports" in doc:
        # CorpusRealLifeTest schema (h14/h18): reports[].ai_mode = ON/OFF + images[].name
        for rep in doc["reports"]:
            key = "ai_on" if str(rep.get("ai_mode", "")).upper() == "ON" else "ai_off"
            for row in rep.get("images", []):
                out[key][row["name"]] = row
    else:
        # BenchmarkArtifacts schema (corpus_Paz*): images[].ai_mode = ai_on/ai_off + image
        for row in doc.get("images", []):
            key = "ai_on" if row.get("ai_mode", "") == "ai_on" else "ai_off"
            out[key][row["image"]] = row
    return out


def det(rows, name):
    row = rows.get(name)
    if row is None:
        return None
    return bool(row.get("detected_found"))


def fmt(v):
    return "?" if v is None else ("Y" if v else ".")


def main():
    os.makedirs(OUT, exist_ok=True)
    data = {label: load_run(path) for label, path in RUNS}

    names = []
    for label, _ in RUNS:
        for key in ("ai_on", "ai_off"):
            for n in data[label][key]:
                if n not in names:
                    names.append(n)
    names.sort()

    # h24 row lookup gives the restored geometry columns for benchmark rows (no corner GT).
    h24_on = data["h24_fixed"]["ai_on"]
    h24_off = data["h24_fixed"]["ai_off"]

    header = ["image"]
    header += ["ON:" + label for label, _ in RUNS]
    header += ["OFF:" + label for label, _ in RUNS]
    header += ["corner_err_pct_diag", "placement"]

    lines = ["| " + " | ".join(header) + " |",
             "|" + "|".join(["---"] * len(header)) + "|"]
    rows_csv = []
    for n in names:
        vals = [n]
        for label, _ in RUNS:
            vals.append(fmt(det(data[label]["ai_on"], n)))
        for label, _ in RUNS:
            vals.append(fmt(det(data[label]["ai_off"], n)))
        r = h24_on.get(n) or h24_off.get(n) or {}
        err = r.get("corner_err_pct_diag", -1)
        place = r.get("quad_placement", "-")
        vals.append("n/a" if err is None or err < 0 else f"{err:.5f}")
        vals.append(place)
        lines.append("| " + " | ".join(vals) + " |")
        rows_csv.append(vals)

    counts = []
    for label, _ in RUNS:
        on = sum(1 for n in names if det(data[label]["ai_on"], n))
        off = sum(1 for n in names if det(data[label]["ai_off"], n))
        counts.append((label, on, off))

    flips = []
    for key, idx in (("ai_on", 1), ("ai_off", 6)):
        for i in range(len(RUNS) - 1):
            a, b = RUNS[i][0], RUNS[i + 1][0]
            lost = [n for n in names
                    if det(data[a][key], n) is True and det(data[b][key], n) is False]
            gained = [n for n in names
                      if det(data[a][key], n) is False and det(data[b][key], n) is True]
            if lost or gained:
                flips.append(f"{key.upper()} {a} -> {b}: LOST={lost or '-'} GAINED={gained or '-'}")
            else:
                flips.append(f"{key.upper()} {a} -> {b}: no flips")

    with open(os.path.join(OUT, "flip_table.md"), "w", encoding="utf-8") as f:
        f.write("SUBTASK H24 - per-image detection across runs\n\n")
        f.write("Y = `[Detect] found=true`, `.` = not detected. "
                "Benchmark rows have no corner GT (has_corner_gt=false), so corner_err is n/a; "
                "the synthetic group carries the corner/placement GT.\n\n")
        f.write("\n".join(lines) + "\n\n")
        f.write("Counts (benchmark group):\n\n")
        f.write("| run | AI-ON detected | AI-OFF detected |\n|---|---|---|\n")
        for label, on, off in counts:
            f.write(f"| {label} | {on}/20 | {off}/20 |\n")
        f.write("\nFlips:\n\n")
        for fl in flips:
            f.write("- " + fl + "\n")

    with open(os.path.join(OUT, "flip_table.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(rows_csv)

    with open(os.path.join(OUT, "flips.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(flips) + "\n")

    for label, on, off in counts:
        print(f"{label:12s} AI-ON={on:2d}/20 AI-OFF={off:2d}/20")
    print("\n".join(flips))
    print(f"\nwrote {OUT}\\flip_table.md")


if __name__ == "__main__":
    main()
