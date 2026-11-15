#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H36 — SAM 2.1 Hiera Large segmentation oracle (measurement only, NEVER shipped).

Purpose-built page-segmentation oracle to replace the demoted Qwen3-VL pseudo-GT.
Runs on the PC (CPU, ONNX Runtime — no torch, no Gradle deps). The model is used
ONLY as a measurement oracle: no Android code depends on it.

Model provenance (pinned, verified after download with certutil -hashfile ... SHA256):
  Hugging Face: facebook/sam2.1-hiera-large, ONNX conversion by onnx-community
  Repo:   https://huggingface.co/onnx-community/sam2.1-hiera-large-ONNX
  Files (resolve URLs, base https://huggingface.co/onnx-community/sam2.1-hiera-large-ONNX/resolve/main/):
    config.json                              5,824 B   sha256 a60f53fac4ae307a2a9d8265ecfa050c4d0b3c6565f0b3e11a58cb51bde301f8
    preprocessor_config.json                   749 B   (not hashed: doc only)
    onnx/vision_encoder.onnx               1,350,487 B sha256 ad3a8161c6bd6d8beea811300c60e20d626035feaa3c19622ace27b22e25bec8
    onnx/vision_encoder.onnx_data        888,587,456 B sha256 88c501a65b6aaeac99b65a275f3f559727404283930d107d499fda8714ba7e5a
    onnx/prompt_encoder_mask_decoder.onnx     213,114 B sha256 a21b3b09f6e5f5838d630d92a150914ca58f8f58b603b8f53fc32b03da982203
    onnx/prompt_encoder_mask_decoder.onnx_data 20,958,208 B sha256 bfdcd4f7ee04298a82fe1aaf1bd7b8edf544d36901e1a886f01892fb915bd63c
  Local cache: E:\\DocScanAI-cache\\sam\\  (total 911,115,838 B)

Protocol (fixed a priori; the same on validation and owner-110):
  1. box prompt = the Qwen page-choice box (first guess) when known, else the whole frame;
  2. mask = the LARGEST paper-region mask of the 3 SAM candidates (area in [0.02, 0.985]
     of the frame; degenerate candidates clamped);
  3. quad = min-area-rect of the mask contour + per-corner refinement (contour point
     furthest along the corner's diagonal within a radius);
  4. per-image oracle metadata for the STEP-2 subsets: mask-contour bow (curvature) and
     low-frequency interior luminance std (adjacent-page shadow).

Modes:
  validate   18 synthetic bookspread assets + 16 annotated real pages vs KNOWN GT ->
             tooling/out/h36/sam_validate.json (corner % diag + IoU, gate verdict)
  owner110   111 bundled owner assets -> sam_gt.json (asset pixel frame)
  image      one ad-hoc image -> stdout quad JSON

Usage:
  python tooling\\h36_sam.py validate
  python tooling\\h36_sam.py validate --resize keep
  python tooling\\h36_sam.py owner110
  python tooling\\h36_sam.py image path\\to.jpg
"""

from __future__ import annotations

import argparse
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
import h30_score as S  # noqa: E402  (iou_of / align_measure / polygon_area)

SAM_DIR = r"E:\DocScanAI-cache\sam"
OUT = os.path.join(HERE, "out", "h36")
BOOKSPREAD = os.path.join(REPO, "app", "src", "androidTest", "assets", "bookspread")
BOOKSPREAD_REAL = os.path.join(REPO, "app", "src", "androidTest", "assets", "bookspread_real")
OWNER110 = os.path.join(REPO, "app", "src", "androidTest", "assets", "owner110")
QWEN_OWNER = os.path.join(HERE, "out", "h34", "pseudo_gt_owner110.json")
QWEN_CALIB = os.path.join(HERE, "out", "h34", "oracle_calib_manifest.json")

MEAN = np.asarray([0.485, 0.456, 0.406], dtype=np.float32).reshape(3, 1, 1)
STD = np.asarray([0.229, 0.224, 0.225], dtype=np.float32).reshape(3, 1, 1)

# Oracle adoption gates (subtask contract).
GATE_SYNTH_CORNER = 3.0   # % diag
GATE_SYNTH_IOU = 0.85
GATE_REAL_CORNER = 8.0    # % diag
GATE_REAL_IOU = 0.50


def hb(msg):
    os.makedirs(OUT, exist_ok=True)
    line = "[%s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg)
    with open(os.path.join(OUT, "HEARTBEAT.txt"), "a", encoding="utf-8") as fh:
        fh.write(line + "\n")
    print(line, flush=True)


# ==========================================================================================
# SAM 2.1 ONNX runner
# ==========================================================================================

class SamOracle:
    def __init__(self, sam_dir=SAM_DIR, resize="squash", threads=None):
        import onnxruntime as ort
        opts = ort.SessionOptions()
        if threads:
            opts.intra_op_num_threads = threads
        # GPU first: onnxruntime-directml (DmlExecutionProvider, AMD RX 9070 XT) when
        # installed; CPU fallback keeps the oracle runnable anywhere. Measured by the
        # caller, not assumed — the provider actually used is recorded per run.
        avail = ort.get_available_providers()
        providers = [p for p in ("DmlExecutionProvider", "CPUExecutionProvider") if p in avail]
        self.providers = providers
        self.enc = ort.InferenceSession(
            os.path.join(sam_dir, "vision_encoder.onnx"), opts, providers=providers)
        self.dec = ort.InferenceSession(
            os.path.join(sam_dir, "prompt_encoder_mask_decoder.onnx"), opts, providers=providers)
        self.resize = resize  # "squash" (1024x1024) or "keep" (longest edge + pad)

    def encode(self, pil):
        w, h = pil.size
        x = self._prep(pil)
        out = self.enc.run(None, {"pixel_values": x})
        return out, (w, h)

    def _prep(self, pil):
        if self.resize == "squash":
            im = pil.convert("RGB").resize((1024, 1024), Image.BILINEAR)
        else:
            s = 1024.0 / max(pil.size)
            nw, nh = int(round(pil.size[0] * s)), int(round(pil.size[1] * s))
            im = pil.convert("RGB").resize((nw, nh), Image.BILINEAR)
            canvas = Image.new("RGB", (1024, 1024))
            canvas.paste(im, (0, 0))
            im = canvas
        x = np.asarray(im, dtype=np.float32).transpose(2, 0, 1) / 255.0
        x = (x - MEAN) / STD
        return x[None]

    def decode_box(self, emb, box_xyxy, wh):
        """emb: encoder outputs; box in ORIGINAL image pixels."""
        w, h = wh
        if self.resize == "squash":
            sx, sy = 1024.0 / w, 1024.0 / h
        else:
            sx = sy = 1024.0 / max(w, h)
        bx = [box_xyxy[0] * sx, box_xyxy[1] * sy, box_xyxy[2] * sx, box_xyxy[3] * sy]
        feed = {
            "input_points": np.zeros((1, 1, 0, 2), dtype=np.float32),
            "input_labels": np.zeros((1, 1, 0), dtype=np.int64),
            "input_boxes": np.asarray([[bx]], dtype=np.float32),
            "image_embeddings.0": emb[0],
            "image_embeddings.1": emb[1],
            "image_embeddings.2": emb[2],
        }
        iou, masks, obj = self.dec.run(["iou_scores", "pred_masks", "object_score_logits"], feed)
        return masks[0]  # [M=1, 3, 256, 256] -> we take [0] below

    def best_mask(self, dec_out, wh, pil=None):
        """The LARGEST PAPER-REGION mask of the 3 candidates (spec).

        "Paper-region" is decided by [paper_score]: bright flat interior with a
        distinctly darker ring just outside (paper on a table). The raw largest
        candidate is frequently the scene/table union or the background — picking
        it by area alone produced the full-frame oracle quads the first validation
        caught. Among candidates that pass the paper test, take the largest.
        """
        w, h = wh
        logits = dec_out[0] if dec_out.ndim == 4 else dec_out  # [3, 256, 256]
        frame = w * h
        gray = np.asarray(pil.convert("L"), dtype=np.float32) if pil is not None else None
        cands = []
        for k in range(logits.shape[0]):
            m256 = logits[k] > 0.0
            m = self._mask_to_image(m256, wh)
            area = float(m.sum()) / frame
            ps = paper_score(m, gray) if gray is not None else 0.5
            cands.append({"area": area, "k": k, "m": m, "paper": ps})
        ok = [c for c in cands if 0.02 <= c["area"] <= 0.985]
        if not ok:
            ok = sorted(cands, key=lambda c: -c["paper"])[:1]
        paper_ok = [c for c in ok if c["paper"] >= 0.30]
        if paper_ok:
            win = max(paper_ok, key=lambda c: c["area"])
        else:
            win = max(ok, key=lambda c: (c["paper"], c["area"]))
        return win["m"], {
            "area_frac": round(win["area"], 4),
            "areas": [round(c["area"], 4) for c in cands],
            "paper_scores": [round(c["paper"], 3) for c in cands],
        }

    def _mask_to_image(self, m256, wh):
        w, h = wh
        m = np.kron(m256, np.ones((4, 4), dtype=bool))[:1024, :1024]  # 256 -> 1024 nearest
        if self.resize == "squash":
            out = np.zeros((h, w), dtype=bool)
            ys = (np.arange(h) * 1024 // h).clip(0, 1023)
            xs = (np.arange(w) * 1024 // w).clip(0, 1023)
            out[:] = m[np.ix_(ys, xs)]
            return out
        s = 1024.0 / max(w, h)
        iw, ih = int(round(w * s)), int(round(h * s))
        m = m[:ih, :iw]
        out = np.zeros((h, w), dtype=bool)
        ys = (np.arange(h) * ih // h).clip(0, ih - 1)
        xs = (np.arange(w) * iw // w).clip(0, iw - 1)
        out[:] = m[np.ix_(ys, xs)]
        return out


# ==========================================================================================
# Mask -> quad (+ oracle metadata)
# ==========================================================================================

def mask_contour(mask):
    """Boundary points of a boolean mask via simple marching (no cv2): 4-neighbour edges."""
    m = mask.astype(np.uint8)
    h, w = m.shape
    pad = np.zeros((h + 2, w + 2), dtype=np.uint8)
    pad[1:-1, 1:-1] = m
    pts = []
    ys, xs = np.nonzero(pad)
    for y, x in zip(ys, xs):
        if x > 0 and pad[y, x - 1] == 0:
            pts.append((x - 1 + 0.0, y - 1 + 0.5))
        if x < w + 1 and pad[y, x + 1] == 0:
            pts.append((x + 0.0, y - 1 + 0.5))
        if y > 0 and pad[y - 1, x] == 0:
            pts.append((x - 1 + 0.5, y - 1 + 0.0))
        if y < h + 1 and pad[y + 1, x] == 0:
            pts.append((x - 1 + 0.5, y + 0.0))
    return np.asarray(pts, dtype=np.float64) if pts else np.zeros((0, 2))


def min_area_rect(pts):
    """Rotating-calipers min-area rect; returns 4 corners (any order)."""
    best = None
    n = len(pts)
    if n < 3:
        return None
    hull = _convex_hull(pts)
    for i in range(len(hull)):
        a, b = hull[i], hull[(i + 1) % len(hull)]
        ex, ey = b[0] - a[0], b[1] - a[1]
        ln = math.hypot(ex, ey)
        if ln < 1e-9:
            continue
        ux, uy = ex / ln, ey / ln
        px, py = -uy, ux
        u = hull[:, 0] * ux + hull[:, 1] * uy
        v = hull[:, 0] * px + hull[:, 1] * py
        u0, u1, v0, v1 = u.min(), u.max(), v.min(), v.max()
        area = (u1 - u0) * (v1 - v0)
        if best is None or area < best[0]:
            corners = []
            for uu, vv in ((u0, v0), (u1, v0), (u1, v1), (u0, v1)):
                corners.append((uu * ux + vv * px, uu * uy + vv * py))
            best = (area, corners)
    return np.asarray(best[1], dtype=np.float64) if best else None


def _convex_hull(pts):
    pts = sorted(map(tuple, pts))

    def half(seq):
        out = []
        for p in seq:
            while len(out) >= 2 and ((out[-1][0] - out[-2][0]) * (p[1] - out[-2][1]) -
                                     (out[-1][1] - out[-2][1]) * (p[0] - out[-2][0])) <= 0:
                out.pop()
            out.append(p)
        return out

    return np.asarray(half(pts)[:-1] + half(pts[::-1])[:-1], dtype=np.float64)


def order_tl_tr_br_bl(corners):
    c = sorted((float(p[0]), float(p[1])) for p in corners)
    c = sorted(c, key=lambda p: (p[1], p[0]))
    top = sorted(c[:2], key=lambda p: p[0])
    bottom = sorted(c[2:], key=lambda p: -p[0])
    return np.asarray([top[0], top[1], bottom[0], bottom[1]], dtype=np.float64)


def refine_corners(quad, contour, diag):
    """Per-corner refinement by robust per-edge LINE FITS on the contour (H36 v2).

    v1 (max-projection along the corner diagonal) systematically EXPANDED the quad —
    any fringe pixel outward wins — and measured 3.53 % mean corner on the synthetic
    set because of it. v2 fits one line per rect side to the contour points lying near
    that side (total-least-squares/PCA) and intersects neighbouring fitted lines: the
    standard quad-from-contour step that snaps to the real page edge.
    """
    if contour is None or len(contour) < 24:
        return quad
    n = len(quad)
    lines = []
    band = 0.035 * diag
    for i in range(n):
        a, b = quad[i], quad[(i + 1) % n]
        ex, ey = float(b[0] - a[0]), float(b[1] - a[1])
        ln = math.hypot(ex, ey)
        if ln < 1e-6:
            lines.append(None)
            continue
        ux, uy = ex / ln, ey / ln
        px, py = -uy, ux
        t = (contour[:, 0] - a[0]) * ux + (contour[:, 1] - a[1]) * uy
        d = (contour[:, 0] - a[0]) * px + (contour[:, 1] - a[1]) * py
        sel = (np.abs(d) <= band) & (t >= -0.10 * ln) & (t <= 1.10 * ln)
        pts = contour[sel]
        if len(pts) < 12:
            lines.append(None)
            continue
        c = pts.mean(axis=0)
        _u, _s, vt = np.linalg.svd(pts - c, full_matrices=False)
        dirv = vt[0]
        lines.append((c, dirv))
    out = []
    guard = 0.12 * diag
    for i in range(n):
        l1, l2 = lines[(i - 1) % n], lines[i]
        p = _line_intersect(l1, l2) if (l1 is not None and l2 is not None) else None
        rx, ry = float(quad[i][0]), float(quad[i][1])
        if p is not None and math.hypot(p[0] - rx, p[1] - ry) <= guard:
            out.append((float(p[0]), float(p[1])))
        else:
            # near-parallel fitted sides intersect far off-frame (bs05/bs09 exploded to
            # 1954 %/608 % corner error) — keep the min-area-rect corner instead.
            out.append((rx, ry))
    return np.asarray(out, dtype=np.float64)


def _line_intersect(l1, l2):
    (p1, d1), (p2, d2) = l1, l2
    den = float(d1[0] * d2[1] - d1[1] * d2[0])
    if abs(den) < 1e-9:
        return None
    t = ((p2[0] - p1[0]) * d2[1] - (p2[1] - p1[1]) * d2[0]) / den
    return (p1[0] + t * float(d1[0]), p1[1] + t * float(d1[1]))


def maybe_split_spread(mask, gray, wh):
    """Page-choice rule for book spreads (H36 v2).

    The GT unit on the real-annotated set is "the fully visible page", but on an open
    book the two pages form ONE contiguous paper mask — "the largest paper region" is
    then the whole spread and the oracle quad fails page choice (measured: rb001/002/
    013 all returned spread unions at ~28 % corner error). Rule: when the winning
    mask's min-area-rect own-axis aspect >= 1.25 (spread-like) AND a gutter shadow is
    visible as a luminance dip near the centre, split the mask at the gutter and keep
    the FULLY VISIBLE half (no frame touch; tie -> larger area). Returns (mask, meta).
    """
    w, h = wh
    contour = mask_contour(mask)
    if len(contour) < 12:
        return mask, {}
    rect = min_area_rect(rect if False else contour)
    if rect is None:
        return mask, {}
    e0 = math.hypot(rect[1][0] - rect[0][0], rect[1][1] - rect[0][1])
    e1 = math.hypot(rect[3][0] - rect[0][0], rect[3][1] - rect[0][1])
    long_e, short_e = max(e0, e1), min(e0, e1)
    # A single portrait page's min-area-rect aspect is ~1.4; an open SPREAD is ~1.9-2.0.
    # Threshold 1.85 keeps single pages (incl. all 18 synthetic stills) out of the split.
    if short_e < 1e-6 or long_e / short_e < 1.85:
        return mask, {"split": False}
    # u coordinate along the LONG axis, centred on the rect
    if e0 >= e1:
        ux, uy = (rect[1][0] - rect[0][0]) / e0, (rect[1][1] - rect[0][1]) / e0
    else:
        ux, uy = (rect[3][0] - rect[0][0]) / e1, (rect[3][1] - rect[0][1]) / e1
    cx, cy = float(rect[:, 0].mean()), float(rect[:, 1].mean())
    ys_m, xs_m = np.nonzero(mask)
    if len(ys_m) < 64:
        return mask, {"split": False}
    u = (xs_m - cx) * ux + (ys_m - cy) * uy
    u_lo, u_hi = float(u.min()), float(u.max())
    if u_hi - u_lo < 8:
        return mask, {"split": False}
    gray_vals = gray[ys_m, xs_m]
    bins = 24
    edges = np.linspace(u_lo, u_hi, bins + 1)
    idx = np.clip(((u - u_lo) / (u_hi - u_lo) * bins).astype(int), 0, bins - 1)
    sums = np.bincount(idx, weights=gray_vals, minlength=bins)
    cnts = np.bincount(idx, minlength=bins).astype(float)
    col = np.where(cnts > 8, sums / np.maximum(cnts, 1), np.nan)
    lo, hi = int(0.30 * bins), int(0.70 * bins)
    window = col[lo:hi]
    if np.all(np.isnan(window)):
        return mask, {"split": False}
    k = lo + int(np.nanargmin(window))
    med = float(np.nanmedian(col))
    if not (float(col[k]) < 0.80 * med):
        return mask, {"split": False, "aspect": round(long_e / short_e, 3)}
    u0 = 0.5 * (edges[k] + edges[k + 1])
    u_all = (np.arange(w)[None, :] - cx) * ux + (np.arange(h)[:, None] - cy) * uy
    left = mask & (u_all < u0)
    right = mask & (u_all >= u0)

    def side_info(m):
        if m.sum() < 64:
            return None
        c2 = mask_contour(m)
        if len(c2) < 12:
            return None
        r2 = min_area_rect(c2)
        if r2 is None:
            return None
        q = order_tl_tr_br_bl(r2)
        touch = False
        tol = 0.02 * min(w, h)
        for (px, py) in q:
            if px <= tol or px >= w - 1 - tol or py <= tol or py >= h - 1 - tol:
                touch = True
        return {"m": m, "touch": touch, "area": float(m.sum()), "rect": r2}

    li, ri = side_info(left), side_info(right)
    if li is None or ri is None:
        return mask, {"split": False}
    if li["touch"] != ri["touch"]:
        keep, side = (ri, "right") if li["touch"] else (li, "left")
    else:
        keep, side = (li, "left") if li["area"] >= ri["area"] else (ri, "right")
    return keep["m"], {"split": True, "side": side,
                       "aspect": round(long_e / short_e, 3),
                       "gutter_u": round(float(u0), 1)}


def quad_from_mask(mask, pil):
    contour = mask_contour(mask)
    if len(contour) < 12:
        return None, {}
    rect = min_area_rect(contour)
    if rect is None:
        return None, {}
    w, h = pil.size
    diag = math.hypot(w, h)
    quad = refine_corners(order_tl_tr_br_bl(rect), contour, diag)

    # curvature ("bow"): 95th-pct perpendicular distance of the contour from its OWN
    # rect edge (points far from the edge plane are excluded), / diag. Straight page
    # edges stay in a pixel band; a curved page edge bows away from the rect edge.
    bow = _bow_metric(contour, quad, diag)

    # shadow: share of interior cells far below the page's bright level (the
    # adjacent-page shadow lights only PART of the page -> bimodal interior).
    gray = np.asarray(pil.convert("L"), dtype=np.float32)
    vals = gray[mask]
    grid = _luma_grid(gray, mask)
    shadow = _shadow_frac(gray, mask)
    meta = {
        "bow": round(float(bow), 5),
        "shadow_frac": round(float(shadow), 4),
        "interior_luma_mean": round(float(vals.mean()), 1) if vals.size else None,
        "interior_luma_std": round(float(vals.std()), 1) if vals.size else None,
        "luma_grid_std": round(float(grid), 1),
    }
    return quad, meta


def paper_score(mask, gray):
    """Paper-likeness of a mask: bright flat interior + darker surrounding ring.

    The scene/table union and the background mask both score low (the interior
    mixes wood tones and the ring is not darker than the interior); the page mask
    scores high. Output in [0, 1]."""
    if gray is None or mask.sum() < 64:
        return 0.0
    m = mask
    ring = _dilate(m, 7) & ~_dilate(m, 2)
    if ring.sum() < 64:
        return 0.0
    inside = gray[m]
    outer = gray[ring]
    p50_in = float(np.percentile(inside, 50))
    p50_out = float(np.percentile(outer, 50))
    contrast = np.clip((p50_in - p50_out) / 40.0, 0.0, 1.0)      # paper brighter than surround
    flat = 1.0 - np.clip(float(np.std(inside)) / 64.0, 0.0, 1.0)  # flat interior
    bright = np.clip((p50_in - 40.0) / 140.0, 0.0, 1.0)           # lit paper, not dark wood
    return float(0.5 * contrast + 0.3 * flat + 0.2 * bright)


def _dilate(mask, r):
    out = mask.copy()
    for _ in range(r):
        p = np.pad(out, 1, constant_values=False)
        out = p[:-2, 1:-1] | p[2:, 1:-1] | p[1:-1, :-2] | p[1:-1, 2:] | out
    return out


def _bow_metric(contour, quad, diag):
    """Curvature ("bow") of the mask contour around its min-area rect (v2).

    Each contour point is assigned to its NEAREST rect edge; per edge the p95 of the
    |perpendicular distance| of its assigned points is that edge's recession — a
    straight page edge sits in a 1-2 px band, a bowed/bumpy edge recedes by the bow
    depth (both convex and concave bows register). bow = max over edges / diag.

    v2 replaces v1, whose t-slab ALSO admitted the ADJACENT edges' points (their
    projection t falls inside [0,1]) and therefore saturated at its 0.25*diag cap —
    every owner photo read ~0.24 and the "curved" selector degenerated to 111/111.
    """
    n = len(quad)
    best_d = np.full(len(contour), np.inf)
    best_t = np.zeros(len(contour))
    best_e = np.zeros(len(contour), dtype=int)
    for i in range(n):
        a, b = quad[i], quad[(i + 1) % n]
        ex, ey = float(b[0] - a[0]), float(b[1] - a[1])
        ln = math.hypot(ex, ey)
        if ln < 1e-9:
            continue
        ux, uy = ex / ln, ey / ln
        nx, ny = -uy, ux
        d = (contour[:, 0] - a[0]) * nx + (contour[:, 1] - a[1]) * ny
        t = ((contour[:, 0] - a[0]) * ux + (contour[:, 1] - a[1]) * uy) / ln
        ad = np.abs(d)
        upd = ad < best_d
        best_d = np.where(upd, ad, best_d)
        best_t = np.where(upd, t, best_t)
        best_e = np.where(upd, i, best_e)
    best = 0.0
    for i in range(n):
        sel = (best_e == i) & (best_t >= -0.02) & (best_t <= 1.02)
        if int(sel.sum()) >= 12:
            best = max(best, float(np.percentile(best_d[sel], 95)))
    return best / max(diag, 1.0)


def _shadow_frac(gray, mask, g=64):
    """Share of interior grid cells whose mean sits below 0.60 of the page's bright
    level (p95). A blanket-dark page cancels out (its p95 is dark too); an
    ADJACENT-PAGE shadow lights only part of the page and shows up here."""
    h, w = gray.shape
    ys = np.linspace(0, h, g + 1).astype(int)
    xs = np.linspace(0, w, g + 1).astype(int)
    cells = []
    for i in range(g):
        for j in range(g):
            blk_m = mask[ys[i]:ys[i + 1], xs[j]:xs[j + 1]]
            if blk_m.any():
                blk = gray[ys[i]:ys[i + 1], xs[j]:xs[j + 1]]
                cells.append(float(blk[blk_m].mean()))
    if len(cells) < 8:
        return 0.0
    arr = np.asarray(cells, dtype=np.float64)
    p95 = float(np.percentile(arr, 95))
    if p95 <= 1.0:
        return 0.0
    return float(np.mean(arr < 0.60 * p95))


def _luma_grid(gray, mask, g=64):
    h, w = gray.shape
    ys = np.linspace(0, h, g + 1).astype(int)
    xs = np.linspace(0, w, g + 1).astype(int)
    vals = []
    for i in range(g):
        for j in range(g):
            blk_m = mask[ys[i]:ys[i + 1], xs[j]:xs[j + 1]]
            if blk_m.any():
                blk = gray[ys[i]:ys[i + 1], xs[j]:xs[j + 1]]
                vals.append(float(blk[blk_m].mean()))
    return float(np.std(vals)) if len(vals) >= 8 else 0.0


# ==========================================================================================
# Scoring (EXACT h30_score / h34 definitions)
# ==========================================================================================

def score_quad(pred, gt, diag):
    mean_px, _ = S.align_measure(np.asarray(pred, dtype=np.float64),
                                 np.asarray(gt, dtype=np.float64))
    corner_pct = 100.0 * mean_px / max(diag, 1.0)
    iou = S.iou_of(np.asarray(pred, dtype=np.float64), np.asarray(gt, dtype=np.float64))
    return round(float(corner_pct), 3), round(float(iou), 4)


# ==========================================================================================
# Dataset wiring
# ==========================================================================================

def qwen_boxes():
    """Qwen page-choice box hints (first guess) keyed by basename -> (x1,y1,x2,y2) norm."""
    out = {}
    if os.path.isfile(QWEN_OWNER):
        with open(QWEN_OWNER, encoding="utf-8") as fh:
            for it in json.load(fh).get("items", []):
                c = it.get("corners") or {}
                if all(k in c for k in ("tl", "tr", "br", "bl")):
                    xs = [c[k][0] for k in ("tl", "tr", "br", "bl")]
                    ys = [c[k][1] for k in ("tl", "tr", "br", "bl")]
                    out[os.path.basename(it["image"])] = (min(xs), min(ys), max(xs), max(ys))
    if os.path.isfile(QWEN_CALIB):
        with open(QWEN_CALIB, encoding="utf-8") as fh:
            for it in json.load(fh).get("items", []):
                c = it.get("corners") or {}
                if all(k in c for k in ("tl", "tr", "br", "bl")):
                    xs, ys = [], []
                    for k in ("tl", "tr", "br", "bl"):
                        v = c[k]
                        if len(v) >= 4:  # box_mid corner boxes: [x0,y0,x1,y1]
                            xs += [float(v[0]), float(v[2])]
                            ys += [float(v[1]), float(v[3])]
                        else:            # plain xy point
                            xs.append(float(v[0]))
                            ys.append(float(v[1]))
                    name = os.path.basename(it["image"])
                    out.setdefault(name, (min(xs), min(ys), max(xs), max(ys)))
    return out


def load_validation():
    """(image_path, gt_quad_px, diag, source, name) for the 18 synthetic + 16 real."""
    rows = []
    with open(os.path.join(BOOKSPREAD, "book_spread_manifest.json"), encoding="utf-8") as fh:
        m = json.load(fh)
    for it in m["items"]:
        for canvas in ("still", "preview"):
            c = it.get(canvas) or {}
            if not c.get("asset"):
                continue
            rows.append({
                "image": os.path.join(BOOKSPREAD, c["asset"]),
                "gt": [tuple(p) for p in c["gt_page"]],
                "diag": float(c["diagonal"]),
                "source": "synthetic",
                "name": c["asset"],
            })
    with open(os.path.join(BOOKSPREAD_REAL, "real_bookspread_manifest.json"), encoding="utf-8") as fh:
        m = json.load(fh)
    for it in m["items"]:
        if not it.get("gt_target_quad"):
            continue
        rows.append({
            "image": os.path.join(BOOKSPREAD_REAL, it["asset"]),
            "gt": [tuple(p) for p in it["gt_target_quad"]],
            "diag": float(it["diagonal"]),
            "source": "real",
            "name": it["asset"],
        })
    return rows


def run_one(oracle, path, qbox):
    pil = Image.open(path)
    w, h = pil.size
    box = (0.0, 0.0, float(w), float(h))
    prompt = "full-frame"
    if qbox is not None:
        # Qwen page-choice box as first guess, with a 5 % margin (its corners are
        # calibrated 18 % diag off on real slices, so the box is a hint, not a crop).
        x0 = max(0.0, min(qbox[0], qbox[2]) - 0.05) * w
        y0 = max(0.0, min(qbox[1], qbox[3]) - 0.05) * h
        x1 = min(1.0, max(qbox[0], qbox[2]) + 0.05) * w
        y1 = min(1.0, max(qbox[1], qbox[3]) + 0.05) * h
        # Degenerate-hint guard: the demoted Qwen oracle emitted collapsed corners
        # (e.g. rb014: all y == 1.0) whose 5 %-inflated box became a thin strip prompt.
        # Both RAW spans must be >= 15 % of the frame or the hint is discarded.
        if (qbox[2] - qbox[0]) >= 0.15 and (qbox[3] - qbox[1]) >= 0.15:
            box = (x0, y0, x1, y1)
            prompt = "qwen-box"
    emb, wh = oracle.encode(pil)
    dec = oracle.decode_box(emb, box, wh)
    mask, mask_meta = oracle.best_mask(dec, wh, pil)
    gray = np.asarray(pil.convert("L"), dtype=np.float32)
    mask, split_meta = maybe_split_spread(mask, gray, wh)
    quad, meta = quad_from_mask(mask, pil)
    meta.update(split_meta)
    meta.update(mask_meta)
    meta["prompt"] = prompt
    return quad, meta, (w, h)


# ==========================================================================================
# Modes
# ==========================================================================================

def mode_validate(args):
    oracle = SamOracle(resize=args.resize)
    qb = qwen_boxes()
    rows = []
    for item in load_validation():
        name = item["name"]
        t0 = time.time()
        quad, meta, wh = run_one(oracle, item["image"], qb.get(name))
        if quad is None:
            rows.append({"name": name, "source": item["source"], "ok": False, "meta": meta})
            hb("validate %s: NO MASK" % name)
            continue
        corner_pct, iou = score_quad(quad, item["gt"], item["diag"])
        rows.append({
            "name": name, "source": item["source"], "ok": True,
            "corner_pct": corner_pct, "iou": iou,
            "quad": [[float(x), float(y)] for x, y in quad],
            "meta": meta, "ms": int((time.time() - t0) * 1000),
        })
        hb("validate %s: corner=%.2f%% iou=%.3f (%.1fs)" %
           (name, corner_pct, iou, time.time() - t0))

    def agg(sel):
        ok = [r for r in rows if r.get("ok") and r["source"] == sel]
        if not ok:
            return {"n": 0}
        c = [r["corner_pct"] for r in ok]
        i = [r["iou"] for r in ok]
        return {"n": len(ok), "mean_corner_pct": round(float(np.mean(c)), 3),
                "max_corner_pct": round(float(np.max(c)), 3),
                "mean_iou": round(float(np.mean(i)), 4),
                "min_iou": round(float(np.min(i)), 4)}

    syn, real = agg("synthetic"), agg("real")
    verdict = bool(
        syn.get("n") and real.get("n")
        and syn["mean_corner_pct"] <= GATE_SYNTH_CORNER and syn["mean_iou"] >= GATE_SYNTH_IOU
        and real["mean_corner_pct"] <= GATE_REAL_CORNER and real["mean_iou"] >= GATE_REAL_IOU
    )
    out = {
        "oracle": "sam2.1-hiera-large (onnx-community), resize=%s" % args.resize,
        "synthetic": syn, "real": real,
        "gates": {"synth_corner_pct": GATE_SYNTH_CORNER, "synth_iou": GATE_SYNTH_IOU,
                  "real_corner_pct": GATE_REAL_CORNER, "real_iou": GATE_REAL_IOU},
        "verdict": "ADOPT" if verdict else "REJECT",
        "rows": rows,
    }
    with open(os.path.join(OUT, "sam_validate.json"), "w", encoding="utf-8") as fh:
        json.dump(out, fh, indent=1)
    hb("validate DONE verdict=%s synth=%s real=%s" % (out["verdict"], syn, real))
    print(json.dumps({k: out[k] for k in ("synthetic", "real", "verdict")}, indent=1))
    return 0 if verdict else 1


def mode_owner110(args):
    oracle = SamOracle(resize=args.resize)
    qb = qwen_boxes()
    with open(os.path.join(OWNER110, "owner110_manifest.json"), encoding="utf-8") as fh:
        manifest = json.load(fh)
    items = []
    for it in manifest["items"]:
        name = it["image"]
        path = os.path.join(OWNER110, name)
        t0 = time.time()
        quad, meta, wh = run_one(oracle, path, qb.get(name))
        rec = {
            "image": name,
            "width": int(it["width"]), "height": int(it["height"]),
            "src_scale": it.get("src_scale"),
            "oracle": "sam2.1-hiera-large",
            "meta": meta,
        }
        if quad is not None:
            rec["corners_px"] = {
                "tl": [round(float(quad[0][0]), 1), round(float(quad[0][1]), 1)],
                "tr": [round(float(quad[1][0]), 1), round(float(quad[1][1]), 1)],
                "br": [round(float(quad[2][0]), 1), round(float(quad[2][1]), 1)],
                "bl": [round(float(quad[3][0]), 1), round(float(quad[3][1]), 1)],
            }
        items.append(rec)
        hb("owner110 %s: %s bow=%.4f gridstd=%.1f (%.1fs)" % (
            name, "quad" if quad is not None else "NO-MASK",
            meta.get("bow", -1), meta.get("luma_grid_std", -1), time.time() - t0))

    gt_out = {
        "dataset": "owner110",
        "oracle": "sam2.1-hiera-large (onnx-community ONNX), measurement-only",
        "frame": "raw-jpeg-pixels-of-the-bundled-asset",
        "resize_mode": args.resize,
        "count": len(items),
        "items": items,
    }
    for dest in (os.path.join(OUT, "sam_gt.json"),
                 os.path.join(HERE, "out", "h34", "sam_gt.json")):
        with open(dest, "w", encoding="utf-8") as fh:
            json.dump(gt_out, fh, indent=1)
    hb("owner110 DONE -> sam_gt.json (%d items)" % len(items))
    return 0


def mode_image(args):
    oracle = SamOracle(resize=args.resize)
    quad, meta, wh = run_one(oracle, args.path, None)
    print(json.dumps({"path": args.path, "size": wh,
                      "quad": [[float(x), float(y)] for x, y in quad] if quad is not None else None,
                      "meta": meta}, indent=1))
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["validate", "owner110", "image"])
    ap.add_argument("path", nargs="?")
    ap.add_argument("--resize", default="squash", choices=["squash", "keep"])
    ap.add_argument("--threads", type=int, default=None)
    args = ap.parse_args()
    if args.mode == "validate":
        return mode_validate(args)
    if args.mode == "owner110":
        return mode_owner110(args)
    return mode_image(args)


if __name__ == "__main__":
    raise SystemExit(main())
