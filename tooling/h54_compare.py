#!/usr/bin/env python3
"""
H54 - compare two quad-vs-mask scores and print the movement, photo by photo.

A single number ("5/42 -> 12/42") hides as much as it shows: it cannot distinguish a real
improvement from a threshold artefact, and it says nothing about what got WORSE. This prints the
per-photo delta and the classification movement, so a regression cannot hide behind a better
mean.

Usage:
  python tooling\\h54_compare.py --before h50/score.json --after h53.json --masks h48/masks
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

ORDER = ["LOCK", "UNDER", "OVER", "PART"]


def load(p: Path) -> dict:
    return {r["image"][:-4]: r for r in json.loads(p.read_text(encoding="utf-8"))}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--before", default="tooling/out/h50/score.json")
    ap.add_argument("--after", default="tooling/out/h53/score.json")
    ap.add_argument("--label-before", default="before")
    ap.add_argument("--label-after", default="after")
    args = ap.parse_args()

    a = load(Path(args.before))
    b = load(Path(args.after))
    common = sorted(set(a) & set(b))
    only_a = sorted(set(a) - set(b))
    only_b = sorted(set(b) - set(a))

    def summary(d: dict, keys) -> str:
        n = len(keys)
        if not n:
            return "n=0"
        c = {k: sum(1 for s in keys if d[s]["verdict"] == k) for k in ORDER}
        mi = sum(d[s]["iou"] for s in keys) / n
        mr = sum(d[s]["recall"] for s in keys) / n
        mp = sum(d[s]["precision"] for s in keys) / n
        return (f"LOCK {c['LOCK']}/{n} ({100 * c['LOCK'] / n:.0f} %)  "
                f"UNDER {c['UNDER']}  OVER {c['OVER']}  PART {c['PART']}  "
                f"| IoU {mi:.3f} rec {mr:.3f} prec {mp:.3f}")

    print(f"{args.label_before:>12}: {summary(a, common)}")
    print(f"{args.label_after:>12}: {summary(b, common)}")
    if only_a:
        print(f"only in before: {only_a}")
    if only_b:
        print(f"only in after : {only_b}")
    print()

    improved, worsened, same = [], [], []
    for s in common:
        d = b[s]["iou"] - a[s]["iou"]
        if d > 0.02:
            improved.append((s, d))
        elif d < -0.02:
            worsened.append((s, d))
        else:
            same.append(s)
    print(f"IoU improved on {len(improved)} photos, worsened on {len(worsened)}, "
          f"unchanged on {len(same)}")
    if improved:
        print("\n  improved:")
        for s, d in sorted(improved, key=lambda x: -x[1]):
            print(f"    {s:22} {a[s]['verdict']:5} -> {b[s]['verdict']:5}  "
                  f"IoU {a[s]['iou']:.2f} -> {b[s]['iou']:.2f}  ({d:+.2f})")
    if worsened:
        print("\n  WORSENED:")
        for s, d in sorted(worsened, key=lambda x: x[1]):
            print(f"    {s:22} {a[s]['verdict']:5} -> {b[s]['verdict']:5}  "
                  f"IoU {a[s]['iou']:.2f} -> {b[s]['iou']:.2f}  ({d:+.2f})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
