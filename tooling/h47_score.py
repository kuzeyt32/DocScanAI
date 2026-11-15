#!/usr/bin/env python3
"""
H47 - score the fresh holdout lane.

Reads the detector trace of a holdout asset set (JSON-only lane) and scores it against the SAM
pseudo-GT oracle built by `h47_intake.py`, using exactly the geometry H45 validated against the
device (mean |IoU difference| 0.0001 on 94 photos).

Reports the metrics that matter, not the gate's loose `correct` label:
  * detected / provenance split
  * quad is the page: IoU >= 0.5
  * signature breakdown (page / partial box / pure inner box / overshoot / mislocated)
  * photos whose oracle is unusable, excluded from every ratio

Usage:
  python tooling\\h47_score.py --tag h47holdout --set holdout42
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from h45_corners import analyse, order_quad  # noqa: E402
from oracle_quality import reason as oracle_reason  # noqa: E402

OUT = Path("tooling/out/h47")
TRACE_ROOT = Path("tooling/out/h39")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="h47holdout")
    ap.add_argument("--set", default="holdout42")
    args = ap.parse_args()

    oracle_path = Path("app/src/androidTest/assets") / args.set / "oracle_manifest.json"
    if not oracle_path.exists():
        print(f"missing {oracle_path} - run h47_intake.py first")
        return 1
    items = {i["image"]: i for i in
             json.loads(oracle_path.read_text(encoding="utf-8"))["items"]}

    # Guard: on the tight-framed holdout the frame-sized box prompt made SAM return the image
    # border for 39 of 42 photos, which would score every detector quad against the frame and
    # produce a plausible-looking number that means nothing. Refuse to run on such an oracle.
    edge = 0
    for it in items.values():
        w, h = float(it["width"]), float(it["height"])
        tol = 0.02 * min(w, h)
        pts = [it["corners_px"][c] for c in ("tl", "tr", "br", "bl")]
        if any(min(x, y, w - 1 - x, h - 1 - y) <= tol for x, y in pts):
            edge += 1
    if edge > 0.5 * len(items):
        print(f"REFUSING: {edge}/{len(items)} oracle quads touch the frame edge - this oracle is "
              f"the image border, not a page. See docs/runs/holdout42-audit.md (h49_audit.py).")
        return 2

    trace_dir = TRACE_ROOT / args.tag
    if not trace_dir.is_dir():
        print(f"missing trace dir {trace_dir}")
        return 1

    rows = []
    for name, item in items.items():
        tp = trace_dir / name[:-4] / "trace.json"
        if not tp.exists():
            continue
        t = json.loads(tp.read_text(encoding="utf-8"))
        problem = oracle_reason(item)
        row = {"image": name, "prov": t.get("provenance"),
               "detected": bool(t.get("detected")), "oracle_problem": problem,
               "have_quad": bool(t.get("quad"))}
        if t.get("quad") and not problem:
            gt = order_quad(np.array([item["corners_px"][k]
                                      for k in ("tl", "tr", "br", "bl")], np.float64))
            got = order_quad(np.array([[p["x"], p["y"]] for p in t["quad"]], np.float64))
            row.update(analyse(gt, got))
        rows.append(row)

    scorable = [r for r in rows if r["have_quad"] and not r["oracle_problem"]]
    det = [r for r in rows if r["detected"]]
    quad_ok = [r for r in scorable if (r.get("iou_check") or 0) >= 0.5]
    sig = Counter(r.get("signature") or "no-detection" for r in rows)

    lines = [
        f"# H47 - fresh holdout `{args.set}` (lane `{args.tag}`)",
        "",
        f"{len(rows)} photos, {len(det)} detected, {len(scorable)} scorable.",
        "",
        "## Detection",
        "",
        "| metric | value |",
        "|---|---|",
        f"| photos | {len(rows)} |",
        f"| detected | {len(det)} ({100 * len(det) / max(1, len(rows)):.0f} %) |",
        f"| CORNER_MODEL | {sum(1 for r in det if r['prov'] == 'CORNER_MODEL')} |",
        f"| CLASSICAL_FALLBACK | {sum(1 for r in det if r['prov'] == 'CLASSICAL_FALLBACK')} |",
        f"| no detection | {len(rows) - len(det)} |",
        "",
        "## Quad quality (strict, IoU >= 0.5 against SAM pseudo-GT)",
        "",
        f"**{len(quad_ok)} / {len(scorable)}** scorable detections = "
        f"**{100 * len(quad_ok) / max(1, len(scorable)):.0f} %**",
        "",
        f"mean IoU (scorable): "
        f"{np.mean([r.get('iou_check') or 0 for r in scorable]):.3f}"
        if scorable else "mean IoU: n/a",
        "",
        "| signature | photos |",
        "|---|---|",
    ]
    for k, n in sig.most_common():
        lines.append(f"| {k} | {n} |")

    bad_oracle = [r for r in rows if r["oracle_problem"]]
    if bad_oracle:
        lines += ["", "## Unusable SAM annotations (excluded)", "",
                  "| photo | problem |", "|---|---|"]
        for r in bad_oracle:
            lines.append(f"| `{r['image']}` | {r['oracle_problem']} |")

    misses = [r for r in rows if not r["detected"]]
    if misses:
        lines += ["", "## No detection", ""]
        for r in misses:
            lines.append(f"- `{r['image']}`")

    weak = sorted(scorable, key=lambda r: r.get("iou_check") or 0)[:20]
    lines += ["", "## Worst 20 quads", "",
              "| photo | prov | IoU | signature | coverage | area vs page |",
              "|---|---|---|---|---|---|"]
    for r in weak:
        lines.append(f"| `{r['image']}` | {r['prov']} | {r.get('iou_check'):.3f} | "
                     f"{r.get('signature')} | {r.get('cover')} | {r.get('ratio')} |")
    lines.append("")

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "SCORE.md").write_text("\n".join(lines), encoding="utf-8")
    (OUT / "score.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")
    print("\n".join(lines[:26]))
    print(f"-> {OUT / 'SCORE.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
