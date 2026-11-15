#!/usr/bin/env python3
"""
H58 - what does the M-LSD repair actually cut off: a facing leaf, or the page's own margin?

The A/B (`h57`/`h57b`) shows the repair is net-harmful on both sets: on the holdout it made
20 of 20 touched quads worse, and on owner110 it changed 4 correct pages into "shrunk".

But the repair exists for a real failure - a quad that absorbed the facing page. The question is
whether the two cases can be told apart from pixels. The proposed discriminator:

    the discarded strip
      * printed (ink / edge density like a page) -> an absorbed FACING LEAF -> trim is right
      * blank (near-uniform paper)               -> the page's own MARGIN   -> trim is wrong

This measures that, per photo, and joins it to the A/B outcome so a threshold can be chosen from
data. Run after h57 (holdout masks) and h57b (owner110 oracle).

Usage: python tooling\\h58_trimclass.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).parent))

SETS = [
    # (label, trace tag, asset set, A/B json)
    ("holdout42", "h53spread", "holdout42", "tooling/out/h57/ab.json"),
    ("owner110", "h56owner", "owner110", "tooling/out/h57/ab_owner.json"),
]


def poly(quad) -> np.ndarray:
    return np.array([[p["x"], p["y"]] for p in quad], np.float32)


def region_stats(gray: np.ndarray, edges: np.ndarray, mask: np.ndarray) -> dict:
    n = int(mask.sum())
    if n < 200:
        return {"px": n}
    vals = gray[mask]
    return {
        "px": n,
        "mean": float(vals.mean()),
        "std": float(vals.std()),
        "ink": float(edges[mask].mean()),
    }


def main() -> int:
    rows = []
    for label, tag, set_name, ab_path in SETS:
        abp = Path(ab_path)
        if not abp.exists():
            print(f"skip {label}: missing {abp}")
            continue
        ab = {r["stem"]: r for r in json.loads(abp.read_text(encoding="utf-8"))}
        root = Path("app/src/androidTest/assets") / set_name
        trace_dir = Path("tooling/out/h39") / tag

        for p in sorted(trace_dir.glob("*/trace.json")):
            stem = p.parent.name
            if stem not in ab:
                continue
            t = json.loads(p.read_text(encoding="utf-8"))
            cp = (t.get("corner_post") or {}).get("quad")
            if not cp or not t.get("quad"):
                continue
            pre = poly(cp)
            ship = poly(t["quad"])
            if np.max(np.abs(pre - ship)) < 1.5:
                continue

            img = cv2.imread(str(root / f"{stem}.jpg"), cv2.IMREAD_GRAYSCALE)
            if img is None:
                continue
            h, w = img.shape
            # traces are in the photo's own pixel space
            if (int(t["image_w"]), int(t["image_h"])) != (w, h):
                pre = pre * np.array([w / t["image_w"], h / t["image_h"]], np.float32)
                ship = ship * np.array([w / t["image_w"], h / t["image_h"]], np.float32)

            m_pre = np.zeros((h, w), np.uint8)
            m_ship = np.zeros((h, w), np.uint8)
            cv2.fillPoly(m_pre, [np.round(pre).astype(np.int32)], 1)
            cv2.fillPoly(m_ship, [np.round(ship).astype(np.int32)], 1)
            discarded = (m_pre == 1) & (m_ship == 0)
            kept = m_ship == 1
            if int(discarded.sum()) < 500:
                continue

            gray = cv2.GaussianBlur(img.astype(np.float32), (5, 5), 0)
            e = cv2.Canny(gray.astype(np.uint8), 40, 120) > 0
            e = cv2.dilate(e.astype(np.uint8), np.ones((3, 3), np.uint8)).astype(bool)

            d = region_stats(gray, e, discarded)
            k = region_stats(gray, e, kept)
            if "ink" not in d or "ink" not in k:
                continue

            r = ab[stem]
            if "pre" in r and isinstance(r["pre"], dict) and "iou" in r["pre"]:
                iou_pre, iou_ship = r["pre"]["iou"], r["ship"]["iou"]
            else:
                iou_pre, iou_ship = 0.0, 0.0
            rows.append({
                "set": label,
                "stem": stem,
                "iou_pre": round(iou_pre, 3),
                "iou_ship": round(iou_ship, 3),
                "delta": round(iou_pre - iou_ship, 3),
                "disc_ink": round(d["ink"], 4),
                "keep_ink": round(k["ink"], 4),
                "ink_ratio": round(d["ink"] / max(1e-6, k["ink"]), 3),
                "disc_std": round(d["std"], 1),
                "keep_std": round(k["std"], 1),
                "disc_px": d["px"],
            })

    rows.sort(key=lambda r: r["ink_ratio"])
    print(f"{len(rows)} photos where the repair trimmed something\n")
    print(f"{'set':10} {'photo':22} {'dIou':>6} {'discInk':>8} {'keepInk':>8} {'ratio':>6} "
          f"{'dStd':>6} {'kStd':>6}")
    for r in rows:
        print(f"{r['set']:10} {r['stem']:22} {r['delta']:+6.2f} {r['disc_ink']:8.4f} "
              f"{r['keep_ink']:8.4f} {r['ink_ratio']:6.2f} {r['disc_std']:6.1f} "
              f"{r['keep_std']:6.1f}")

    # Can a threshold on ink_ratio tell "trim helped" from "trim hurt"?
    print("\nthreshold scan: trim only when the discarded strip is at least this inky")
    print(f"{'ink_ratio>=':>12} {'n applied':>9} {'helped':>7} {'hurt':>6} {'net dIou':>9}")
    for thr in (0.0, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.2):
        applied = [r for r in rows if r["ink_ratio"] >= thr]
        helped = sum(1 for r in applied if r["delta"] > 0.02)
        hurt = sum(1 for r in applied if r["delta"] < -0.02)
        net = sum(r["delta"] for r in applied)
        print(f"{thr:12.1f} {len(applied):9d} {helped:7d} {hurt:6d} {net:+9.2f}")

    out = Path("tooling/out/h58")
    out.mkdir(parents=True, exist_ok=True)
    (out / "trimclass.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
