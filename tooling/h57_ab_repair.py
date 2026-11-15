#!/usr/bin/env python3
"""
H57 - offline A/B: score the PRE-repair quad vs the SHIPPED quad, on the same masks.

Why this is possible without rebuilding anything
------------------------------------------------
The trace stores two quads per photo:
  * `corner_post.quad` - what the corner model returned, BEFORE applyMlsdEvidence;
  * `quad`             - what the detector shipped, after the M-LSD merge-repair.

`dbg_trim.py` showed recall tracking `1 - strip` almost exactly, which means the trim is removing
real page area rather than a facing leaf. Example `20261115_192321`: the pre-repair quad ends at
x=1148, the page ends at x=1064, and the shipped quad ends at x=1000 - so the trim cut 64 px into
the paper, locking a text-block edge.

This script answers, with numbers, whether the repair earns its keep: it scores both quads the
same way (recall/precision/IoU against the SAM target mask) and reports the totals.

Limitation, stated up front: `corner_post` exists only for CORNER_MODEL photos. A
CLASSICAL_FALLBACK photo's pre-repair quad is not recorded, so those photos are scored on the
shipped quad in both arms and are reported separately rather than mixed in.

Usage: python tooling\\h57_ab_repair.py --tag h53spread --set holdout42
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from h50_quad_vs_mask import quad_mask  # noqa: E402

MASKS = Path("tooling/out/h48/masks")
TRACES = Path("tooling/out/h39")
LOCK_IOU = 0.85


def as_norm(quad_json, w: int, h: int) -> np.ndarray:
    return np.array([[d["x"] / w, d["y"] / h] for d in quad_json], np.float64)


def score(q: np.ndarray, target: np.ndarray) -> tuple[float, float, float]:
    m = quad_mask(q, target.shape)
    inter = int(np.logical_and(m, target).sum())
    union = int(np.logical_or(m, target).sum())
    rt = int(target.sum())
    qa = int(m.sum())
    return (inter / rt if rt else 0.0,
            inter / qa if qa else 0.0,
            inter / union if union else 0.0)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="h53spread")
    ap.add_argument("--set", default="holdout42")
    args = ap.parse_args()

    rows = []
    for npy in sorted(MASKS.glob("*.npy")):
        stem = npy.stem
        tp = TRACES / args.tag / stem / "trace.json"
        if not tp.exists():
            continue
        t = json.loads(tp.read_text(encoding="utf-8"))
        if not t.get("quad"):
            continue
        target = np.load(npy).astype(bool)
        w, h = int(t["image_w"]), int(t["image_h"])

        cp = (t.get("corner_post") or {}).get("quad")
        pre = as_norm(cp, w, h) if cp else None
        shipped = as_norm(t["quad"], w, h)

        r_ship = score(shipped, target)
        r_pre = score(pre, target) if pre is not None else r_ship

        # "did the repair change anything", in PIXELS (normalised coords would make a pixel
        # tolerance meaningless - the first version of this used atol=1.0 on 0..1 values and
        # therefore reported every photo as unchanged)
        same = pre is None or np.max(np.abs(pre - shipped) * np.array([w, h])) < 1.5

        rows.append({
            "stem": stem,
            "prov": t.get("provenance"),
            "has_pre": pre is not None,
            "same": same,
            "ship": {"recall": round(r_ship[0], 3), "precision": round(r_ship[1], 3),
                     "iou": round(r_ship[2], 3)},
            "pre": {"recall": round(r_pre[0], 3), "precision": round(r_pre[1], 3),
                    "iou": round(r_pre[2], 3)},
        })

    both = [r for r in rows if r["has_pre"] and not r["same"]]
    print(f"{len(rows)} scored, {len(both)} CORNER_MODEL photos where the repair changed the quad\n")
    print(f"{'photo':22} {'pre rec':>7} {'pre iou':>7} {'ship rec':>8} {'ship iou':>8}  delta")
    for r in sorted(both, key=lambda r: r["pre"]["iou"] - r["ship"]["iou"], reverse=True):
        d = r["pre"]["iou"] - r["ship"]["iou"]
        print(f"{r['stem']:22} {r['pre']['recall']:7.2f} {r['pre']['iou']:7.2f} "
              f"{r['ship']['recall']:8.2f} {r['ship']['iou']:8.2f}  {d:+.2f}")

    def agg(group, key, field):
        return sum(r[key][field] for r in group) / max(1, len(group))

    def locks(group, key):
        return sum(1 for r in group if r[key]["iou"] >= LOCK_IOU)

    print(f"\n--- the {len(both)} photos the repair actually touched ---")
    for key, label in (("pre", "PRE-repair "), ("ship", "SHIPPED    ")):
        print(f"  {label} LOCK {locks(both, key):2}/{len(both)}  "
              f"mean IoU {agg(both, key, 'iou'):.3f}  recall {agg(both, key, 'recall'):.3f}  "
              f"precision {agg(both, key, 'precision'):.3f}")
    better = sum(1 for r in both if r["pre"]["iou"] > r["ship"]["iou"] + 0.02)
    worse = sum(1 for r in both if r["pre"]["iou"] < r["ship"]["iou"] - 0.02)
    print(f"  pre-repair better on {better}, shipped better on {worse}, "
          f"equal on {len(both) - better - worse}")

    print(f"\n--- all {len(rows)} photos (untouched ones count in both arms) ---")
    for key, label in (("pre", "PRE-repair "), ("ship", "SHIPPED    ")):
        print(f"  {label} LOCK {locks(rows, key):2}/{len(rows)}  "
              f"mean IoU {agg(rows, key, 'iou'):.3f}")

    Path("tooling/out/h57").mkdir(parents=True, exist_ok=True)
    (Path("tooling/out/h57") / "ab.json").write_text(json.dumps(rows, indent=1),
                                                     encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
