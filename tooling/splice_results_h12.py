#!/usr/bin/env python3
"""Splice the generated evidence tables into docs/RESULTS.md.

Keeps RESULTS.md prose hand-written while every table row comes straight from
tooling/extract_results_h12.py output.
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = os.path.join(ROOT, "docs", "RESULTS.md")
OUT = os.path.join(ROOT, "tooling", "out", "h12_final")

MARKERS = {
    "<!--H12_TABLE_BENCHMARK-->": "table_benchmark.md",
    "<!--H12_TABLE_SYNTHETIC-->": "table_synthetic.md",
    "<!--H12_TABLE_NEGATIVES-->": "table_negatives.md",
    "<!--H12_TABLE_WORST10-->": "table_worst10.md",
}


def main():
    with open(DOC, "r", encoding="utf-8") as fh:
        text = fh.read()
    changed = 0
    for marker, filename in MARKERS.items():
        if marker not in text:
            print("marker already spliced or missing:", marker)
            continue
        path = os.path.join(OUT, filename)
        with open(path, "r", encoding="utf-8") as fh:
            table = fh.read().rstrip("\n")
        text = text.replace(marker, table)
        changed += 1
        rows = sum(1 for line in table.splitlines() if line.startswith("| `"))
        print("%-28s -> %d data rows" % (filename, rows))
    with open(DOC, "w", encoding="utf-8") as fh:
        fh.write(text)
    print("spliced %d table(s) into docs/RESULTS.md" % changed)


if __name__ == "__main__":
    sys.exit(main())
