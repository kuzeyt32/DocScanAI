#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H30 — offline replica of the shipped corner-model detection path.

Why replicate instead of trusting the published numbers
-------------------------------------------------------
The H30 merge rule has to be evaluated **against the quads the corner model
actually returns**, not against a table of aggregate metrics. The device logs
only the aggregate (`mergedSlivers=7/12`, `meanCornerPct=3.9562`); they do not
carry the four corner coordinates of each of the 18 assets. So this module
re-implements, in numpy, exactly what
[`OpenCvQuadPageDetector.detectWithCornerModel`](../../app/src/main/java/com/docscanai/capture/OpenCvQuadPageDetector.kt)
does with the same weight file, and `h30_score.py` checks the result against the
published H29 aggregate before using it for anything.

What is replicated, line for line
---------------------------------
| Kotlin | here |
|---|---|
| `preprocessForCornerModel` (squash to 256², RGB/255, NCHW) | `preprocess` |
| `subpixelPeak` (3-point quadratic peak, clamped to ±0.5 cell) | `subpixel_peak` |
| `(sub + 0.5) * frame / hw` mapping | `postprocess_heat` |
| `orderCorners` (sum/difference heuristic) | `order_corners` |
| `isPlausiblePageQuad` (area band, min-area-rect side/aspect/fill, convexity, frame-hug) | `is_plausible_page_quad` |
| `CORNER_PEAK_MIN = 0.30` gate per channel | `postprocess_heat` |
| `REFINE_ENABLED = false` (H18 gate) | not applied |

The only piece not replicated is the classical fallback (`detectClassical`), which
needs OpenCV. That is safe here because H29 measured the corner model returning
the quad on **18/18** assets, so the fallback never ran on this dataset. If this
replica ever misses an asset, `h30_score.py` reports `corner=miss` instead of
pretending a fallback happened.
"""

import numpy as np
from PIL import Image

# --- constants mirrored from OpenCvQuadPageDetector's companion object ----------
CORNER_INPUT_EDGE = 256
CORNER_PEAK_MIN = 0.30
CORNER_SUBPIXEL_ENABLED = True

MIN_QUAD_AREA_RATIO = 0.03
MAX_QUAD_AREA_RATIO = 0.98
MIN_QUAD_SIDE = 8.0
MAX_QUAD_ASPECT_RATIO = 6.0
MIN_QUAD_FILL = 0.30
FRAME_HUG_TOL_FRAC = 0.03


# ==========================================================================================
# Geometry (shared with the scorer and the merge rule)
# ==========================================================================================

def polygon_area(pts):
    arr = np.asarray(pts, dtype=np.float64)
    if arr.shape[0] < 3:
        return 0.0
    x = arr[:, 0]
    y = arr[:, 1]
    return float(abs(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1))) / 2.0)


def order_corners(pts):
    """TL, TR, BR, BL by the sum/difference heuristic (Kotlin `orderCorners`)."""
    arr = np.asarray(pts, dtype=np.float64)
    s = arr[:, 0] + arr[:, 1]
    d = arr[:, 0] - arr[:, 1]
    tl = arr[int(np.argmin(s))]
    br = arr[int(np.argmax(s))]
    bl = arr[int(np.argmin(d))]
    tr = arr[int(np.argmax(d))]
    return np.asarray([tl, tr, br, bl], dtype=np.float64)


def convex_hull(points):
    pts = sorted(set(map(tuple, np.round(np.asarray(points, dtype=np.float64), 9))))
    if len(pts) <= 2:
        return np.asarray(pts, dtype=np.float64)

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower = []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    upper = []
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return np.asarray(lower[:-1] + upper[:-1], dtype=np.float64)


def min_area_rect(pts):
    """`(w, h, area)` of the minimum-area rectangle, via each hull edge's frame."""
    hull = convex_hull(pts)
    if hull.shape[0] < 3:
        return 0.0, 0.0, 0.0
    best = None
    n = hull.shape[0]
    for i in range(n):
        p0 = hull[i]
        p1 = hull[(i + 1) % n]
        ex, ey = p1[0] - p0[0], p1[1] - p0[1]
        norm = float(np.hypot(ex, ey))
        if norm < 1e-9:
            continue
        ux, uy = ex / norm, ey / norm
        vx, vy = -uy, ux
        us = (hull[:, 0] - p0[0]) * ux + (hull[:, 1] - p0[1]) * uy
        vs = (hull[:, 0] - p0[0]) * vx + (hull[:, 1] - p0[1]) * vy
        w = float(us.max() - us.min())
        h = float(vs.max() - vs.min())
        area = w * h
        if best is None or area < best[2]:
            best = (w, h, area)
    return best if best is not None else (0.0, 0.0, 0.0)


def is_convex(pts):
    arr = np.asarray(pts, dtype=np.float64)
    n = arr.shape[0]
    if n < 3:
        return False
    sign = 0
    for i in range(n):
        a = arr[i]
        b = arr[(i + 1) % n]
        c = arr[(i + 2) % n]
        cross = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
        if abs(cross) < 1e-9:
            continue
        s = 1 if cross > 0 else -1
        if sign == 0:
            sign = s
        elif s != sign:
            return False
    return True


def is_frame_hugging(pts, w, h):
    arr = np.asarray(pts, dtype=np.float64)
    tol_x = max(2.0, w * FRAME_HUG_TOL_FRAC)
    tol_y = max(2.0, h * FRAME_HUG_TOL_FRAC)
    return (arr[:, 0].min() <= tol_x and arr[:, 0].max() >= w - tol_x
            and arr[:, 1].min() <= tol_y and arr[:, 1].max() >= h - tol_y)


def is_plausible_page_quad(quad, w, h):
    """Port of `isPlausiblePageQuad`; returns `(ok, reason)`."""
    pts = np.asarray(quad, dtype=np.float64)
    if pts.shape[0] != 4:
        return False, "points"
    frame = max(w * h, 1.0)
    area = polygon_area(pts)
    ratio = area / frame
    if ratio < MIN_QUAD_AREA_RATIO or ratio > MAX_QUAD_AREA_RATIO:
        return False, "area"
    rw, rh, rrect = min_area_rect(pts)
    if rw < MIN_QUAD_SIDE or rh < MIN_QUAD_SIDE:
        return False, "side"
    if max(rw, rh) / max(min(rw, rh), 1e-9) > MAX_QUAD_ASPECT_RATIO:
        return False, "aspect"
    if area / max(rrect, 1.0) < MIN_QUAD_FILL:
        return False, "fill"
    if not is_convex(pts):
        return False, "convex"
    if is_frame_hugging(pts, w, h):
        return False, "frame"
    return True, "ok"


# ==========================================================================================
# Corner-model inference
# ==========================================================================================

#: Resampling mode for the squash-resize. Android's `Bitmap.createScaledBitmap(..., true)`
#: is not documented down to the kernel, so which PIL filter reproduces it is
#: **measured** (`h30_score.py --resize-probe`) against the device's per-asset
#: numbers instead of assumed. `twostep` mimics a box prefilter (integer factor)
#: followed by bilinear, which is what a large downscale needs to avoid aliasing
#: text lines away — and text lines are the only thing M-LSD eats.
#: `tooling/h30_resize_probe.py` calibred every mode against the device's published
#: per-asset numbers. `box` reproduced the device's merged-sliver count exactly
#: (7/12) with a mean corner agreement of 0.70 pp; `bilinear` (the naive choice)
#: got 6/12. The device stays the authority — the probe's residual is reported in
#: `tooling/out/h30/resize_probe.json` rather than hidden.
RESIZE_MODE = "box"
RESIZE_MODES = ("bilinear", "box", "bicubic", "lanczos", "twostep")


def _resize(img, edge, mode):
    if mode == "bilinear":
        return img.resize((edge, edge), Image.BILINEAR)
    if mode == "box":
        return img.resize((edge, edge), Image.BOX)
    if mode == "bicubic":
        return img.resize((edge, edge), Image.BICUBIC)
    if mode == "lanczos":
        return img.resize((edge, edge), Image.LANCZOS)
    if mode == "twostep":
        w, h = img.size
        factor = max(1, int(min(w / float(edge), h / float(edge))))
        if factor > 1:
            img = img.resize((w // factor, h // factor), Image.BOX)
        return img.resize((edge, edge), Image.BILINEAR)
    raise ValueError("unknown resize mode %r" % (mode,))


def preprocess(rgb, edge=CORNER_INPUT_EDGE, mode=None):
    """Squash-resize to `edge`², RGB/255, NCHW float32 (Kotlin preprocess)."""
    img = _resize(Image.fromarray(rgb), edge, mode or RESIZE_MODE)
    arr = np.asarray(img, dtype=np.float32) / 255.0
    return arr.transpose(2, 0, 1)[None, ...]


def subpixel_peak(heat, off, px, py, hw):
    """3-point quadratic peak interpolation (Kotlin `subpixelPeak`)."""
    center = float(heat[off + py * hw + px])

    def delta(a, b, c):
        denom = a - 2.0 * b + c
        if abs(denom) < 1e-9:
            return 0.0
        return float(np.clip(0.5 * (a - c) / denom, -0.5, 0.5))

    dx = 0.0
    dy = 0.0
    if 0 < px < hw - 1:
        dx = delta(float(heat[off + py * hw + px - 1]), center, float(heat[off + py * hw + px + 1]))
    if 0 < py < hw - 1:
        dy = delta(float(heat[off + (py - 1) * hw + px]), center, float(heat[off + (py + 1) * hw + px]))
    x = px + dx
    y = py + dy
    if not np.isfinite(x) or not np.isfinite(y):
        return None
    return x, y


def postprocess_heat(heat, frame_w, frame_h):
    """`[4, hw, hw]` heat maps -> ordered quad, or `(None, reason)`."""
    heat = np.asarray(heat, dtype=np.float32)
    if heat.ndim == 4:
        heat = heat[0]
    if heat.shape[0] != 4:
        return None, "channels"
    hw = heat.shape[1]
    if heat.shape[2] != hw:
        return None, "nonsquare"
    plane = hw * hw
    corners = []
    confs = []
    for c in range(4):
        chan = heat[c].reshape(-1)
        mi = int(np.argmax(chan))
        peak = float(chan[mi])
        confs.append(peak)
        if peak < CORNER_PEAK_MIN:
            return None, "peak"
        px = mi % hw
        py = mi // hw
        sub = subpixel_peak(chan, 0, px, py, hw) if CORNER_SUBPIXEL_ENABLED else None
        if sub is None:
            sub = (px, py)
        corners.append(((sub[0] + 0.5) * frame_w / hw, (sub[1] + 0.5) * frame_h / hw))
    quad = order_corners(corners)
    ok, reason = is_plausible_page_quad(quad, frame_w, frame_h)
    if not ok:
        return None, reason
    return quad, float(min(confs))


def detect(session, rgb, mode=None):
    """Run the pinned LCNet100 graph; returns `(quad | None, info dict)`."""
    batch = preprocess(rgb, mode=mode)
    inp = session.get_inputs()[0].name
    out = session.get_outputs()[0].name
    heat = session.run([out], {inp: batch})[0]
    h, w = rgb.shape[0], rgb.shape[1]
    quad, info = postprocess_heat(heat, w, h)
    return quad, info
