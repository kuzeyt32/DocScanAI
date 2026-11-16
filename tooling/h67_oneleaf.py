#!/usr/bin/env python3
"""
H67 - "one leaf of a two-leaf page", measured properly this time.

`h61b_leaf.py` reported that only 1 of 42 photos was a one-leaf lock, and that finding was used
to justify NOT building an extend rule. That measurement was wrong: it required
`pointgrid.spread == True`, but that flag means "two SAM masks were merged". When SAM returns the
whole open spread as a SINGLE mask - which is its usual behaviour - the flag is False even though
the target IS a spread. So the test excluded exactly the cases it was looking for.

This replaces it with a geometric test that does not depend on how the mask was assembled:

    precision >= 0.95            the quad is entirely on paper
    1.4 <= area(target)/area(quad) <= 2.8    the target is about twice the quad
    the uncovered region has ONE dominant connected blob >= 0.45 x the quad   (the other leaf)

and then, for each candidate, computes what extending would actually score.

The last part matters: the quad could also be short on all four sides (imprecise corners), which
would leave a RING rather than a blob. A ring fails the single-blob test, so the two failure
modes stay separated.

Usage: python tooling\\h67_oneleaf.py --tag h64fast --score tooling/out/h50/fast.json
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np


def order_pts(q: np.ndarray) -> np.ndarray:
    c = q.mean(axis=0)
    return q[np.argsort(np.arctan2(q[:, 1] - c[1], q[:, 0] - c[0]))].astype(np.float64)


def quad_mask(q: np.ndarray, shape) -> np.ndarray:
    m = np.zeros(shape, np.uint8)
    cv2.fillPoly(m, [np.round(q).astype(np.int32)], 1)
    return m.astype(bool)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="h64fast")
    ap.add_argument("--score", default="tooling/out/h50/fast.json")
    ap.add_argument("--masks", default="tooling/out/h48/masks")
    args = ap.parse_args()

    traces = Path("tooling/out/h39") / args.tag
    scores = {r["image"][:-4]: r
              for r in json.loads(Path(args.score).read_text(encoding="utf-8"))}

    rows = []
    print(f"{'photo':22} {'verdict':6} {'prec':>5} {'tgt/quad':>8} {'blob/quad':>9} "
          f"{'side':>7} {'iou now':>7} {'iou ext':>7}  one-leaf?")
    for mp in sorted(Path(args.masks).glob("*.npy")):
        stem = mp.stem
        tp = traces / stem / "trace.json"
        if not tp.exists():
            continue
        t = json.loads(tp.read_text(encoding="utf-8"))
        if not t.get("quad"):
            continue
        mask = np.load(mp).astype(bool)
        h, w = mask.shape
        W, H = float(t["image_w"]), float(t["image_h"])
        q = order_pts(np.array([[d["x"] / W * w, d["y"] / H * h] for d in t["quad"]],
                               np.float64))
        qm = quad_mask(q, (h, w))
        qa = int(qm.sum())
        ta = int(mask.sum())
        if qa < 500 or ta < 500:
            continue
        inter = int(np.logical_and(qm, mask).sum())
        prec = inter / qa
        ratio = ta / qa

        uncovered = (mask & ~qm).astype(np.uint8)
        n, lab, stats, cents = cv2.connectedComponentsWithStats(uncovered, 8)
        if n <= 1:
            continue
        areas = stats[1:, cv2.CC_STAT_AREA]
        k = int(np.argmax(areas)) + 1
        blob = int(areas.max())
        bx, by = cents[k]

        # which side of the quad is that blob on?
        qx, qy = cents_of(qm)
        dx, dy = bx - qx, by - qy
        side = ("right" if dx > 0 else "left") if abs(dx) >= abs(dy) else \
               ("below" if dy > 0 else "above")

        one_leaf = bool(prec >= 0.95 and 1.4 <= ratio <= 2.8 and blob >= 0.45 * qa)

        iou_now = inter / (ta + qa - inter)
        # What would extending actually score? Fit a rect to quad + the dominant blob.
        ext_q = None
        if one_leaf:
            pts = np.vstack([np.column_stack(np.nonzero(qm)[::-1]),
                             np.column_stack(np.nonzero(lab == k)[::-1])]).astype(np.float32)
            ext_q = order_pts(cv2.boxPoints(cv2.minAreaRect(pts)).astype(np.float64))
            em = quad_mask(ext_q, (h, w))
            ei = int(np.logical_and(em, mask).sum())
            iou_ext = ei / (ta + int(em.sum()) - ei)
        else:
            iou_ext = iou_now

        sc = scores.get(stem, {})
        rows.append({"stem": stem, "verdict": sc.get("verdict"),
                     "precision": round(prec, 3), "area_ratio": round(ratio, 2),
                     "blob_ratio": round(blob / qa, 2), "side": side,
                     "iou_now": round(iou_now, 3), "iou_extended": round(float(iou_ext), 3),
                     "one_leaf": one_leaf})
        flag = "YES" if one_leaf else ""
        print(f"{stem:22} {str(sc.get('verdict')):6} {prec:5.2f} {ratio:8.2f} "
              f"{blob / qa:9.2f} {side:>7} {iou_now:7.2f} {iou_ext:7.2f}  {flag}")

    cand = [r for r in rows if r["one_leaf"]]
    locks = [r for r in rows if r["verdict"] == "LOCK"]
    false_pos = [r for r in cand if r["verdict"] == "LOCK"]
    print(f"\n{len(cand)} photos look like ONE LEAF of a two-leaf page:")
    for r in cand:
        print(f"   {r['stem']:22} {r['verdict']:6} IoU {r['iou_now']:.2f} -> "
              f"{r['iou_extended']:.2f}  (extends {r['side']})")
    if false_pos:
        print(f"\nWARNING: {len(false_pos)} of them are currently LOCKs - the test is too loose:")
        for r in false_pos:
            print(f"   {r['stem']}")
    else:
        print("\nNo currently-locking photo is misclassified, so the test does not penalise")
        print("photos that already work.")

    out = Path("tooling/out/h67")
    out.mkdir(parents=True, exist_ok=True)
    (out / "oneleaf.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")
    return 0


def cents_of(m: np.ndarray) -> tuple[float, float]:
    ys, xs = np.nonzero(m)
    return float(xs.mean()), float(ys.mean())


if __name__ == "__main__":
    raise SystemExit(main())
