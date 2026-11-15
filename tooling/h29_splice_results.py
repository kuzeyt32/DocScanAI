"""Splice the H29 section into docs/RESULTS.md.

The section is kept as a separate, reviewable file (tooling/out/h29/results_h29_section.md) and
inserted here rather than edited in place, so the wording can be diffed on its own. RESULTS.md
contains non-ASCII punctuation (em dashes, arrows), which makes hand-editing error-prone; this
script reads and writes UTF-8 explicitly.

Usage:  python tooling\\h29_splice_results.py
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
RESULTS = ROOT / "docs" / "RESULTS.md"
SECTION = ROOT / "tooling" / "out" / "h29" / "results_h29_section.md"

MARKER = (
    "## Latest \u2014 H27 (0.3.8): the grid dewarp is bit-deterministic, "
    "and B&W is one modest step softer"
)
PROMOTED = MARKER.replace("## Latest \u2014 H27", "## H27", 1)

ANCHOR = "**0.3.8 determinism + B&W evidence (H27):**"
BULLET = (
    "**H29 detector-strategy evidence (no build change):** `tooling/out/h29/` \u2014 "
    "`HEADTOHEAD.md` (graph contracts, per-asset tables, decision), `results_h29_section.md` "
    "(the section below), `fastvit_inspect.txt`, `mlsd_320_inspect.txt`, `mlsd_512_inspect.txt`, "
    "`models_fastvit/`; `tooling/out/h27/H29/`, `H29B/`, `H29P/`, `FASTVIT/` (instrumentation "
    "output + logcat); 12-class lane `tooling/out/h7_tests/`  \n"
)


def main() -> int:
    if not RESULTS.is_file():
        print("MISSING %s" % RESULTS)
        return 2
    if not SECTION.is_file():
        print("MISSING %s" % SECTION)
        return 2

    text = RESULTS.read_text(encoding="utf-8")
    section = SECTION.read_text(encoding="utf-8").rstrip("\n") + "\n\n"

    if text.count(MARKER) != 1:
        print("MARKER count=%d (expected 1) - refusing to write" % text.count(MARKER))
        return 3
    if text.count(ANCHOR) != 1:
        print("ANCHOR count=%d (expected 1) - refusing to write" % text.count(ANCHOR))
        return 3
    if "## Latest \u2014 H29" in text:
        print("H29 section already present - refusing to write twice")
        return 4

    text = text.replace(MARKER, section + PROMOTED, 1)
    text = text.replace(ANCHOR, BULLET + ANCHOR, 1)
    RESULTS.write_text(text, encoding="utf-8")

    lines = text.splitlines()
    for i, line in enumerate(lines, 1):
        if line.startswith("## H29") or line.startswith("## Latest \u2014 H29") or line.startswith("## H27"):
            print("%4d  %s" % (i, line))
    print("OK lines=%d bytes=%d" % (len(lines), len(text.encode("utf-8"))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
