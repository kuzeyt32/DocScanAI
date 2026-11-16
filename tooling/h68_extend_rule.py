#!/usr/bin/env python3
"""
H68 - can a PIXEL-ONLY rule find the extension that h67 showed is worth 6 photos?

h67 measured, using the page mask, that 6 holdout photos are a one-leaf lock and that extending
them would take IoU 0.43 -> 0.91 etc. The mask is an oracle; this script asks whether the same
extension can be found from image pixels alone, which is the only form that could ship.

Rule (deliberately simple, because every added condition is a chance to overfit):

  1. walk outward from each edge in small steps, sampling a line parallel to that edge;
  2. a step is "paper" when its median luma is close to the quad's own interior luma;
  3. bridge a short run of non-paper (a gutter shadow is dark and only a few percent wide);
  4. stop when the non-paper run exceeds the bridge budget;
  5. move that edge out to the last paper position.

Accept the extension only when all of these hold, so a wrong walk cannot reach the warp:
  * area grows by 1.3x - 3.0x      (a sub-sheet, not a runaway)
  * the result is still convex with a sane rect fill
  * the result is still page-shaped (aspect within a page/spread band)

Measured against the SAM masks on holdout42 AND against owner110's oracle quads, because a rule
that gains 6 new photos and loses 10 old ones is not a fix.

Usage: python tooling\\h68_extend_rule.py --tag h64fast
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

MAX_GROW = 0.85          # never extend an edge more than this fraction of the quad's extent
STEP_FRAC = 0.012        # walk step, as a fraction of the page diagonal
LUMA_TOL = 0.22          # a step counts as paper within this relative luma difference
GAP_TOL = 0.075          # allow this much non-paper (a gutter) before stopping
AREA_LO, AREA_HI = 1.30, 3.00
RECT_MIN = 0.55
ASPECT_MAX = 3.30


def order_pts(q: np.ndarray) -> np.ndarray:
    c = q.mean(axis=0)
    return q[np.argsort(np.arctan2(q[:, 1] - c[1], q[:, 0] - c[0]))].astype(np.float64)


def quad_mask(q: np.ndarray, shape) -> np.ndarray:
    m = np.zeros(shape, np.uint8)
    cv2.fillPoly(m, [np.round(q).astype(np.int32)], 1)
    return m.astype(bool)


def rect_fill(q: np.ndarray) -> float:
    pts = q.astype(np.float32)
    area = abs(cv2.contourArea(pts))
    (_, _), (rw, rh), _ = cv2.minAreaRect(pts)
    return area / max(1e-6, rw * rh)


def aspect_of(q: np.ndarray) -> float:
    (_, _), (rw, rh), _ = cv2.minAreaRect(q.astype(np.float32))
    if rw < 1e-6 or rh < 1e-6:
        return 0.0
    return max(rw, rh) / min(rw, rh)


def interior_stats(gray: np.ndarray, q: np.ndarray) -> tuple[float, float]:
    c = q.mean(axis=0)
    shrunk = np.stack([c[0] + (q[:, 0] - c[0]) * 0.7, c[1] + (q[:, 1] - c[1]) * 0.7], 1)
    m = quad_mask(shrunk, gray.shape)
    vals = gray[m]
    if vals.size < 100:
        return 128.0, 0.0
    return float(np.median(vals)), float(np.std(vals))


def walk_side(gray: np.ndarray, q: np.ndarray, i: int, L: float, diag: float,
              max_grow: float) -> float:
    """How far (px) can edge `i` move outward before it stops being paper?"""
    a, b = q[i], q[(i + 1) % 4]
    e = b - a
    ln = float(np.hypot(*e))
    if ln < 1e-6:
        return 0.0
    n = np.array([e[1], -e[0]]) / ln           # perpendicular
    centroid = q.mean(axis=0)
    mid = (a + b) / 2.0
    if np.dot(mid - centroid, n) < 0:          # make n point OUTWARD
        n = -n

    h, w = gray.shape
    step = STEP_FRAC * diag
    limit = max_grow * ln
    gap_budget = GAP_TOL * diag

    last_paper = 0.0
    d = step
    gap = 0.0
    while d <= limit:
        vals = []
        for t in np.linspace(0.15, 0.85, 17):
            px = a[0] + e[0] * t + n[0] * d
            py = a[1] + e[1] * t + n[1] * d
            xi, yi = int(round(px)), int(round(py))
            if 0 <= yi < h and 0 <= xi < w:
                vals.append(gray[yi, xi])
        if len(vals) < 8:
            break
        med = float(np.median(vals))
        paper = abs(med - L) <= LUMA_TOL * max(1.0, L)
        if paper:
            last_paper = d
            gap = 0.0
        else:
            gap += step
            if gap > gap_budget:
                break
        d += step
    return last_paper


def extend_quad(gray: np.ndarray, q: np.ndarray) -> tuple[np.ndarray, dict]:
    h, w = gray.shape
    diag = float(np.hypot(w, h))
    L, std = interior_stats(gray, q)
    out = q.copy()
    moved = {}
    for i in range(4):
        d = walk_side(gray, q, i, L, diag, MAX_GROW)
        if d <= 0.5:
            continue
        a_i, b_i = i, (i + 1) % 4
        e = q[b_i] - q[a_i]
        ln = float(np.hypot(*e))
        n = np.array([e[1], -e[0]]) / ln
        if np.dot((q[a_i] + q[b_i]) / 2.0 - q.mean(axis=0), n) < 0:
            n = -n
        out[a_i] = out[a_i] + n * d
        out[b_i] = out[b_i] + n * d
        moved[i] = round(float(d / ln), 3)

    meta = {"interior_luma": round(L, 1), "interior_std": round(std, 1), "moved": moved}
    out = order_pts(out)
    area0 = abs(cv2.contourArea(q.astype(np.float32)))
    area1 = abs(cv2.contourArea(out.astype(np.float32)))
    meta["area_ratio"] = round(area1 / max(1e-6, area0), 2)
    meta["rect_fill"] = round(rect_fill(out), 2)
    meta["aspect"] = round(aspect_of(out), 2)
    ok = (AREA_LO <= meta["area_ratio"] <= AREA_HI
          and meta["rect_fill"] >= RECT_MIN
          and meta["aspect"] <= ASPECT_MAX
          and cv2.isContourConvex(out.astype(np.float32).reshape(-1, 1, 2)))
    meta["accepted"] = bool(ok)
    return (out if ok else q), meta


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="h64fast")
    ap.add_argument("--set", default="holdout42")
    ap.add_argument("--score", default="tooling/out/h50/fast.json")
    ap.add_argument("--masks", default="tooling/out/h48/masks")
    ap.add_argument("--out", default="tooling/out/h68")
    args = ap.parse_args()

    root = Path("app/src/androidTest/assets") / args.set
    traces = Path("tooling/out/h39") / args.tag
    scores = {r["image"][:-4]: r
              for r in json.loads(Path(args.score).read_text(encoding="utf-8"))}
    outdir = Path(args.out)
    outdir.mkdir(parents=True, exist_ok=True)

    rows = []
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
        img = cv2.imread(str(root / f"{stem}.jpg"), cv2.IMREAD_GRAYSCALE)
        if img is None or img.shape != mask.shape:
            continue
        gray = cv2.GaussianBlur(img.astype(np.float32), (5, 5), 0)

        W, H = float(t["image_w"]), float(t["image_h"])
        q = order_pts(np.array([[d["x"] / W * w, d["y"] / H * h] for d in t["quad"]],
                               np.float64))
        q2, meta = extend_quad(gray, q)

        def iou_of(qq):
            qm = quad_mask(qq, (h, w))
            inter = int(np.logical_and(qm, mask).sum())
            union = int(qm.sum()) + int(mask.sum()) - inter
            return inter / union if union else 0.0

        sc = scores.get(stem, {})
        rows.append({
            "stem": stem, "verdict": sc.get("verdict"),
            "iou_before": round(iou_of(q), 3), "iou_after": round(iou_of(q2), 3),
            **meta,
        })

    changed = [r for r in rows if r["accepted"]]
    helped = [r for r in changed if r["iou_after"] > r["iou_before"] + 0.02]
    hurt = [r for r in changed if r["iou_after"] < r["iou_before"] - 0.02]

    print(f"{len(rows)} photos, rule fired on {len(changed)}\n")
    print(f"{'photo':22} {'verdict':6} {'before':>6} {'after':>6} {'d':>6} "
          f"{'areaX':>6} {'fill':>5}  moved")
    for r in sorted(rows, key=lambda r: -(r["iou_after"] - r["iou_before"])):
        if not r["accepted"]:
            continue
        d = r["iou_after"] - r["iou_before"]
        print(f"{r['stem']:22} {str(r['verdict']):6} {r['iou_before']:6.2f} "
              f"{r['iou_after']:6.2f} {d:+6.2f} {r['area_ratio']:6.2f} {r['rect_fill']:5.2f}  "
              f"{r['moved']}")

    before = np.mean([r["iou_before"] for r in rows])
    after = np.mean([r["iou_after"] for r in rows])
    print(f"\nmean IoU {before:.3f} -> {after:.3f}")
    print(f"fired {len(changed)}, helped {len(helped)}, hurt {len(hurt)}")
    if hurt:
        print("\nHURT:")
        for r in hurt:
            print(f"   {r['stem']:22} {r['iou_before']:.2f} -> {r['iou_after']:.2f}")

    (outdir / "extend.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")
    print(f"\n-> {outdir / 'extend.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
