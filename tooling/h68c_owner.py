#!/usr/bin/env python3
"""
H68c - the extension rule v2 on owner110, scored against its oracle quads.

`h68b` found ink_min=0.08 gives 6 fired / 6 helped / 0 hurt on holdout42 (+0.051 mean IoU).
That alone must not authorise shipping: a rule that gains 6 new photos and loses 10 old ones is
not a fix. This runs the SAME rule on owner110 using the same geometry `h47_score.py` uses, so
the number is comparable to the published 54/94.

owner110 has no page masks, so the oracle quad stands in for the target region - which is exactly
what the official score does.

Usage: python tooling\\h68c_owner.py --tag h64owner --set owner110
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from h68b_extend_ink import extend, order_pts, quad_mask  # noqa: E402
from oracle_quality import reason as oracle_reason  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="h64owner")
    ap.add_argument("--set", default="owner110")
    ap.add_argument("--ink", type=float, default=0.08)
    args = ap.parse_args()

    root = Path("app/src/androidTest/assets") / args.set
    oracle_path = root / "oracle_manifest.json"
    items = {i["image"]: i for i in
             json.loads(oracle_path.read_text(encoding="utf-8"))["items"]}
    traces = Path("tooling/out/h39") / args.tag

    rows = []
    for name, it in items.items():
        tp = traces / name[:-4] / "trace.json"
        if not tp.exists() or oracle_reason(it):
            continue
        t = json.loads(tp.read_text(encoding="utf-8"))
        if not t.get("quad"):
            continue
        img = cv2.imread(str(root / name), cv2.IMREAD_GRAYSCALE)
        if img is None:
            continue
        h, w = img.shape
        if (int(it["width"]), int(it["height"])) != (w, h):
            continue

        tgt = order_pts(np.array([it["corners_px"][k] for k in ("tl", "tr", "br", "bl")],
                                 np.float64))
        det = order_pts(np.array([[p["x"], p["y"]] for p in t["quad"]], np.float64))

        gray = cv2.GaussianBlur(img.astype(np.float32), (5, 5), 0)
        e = cv2.Canny(gray.astype(np.uint8), 40, 120) > 0
        e = cv2.dilate(e.astype(np.uint8), np.ones((3, 3), np.uint8)).astype(bool)

        q2, meta = extend(gray, e, det, args.ink)

        tmask = quad_mask(tgt, (h, w))

        def iou_of(qq):
            qm = quad_mask(qq, (h, w))
            inter = int(np.logical_and(qm, tmask).sum())
            union = int(qm.sum()) + int(tmask.sum()) - inter
            return inter / union if union else 0.0

        rows.append({"stem": name[:-4], "before": round(iou_of(det), 3),
                     "after": round(iou_of(q2), 3), **meta})

    fired = [r for r in rows if r["accepted"]]
    helped = [r for r in fired if r["after"] > r["before"] + 0.02]
    hurt = [r for r in fired if r["after"] < r["before"] - 0.02]
    mb = float(np.mean([r["before"] for r in rows]))
    ma = float(np.mean([r["after"] for r in rows]))
    lb = sum(1 for r in rows if r["before"] >= 0.5)
    la = sum(1 for r in rows if r["after"] >= 0.5)

    print(f"owner110, ink_min={args.ink}: {len(rows)} scorable")
    print(f"  mean IoU     {mb:.3f} -> {ma:.3f}  ({ma - mb:+.3f})")
    print(f"  IoU >= 0.5   {lb}/{len(rows)} -> {la}/{len(rows)}")
    print(f"  fired {len(fired)}, helped {len(helped)}, hurt {len(hurt)}")
    if fired:
        print(f"\n{'photo':22} {'before':>6} {'after':>6} {'d':>6} {'areaX':>6}")
        for r in sorted(fired, key=lambda r: -(r["after"] - r["before"])):
            print(f"{r['stem']:22} {r['before']:6.2f} {r['after']:6.2f} "
                  f"{r['after'] - r['before']:+6.2f} {r['area_ratio']:6.2f}")
    if hurt:
        print("\nHURT:")
        for r in hurt:
            print(f"   {r['stem']:22} {r['before']:.2f} -> {r['after']:.2f}")
        print("\n=> DO NOT SHIP: the rule damages photos that already work.")
    elif helped:
        print("\n=> no regressions on owner110.")
    out = Path("tooling/out/h68")
    out.mkdir(parents=True, exist_ok=True)
    (out / "extend_owner.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
