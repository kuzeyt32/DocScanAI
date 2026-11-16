#!/usr/bin/env python3
"""
H66 - the CEILING: what is the best IoU ANY quadrilateral could get against each page mask?

Why this matters
----------------
The detector is a 4-corner model. It outputs four points, the warp consumes four points, and the
score rasterises a four-point polygon. If the page is CURVED, no quadrilateral can contain it:
either the quad covers all the paper and spills onto the background, or it stays inside the paper
and misses the curved edges. The metric then charges the detector for a shape it cannot represent.

That is a testable claim, so this measures it instead of arguing:

    ceiling = max IoU over all quads, found by coordinate hill-climbing from several starts
    actual  = the detector's IoU on the same mask
    gap     = ceiling - actual        <- the part of the miss a better 4-corner detector could fix

A photo with ceiling 0.78 is not "22 % wrong" - it is unrepresentable with four corners. A photo
with ceiling 0.95 and actual 0.75 has a real 0.20 of detector error.

Working at reduced scale (long edge ~420 px) keeps this fast; IoU is scale-invariant enough that
the ceiling is accurate to about 0.01, which is all the argument needs.

Usage:
  python tooling\\h66_ceiling.py --tag h64fast --score tooling/out/h50/fast.json
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

WORK_EDGE = 420
STEP_FRACS = (0.070, 0.035, 0.018, 0.009)


def order_pts(q: np.ndarray) -> np.ndarray:
    """Cyclic order around the centroid, so fillPoly and convexity behave."""
    c = q.mean(axis=0)
    ang = np.arctan2(q[:, 1] - c[1], q[:, 0] - c[0])
    return q[np.argsort(ang)].astype(np.float64)


def quad_mask(q: np.ndarray, shape) -> np.ndarray:
    h, w = shape
    m = np.zeros((h, w), np.uint8)
    cv2.fillPoly(m, [np.round(q).astype(np.int32)], 1)
    return m.astype(bool)


def iou_of(q: np.ndarray, mask: np.ndarray, area: int) -> float:
    qm = quad_mask(q, mask.shape)
    inter = int(np.logical_and(qm, mask).sum())
    union = int(mask.sum()) + int(qm.sum()) - inter
    return inter / union if union else 0.0


def convex(q: np.ndarray) -> bool:
    return bool(cv2.isContourConvex(q.astype(np.float32).reshape(-1, 1, 2)))


def climb(mask: np.ndarray, q0: np.ndarray, w: int, h: int) -> tuple[np.ndarray, float]:
    """Hill-climb a quad's 8 coordinates to maximise IoU with `mask`."""
    q = order_pts(q0.copy())
    best = iou_of(q, mask, 0)
    for frac in STEP_FRACS:
        step = frac * min(w, h)
        for _ in range(80):
            improved = False
            for i in range(4):
                for ax in range(2):
                    for sgn in (1.0, -1.0):
                        cand = q.copy()
                        cand[i, ax] += sgn * step
                        cand[i, 0] = min(max(cand[i, 0], 0.0), w - 1.0)
                        cand[i, 1] = min(max(cand[i, 1], 0.0), h - 1.0)
                        if not convex(cand):
                            continue
                        v = iou_of(cand, mask, 0)
                        if v > best + 1e-7:
                            best, q = v, cand
                            improved = True
            if not improved:
                break
    return q, best


def starts(mask: np.ndarray, det: np.ndarray, w: int, h: int) -> list[np.ndarray]:
    """A few different initial quads, so the search is not stuck in one basin."""
    out = [det]
    ys, xs = np.nonzero(mask)
    if len(ys) >= 4:
        pts = np.stack([xs, ys], 1).astype(np.float32)
        rect = cv2.minAreaRect(pts)
        out.append(cv2.boxPoints(rect).astype(np.float64))
        out.append(np.array([[xs.min(), ys.min()], [xs.max(), ys.min()],
                             [xs.max(), ys.max()], [xs.min(), ys.max()]], np.float64))
    return [o for o in out if o.shape == (4, 2)]


def miss_above_frac(mask: np.ndarray, quad: np.ndarray) -> float:
    """Share of the uncovered mask that lies above the quad - the 'page behind' signature (h62)."""
    qm = quad_mask(quad, mask.shape)
    missed = mask & ~qm
    tot = int(missed.sum())
    if tot < 200:
        return 0.0
    qys = np.nonzero(qm)[0]
    if qys.size == 0:
        return 0.0
    above = int((missed & (np.arange(mask.shape[0])[:, None] < qys.min())).sum())
    return above / tot


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="h64fast")
    ap.add_argument("--set", default="holdout42")
    ap.add_argument("--score", default="tooling/out/h50/fast.json")
    args = ap.parse_args()

    root = Path("app/src/androidTest/assets") / args.set
    masks_dir = Path("tooling/out/h48/masks")
    traces = Path("tooling/out/h39") / args.tag
    scores = {r["image"][:-4]: r
              for r in json.loads(Path(args.score).read_text(encoding="utf-8"))}

    rows = []
    for mp in sorted(masks_dir.glob("*.npy")):
        stem = mp.stem
        tp = traces / stem / "trace.json"
        if not tp.exists():
            continue
        t = json.loads(tp.read_text(encoding="utf-8"))
        if not t.get("quad"):
            continue
        full = np.load(mp).astype(np.uint8)
        h, w = full.shape
        s = WORK_EDGE / max(w, h)
        sw, sh = max(8, int(round(w * s))), max(8, int(round(h * s)))
        mask = cv2.resize(full, (sw, sh), interpolation=cv2.INTER_AREA) > 0
        if mask.sum() < 200:
            continue

        W, H = float(t["image_w"]), float(t["image_h"])
        det = np.array([[d["x"] / W * sw, d["y"] / H * sh] for d in t["quad"]],
                       np.float64)

        best_v, best_q = -1.0, det
        for q0 in starts(mask, det, sw, sh):
            q, v = climb(mask, q0, sw, sh)
            if v > best_v:
                best_v, best_q = v, q

        actual = iou_of(det, mask, 0)
        # solidity / hull rect fill are cheap curvature proxies on the page OUTLINE itself
        cnts, _ = cv2.findContours(mask.astype(np.uint8), cv2.RETR_EXTERNAL,
                                   cv2.CHAIN_APPROX_SIMPLE)
        c = max(cnts, key=cv2.contourArea)
        hull = cv2.convexHull(c)
        hull_area = max(1e-6, float(cv2.contourArea(hull)))
        rect_area = max(1e-6, cv2.minAreaRect(hull)[1][0] * cv2.minAreaRect(hull)[1][1])
        solidity = float(cv2.contourArea(c)) / hull_area
        hull_fill = hull_area / rect_area

        sc = scores.get(stem, {})
        rows.append({
            "stem": stem,
            "verdict": sc.get("verdict"),
            "actual": round(float(actual), 3),
            "ceiling": round(float(best_v), 3),
            "gap": round(float(best_v - actual), 3),
            "solidity": round(solidity, 3),
            "hull_fill": round(hull_fill, 3),
            "referee": miss_above_frac(mask, det) >= 0.55,
        })

    rows.sort(key=lambda r: r["ceiling"])
    print(f"{'photo':22} {'verdict':6} {'actual':>6} {'ceiling':>7} {'gap':>6} "
          f"{'solid':>6} {'hullfil':>7}  note")
    for r in rows:
        note = "referee?" if r["referee"] else ""
        print(f"{r['stem']:22} {str(r['verdict']):6} {r['actual']:6.2f} {r['ceiling']:7.2f} "
              f"{r['gap']:+6.2f} {r['solidity']:6.2f} {r['hull_fill']:7.2f}  {note}")

    def summary(tag, group):
        if not group:
            return
        ce = np.array([r["ceiling"] for r in group])
        ac = np.array([r["actual"] for r in group])
        gp = np.array([r["gap"] for r in group])
        print(f"\n--- {tag} (n={len(group)}) ---")
        print(f"  ceiling   min {ce.min():.2f}  median {np.median(ce):.2f}  max {ce.max():.2f}")
        print(f"  actual    min {ac.min():.2f}  median {np.median(ac):.2f}  max {ac.max():.2f}")
        print(f"  gap       median {np.median(gp):.2f}   photos within 0.05 of ceiling: "
              f"{int((gp <= 0.05).sum())}/{len(group)}")
        print(f"  photos where NO quad can exceed 0.85: {int((ce < 0.85).sum())}")
        print(f"  photos where NO quad can exceed 0.90: {int((ce < 0.90).sum())}")
        print(f"  'locks the page' (IoU>=0.85) actual {int((ac >= 0.85).sum())} vs "
              f"ceiling {int((ce >= 0.85).sum())}")

    summary("all scored photos", rows)
    summary("excluding referee-suspect photos", [r for r in rows if not r["referee"]])

    out = Path("tooling/out/h66")
    out.mkdir(parents=True, exist_ok=True)
    (out / "ceiling.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")
    print(f"\n-> {out / 'ceiling.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
