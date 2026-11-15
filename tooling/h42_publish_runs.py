#!/usr/bin/env python3
"""
H42 - PUBLISH RUN OUTPUTS: turn a lane's raw artifacts into a committed, readable record.

The owner keeps asking "what did the run actually do?" without re-running a phone lane. The photos
themselves are private (never committed), but the *measurements* are not: per-photo scalars, the
verdict tables and the ship provenance are all text and safe to publish.

Usage:
    python tooling/h42_publish_runs.py                 # rebuilds every table under docs/runs/
    python tooling/h42_publish_runs.py --check         # exit 1 if a source artifact is missing

Reads (all under tooling/out/, gitignored):
    h39/h40all/manifest.json                     116-photo detector trace (H40)
    h38/h38fixd/report_h38fixd.json              baseline lane (0.3.13)
    h38/h40relax/report_h40relax.json            same lane with the H40 corner-gate fix
    h41/<tag>/SHIP.txt, badging.txt, SHA256.txt  ship provenance

Writes (committed):
    docs/runs/detector-116.md      per-photo table + summary, both lanes side by side
    docs/runs/README.md            index of what each run proves
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

OUT = Path("tooling/out")
DOCS = Path("docs/runs")

TRACE = OUT / "h39/h43full/manifest.json"
# `h38_gate.py` writes report_<tag>.json into the LANE directory (tooling/out/h38/), not into the
# per-tag folder, so these two live side by side with their evidence folders.
BASELINE = OUT / "h38/report_h40relax.json"      # 0.3.15: corner model reaches 33 photos
FIXED = OUT / "h38/report_h44crop.json"          # 0.3.16: letterbox + multi-crop rescue


def load(path: Path):
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def cls(row: dict) -> str:
    """One label per photo, in the priority the gate uses."""
    if not row.get("detected"):
        return "no-detection"
    if row.get("wrong_page"):
        return "wrong-page"
    if row.get("merged"):
        return "merged"
    if row.get("correct_page"):
        return "correct"
    return "unlabelled"


def summary(rows: list[dict]) -> dict:
    counts: dict[str, int] = {}
    for r in rows:
        counts[cls(r)] = counts.get(cls(r), 0) + 1
    prov: dict[str, int] = {}
    for r in rows:
        if r.get("detected"):
            p = r.get("prov") or "?"
            prov[p] = prov.get(p, 0) + 1
    return {"photos": len(rows), "classes": counts, "provenance": prov}


def build_detector_page() -> str | None:
    base = load(BASELINE)
    fixd = load(FIXED)
    if not base or not fixd:
        return None

    base_rows = {r["image"]: r for r in base["rows"]}
    fixd_rows = {r["image"]: r for r in fixd["rows"]}
    sb, sf = summary(base["rows"]), summary(fixd["rows"])

    def cell(counts: dict, key: str) -> int:
        return counts.get(key, 0)

    lines = [
        "# Detector lane - all 116 owner photos",
        "",
        "Machine output of the real in-app detector path (`Owner110RealPathTest`) over the private",
        "owner corpus. **The photos are not in this repository**; these are the measurements they",
        "produced, published so the numbers can be checked without a phone.",
        "",
        "Regenerate: `tooling\\h38_lane.cmd <tag> Owner110RealPathTest`, then",
        "`python tooling\\h38_gate.py --tag <tag>`. Rebuild this page:",
        "`python tooling\\h42_publish_runs.py`.",
        "",
        "## Summary",
        "",
        "| class | 0.3.15 (`h40relax`) | 0.3.16 (`h44crop`) | change |",
        "|---|---|---|---|",
    ]
    for key in ("correct", "merged", "wrong-page", "no-detection"):
        a, b = cell(sb["classes"], key), cell(sf["classes"], key)
        delta = b - a
        lines.append(
            f"| {key} | {a} | {b} | {'+' if delta > 0 else ''}{delta if delta else '0'} |"
        )
    a, b = sb["photos"] - cell(sb["classes"], "no-detection"), sf["photos"] - cell(sf["classes"], "no-detection")
    lines.append(f"| **detected** | {a} | {b} | {'+' if b-a > 0 else ''}{b - a} |")

    # The strict metric. `correct` above only means "detected and not flagged", which is what the
    # H38 gate counts; it says nothing about how close the quad is to the page. These two rows do.
    def _scoreable_det(rows: list[dict]) -> list[dict]:
        return [r for r in rows if r.get("detected") and r.get("scoreable")]

    def _quad_ok(rows: list[dict]) -> int:
        return sum(1 for r in rows if r.get("quad_ok"))

    def _mean_iou(rows: list[dict]) -> float:
        xs = [r["iou"] for r in _scoreable_det(rows) if r.get("iou") is not None]
        return sum(xs) / len(xs) if xs else 0.0

    sa, sb_ = len(_scoreable_det(base["rows"])), len(_scoreable_det(fixd["rows"]))
    qa, qb = _quad_ok(base["rows"]), _quad_ok(fixd["rows"])
    lines.append(f"| **quad matches page (IoU>=0.5)** | **{qa}/{sa}** | **{qb}/{sb_}** | "
                 f"{'+' if qb - qa > 0 else ''}{qb - qa} |")
    lines.append(f"| mean IoU (scoreable detections) | {_mean_iou(base['rows']):.3f} | "
                 f"{_mean_iou(fixd['rows']):.3f} | - |")
    lines.append(f"| unscoreable oracle annotations | "
                 f"{sum(1 for r in base['rows'] if not r.get('scoreable'))} | "
                 f"{sum(1 for r in fixd['rows'] if not r.get('scoreable'))} | - |")

    lines += ["", "### By provenance (detected photos only)", "",
              "| provenance | 0.3.15 | 0.3.16 |", "|---|---|---|"]
    for p in sorted(set(sb["provenance"]) | set(sf["provenance"])):
        lines.append(f"| {p} | {cell(sb['provenance'], p)} | {cell(sf['provenance'], p)} |")

    lines += ["", "`correct` is the H38 gate's label: **detected and not flagged** as wrong-page or",
              "merged. It does **not** mean the quad is the page - many `correct` photos have a quad",
              "that overshoots or clips the page. The strict metric is the",
              "`quad matches page (IoU>=0.5)` row above.",
              "",
              "`wrong-page`/`merged` are evaluated only where the oracle annotation is usable.",
              "Against a collapsed or half-placed annotation, IoU is 0 by construction and",
              "`merged` (which needs `area_ratio >= 1.5`) is infinite, so the old reports carried",
              "fabricated flags on those rows. See `tooling/oracle_quality.py`.",
              ""]

    # The table is a strict partition (wrong-page wins over merged); the gate quotes the two flags
    # independently, so state both so the two tables cannot look contradictory.
    def _flags(rows: list[dict]) -> tuple[int, int]:
        return (sum(1 for r in rows if r.get("wrong_page")),
                sum(1 for r in rows if r.get("merged")))

    wa, ma = _flags(base["rows"])
    wb, mb = _flags(fixd["rows"])
    lines += [f"Rows above are a strict partition: a photo carrying both flags counts once, as",
              f"`wrong-page`. The independent flags are **0.3.15: {wa} wrong-page / {ma} merged** "
              f"and",
              f"**0.3.16: {wb} wrong-page / {mb} merged**."]

    changed = [n for n in base_rows
               if (base_rows[n].get("prov"), base_rows[n].get("detected")) !=
                  (fixd_rows[n].get("prov"), fixd_rows[n].get("detected"))]
    lines += ["", f"### Photos whose decision changed ({len(changed)})", ""]
    if changed:
        lines += ["| photo | 0.3.15 | 0.3.16 | IoU vs oracle | class |", "|---|---|---|---|---|"]
        for n in sorted(changed):
            b_, f_ = base_rows[n], fixd_rows[n]
            lines.append(
                f"| `{n}` | {b_.get('prov')} / detected={b_.get('detected')} | "
                f"{f_.get('prov')} / detected={f_.get('detected')} | "
                f"{(f_.get('iou') or 0):.3f} | {cls(f_)} |"
            )
    else:
        lines.append("_none_")

    lines += ["", "## Per-photo measurements (0.3.16)", "",
              "`iou` is against the pseudo-GT oracle quad; `corner %` is mean corner error as a",
              "percentage of the image diagonal. Both are lower bounds (oracle corners are hand-",
              "marked, ~5-10 % of the diagonal off on curved pages).", "",
              "| photo | class | prov | gate | iou | oracle | corner % | area ratio | mlsd | ms |",
              "|---|---|---|---|---|---|---|---|---|---|"]
    for n in sorted(fixd_rows):
        f_ = fixd_rows[n]
        iou = f_.get("iou")
        cp = f_.get("corner_pct")
        ar = f_.get("area_ratio")
        lines.append(
            f"| `{n}` | {cls(f_)} | {f_.get('prov') or '-'} | "
            f"{'pass' if f_.get('gate_pass') else 'fail'} | "
            f"{'—' if iou is None else f'{iou:.3f}'} | "
            f"{'ok' if f_.get('scoreable', True) else 'BAD'} | "
            f"{'—' if cp is None else f'{cp:.1f}'} | "
            f"{'—' if ar is None else f'{ar:.2f}'} | "
            f"{'yes' if f_.get('mlsd_invoked') else 'no'} | "
            f"{(f_.get('ms') or 0):.0f} |"
        )

    # Detector trace (H40): corner-heat evidence per photo.
    trace = load(TRACE)
    if trace:
        rows = trace["rows"]
        weak = [r for r in rows if not r.get("detected")]
        lines += ["", "## Corner-model blind spots (from the H40 trace)", "",
                  f"`DOCSCAN_TRACE_JSON_ONLY=1` re-ran all {len(rows)} photos through the detector",
                  "with the corner heat maps recorded. Of the photos that ended with no quad,",
                  "these are the ones where the model had no confident corner at all", "",
                  "| photo | gate |", "|---|---|"]
        for r in sorted(weak, key=lambda x: x["image"]):
            lines.append(f"| `{r['image']}` | {'no-detection' if not r.get('detected') else r.get('provenance')} |")
        lines += ["", "Per-channel heat peaks, quad and gate scalars live in the trace dump",
                  "(`tooling/out/h39/h40all/<photo>/trace.json`, regenerable)."]

    return "\n".join(lines) + "\n"


def build_ship_page() -> tuple[str, str] | None:
    """Latest ship lane output. Returns (filename, markdown)."""
    root = OUT / "h41"
    if not root.is_dir():
        return None
    tags = sorted(
        (p for p in root.iterdir() if p.is_dir() and (p / "SHIP.txt").exists()),
        key=lambda p: (p / "SHIP.txt").stat().st_mtime,
    )
    if not tags:
        return None
    tag = tags[-1]
    text = (tag / "SHIP.txt").read_text(encoding="utf-8").rstrip()
    version = "unknown"
    sha = tag / "SHA256.txt"
    if sha.exists():
        for line in sha.read_text(encoding="utf-8").splitlines():
            # The version lives on a combined line: "versionCode=18 versionName=0.3.15".
            if "versionName=" in line:
                version = line.split("versionName=", 1)[1].split()[0].strip()
                break
    name = f"ship-{version}.md"
    lines = [f"# Ship provenance - {version}", "",
             f"Raw output of `tooling\\h41_ship.cmd {tag.name}` (build identity, certificate",
             "continuity, hashes, install smoke).", "", "```text", text, "```", ""]
    return name, "\n".join(lines)


def main(argv: list[str]) -> int:
    DOCS.mkdir(parents=True, exist_ok=True)

    produced: list[str] = []
    detector = build_detector_page()
    if detector:
        (DOCS / "detector-116.md").write_text(detector, encoding="utf-8")
        produced.append("detector-116.md")
    ship_name = None
    ship = build_ship_page()
    if ship:
        ship_name, body = ship
        (DOCS / ship_name).write_text(body, encoding="utf-8")
        produced.append(ship_name)
        for stale in DOCS.glob("ship-*.md"):
            if stale.name != ship_name:
                stale.unlink()

    ship_row = (
        f"| [`{ship_name}`]({ship_name}) | Build identity, certificate continuity, SHA-256, "
        f"install/launch smoke for the published APK | `h41_ship.cmd` |"
        if ship_name else
        "| _none yet_ | run `tooling\\h41_ship.cmd <tag>` | `h41_ship.cmd` |"
    )
    index = "\n".join([
        "# Run records",
        "",
        "Text output of the measurement lanes, published so results can be read without a phone or",
        "the private photo corpus. Nothing here contains image data.",
        "",
        "| file | what it is | source lane |",
        "|---|---|---|",
        "| [`detector-116.md`](detector-116.md) | Detector verdict over all 116 owner photos: "
        "0.3.15 vs 0.3.16, per-photo quad measurements, corner-model blind spots | "
        "`h38_lane.cmd` + `h42_publish_runs.py` |",
        "| [`corner-signatures.md`](corner-signatures.md) | Which corners the returned quad actually "
        "touches (page / inner box / outside), the oracle-annotation defects, and strict IoU quality | "
        "`h39_dump.cmd` + `h45_corners.py` |",
        ship_row,
        "",
        "Regenerate everything:",
        "",
        "```bat",
        "tooling\\h38_lane.cmd h40relax Owner110RealPathTest",
        "python tooling\\h38_gate.py --tag h40relax",
        "tooling\\h41_ship.cmd v0316",
        "python tooling\\h42_publish_runs.py",
        "```",
        "",
    ])
    (DOCS / "README.md").write_text(index, encoding="utf-8")
    produced.append("README.md")

    print(f"wrote {len(produced)} file(s) under {DOCS}: {', '.join(produced)}")
    if "--check" in argv and len(produced) < 3:
        print("! missing source artifacts (lanes not run?)")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
