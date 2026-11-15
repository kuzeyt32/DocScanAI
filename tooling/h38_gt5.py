#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H38 — SAM ground truth for the 5 new owner photos (point prompts + ink-aware selection).

Why not the H36 full-frame protocol: on these photos the TABLE is as bright and flat as the
page, so `best_mask`'s "largest paper-region" rule selects the background (measured: all 5
quads came out near-full-frame, see tooling/out/h38/mask_cands/). This script prompts SAM
with a 3x3 positive-point grid (the page always contains interior points) and selects the
most PAGE-LIKE candidate instead:

  1. area in [0.04, 0.90] of the frame;
  2. NOT frame-hugging (bbox reaches all 4 borders -> full-frame / background rejected);
  3. ink in band: dark-pixel fraction inside the mask (text/figures on the page; an empty
     table mask has ~0) within [0.015, 0.25];
  4. rectangularity area/minAreaRectArea >= 0.50;
  5. score = inkBand * rect * sizeScore, sizeScore = min(1, area/0.20) (page-sized wins
     over inner text blocks; a whole spread is bigger still and wins when present).

Then quad = min-area-rect + per-corner refinement (the same h36_sam.quad_from_mask used by
the validated SAM oracle protocol). The result replaces the (bad) SAM entries in
sam_gt_new5.json + the 3 oracle manifests this subtask owns for the new-5 photos.

Usage:
  python tooling\\h38_gt5.py
"""

from __future__ import annotations

import json
import math
import os
import sys
import time

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import h36_sam as SAM  # noqa: E402

OUT38 = os.path.join(HERE, "out", "h38")
ASSETS = os.path.join(REPO, "app", "src", "androidTest", "assets", "owner110")
ORACLE = os.path.join(ASSETS, "oracle_manifest.json")
GT_ASSET = os.path.join(HERE, "out", "h35", "pseudo_gt_asset.json")
GT_RAW = os.path.join(HERE, "out", "h34", "pseudo_gt_owner110.json")
CORPUS = os.path.join(REPO, "corpus_owner")

NAMES = ["20261025_130818.jpg", "20261025_130821.jpg", "20261025_131201.jpg",
         "20261025_131246.jpg", "20261025_131249.jpg"]
GRID = (0.30, 0.50, 0.70)  # 9 positive points, fractions of w/h
AREA_LO, AREA_HI = 0.04, 0.90
INK_LO, INK_HI = 0.015, 0.25
RECT_MIN = 0.50
HUG_TOL = 0.02
CORNER_KEYS = ("tl", "tr", "br", "bl")


def hb(msg):
    line = "[%s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg)
    with open(os.path.join(OUT38, "HEARTBEAT.txt"), "a", encoding="utf-8") as fh:
        fh.write(line + "\n")
    print(line, flush=True)


def decode_points(oracle, emb, points, wh):
    """Same feed as SamOracle.decode_box: positive point prompts + the full-frame box.

    The box is kept (not zeroed) so the prompt encoder never sees a degenerate box;
    the interior points carry the page selection.
    """
    w, h = wh
    if oracle.resize == "squash":
        sx, sy = 1024.0 / w, 1024.0 / h
    else:
        sx = sy = 1024.0 / max(w, h)
    # The pinned decoder declares points_dim=1: the prompt axis is the BATCH, i.e. each
    # prompt is one batch row [B, 1, 1, 2] / [B, 1, 1], and pred_masks comes back [B, 3, ...].
    # The pinned decoder declares points_dim=1: the prompt axis is the BATCH, i.e. each
    # prompt is one batch row [1, 1, 1, 2] / [1, 1, 1]. DML fails on a multi-row batch
    # (Concat error), so prompts are decoded ONE AT A TIME and stacked.
    bx = [0.0, 0.0, float(w) * sx, float(h) * sy]
    rows = []
    for x, y in points:
        feed = {
            "input_points": np.asarray([[[[float(x) * sx, float(y) * sy]]]], dtype=np.float32),
            "input_labels": np.asarray([[[1]]], dtype=np.int64),
            "input_boxes": np.asarray([[bx]], dtype=np.float32),
            "image_embeddings.0": emb[0],
            "image_embeddings.1": emb[1],
            "image_embeddings.2": emb[2],
        }
        iou, masks, obj = oracle.dec.run(
            ["iou_scores", "pred_masks", "object_score_logits"], feed)
        m = masks[0]  # [M=1, 3, 256, 256]
        rows.append(m[0] if m.ndim == 4 else m)  # [3, 256, 256]
    return np.stack(rows, axis=0)  # [B, 3, 256, 256] — one row per point prompt


def quad_area(pts):
    a = 0.0
    for i in range(4):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % 4]
        a += x1 * y2 - x2 * y1
    return abs(a) / 2.0


def score_mask(mask, gray, wh):
    """(score, meta) or (None, meta) when the candidate is not page-like."""
    w, h = wh
    frame = float(w * h)
    area = float(mask.sum()) / frame
    meta = {"area": round(area, 4)}
    if not (AREA_LO <= area <= AREA_HI):
        meta["reject"] = "area"
        return None, meta
    ys, xs = np.nonzero(mask)
    x0, x1, y0, y1 = float(xs.min()), float(xs.max()), float(ys.min()), float(ys.max())
    hug = (x0 <= HUG_TOL * w and x1 >= w - 1 - HUG_TOL * w
           and y0 <= HUG_TOL * h and y1 >= h - 1 - HUG_TOL * h)
    if hug:
        meta["reject"] = "frame-hug"
        return None, meta

    inside = gray[mask]
    if inside.size < 200:
        meta["reject"] = "tiny"
        return None, meta
    p75 = float(np.percentile(inside, 75))
    ink = float((inside < p75 - 25.0).mean())
    meta["ink"] = round(ink, 4)
    if not (INK_LO <= ink <= INK_HI):
        meta["reject"] = "ink-band"
        return None, meta

    contour = SAM.mask_contour(mask)
    rect = SAM.min_area_rect(contour) if len(contour) >= 8 else None
    if rect is None:
        meta["reject"] = "no-rect"
        return None, meta
    rect_area = quad_area([(float(p[0]), float(p[1])) for p in rect])
    fill = (area * frame) / rect_area if rect_area > 0 else 0.0
    meta["rect_fill"] = round(fill, 3)
    if fill < RECT_MIN:
        meta["reject"] = "rect-fill"
        return None, meta

    ink_band = 1.0 - min(1.0, abs(ink - 0.06) / 0.12)
    size_score = min(1.0, area / 0.20)
    score = ink_band * fill * size_score
    meta["score"] = round(score, 4)
    return score, meta


def main():
    os.makedirs(OUT38, exist_ok=True)
    oracle = SAM.SamOracle(resize="squash")
    hb("SAM providers: %s" % (oracle.providers,))

    evidence = []
    for name in NAMES:
        path = os.path.join(ASSETS, name)
        pil = Image.open(path)
        w, h = pil.size
        wh = (w, h)
        gray = np.asarray(pil.convert("L"), dtype=np.float32)
        emb, _ = oracle.encode(pil)

        points = [(fx * w, fy * h) for fy in GRID for fx in GRID]
        logits = decode_points(oracle, emb, points, wh)  # [P, 3, 256, 256]

        best = None
        cands = []
        for pi in range(logits.shape[0]):
            for k in range(3):
                m256 = logits[pi, k] > 0.0
                m = oracle._mask_to_image(m256, wh)
                sc, meta = score_mask(m, gray, wh)
                meta["prompt"] = pi
                cands.append(meta)
                if sc is not None and (best is None or sc > best[0]):
                    best = (sc, m, meta)
        if best is None:
            hb("ERROR %s: NO page-like mask among %d candidates" % (name, len(cands)))
            evidence.append({"image": name, "ok": False, "candidates": cands})
            continue

        sc, m, meta = best
        quad, qmeta = SAM.quad_from_mask(m, pil)
        if quad is None:
            hb("ERROR %s: quad_from_mask failed" % name)
            evidence.append({"image": name, "ok": False, "candidates": cands})
            continue

        px = {k: [round(float(quad[i][0]), 1), round(float(quad[i][1]), 1)]
              for i, k in enumerate(CORNER_KEYS)}
        norm = {k: [round(float(quad[i][0]) / w, 6), round(float(quad[i][1]) / h, 6)]
                for i, k in enumerate(CORNER_KEYS)}
        evidence.append({
            "image": name, "ok": True, "asset": [w, h], "score": sc,
            "selected": meta, "quad_meta": qmeta, "corners_px": px, "corners_norm": norm,
            "candidates": cands,
        })
        hb("%s selected score=%.3f area=%.3f ink=%.3f rect=%.2f prompt=%s quad tl=%s br=%s" % (
            name, sc, meta["area"], meta["ink"], meta["rect_fill"], meta["prompt"],
            px["tl"], px["br"]))

        # ---- patch the manifests (replace the earlier full-frame SAM entries) -----------
        for mpath, key_px in ((ORACLE, True), (GT_ASSET, True)):
            with open(mpath, "r", encoding="utf-8") as fh:
                doc = json.load(fh)
            for it in doc.get("items", []):
                if it.get("image") == name:
                    it["corners_px"] = px
            with open(mpath, "w", encoding="utf-8") as fh:
                json.dump(doc, fh, indent=1)
        with open(GT_RAW, "r", encoding="utf-8") as fh:
            doc = json.load(fh)
        for it in doc.get("items", []):
            if os.path.basename(it.get("image", "")) == name:
                it["corners"] = norm
                it["corners_lenient"] = norm
                it["model"] = "sam2.1-hiera-large-point"
        with open(GT_RAW, "w", encoding="utf-8") as fh:
            json.dump(doc, fh, indent=1)

    ok = sum(1 for e in evidence if e.get("ok"))
    with open(os.path.join(OUT38, "sam_gt_new5.json"), "w", encoding="utf-8") as fh:
        json.dump({
            "dataset": "owner110-new5",
            "oracle": "sam2.1-hiera-large point-prompt + ink-aware selection (measurement-only)",
            "frame": "raw-jpeg-pixels-of-the-bundled-asset",
            "items": evidence,
        }, fh, indent=1)
    hb("h38_gt5 DONE ok=%d/%d" % (ok, len(NAMES)))
    return 0 if ok == len(NAMES) else 1


if __name__ == "__main__":
    sys.exit(main())
