#!/usr/bin/env python3
"""
H48d - SAM reference by POINT prompts instead of one frame-sized box.

Why this exists
---------------
`h36_sam.run_one` prompts SAM with a box covering the whole frame. On owner110 that worked
because the book sat on a table with margin around it. The holdout photos are framed tightly on
the book, so the box prompt covers the entire scene and SAM returns a sparse scribble of bright
paper-ish pixels across the whole frame (measured: largest component bbox = 1400x1046 on a
1400x1050 photo, mask fill only 28 % of that box). Every oracle quad then came back as the image
border.

Point prompts do not have that failure mode: seed many points, keep the masks that actually look
like a sheet, and dedupe. This is the standard SAM automatic-mask-generation loop, restricted to
a sane area band.

Per photo:
  1. one image embedding (the expensive part);
  2. a grid of point prompts, each decoded independently (cheap);
  3. candidate kept when area is 4-85 % of the frame and the mask is sheet-like
     (rectangularity, convexity, paper ring-contrast);
  4. dedupe near-identical masks, rank, and draw the top three on an overlay.

Usage:
  python tooling\\h48_pointgrid.py --set holdout42 --images 20261115_192319,20261115_192403
  python tooling\\h48_pointgrid.py --set holdout42 --all      # every photo, overlays for each
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from h36_sam import SamOracle, mask_contour, min_area_rect, paper_score, quad_from_mask  # noqa: E402

OUT = Path("tooling/out/h48")

MIN_AREA = 0.04
MAX_AREA = 0.85


def decode_point(oracle: SamOracle, emb, pt_xy, wh) -> np.ndarray:
    """masks + iou for a single positive point prompt, in ORIGINAL image pixels."""
    w, h = wh
    sx, sy = (1024.0 / w, 1024.0 / h) if oracle.resize == "squash" else \
             (1024.0 / max(w, h),) * 2
    feed = {
        "input_points": np.asarray([[[[pt_xy[0] * sx, pt_xy[1] * sy]]]], np.float32),
        "input_labels": np.ones((1, 1, 1), np.int64),
        "input_boxes": np.zeros((1, 0, 4), np.float32),
        "image_embeddings.0": emb[0],
        "image_embeddings.1": emb[1],
        "image_embeddings.2": emb[2],
    }
    iou, masks, _ = oracle.dec.run(["iou_scores", "pred_masks", "object_score_logits"], feed)
    return iou[0][0], masks[0][0]  # (3,) scores, (3, 256, 256) candidates


def fill_holes(mask: np.ndarray) -> np.ndarray:
    """Close interior holes (print, stickers, shadows) before judging sheet shape.

    A page mask that keeps every dark letter as a hole has low rectangularity even when its
    outline is a perfect sheet, so shape is judged on the filled mask.
    """
    m = mask.astype(np.uint8)
    h, w = m.shape
    ff = m.copy()
    canvas = np.zeros((h + 2, w + 2), np.uint8)
    cv2.floodFill(ff, canvas, (0, 0), 1)
    holes = ff == 0
    return (m.astype(bool) | holes)


def shape_stats(mask: np.ndarray, gray: np.ndarray) -> dict:
    area = float(mask.sum())
    filled = fill_holes(mask)
    contour = mask_contour(filled)
    if len(contour) < 3:
        return {"area": 0.0}
    rect = min_area_rect(contour)
    if rect is None:
        return {"area": 0.0}
    e0 = np.linalg.norm(rect[1] - rect[0])
    e1 = np.linalg.norm(rect[3] - rect[0])
    rect_area = max(1e-6, e0 * e1)
    hull = cv2.convexHull(contour.astype(np.float32))
    hull_area = max(1e-6, float(cv2.contourArea(hull)))
    return {
        "area": area,
        "rect_area": float(rect_area),
        "rectangularity": float(filled.sum()) / rect_area,
        "convexity": float(filled.sum()) / hull_area,
        "aspect": float(max(e0, e1) / max(1e-6, min(e0, e1))),
        "paper": float(paper_score(filled, gray)),
        "rect_quad": np.asarray(rect, np.float64),
    }


def point_in_quad(px: float, py: float, quad: np.ndarray) -> bool:
    """Even-odd ray cast."""
    inside = False
    n = len(quad)
    for i in range(n):
        x1, y1 = quad[i]
        x2, y2 = quad[(i + 1) % n]
        if (y1 > py) != (y2 > py):
            xin = x1 + (py - y1) * (x2 - x1) / (y2 - y1)
            if px < xin:
                inside = not inside
    return inside


def pad(mask: np.ndarray, grow: int = 8) -> np.ndarray:
    k = np.ones((2 * grow + 1, 2 * grow + 1), np.uint8)
    return cv2.dilate(mask.astype(np.uint8), k).astype(bool)


def iou(a: np.ndarray, b: np.ndarray) -> float:
    inter = np.logical_and(a, b).sum()
    union = np.logical_or(a, b).sum()
    return float(inter / union) if union else 0.0


def analyse_photo(oracle: SamOracle, path: Path, grid: int, overlay: bool,
                  debug: bool = False) -> dict:
    pil = Image.open(path)
    w, h = pil.size
    gray = np.asarray(pil.convert("L"), dtype=np.float32)
    emb, wh = oracle.encode(pil)

    cands = []
    for gy in np.linspace(0.12, 0.88, grid):
        for gx in np.linspace(0.12, 0.88, grid):
            scores, masks = decode_point(oracle, emb, (gx * w, gy * h), wh)
            k = int(np.argmax(scores))
            m = oracle._mask_to_image(masks[k] > 0.0, wh)
            frac = float(m.sum()) / (w * h)
            if not (MIN_AREA <= frac <= MAX_AREA):
                continue
            st = shape_stats(m, gray)
            if not st.get("area"):
                continue
            st.update({"score": float(scores[k]), "frac": frac})
            if any(iou(m, c["mask"]) > 0.80 for c in cands):
                continue
            st["mask"] = m
            cands.append(st)

    # The owner's target, stated mechanically: the sheet that contains the frame centre, is
    # fully inside the frame, and is the largest. Colour-agnostic on purpose - one holdout page
    # is a green workbook cover, which the old paper-brightness filter rejected outright.
    cx, cy = w / 2.0, h / 2.0
    tol = 0.015 * min(w, h)
    for c in cands:
        q = c["rect_quad"]
        c["centre"] = point_in_quad(cx, cy, q)
        c["inside"] = bool(np.all(q[:, 0] >= -tol) and np.all(q[:, 0] <= w - 1 + tol)
                           and np.all(q[:, 1] >= -tol) and np.all(q[:, 1] <= h - 1 + tol))

    keep = [c for c in cands
            if c["rectangularity"] >= 0.65 and c["convexity"] >= 0.85]
    pool = [c for c in keep if c["centre"] and c["inside"]]
    if not pool:
        pool = [c for c in keep if c["inside"]] or [c for c in keep if c["centre"]] or keep
    pool.sort(key=lambda c: -c["area"])

    quad, meta = (None, {})
    if pool:
        best = pool[0]
        quad, meta = quad_from_mask(best["mask"], pil)

    if overlay:
        img = cv2.imread(str(path), cv2.IMREAD_COLOR)
        colours = [(0, 220, 0), (0, 160, 255), (255, 120, 0)]
        for c, col in zip(pool[:3], colours):
            q, _ = quad_from_mask(c["mask"], pil)
            if q is None:
                continue
            cv2.polylines(img, [np.array([[int(x), int(y)] for x, y in q], np.int32)],
                          True, col, 3)
        if quad is not None:
            cv2.polylines(img, [np.array([[int(x), int(y)] for x, y in quad], np.int32)],
                          True, (0, 0, 255), 2)
        cv2.putText(img, f"{path.stem}  cands={len(cands)} pool={len(pool)}",
                    (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2, cv2.LINE_AA)
        OUT.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(OUT / f"pg_{path.stem}.png"), img)

    top = [{k: c[k] for k in ("frac", "aspect", "rectangularity", "convexity", "paper",
                              "score", "centre", "inside")}
           for c in pool[:5]]
    if debug:
        return {"image": path.name, "width": w, "height": h, "candidates": len(cands),
                "sheet_like": len(keep), "top": top,
                "all": [{k: c[k] for k in ("frac", "aspect", "rectangularity", "convexity",
                                           "paper", "score", "centre", "inside")}
                        for c in cands],
                "quad": [[round(float(x), 1), round(float(y), 1)] for x, y in quad]
                if quad is not None else None, "meta": meta}
    return {"image": path.name, "width": w, "height": h, "candidates": len(cands),
            "sheet_like": len(keep), "top": top,
            "quad": [[round(float(x), 1), round(float(y), 1)] for x, y in quad]
            if quad is not None else None, "meta": meta}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", default="holdout42")
    ap.add_argument("--images", default="")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--grid", type=int, default=6)
    ap.add_argument("--no-overlay", action="store_true")
    ap.add_argument("--debug", action="store_true", help="print every candidate, not just the pool")
    ap.add_argument("--out", default="pointgrid.json")
    args = ap.parse_args()

    root = Path("app/src/androidTest/assets") / args.set
    if args.all:
        names = sorted(p.name for p in root.glob("*.jpg"))
    else:
        names = [n.strip() if n.strip().endswith(".jpg") else n.strip() + ".jpg"
                 for n in args.images.split(",") if n.strip()]

    oracle = SamOracle()
    print(f"providers: {oracle.providers}, grid {args.grid}x{args.grid}")
    rows = []
    for i, name in enumerate(names, 1):
        t0 = time.time()
        rec = analyse_photo(oracle, root / name, args.grid, not args.no_overlay, args.debug)
        rec["ms"] = int((time.time() - t0) * 1000)
        rows.append(rec)
        t = rec["top"][0] if rec["top"] else {}
        print(f"[{i}/{len(names)}] {name}: cands={rec['candidates']} sheet={rec['sheet_like']} "
              f"best area={t.get('frac', 0):.2f} aspect={t.get('aspect', 0):.2f} "
              f"rect={t.get('rectangularity', 0):.2f} ctr={t.get('centre')} "
              f"in={t.get('inside')} ({rec['ms']} ms)", flush=True)

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / args.out).write_text(json.dumps(rows, indent=1), encoding="utf-8")
    print(f"-> {OUT / args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
