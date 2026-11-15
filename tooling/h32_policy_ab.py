#!/usr/bin/env python3
"""h32_policy_ab.py - measure each H32 policy fix SEPARATELY, offline, before touching production.

STEP 3 says "Address, in this order, and keep only what measurably helps". The real-set replay
must use the SAME input quads the device produced (corner-model or classical-fallback quads,
captured in tooling/out/h32/H32RED/logcat.txt), because the classical fallback cannot be
replicated offline. The repair stage (fold search -> guard -> split -> side choice -> acceptsRepair)
IS replicated here, constant-for-constant with MlsdMergeRepair.kt / MlsdTrigger.kt.

Variants (each layered on the previous, plus the individual pieces A/B'd alone):

  base       shipped 0.3.9 rule: nearest fold per side, strip 5-45 %, keep larger half
  d          strip 5-55 % + all fold candidates (best-first)     [STEP 3(d)]
  da         + uniformity-along-the-fold guard                   [STEP 3(a)]
  da_bc      + partial-page rejection / interior side selection  [STEP 3(b,c)]
  ... and the pieces alone (d_a, bc_only, ...) so every number has one cause.

Metrics:
  real   : merged = quad covers >= 50 % of BOTH annotated pages (the spread was returned);
           wrong-page = on partial-page scenes, the quad sits mostly on the partial page;
           corner% strict (vs annotated target) and best (vs the better page - spreads and
           blank controls accept either single page).
  synth  : unchanged H30/H31 definitions (sliver-cover >= 0.5 on sliver assets, corner vs
           gt_page, IoU vs gt_page) so the numbers stay comparable to the shipped pins.

Usage:
  python tooling\\h32_policy_ab.py                 # all variants, both sets
  python tooling\\h32_policy_ab.py --variant da_bc
"""

import argparse
import json
import os
import re
import sys

import numpy as np
import onnxruntime as ort
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import h30_corner as C  # noqa: E402
import h30_mlsd as M  # noqa: E402
import h32_replay as R  # noqa: E402

OUT_DIR = os.path.join(REPO, "tooling", "out", "h32")
MLSD_EDGE = 320
MLSD_SCORE_THR = 0.06

# ---- H32 policy constants (mirrored 1:1 into Kotlin when a variant wins) --------------------
MIN_STRIP_FRAC = 0.05
MAX_STRIP_BASE = 0.45
MAX_STRIP_H32 = 0.55
REPAIR_MIN_STRIP = 0.075
ABUT_EPS_FRAC = 0.015       # a text line "abuts" the fold when an endpoint is this close
ABUT_BIN_FRAC = 0.60        # a side is "abutting" when >= this fraction of the fold length has
                            # at least one abutting endpoint
FRAME_TOL_FRAC = 0.02       # frame-contact tolerance, fraction of the frame's short side
BORDER_PATCH = 4            # px half-size of the paper-at-border sample patch
PAPER_REF_PCT = 75          # paper = this percentile of luma inside the half
PAPER_BORDER_RATIO = 0.70   # border sample is "paper" when luma >= ratio * paper


def gray_of(rgb):
    a = np.asarray(rgb, dtype=np.float32)
    return (0.299 * a[:, :, 0] + 0.587 * a[:, :, 1] + 0.114 * a[:, :, 2]) / 255.0


# ==========================================================================================
# Fold candidates (all of them, not just the nearest per side)
# ==========================================================================================

#: H32: the fold-line chord window (was 0.80). Measured: the true gutter segments on the real
#: residuals cover 0.74..0.83 of the quad along their direction and were rejected at 0.80.
CHORD_BASE = 0.80
CHORD_H32 = 0.65
SPREAD_ASPECT_MIN = 1.10
SPREAD_STRIP_MAX = 0.55


def quad_asp(quad):
    q = np.asarray(quad, dtype=np.float64)
    w = float(np.linalg.norm((q[1] - q[0] + q[2] - q[3]) / 2.0))
    h = float(np.linalg.norm((q[3] - q[0] + q[2] - q[1]) / 2.0))
    return w / max(h, 1e-9)


def max_strip_for(quad, adaptive):
    """Adaptive fold window: a spread-signature quad may split up to 55 % of its width."""
    if adaptive and quad_asp(quad) >= SPREAD_ASPECT_MIN:
        return SPREAD_STRIP_MAX
    return MAX_STRIP_BASE


def fold_candidates(quad, segs, max_strip, chord=CHORD_BASE):
    """All qualifying fold candidates: (side, d_side, seg, ev), nearest-first per side."""
    q = np.asarray(quad, dtype=np.float64)
    if q.shape[0] != 4 or segs is None or len(segs) == 0:
        return []
    width = float(np.linalg.norm((q[1] - q[0] + q[2] - q[3]) / 2.0))
    height = float(np.linalg.norm((q[3] - q[0] + q[2] - q[1]) / 2.0))
    if width < 1e-6 or height < 1e-6:
        return []
    out = []
    for seg in np.asarray(segs, dtype=np.float64):
        x1, y1, x2, y2 = [float(v) for v in seg]
        dx, dy = x2 - x1, y2 - y1
        ln = float(np.hypot(dx, dy))
        if ln < 1e-6:
            continue
        ux, uy = dx / ln, dy / ln
        hux, huy = (q[3] - q[0] + q[2] - q[1]) / 2.0 / height
        cos_h = abs(ux * hux + uy * huy)
        if cos_h < np.cos(np.radians(25.0)):
            continue
        if ln < 0.45 * height:
            continue
        if not M.line_through_quad(q, seg, min_inside_frac=chord):
            continue
        crossings = _line_crossing_fractions(q, seg)
        if crossings is None:
            continue
        if any(u < 0.05 or u > 0.95 for u in crossings):
            continue
        mid = np.array([(x1 + x2) / 2.0, (y1 + y2) / 2.0])
        d_left = _point_seg_dist(mid, q[0], q[3]) / width
        d_right = _point_seg_dist(mid, q[1], q[2]) / width
        for side, dist in (("left", d_left), ("right", d_right)):
            if dist < MIN_STRIP_FRAC or dist > max_strip:
                continue
            out.append((side, dist, seg, {"cos_spine": cos_h, "len": ln,
                                          "u_top": crossings[0], "u_bottom": crossings[1]}))
    out.sort(key=lambda t: (t[0], t[1]))
    return out


def _line_crossing_fractions(quad, seg):
    q = np.asarray(quad, dtype=np.float64)
    x1, y1, x2, y2 = [float(v) for v in seg]
    dx, dy = x2 - x1, y2 - y1
    out = []
    for a, b in ((q[0], q[1]), (q[3], q[2])):
        ex, ey = b[0] - a[0], b[1] - a[1]
        denom = dx * ey - dy * ex
        if abs(denom) < 1e-12:
            return None
        u = ((x1 - a[0]) * dy - (y1 - a[1]) * dx) / -denom
        out.append(float(u))
    return out[0], out[1]


def _point_seg_dist(p, a, b):
    p = np.asarray(p, dtype=np.float64)
    a = np.asarray(a, dtype=np.float64)
    b = np.asarray(b, dtype=np.float64)
    ab = b - a
    denom = float(np.dot(ab, ab))
    if denom < 1e-12:
        return float(np.linalg.norm(p - a))
    t = float(np.clip(np.dot(p - a, ab) / denom, 0.0, 1.0))
    return float(np.linalg.norm(p - (a + t * ab)))


# ==========================================================================================
# STEP 3(a) - uniformity-along-the-fold guard
# ==========================================================================================

def margin_gap(quad, fold_seg, segs, skip_idx=None):
    """Do text lines ABOUND the fold on either side? (the text-block-edge signature)

    A real page edge (gutter / facing-page boundary) keeps a MARGIN on both sides: detected
    text-line endpoints stop away from the line. A text-block edge (or a column separator)
    has line endpoints ON the line on at least one side.

    Returns (ok, abut_left_frac, abut_right_frac, min_gap_left, min_gap_right) where ok =
    NEITHER side abuts (both sides keep a margin).
    """
    q = np.asarray(quad, dtype=np.float64)
    x1, y1, x2, y2 = [float(v) for v in fold_seg]
    dx, dy = x2 - x1, y2 - y1
    ln = float(np.hypot(dx, dy))
    if ln < 1e-6:
        return False, 1.0, 1.0, 0.0, 0.0
    ux, uy = dx / ln, dy / ln
    nx, ny = -uy, ux
    width = float(np.linalg.norm((q[1] - q[0] + q[2] - q[3]) / 2.0))
    eps = ABUT_EPS_FRAC * width
    n_bins = 20
    left_hits = np.zeros(n_bins, dtype=bool)
    right_hits = np.zeros(n_bins, dtype=bool)
    min_gap_l = 1e9
    min_gap_r = 1e9
    for i, seg in enumerate(np.asarray(segs, dtype=np.float64)):
        if skip_idx is not None and i == skip_idx:
            continue
        for (px, py) in ((seg[0], seg[1]), (seg[2], seg[3])):
            rx, ry = px - x1, py - y1
            t = rx * ux + ry * uy
            if t < -0.05 * ln or t > 1.05 * ln:
                continue
            dist = rx * nx + ry * ny
            b = int(np.clip(t / ln * n_bins, 0, n_bins - 1))
            if dist >= 0:
                if dist < min_gap_r:
                    min_gap_r = dist
                if dist <= eps:
                    right_hits[b] = True
            else:
                if -dist < min_gap_l:
                    min_gap_l = -dist
                if -dist <= eps:
                    left_hits[b] = True
    frac_l = float(left_hits.mean())
    frac_r = float(right_hits.mean())
    ok = frac_l < ABUT_BIN_FRAC and frac_r < ABUT_BIN_FRAC
    return ok, frac_l, frac_r, min_gap_l / max(width, 1e-9), min_gap_r / max(width, 1e-9)


# ==========================================================================================
# STEP 3(b,c) - side selection: reject a half cut by the frame edge
# ==========================================================================================

def half_frame_contact(half, w, h, frac=FRAME_TOL_FRAC):
    """Total length of the half's edges lying within `frac` of the frame border (geometry)."""
    q = np.asarray(half, dtype=np.float64)
    tol_x = w * frac
    tol_y = h * frac
    total = 0.0
    for i in range(4):
        a = q[i]
        b = q[(i + 1) % 4]
        n = 16
        for k in range(n + 1):
            t = k / float(n)
            px = a[0] + (b[0] - a[0]) * t
            py = a[1] + (b[1] - a[1]) * t
            near = px <= tol_x or px >= w - tol_x or py <= tol_y or py >= h - tol_y
            if near:
                total += float(np.hypot(b[0] - a[0], b[1] - a[1])) / n
    return total


def _reached_borders(q, w, h, tol):
    """Which FRAME borders the polygon's bounding box reaches (0=left,1=right,2=top,3=bottom)."""
    minx, maxx = float(q[:, 0].min()), float(q[:, 0].max())
    miny, maxy = float(q[:, 1].min()), float(q[:, 1].max())
    out = set()
    if minx <= tol:
        out.add(0)
    if maxx >= w - 1 - tol:
        out.add(1)
    if miny <= tol:
        out.add(2)
    if maxy >= h - 1 - tol:
        out.add(3)
    return out


def border_is_paper(half, gray, w, h, sibling=None):
    """Does the PAGE run off the frame on the side that is THIS half's alone? (the cut-page test)

    SUBTASK H32 STEP 3(b,c). A fold split separates two halves along the width direction, so the
    border that distinguishes them is the one on each half's OUTER side. Borders shared by both
    halves (typically top/bottom of a portrait page) say nothing about which side is the partial
    page — an earlier version that sampled every reached border marked a page as "cut" merely for
    filling the frame vertically, and was replaced after the H32 unit tests caught it.

    For each frame border this half reaches AND the [sibling] half does not, sample luma ON that
    border within the half's extent: paper-like (>= PAPER_BORDER_RATIO of the half's own paper
    luma) means the page material continues past the frame => PARTIAL page. With no sibling every
    reached border counts. Returns (is_cut, worst_border_luma, paper_luma).
    """
    q = np.asarray(half, dtype=np.float64)
    # paper reference inside the half
    gx, gy = np.meshgrid(np.linspace(q[:, 0].min(), q[:, 0].max(), 24),
                         np.linspace(q[:, 1].min(), q[:, 1].max(), 24))
    inside = np.array([M.point_in_convex_quad((gx[i, j], gy[i, j]), q)
                       for i in range(24) for j in range(24)]).reshape(24, 24)
    ix = np.clip(gx.astype(np.int32), 0, w - 1)
    iy = np.clip(gy.astype(np.int32), 0, h - 1)
    vals = gray[iy, ix][inside] if inside.any() else np.array([0.5])
    paper = float(np.percentile(vals, PAPER_REF_PCT))
    tol = FRAME_TOL_FRAC * min(w, h)

    def patch_mean(x0, x1, y0, y1):
        x0i, x1i = int(np.clip(x0, 0, w - 1)), int(np.clip(x1, 0, w - 1))
        y0i, y1i = int(np.clip(y0, 0, h - 1)), int(np.clip(y1, 0, h - 1))
        if x1i <= x0i or y1i <= y0i:
            return 0.0
        return float(gray[y0i:y1i + 1, x0i:x1i + 1].mean())

    minx, maxx = float(q[:, 0].min()), float(q[:, 0].max())
    miny, maxy = float(q[:, 1].min()), float(q[:, 1].max())
    borders = _reached_borders(q, w, h, tol)
    if sibling is not None:
        borders -= _reached_borders(np.asarray(sibling, dtype=np.float64), w, h, tol)
    samples = []
    if 0 in borders:  # left border, this half alone
        samples.append(patch_mean(0, BORDER_PATCH, miny + 0.25 * (maxy - miny), miny + 0.75 * (maxy - miny)))
    if 1 in borders:  # right border, this half alone
        samples.append(patch_mean(w - 1 - BORDER_PATCH, w - 1, miny + 0.25 * (maxy - miny), miny + 0.75 * (maxy - miny)))
    if 2 in borders:  # top border, this half alone
        samples.append(patch_mean(minx + 0.25 * (maxx - minx), minx + 0.75 * (maxx - minx), 0, BORDER_PATCH))
    if 3 in borders:  # bottom border, this half alone
        samples.append(patch_mean(minx + 0.25 * (maxx - minx), minx + 0.75 * (maxx - minx), h - 1 - BORDER_PATCH, h - 1))
    if not samples:
        return False, 0.0, paper
    worst = max(samples)
    return worst >= PAPER_BORDER_RATIO * max(paper, 1e-6), worst, paper


# ==========================================================================================
# Guarded brightness-valley fold (fallback for spreads whose gutter has no line evidence)
# ==========================================================================================

VALLEY_SEARCH = (0.20, 0.80)
VALLEY_MIN_CONTRAST = 0.03   # valley must sit this far below the profile mean (luma 0..1)
VALLEY_LOCAL_WIN = 9


def valley_fold(quad, gray, w, h):
    """A guarded vertical fold at the strongest interior brightness valley of the quad.

    Only meaningful for spread-signature quads (the caller gates on aspect). A real gutter is a
    local luma minimum running the full height of the spread (gutter shadow / the gap between
    the pages). Guards: the valley must be interior ([VALLEY_SEARCH]), a true LOCAL minimum
    (below both neighbours in a window), and deep enough ([VALLEY_MIN_CONTRAST]). Returns the
    fold segment in frame pixels, or None. Never used when a line-evidence fold was accepted —
    line evidence always wins.
    """
    q = np.asarray(quad, dtype=np.float64)
    GW, GH = 64, 96
    pts = np.zeros((GH, GW), dtype=np.float32)
    for i in range(GH):
        v = i / float(GH - 1)
        for j in range(GW):
            u = j / float(GW - 1)
            t0 = q[0] + (q[1] - q[0]) * u
            b0 = q[3] + (q[2] - q[3]) * u
            p = t0 + (b0 - t0) * v
            pts[i, j] = gray[int(np.clip(p[1], 0, h - 1)), int(np.clip(p[0], 0, w - 1))]
    col = pts.mean(axis=0)
    smooth = np.convolve(col, np.ones(5) / 5.0, mode="same")
    lo, hi = int(VALLEY_SEARCH[0] * GW), int(VALLEY_SEARCH[1] * GW)
    win = VALLEY_LOCAL_WIN // 2
    best_u = None
    best_depth = 0.0
    for j in range(lo + win, hi - win):
        window = smooth[j - win:j + win + 1]
        if smooth[j] > window.min() + 1e-9:
            continue  # not the local minimum of its own window
        depth = float(smooth.mean() - smooth[j])
        if depth > best_depth:
            best_depth = depth
            best_u = j / float(GW - 1)
    if best_u is None or best_depth < VALLEY_MIN_CONTRAST:
        return None
    t0 = q[0] + (q[1] - q[0]) * best_u
    b0 = q[3] + (q[2] - q[3]) * best_u
    return np.array([t0[0], t0[1], b0[0], b0[1]], dtype=np.float64)


# ==========================================================================================
# The policy engine
# ==========================================================================================

CFG = {
    "base":       dict(max_strip=MAX_STRIP_BASE, gap_guard=False, keep="larger", all_cands=False,
                       chord=CHORD_BASE, adaptive=False, select="first"),
    "d":          dict(max_strip=MAX_STRIP_H32, gap_guard=False, keep="larger", all_cands=True,
                       chord=CHORD_BASE, adaptive=False, select="first"),
    "da":         dict(max_strip=MAX_STRIP_H32, gap_guard=True, keep="larger", all_cands=True,
                       chord=CHORD_BASE, adaptive=False, select="first"),
    "da_bcgeom":  dict(max_strip=MAX_STRIP_H32, gap_guard=True, keep="less_contact", all_cands=True,
                       chord=CHORD_BASE, adaptive=False, select="first"),
    "da_bcpix":   dict(max_strip=MAX_STRIP_H32, gap_guard=True, keep="non_cut_border", all_cands=True,
                       chord=CHORD_BASE, adaptive=False, select="first"),
    "d_bcgeom":   dict(max_strip=MAX_STRIP_H32, gap_guard=False, keep="less_contact", all_cands=True,
                       chord=CHORD_BASE, adaptive=False, select="first"),
    "d_bcpix":    dict(max_strip=MAX_STRIP_H32, gap_guard=False, keep="non_cut_border", all_cands=True,
                       chord=CHORD_BASE, adaptive=False, select="first"),
    "a_only":     dict(max_strip=MAX_STRIP_BASE, gap_guard=True, keep="larger", all_cands=False,
                       chord=CHORD_BASE, adaptive=False, select="first"),
    "bcgeom_only": dict(max_strip=MAX_STRIP_BASE, gap_guard=False, keep="less_contact", all_cands=False,
                        chord=CHORD_BASE, adaptive=False, select="first"),
    "bcpix_only": dict(max_strip=MAX_STRIP_BASE, gap_guard=False, keep="non_cut_border", all_cands=False,
                       chord=CHORD_BASE, adaptive=False, select="first"),
    # ---- the H32 production design (mirrors the Kotlin change) ----------------------------
    "h32_full":   dict(max_strip=MAX_STRIP_H32, gap_guard=False, keep="maxprior", all_cands=True,
                       chord=CHORD_H32, adaptive=True, select="maxprior"),
    "h32_nochord": dict(max_strip=MAX_STRIP_H32, gap_guard=False, keep="maxprior", all_cands=True,
                        chord=CHORD_BASE, adaptive=True, select="maxprior"),
    "h32_chord70": dict(max_strip=MAX_STRIP_H32, gap_guard=False, keep="maxprior", all_cands=True,
                        chord=0.70, adaptive=True, select="maxprior"),
    # ---- H32 v2: pixel border-is-paper penalty + guarded valley fold for no-line spreads ----
    "h32_v2":     dict(max_strip=MAX_STRIP_H32, gap_guard=False, keep="maxprior", all_cands=True,
                       chord=0.70, adaptive=True, select="maxprior", valley=True),
    "h32_v2_novalley": dict(max_strip=MAX_STRIP_H32, gap_guard=False, keep="maxprior", all_cands=True,
                            chord=0.70, adaptive=True, select="maxprior", valley=False),
    # isolation: identical to h32_v2_novalley but WITHOUT the pixel cut penalty
    # (the prior alone selects) — to measure what the penalty itself contributes.
    "h32_nopen":  dict(max_strip=MAX_STRIP_H32, gap_guard=False, keep="maxprior", all_cands=True,
                       chord=0.70, adaptive=True, select="maxprior", valley=False, pixel_penalty=False),
    # chord 0.65 + pixel border penalty, no valley: does the pixel penalty pick the interior
    # side where the geometry penalty picked the cut side (rb012)?
    "h32_v3":     dict(max_strip=MAX_STRIP_H32, gap_guard=False, keep="maxprior", all_cands=True,
                       chord=0.65, adaptive=True, select="maxprior", valley=False),
}


def run_policy(quad, segs, gray, w, h, variant):
    """Returns (out_quad | None, info). None = not applied (input quad stands)."""
    cfg = CFG[variant]
    if quad is None:
        return None, {"reason": "no_input"}
    if segs is None or len(segs) == 0:
        return None, {"reason": "no_segments"}
    max_strip = cfg["max_strip"]
    if cfg.get("adaptive"):
        max_strip = max_strip_for(quad, True)
    cands = fold_candidates(quad, segs, max_strip, chord=cfg.get("chord", CHORD_BASE))
    if not cands and not cfg.get("valley"):
        return None, {"reason": "no_interior_line"}
    if cands and not cfg["all_cands"]:
        # shipped behaviour: nearest candidate per side only
        best = {}
        for side, dist, seg, ev in cands:
            if side not in best or dist < best[side][0]:
                best[side] = (dist, seg, ev)
        cands = [(s, v[0], v[1], v[2]) for s, v in best.items()]
        cands.sort(key=lambda t: t[1])

    q = np.asarray(quad, dtype=np.float64)
    width = float(np.linalg.norm((q[1] - q[0] + q[2] - q[3]) / 2.0))
    accepted = []
    for side, dist, seg, ev in (cands or []):
        halves = M.split_quad_by_line(q, seg)
        if halves[0] is None or halves[1] is None:
            continue
        h0, h1 = halves
        areas = [C.polygon_area(h0), C.polygon_area(h1)]
        total = areas[0] + areas[1]
        if total <= 1e-9:
            continue
        strip_frac = min(areas) / total
        if strip_frac < REPAIR_MIN_STRIP or strip_frac > max_strip:
            continue
        if cfg["gap_guard"]:
            # skip the fold segment itself when testing endpoints abutting it
            skip = None
            for i, s2 in enumerate(np.asarray(segs, dtype=np.float64)):
                if abs(s2[0] - seg[0]) < 1e-6 and abs(s2[1] - seg[1]) < 1e-6 and \
                   abs(s2[2] - seg[2]) < 1e-6 and abs(s2[3] - seg[3]) < 1e-6:
                    skip = i
                    break
            ok, fl, fr, gl, gr = margin_gap(q, seg, segs, skip_idx=skip)
            if not ok:
                continue
        # --- side choice -------------------------------------------------------------------
        keep_idx = 0 if areas[0] >= areas[1] else 1
        if cfg["keep"] == "less_contact":
            c0 = half_frame_contact(h0, w, h)
            c1 = half_frame_contact(h1, w, h)
            if abs(c0 - c1) > 1.0:
                keep_idx = 0 if c0 < c1 else 1
        elif cfg["keep"] == "non_cut_border":
            p0 = border_is_paper(h0, gray, w, h)[0]
            p1 = border_is_paper(h1, gray, w, h)[0]
            if p0 != p1:
                keep_idx = 1 if p0 else 0
        keep = C.order_corners(halves[keep_idx])
        if not R.accepts_repair(q, keep, strip_frac):
            continue
        ok, why = C.is_plausible_page_quad(keep, w, h)
        if not ok:
            continue
        score = R.paper_aspect_prior(keep)
        if cfg["keep"] == "maxprior" or cfg.get("select") == "maxprior":
            # H32 side choice: among accepted splits, the one that turns the quad into the
            # best-shaped PAGE wins. Plus the partial-page term: a kept half whose outward frame
            # border is still PAPER is a cut page (STEP 3 b/c) and is penalised.
            if cfg.get("pixel_penalty", True):
                cut0, l0, p0 = border_is_paper(h0, gray, w, h, sibling=h1)
                cut1, l1, p1 = border_is_paper(h1, gray, w, h, sibling=h0)
                if keep_idx == 0 and cut0 and not cut1:
                    score -= 0.25
                if keep_idx == 1 and cut1 and not cut0:
                    score -= 0.25
        accepted.append((score, keep, side, strip_frac))
        if cfg.get("select") != "maxprior":
            return keep, {"reason": "split(%s,strip=%.3f)" % (side, strip_frac), "side": side,
                          "strip_frac": strip_frac}
    if accepted:
        accepted.sort(key=lambda t: -t[0])
        score, keep, side, strip_frac = accepted[0]
        return keep, {"reason": "split(%s,strip=%.3f,prior=%.3f)" % (side, strip_frac, score),
                      "side": side, "strip_frac": strip_frac}

    # ---- guarded brightness-valley fold: no line evidence, but the quad is a spread --------
    if cfg.get("valley") and quad_asp(q) >= SPREAD_ASPECT_MIN:
        seg = valley_fold(q, gray, w, h)
        if seg is not None:
            halves = M.split_quad_by_line(q, seg)
            if halves[0] is not None and halves[1] is not None:
                h0, h1 = halves
                areas = [C.polygon_area(h0), C.polygon_area(h1)]
                total = areas[0] + areas[1]
                if total > 1e-9:
                    strip_frac = min(areas) / total
                    if REPAIR_MIN_STRIP <= strip_frac <= max_strip:
                        keep_idx = 0 if areas[0] >= areas[1] else 1
                        cut0, _, _ = border_is_paper(h0, gray, w, h, sibling=h1)
                        cut1, _, _ = border_is_paper(h1, gray, w, h, sibling=h0)
                        if cut0 and not cut1:
                            keep_idx = 1
                        elif cut1 and not cut0:
                            keep_idx = 0
                        keep = C.order_corners(halves[keep_idx])
                        if R.accepts_repair(q, keep, strip_frac):
                            ok, _why = C.is_plausible_page_quad(keep, w, h)
                            if ok:
                                return keep, {"reason": "valley_split(strip=%.3f)" % strip_frac,
                                              "side": "valley", "strip_frac": strip_frac}
    return None, {"reason": "no_accepted_split"}


# ==========================================================================================
# Scoring
# ==========================================================================================

def score_real(item, quad):
    """Failure semantics (aligned with RealBookSpreadDetectionTest):

    merged       — the quad absorbed BOTH annotated pages (cover >= 0.5 of each). A quad that is
                   exactly one page is never "merged", even on a full-spread control where the
                   other page is a legitimate single-page target.
    wrong-page   — only on PARTIAL-page scenes (the owner's directive): the quad sits mostly on
                   the page that is NOT fully seen.
    target_mismatch — on full-spread/blank scenes, the quad picked the non-annotated page. A
                   single-page pick either way; reported separately, never counted as wrong-page.
    """
    row = {"detected": quad is not None}
    if quad is None:
        row.update({"merged": None, "wrong": None, "strict": None, "best": None})
        return row
    q = np.asarray(quad, dtype=np.float64)
    cover_t = R.coverage(item.page, q)
    cover_o = R.coverage(item.facing, q) if item.facing is not None else 0.0
    iou_t = R.iou_of(q, item.page)
    iou_o = R.iou_of(q, item.facing) if item.facing is not None else 0.0
    merged = (cover_t >= 0.50 and cover_o >= 0.50)
    is_partial = item.facing_side is not None
    wrong = bool(is_partial and iou_o > iou_t and iou_o >= R.WRONG_PAGE_IOU)
    target_mismatch = bool((not is_partial) and item.facing is not None and
                           iou_o > iou_t and iou_o >= R.WRONG_PAGE_IOU and not merged)
    strict = 100.0 * R.align_measure(q, item.page) / max(item.diagonal, 1.0)
    if item.facing is not None and not is_partial:
        best_px = min(R.align_measure(q, item.page), R.align_measure(q, item.facing))
        best = 100.0 * best_px / max(item.diagonal, 1.0)
    else:
        best = strict
    row.update({"merged": bool(merged), "wrong": wrong, "mismatch": target_mismatch,
                "strict": strict, "best": best,
                "iou_t": iou_t, "iou_o": iou_o, "cover_t": cover_t, "cover_o": cover_o})
    return row


def score_synth(item, quad):
    if quad is None:
        return {"detected": False, "merged": False, "corner": None, "iou": None}
    q = np.asarray(quad, dtype=np.float64)
    cover = R.coverage(item.facing, q) if item.facing is not None else 0.0
    return {
        "detected": True,
        "merged": bool(item.sliver_fraction > 0.0 and cover >= R.COVER_MERGED),
        "corner": 100.0 * R.align_measure(q, item.page) / max(item.diagonal, 1.0),
        "iou": R.iou_of(q, item.page),
    }


# ==========================================================================================
# Device red input quads
# ==========================================================================================

def parse_device_quads(log_path):
    out = {}
    pat = re.compile(r"H32_REAL name=(\S+) .*?quad=([0-9,;]+)")
    with open(log_path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            m = pat.search(line)
            if not m:
                continue
            name = m.group(1)
            pts = [tuple(float(v) for v in p.split(",")) for p in m.group(2).split(";")]
            if len(pts) == 4:
                out[name] = C.order_corners(np.asarray(pts, dtype=np.float64))
    return out


# ==========================================================================================
# Main
# ==========================================================================================

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--variant", default="all")
    args = ap.parse_args()
    variants = list(CFG) if args.variant == "all" else [args.variant]

    corner = ort.InferenceSession(R.CORNER_MODEL, providers=["CPUExecutionProvider"])
    mlsd = ort.InferenceSession(R.MLSD_MODEL, providers=["CPUExecutionProvider"])

    # ---- real set: input quads from the device red run ----
    dev_quads = parse_device_quads(os.path.join(OUT_DIR, "H32RED", "logcat.txt"))
    real_items = R.load_items("real")

    # pre-compute M-LSD segments once per real image
    real_cache = []
    for it in real_items:
        rgb = R.load_rgb(it.path)
        h, w = rgb.shape[:2]
        segs = M.pred_lines(rgb, mlsd, input_shape=(MLSD_EDGE, MLSD_EDGE), score_thr=MLSD_SCORE_THR)
        quad0 = dev_quads.get(it.asset)
        real_cache.append((it, rgb, gray_of(rgb), w, h, segs, quad0))

    # ---- synthetic set: input quads from the corner model (matches the device) ----
    synth_items = R.load_items("synth")
    synth_cache = []
    for it in synth_items:
        rgb = R.load_rgb(it.path)
        h, w = rgb.shape[:2]
        segs = M.pred_lines(rgb, mlsd, input_shape=(MLSD_EDGE, MLSD_EDGE), score_thr=MLSD_SCORE_THR)
        quad0, _info = C.detect(corner, rgb)
        if quad0 is not None:
            quad0 = np.asarray(quad0, dtype=np.float64)
        synth_cache.append((it, rgb, gray_of(rgb), w, h, segs, quad0))

    report = {}
    for variant in variants:
        rows_real = []
        for it, rgb, gray, w, h, segs, quad0 in real_cache:
            out_q, info = run_policy(quad0, segs, gray, w, h, variant)
            final = out_q if out_q is not None else quad0
            sc = score_real(it, final)
            sc.update({"name": it.name, "scene": it.scene, "decision": info.get("reason", ""),
                       "input": "device"})
            rows_real.append(sc)
        rows_synth = []
        for it, rgb, gray, w, h, segs, quad0 in synth_cache:
            out_q, info = run_policy(quad0, segs, gray, w, h, variant)
            final = out_q if out_q is not None else quad0
            sc = score_synth(it, final)
            sc.update({"name": it.name, "decision": info.get("reason", "")})
            rows_synth.append(sc)

        det_r = [r for r in rows_real if r["detected"]]
        sliver = [r for r in rows_synth if r["name"].split("/")[0].startswith("bs0") and
                  "control" not in r["name"] and "gutter" not in r["name"]]
        det_s = [r for r in rows_synth if r["detected"]]
        summary = {
            "real": {
                "n": len(rows_real),
                "detected": len(det_r),
                "merged": sum(1 for r in rows_real if r["merged"]),
                "wrong": sum(1 for r in rows_real if r["wrong"]),
                "mismatch": sum(1 for r in rows_real if r.get("mismatch")),
                "mean_strict": float(np.mean([r["strict"] for r in det_r])) if det_r else None,
                "mean_best": float(np.mean([r["best"] for r in det_r])) if det_r else None,
            },
            "synth": {
                "n": len(rows_synth),
                "detected": len(det_s),
                "merged_slivers": sum(1 for r in sliver if r["merged"]),
                "sliver_total": len(sliver),
                "mean_corner": float(np.mean([r["corner"] for r in det_s])) if det_s else None,
                "mean_iou": float(np.mean([r["iou"] for r in det_s])) if det_s else None,
            },
        }
        report[variant] = {"summary": summary, "rows_real": rows_real, "rows_synth": rows_synth}
        sr, ss = summary["real"], summary["synth"]
        print("[%s] REAL  det=%d/%d merged=%d wrong=%d strict=%.4f best=%.4f" % (
            variant, sr["detected"], sr["n"], sr["merged"], sr["wrong"],
            sr["mean_strict"] or -1, sr["mean_best"] or -1))
        print("[%s] SYNTH det=%d/%d mergedSlivers=%d/%d corner=%.4f iou=%.4f" % (
            variant, ss["detected"], ss["n"], ss["merged_slivers"], ss["sliver_total"],
            ss["mean_corner"] or -1, ss["mean_iou"] or -1))

    out_path = os.path.join(OUT_DIR, "policy_ab.json")
    # accumulate across runs so a --variant re-run never erases earlier variants
    merged_report = {}
    if os.path.exists(out_path):
        try:
            with open(out_path, encoding="utf-8") as fh:
                merged_report = json.load(fh)
        except (ValueError, OSError):
            merged_report = {}
    merged_report.update(report)
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(merged_report, fh, indent=2)
    print("wrote %s (%d variants)" % (out_path, len(merged_report)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
