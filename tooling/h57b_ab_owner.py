#!/usr/bin/env python3
"""
H57b - offline A/B of the M-LSD repair on owner110, against its own oracle manifest.

Same question as `h57_ab_repair.py` (holdout, mask-based), different referee: owner110 is scored
against `oracle_manifest.json` quads with the same geometry `h47_score.py` uses, so the number is
directly comparable to the published 44/94 and 53/94 figures.

The trace carries `corner_post.quad` (before applyMlsdEvidence) and `quad` (shipped). Scoring
both decides whether the repair earns its keep on the set it was tuned on.

Usage: python tooling\\h57b_ab_owner.py --tag h56owner --set owner110
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from h45_corners import analyse, order_quad  # noqa: E402
from oracle_quality import reason as oracle_reason  # noqa: E402

TRACE_ROOT = Path("tooling/out/h39")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="h56owner")
    ap.add_argument("--set", default="owner110")
    args = ap.parse_args()

    oracle_path = Path("app/src/androidTest/assets") / args.set / "oracle_manifest.json"
    items = {i["image"]: i for i in
             json.loads(oracle_path.read_text(encoding="utf-8"))["items"]}
    trace_dir = TRACE_ROOT / args.tag

    rows = []
    for name, item in items.items():
        tp = trace_dir / name[:-4] / "trace.json"
        if not tp.exists():
            continue
        t = json.loads(tp.read_text(encoding="utf-8"))
        if not t.get("quad") or oracle_reason(item):
            continue
        gt = order_quad(np.array([item["corners_px"][k]
                                  for k in ("tl", "tr", "br", "bl")], np.float64))
        w, h = float(item["width"]), float(item["height"])

        shipped = order_quad(np.array([[p["x"], p["y"]] for p in t["quad"]], np.float64))
        cp = (t.get("corner_post") or {}).get("quad")
        if cp:
            pre = order_quad(np.array([[p["x"], p["y"]] for p in cp], np.float64))
            same = np.max(np.abs(pre - shipped)) < 1.5
        else:
            pre, same = shipped, True

        a_ship = analyse(gt, shipped)
        a_pre = analyse(gt, pre)
        rows.append({
            "stem": name[:-4],
            "touched": not same,
            "ship": {"iou": float(a_ship.get("iou_check") or 0.0),
                     "sig": a_ship.get("signature")},
            "pre": {"iou": float(a_pre.get("iou_check") or 0.0),
                    "sig": a_pre.get("signature")},
        })

    touched = [r for r in rows if r["touched"]]
    print(f"{len(rows)} scorable, {len(touched)} touched by the repair\n")

    def locks(g, k):
        return sum(1 for r in g if r[k]["iou"] >= 0.5)

    def mean(g, k):
        return sum(r[k]["iou"] for r in g) / max(1, len(g))

    for group, label in ((rows, "ALL"), (touched, "TOUCHED BY REPAIR")):
        print(f"--- {label} (n={len(group)}) ---")
        for k, kl in (("pre", "PRE-repair"), ("ship", "SHIPPED   ")):
            print(f"  {kl}  IoU>=0.5 {locks(group, k):3}/{len(group)} "
                  f"({100 * locks(group, k) / max(1, len(group)):.0f} %)  "
                  f"mean IoU {mean(group, k):.3f}")
        if len(group) == len(touched) and touched:
            better = sum(1 for r in touched if r["pre"]["iou"] > r["ship"]["iou"] + 0.02)
            worse = sum(1 for r in touched if r["pre"]["iou"] < r["ship"]["iou"] - 0.02)
            print(f"  pre better on {better}, shipped better on {worse}, "
                  f"equal on {len(touched) - better - worse}")
        print()

    if touched:
        print(f"{'photo':22} {'pre':>5} {'ship':>5}  delta   pre-sig -> ship-sig")
        for r in sorted(touched, key=lambda r: r["pre"]["iou"] - r["ship"]["iou"], reverse=True):
            d = r["pre"]["iou"] - r["ship"]["iou"]
            print(f"{r['stem']:22} {r['pre']['iou']:5.2f} {r['ship']['iou']:5.2f}  {d:+.2f}   "
                  f"{r['pre']['sig']} -> {r['ship']['sig']}")

    out = Path("tooling/out/h57")
    out.mkdir(parents=True, exist_ok=True)
    (out / "ab_owner.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
