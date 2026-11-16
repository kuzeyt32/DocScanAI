#!/usr/bin/env python3
"""
H67b - is the one-leaf problem GENERAL, or specific to the 42 holdout photos?

`h67` found 6 one-leaf locks on holdout42. If the same pattern exists on owner110, the problem is
a general detector weakness and worth solving properly. If it is absent there, then the 6 are a
quirk of this particular set and a rule tuned to them would be fitting to 42 photos.

owner110 has no page masks, only oracle QUADS, so the test uses the oracle quad as the target
region instead of a mask. That is exactly the geometry `h47_score.py` already scores against, so
the numbers stay comparable.

Usage: python tooling\\h67b_oneleaf_owner.py --tag h64owner --set owner110
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from oracle_quality import reason as oracle_reason  # noqa: E402


def order_pts(q: np.ndarray) -> np.ndarray:
    c = q.mean(axis=0)
    return q[np.argsort(np.arctan2(q[:, 1] - c[1], q[:, 0] - c[0]))].astype(np.float64)


def quad_mask(q: np.ndarray, shape) -> np.ndarray:
    m = np.zeros(shape, np.uint8)
    cv2.fillPoly(m, [np.round(q).astype(np.int32)], 1)
    return m.astype(bool)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="h64owner")
    ap.add_argument("--set", default="owner110")
    args = ap.parse_args()

    oracle_path = Path("app/src/androidTest/assets") / args.set / "oracle_manifest.json"
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
        w, h = int(it["width"]), int(it["height"])
        tgt = order_pts(np.array([it["corners_px"][k] for k in ("tl", "tr", "br", "bl")],
                                 np.float64))
        det = order_pts(np.array([[p["x"], p["y"]] for p in t["quad"]], np.float64))
        qm = quad_mask(det, (h, w))
        tm = quad_mask(tgt, (h, w))
        qa, ta = int(qm.sum()), int(tm.sum())
        if qa < 500 or ta < 500:
            continue
        inter = int(np.logical_and(qm, tm).sum())
        prec = inter / qa
        ratio = ta / qa
        uncovered = (tm & ~qm).astype(np.uint8)
        n, lab, stats, _ = cv2.connectedComponentsWithStats(uncovered, 8)
        blob = int(stats[1:, cv2.CC_STAT_AREA].max()) if n > 1 else 0
        one_leaf = bool(prec >= 0.95 and 1.4 <= ratio <= 2.8 and blob >= 0.45 * qa)
        iou = inter / (qa + ta - inter)
        rows.append({"stem": name[:-4], "iou": round(iou, 3), "precision": round(prec, 3),
                     "area_ratio": round(ratio, 2), "one_leaf": one_leaf})

    cand = [r for r in rows if r["one_leaf"]]
    print(f"{len(rows)} scorable owner110 photos")
    print(f"{len(cand)} look like a ONE LEAF / sub-sheet lock "
          f"({100 * len(cand) / max(1, len(rows)):.0f} %)")
    print(f"\n{'photo':22} {'iou':>5} {'prec':>5} {'tgt/quad':>8}")
    for r in sorted(cand, key=lambda r: r["iou"]):
        print(f"{r['stem']:22} {r['iou']:5.2f} {r['precision']:5.2f} {r['area_ratio']:8.2f}")

    short = [r for r in rows if r["iou"] < 0.85]
    print(f"\n{len(short)} of {len(rows)} are below IoU 0.85; "
          f"{sum(1 for r in short if r['one_leaf'])} of those are one-leaf locks")
    out = Path("tooling/out/h67")
    out.mkdir(parents=True, exist_ok=True)
    (out / "oneleaf_owner.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
