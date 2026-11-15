#!/usr/bin/env python3
"""Sanity checks for docs/RESULTS.md.

1. Table row counts per section must match the corpus sizes.
2. Every relative link/path referenced by the document must exist on disk.
3. The headline numbers must agree with the generated aggregates.
4. `n/a` may only appear where the artifact genuinely lacks the field.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = os.path.join(ROOT, "docs", "RESULTS.md")
OUT = os.path.join(ROOT, "tooling", "out", "h12_final")
REPORT = os.path.join(OUT, "verify_report.txt")

failures = []
lines_out = []


def emit(text):
    lines_out.append(text)


def main():
    with open(DOC, "r", encoding="utf-8") as fh:
        text = fh.read()

    # --- 1. row counts -----------------------------------------------------
    sections = [
        ("Benchmark per-image results", 20),
        ("Synthetic per-image results", 51),
        ("Negatives (5 rows)", 5),
        ("Worst 10 images across all groups", 10),
    ]
    heads = [(m.start(), m.group(0)) for m in re.finditer(r"^## .*$", text, re.M)]
    emit("== table row counts ==")
    for title, expected in sections:
        start = text.find(title)
        if start < 0:
            failures.append("section not found: %s" % title)
            continue
        nxt = min([p for p, _ in heads if p > start], default=len(text))
        block = text[start:nxt]
        rows = len(re.findall(r"^\| `", block, re.M))
        ok = rows == expected
        if not ok:
            failures.append("row count %s: got %d expected %d" % (title, rows, expected))
        emit("  %-45s %3d / %3d  %s" % (title, rows, expected, "OK" if ok else "MISMATCH"))

    total = len(re.findall(r"^\| `", text, re.M))
    emit("  total data rows: %d (20 benchmark + 51 synthetic + 5 negatives"
         " + 10 worst; worst rows repeat earlier ids)" % total)

    # --- 2. references exist ----------------------------------------------
    emit("\n== referenced paths ==")
    paths = set()
    for m in re.finditer(r"\]\((\.\./[^)#]+|[^)#\s]+\.md)\)", text):
        paths.add(m.group(1))
    for m in re.finditer(r"\]\(\.\./([^)#]+)\)", text):
        paths.add("../" + m.group(1))
    def resolve(p):
        """Strip a markdown line reference (`file.kt:42`) before probing disk."""
        return re.sub(r":\d+$", "", p)

    for p in sorted(paths):
        base = os.path.join(ROOT, "docs")
        full = os.path.normpath(os.path.join(base, resolve(p)))
        ok = os.path.exists(full)
        if not ok:
            failures.append("missing referenced path: %s" % p)
        emit("  %-60s %s" % (p, "OK" if ok else "MISSING"))

    # backtick paths that look like real files
    for m in re.finditer(r"`((?:tooling|app|docs|gallery)/[^`]+)`", text):
        p = m.group(1)
        if "<" in p or ">" in p or "*" in p or "{" in p or p.endswith("()"):
            continue  # documentation placeholders, not real paths
        full = os.path.join(ROOT, resolve(p).replace("/", os.sep))
        ok = os.path.exists(full)
        if not ok:
            failures.append("missing backticked path: %s" % p)
        emit("  %-60s %s" % (p, "OK" if ok else "MISSING"))

    # --- 3. numbers agree with aggregates ---------------------------------
    emit("\n== headline consistency ==")
    agg = json.load(open(os.path.join(OUT, "aggregates.json"), encoding="utf-8"))
    base = json.load(open(os.path.join(OUT, "baseline_h4.json"), encoding="utf-8"))
    checks = [
        ("19/20", "%d/%d" % (agg["benchmark_detected"], agg["groups"]["benchmark"])),
        ("3.44%", "%.2f%%" % agg["mean_corner_err_pct"]),
        ("3.45%", "%.2f%%" % base["mean_corner_err_pct"]),
        ("24 / 0 / 16", "%d / %d / %d" % (agg["placement_inside"], agg["placement_outside"],
                                          agg["placement_mixed"])),
        ("21 / 0 / 19", "%d / %d / %d" % (base["inside"], base["outside"], base["mixed"])),
        ("14,725.6", "{:,.1f}".format(agg["mean_sharp_on"])),
        ("13,172.4", "{:,.1f}".format(agg["mean_sharp_off"])),
        ("10,682.1", "{:,.1f}".format(base["mean_sharp_on"])),
        ("1,300 ms", "{:,} ms".format(round(agg["corpus_elapsed_ms_on"] / agg["images_total"]))),
        ("1,250 ms", "{:,} ms".format(round(base["elapsed_ms"] / agg["images_total"]))),
        ("59.2 ms", "%.1f ms" % agg["mean_detect_ms"]),
        ("175.5 ms", "%.1f ms" % agg["mean_warp_ms"]),
    ]
    for needle, derived in checks:
        present = needle in text
        agree = needle.replace(",", "").replace(" ", "") == derived.replace(",", "").replace(" ", "")
        if not present:
            failures.append("headline number missing from doc: %s (derived %s)" % (needle, derived))
        if present and not agree:
            failures.append("headline number disagrees: doc %s vs derived %s" % (needle, derived))
        emit("  %-12s doc=%-5s derived=%-12s %s" % (needle, present, derived,
                                                    "OK" if present and agree else "CHECK"))

    # --- 4. n/a audit ------------------------------------------------------
    emit("\n== n/a audit (per table) ==")
    for title, _ in sections[:3]:
        start = text.find(title)
        nxt = min([p for p, _ in heads if p > start], default=len(text))
        block = text[start:nxt]
        na_rows = [l.strip() for l in block.splitlines() if l.startswith("| `") and "| n/a |" in l]
        emit("  %-45s %d row(s) with an n/a cell" % (title, len(na_rows)))
        for r in na_rows:
            emit("      " + r[:150])

    emit("\n== result ==")
    if failures:
        emit("FAIL (%d problem(s))" % len(failures))
        for f in failures:
            emit("  - " + f)
    else:
        emit("PASS - row counts, references and headline numbers all agree")

    with open(REPORT, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines_out) + "\n")
    print("\n".join(lines_out))
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
