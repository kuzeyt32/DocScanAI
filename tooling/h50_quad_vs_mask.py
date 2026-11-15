#!/usr/bin/env python3
"""
H50 - score the detector quad against the TARGET PAGE MASK.

Why not quad-vs-quad
--------------------
Two attempts to build a reference failed on this holdout, both for the same reason: the photos
are framed tightly on the book, so the page has little or no margin, and a four-corner pseudo-GT
is only as good as the segmenter that produced it. The frame-box SAM oracle returned the image
border on 39/42 photos; a strict visual audit then over-credited quads because a white page on a
white table hides its own edge (192321 was called LOCK by eye, while its quad covers x 0.39-0.82
of a page that runs x 0.11-0.76).

A segmentation mask does not have that failure mode: it follows the actual sheet, including
curved pages, and it survives a page whose outline is invisible to the eye.

Who decides what the target IS
------------------------------
`h48_pointgrid.py`, once, at mask-build time. It applies the owner's rule (spread when the facing
leaf is >= 25 % of the page being read, single leaf otherwise) and saves exactly the resulting
mask. This script does NOT re-derive it: an earlier version gutter-split the mask again and
scored against the wrong half on 4 photos, producing recall AND precision of 0.00, which cannot
happen for a real overlap and was purely an artefact of the double decision.

Metrics per photo, against the target mask:
    recall    = share of the target page covered by the quad   (low = under-cover)
    precision = share of the quad that is page                 (low = over-cover)
    IoU

Usage:
  python tooling\\h50_quad_vs_mask.py --tag h47holdout --set holdout42
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))

MASKS = Path("tooling/out/h48/masks")
TRACES = Path("tooling/out/h39")
OUT = Path("tooling/out/h50")
POINTGRID = Path("tooling/out/h48/pointgrid.json")

LOCK_IOU = 0.85


def norm_quad(t: dict) -> np.ndarray:
    w, h = float(t["image_w"]), float(t["image_h"])
    return np.array([[d["x"] / w, d["y"] / h] for d in t["quad"]], np.float64)


def quad_mask(quad: np.ndarray, shape) -> np.ndarray:
    """Rasterise a normalised quad (4x2) into a boolean mask."""
    import cv2
    h, w = shape
    q = (quad * np.array([w - 1, h - 1])).astype(np.float32)
    m = np.zeros((h, w), np.uint8)
    cv2.fillPoly(m, [np.round(q).astype(np.int32)], 1)
    return m.astype(bool)


def touches_frame(mask: np.ndarray, tol_frac: float = 0.01) -> bool:
    h, w = mask.shape
    tol = max(1, int(tol_frac * min(w, h)))
    return bool(mask[:tol, :].any() or mask[-tol:, :].any()
                or mask[:, :tol].any() or mask[:, -tol:].any())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="h47holdout")
    ap.add_argument("--set", default="holdout42")
    ap.add_argument("--masks", default=str(MASKS))
    ap.add_argument("--pointgrid", default=str(POINTGRID))
    ap.add_argument("--out", default="SCORE.md")
    ap.add_argument("--label", default="")
    args = ap.parse_args()

    mask_dir = Path(args.masks)
    trace_dir = TRACES / args.tag
    pg = {}
    pgp = Path(args.pointgrid)
    if pgp.exists():
        pg = {r["image"][:-4]: r for r in json.loads(pgp.read_text(encoding="utf-8"))}

    rows, missing = [], []
    for npy in sorted(mask_dir.glob("*.npy")):
        stem = npy.stem
        tp = trace_dir / stem / "trace.json"
        if not tp.exists():
            missing.append((stem, "no detector trace"))
            continue
        t = json.loads(tp.read_text(encoding="utf-8"))
        if not t.get("quad"):
            missing.append((stem, "detector returned no quad"))
            continue
        target = np.load(npy).astype(bool)
        if not target.any():
            missing.append((stem, "empty target mask"))
            continue

        q = quad_mask(norm_quad(t), target.shape)
        inter = int(np.logical_and(q, target).sum())
        union = int(np.logical_or(q, target).sum())
        rt = int(target.sum())
        qa = int(q.sum())

        ref = pg.get(stem, {})
        row = {
            "image": stem + ".jpg",
            "prov": t.get("provenance"),
            "gate": t.get("gate_passed"),
            "spread": bool(ref.get("spread", False)),
            "second_leaf": float(ref.get("second_leaf") or 0.0),
            "target_truncated": touches_frame(target),
            "recall": round(inter / rt, 3) if rt else 0.0,
            "precision": round(inter / qa, 3) if qa else 0.0,
            "iou": round(inter / union, 3) if union else 0.0,
            "target_frac": round(rt / float(target.size), 3),
            "quad_frac": round(qa / float(target.size), 3),
        }
        iou, rec, prec = row["iou"], row["recall"], row["precision"]
        if iou >= LOCK_IOU:
            row["verdict"] = "LOCK"
        elif rec >= LOCK_IOU and prec < LOCK_IOU:
            row["verdict"] = "OVER"
        elif prec >= LOCK_IOU and rec < LOCK_IOU:
            row["verdict"] = "UNDER"
        else:
            row["verdict"] = "PART"
        rows.append(row)

    order = ["LOCK", "UNDER", "OVER", "PART"]
    counts = {k: sum(1 for r in rows if r["verdict"] == k) for k in order}
    n = len(rows)
    lock = counts["LOCK"]
    ious = np.array([r["iou"] for r in rows]) if rows else np.array([0.0])
    recs = np.array([r["recall"] for r in rows]) if rows else np.array([0.0])
    precs = np.array([r["precision"] for r in rows]) if rows else np.array([0.0])

    spreads = sum(1 for r in rows if r["spread"])
    lines = [
        "# Detector quad vs target page mask" + (f" - {args.label}" if args.label else ""),
        "",
        f"{n} scored photos. Detector build as traced in `tooling/out/h39/{args.tag}`.",
        "",
        "**Target rule** (owner, 2026-11-15): both leaves centred -> scan both as one (spread);",
        "single page only when the facing leaf is under ~25 % visible. `h48_pointgrid.py` applies",
        "that rule once when it builds the mask; this score uses that mask unchanged.",
        "",
        "| verdict | photos | meaning |",
        "|---|---|---|",
        f"| LOCK | {counts['LOCK']} | IoU >= {LOCK_IOU} with the page |",
        f"| UNDER | {counts['UNDER']} | covers only part of the page |",
        f"| OVER | {counts['OVER']} | covers the page plus background |",
        f"| PART | {counts['PART']} | neither a clean lock nor a plain under/over |",
        "",
        f"**{lock} / {n} = {100 * lock / max(1, n):.0f} % locked the page.**",
        "",
        f"mean IoU {ious.mean():.3f} | mean recall {recs.mean():.3f} | "
        f"mean precision {precs.mean():.3f}",
        "",
        f"Lock rate at other thresholds, so a single cut-off is not doing the arguing: "
        f"IoU>=0.80 {100 * float((ious >= 0.80).mean()):.0f} %, "
        f"IoU>=0.85 {100 * float((ious >= 0.85).mean()):.0f} %, "
        f"IoU>=0.90 {100 * float((ious >= 0.90).mean()):.0f} %.",
        "",
        f"{spreads} of {n} were scored against a spread (both leaves), the rest against one leaf.",
        "",
        "| photo | prov | gate | target | 2nd | recall | prec | IoU | verdict |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for r in rows:
        lines.append(
            f"| `{r['image'][:-4]}` | {r['prov']} | {'pass' if r['gate'] else 'FAIL'} | "
            f"{'spread' if r['spread'] else 'leaf'} | {r['second_leaf']:.2f} | "
            f"{r['recall']:.2f} | {r['precision']:.2f} | {r['iou']:.2f} | {r['verdict']} |")
    if missing:
        lines += ["", f"## Not scored ({len(missing)} of {len(missing) + n})", ""]
        lines += [f"* `{s}` - {why}" for s, why in missing]
    lines.append("")

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / args.out).write_text("\n".join(lines), encoding="utf-8")
    (OUT / args.out.replace(".md", ".json")).write_text(json.dumps(rows, indent=1),
                                                        encoding="utf-8")
    print("\n".join(lines[:20]))
    print(f"-> {OUT / args.out}")
    if missing:
        print(f"{len(missing)} not scored: {[m[0] for m in missing]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
