#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H30 — the merge rejection/repair rule, in one place.

The problem
-----------
Both corner models answer "where is the sheet of paper?". On an open book that
sheet is the whole spread, so the returned quad contains the facing page. No
weight file fixes that (H29 §5); only evidence about where *this page* ends does.

What this rule does
-------------------
Given a candidate quad `Q` and the line segments M-LSD found in the frame:

1. **Find the fold.** A segment qualifies as a fold candidate when it is
   spine-parallel (within [ANGLE_TOL_DEG] of the quad's own left/right edge
   direction), long enough to be a page edge ([MIN_LEN_FRAC] of the quad's
   height), and its *line* spans the quad's full extent along that direction
   ([CHORD_FRAC]) — measured on the infinite line, because M-LSD segments
   routinely stop a few pixels short of the border they mark.
2. **Only near a side.** The fold's distance from the quad's left (or right)
   edge must be at least [MIN_STRIP_FRAC] of the quad's width — otherwise it is
   the page's own edge shadow (bs06's gutter shadow sits 3.5 % inside the page
   and is *not* a facing page) — and at most [MAX_STRIP_FRAC], or the "sliver"
   would be the page.
3. **Split and choose.** The split yields two halves; the page is the half that
   contains the text block. The area prior is the primary signal (the requested
   page dominates the frame; the facing page is the add-on), and the text-block
   score overrides it only when it is *much* stronger. Both are reported.
4. **Do no harm.** The chosen half must still pass the app's own
   `isPlausiblePageQuad`, and must be at least [MIN_HALF_AREA_FRAC] of the frame.
   Any failed condition returns the input quad unchanged with a reason string.

Everything here is mirrored in Kotlin (`MlsdMergeRepair.kt`); the constants and
the reason strings are deliberately identical so the on-device `[MLSD]` log line
and the offline JSON can be read side by side.
"""

import numpy as np

import h30_corner as C
import h30_mlsd as M

MERGE_CFG = {
    "angle_tol_deg": 25.0,
    "min_len_frac": 0.45,
    "chord_frac": 0.80,
    "min_strip_frac": 0.05,
    "max_strip_frac": 0.45,
    "min_half_area_frac": 0.12,
    # A text score this many times stronger than the other half's overrides the
    # area prior. 0.55 is "clearly richer", not "slightly richer".
    "text_override_ratio": 0.55,
    # Which side selector decides the page: "area" (measured winner) or "text".
    # MEASURED, not assumed: on the 18 fixtures the text selector chose the
    # facing-page sliver on bs02/bs03/bs07 preview and scored 3/12 merged,
    # 3.8984 % corner, while the area prior scored 0/12 merged and 3.6923 %.
    # The text score is still computed and reported so the decision is auditable.
    "selector": "area",
}


def _cfg(cfg):
    p = dict(MERGE_CFG)
    if cfg:
        p.update(cfg)
    return p


def unit(v):
    n = float(np.hypot(v[0], v[1]))
    return None if n < 1e-9 else (v[0] / n, v[1] / n)


def edge_dirs(quad):
    """Unit directions of the TL→TR (width) and TL→BL (height) axes."""
    q = np.asarray(quad, dtype=np.float64)
    wdir = unit((q[1] - q[0] + q[2] - q[3]) / 2.0)
    hdir = unit((q[3] - q[0] + q[2] - q[1]) / 2.0)
    return wdir, hdir


def point_seg_dist(p, a, b):
    p = np.asarray(p, dtype=np.float64)
    a = np.asarray(a, dtype=np.float64)
    b = np.asarray(b, dtype=np.float64)
    ab = b - a
    denom = float(np.dot(ab, ab))
    if denom < 1e-12:
        return float(np.linalg.norm(p - a))
    t = float(np.clip(np.dot(p - a, ab) / denom, 0.0, 1.0))
    return float(np.linalg.norm(p - (a + t * ab)))


def _line_crossing_fractions(quad, seg):
    """Where the **infinite line** of `seg` crosses the quad's two long edges.

    Returns `(u_top, u_bottom)` as fractions along TL→TR and BL→BR, or `None`
    when the line is parallel to them.
    """
    q = np.asarray(quad, dtype=np.float64)
    x1, y1, x2, y2 = [float(v) for v in seg]
    dx, dy = x2 - x1, y2 - y1
    out = []
    for a, b in ((q[0], q[1]), (q[3], q[2])):
        ex, ey = b[0] - a[0], b[1] - a[1]
        denom = dx * ey - dy * ex
        if abs(denom) < 1e-12:
            return None
        # Solve a + u*e = s + t*d for u (u along the edge, t along the segment).
        u = ((x1 - a[0]) * dy - (y1 - a[1]) * dx) / -denom
        out.append(float(u))
    return out[0], out[1]


def find_merge_lines(quad, segments, cfg=None):
    """Best fold candidate on the **left** and on the **right** of the quad.

    Returns `{"left": (seg, ev) | None, "right": ...}`. "Best" means the one
    nearest that side, because a page's boundary is the *first* long
    spine-parallel line inside the quad measured from the outer edge, while a
    text-block margin sits further in.
    """
    p = _cfg(cfg)
    empty = {"left": None, "right": None}
    if quad is None or segments is None or len(segments) == 0:
        return empty
    q = np.asarray(quad, dtype=np.float64)
    wdir, hdir = edge_dirs(q)
    if wdir is None or hdir is None:
        return empty

    width = float(np.linalg.norm((q[1] - q[0] + q[2] - q[3]) / 2.0))
    height = float(np.linalg.norm((q[3] - q[0] + q[2] - q[1]) / 2.0))
    if width < 1e-6 or height < 1e-6:
        return empty

    best = {"left": None, "right": None}
    for seg in np.asarray(segments, dtype=np.float64):
        x1, y1, x2, y2 = seg
        d = unit((x2 - x1, y2 - y1))
        if d is None:
            continue
        cos_h = abs(d[0] * hdir[0] + d[1] * hdir[1])
        if cos_h < np.cos(np.radians(p["angle_tol_deg"])):
            continue
        length = float(np.hypot(x2 - x1, y2 - y1))
        if length < p["min_len_frac"] * height:
            continue
        if not M.line_through_quad(q, seg, min_inside_frac=p["chord_frac"]):
            continue
        crossings = _line_crossing_fractions(q, seg)
        if crossings is None:
            continue
        u_top, u_bottom = crossings
        # The fold must cut both long sides well away from the corners.
        if any(u < 0.05 or u > 0.95 for u in (u_top, u_bottom)):
            continue

        mid = np.asarray([(x1 + x2) / 2.0, (y1 + y2) / 2.0])
        d_left = point_seg_dist(mid, q[0], q[3]) / width
        d_right = point_seg_dist(mid, q[1], q[2]) / width
        ev = {
            "len": length,
            "cos_spine": cos_h,
            "d_left": d_left,
            "d_right": d_right,
            "u_top": u_top,
            "u_bottom": u_bottom,
        }
        for side, dist in (("left", d_left), ("right", d_right)):
            if dist < p["min_strip_frac"] or dist > p["max_strip_frac"]:
                continue
            cur = best[side]
            if cur is None or dist < cur[1]["d_side"]:
                best[side] = (seg, dict(ev, d_side=dist))
    return best


def text_block_score(quad, gray, grid=40):
    """How much of a *compact* text block this polygon contains.

    Sampled on a `grid × grid` lattice over the polygon's bounding box (only
    points inside are used), so it is cheap enough for the device. Ink is
    "clearly darker than this region's own paper", where paper is the region's
    75th percentile — that makes the score exposure-invariant within the region.
    Returns `(ink_fraction * row_coverage * col_coverage, ink_fraction)`.
    """
    q = np.asarray(quad, dtype=np.float64)
    x0, y0 = q[:, 0].min(), q[:, 1].min()
    x1, y1 = q[:, 0].max(), q[:, 1].max()
    if x1 - x0 < 2 or y1 - y0 < 2:
        return 0.0, 0.0
    xs = np.linspace(x0, x1, grid)
    ys = np.linspace(y0, y1, grid)
    gx, gy = np.meshgrid(xs, ys)
    inside = np.zeros_like(gx, dtype=bool)
    for i in range(grid):
        for j in range(grid):
            inside[i, j] = M.point_in_convex_quad((gx[i, j], gy[i, j]), q)
    if inside.sum() < 16:
        return 0.0, 0.0
    h, w = gray.shape
    ix = np.clip(gx.astype(np.int32), 0, w - 1)
    iy = np.clip(gy.astype(np.int32), 0, h - 1)
    vals = gray[iy, ix][inside]
    paper = float(np.percentile(vals, 75))
    ink = vals < 0.72 * paper
    ink_frac = float(ink.mean())
    mask = np.zeros((grid, grid), dtype=bool)
    mask[inside] = ink
    rows = np.where(mask.any(axis=1))[0]
    cols = np.where(mask.any(axis=0))[0]
    row_cov = (rows.max() - rows.min() + 1) / float(grid) if rows.size else 0.0
    col_cov = (cols.max() - cols.min() + 1) / float(grid) if cols.size else 0.0
    return ink_frac * row_cov * col_cov, ink_frac


def choose_page_side(halves, gray, cfg=None):
    """Pick the page half. Returns `(index, evidence)`; both signals reported.

    * `area` — the requested page is the dominant part of the frame and the
      facing page is the add-on. This is the prior.
    * `text` — [`text_block_score`], the literal "contains the page's text block"
      test the subtask asks for.

    The text signal overrides the area prior only when it is clearly stronger
    ([text_override_ratio]); otherwise the larger half wins. The asymmetry is
    deliberate: the area prior is robust on this failure and a noisy text
    estimate must not be able to flip a clear geometric answer.
    """
    p = _cfg(cfg)
    areas = [C.polygon_area(h) for h in halves]
    total = sum(areas)
    if total <= 1e-9:
        return 0, {"reason": "degenerate", "area_fracs": [0.0, 0.0],
                   "text_scores": [0.0, 0.0], "ink_fracs": [0.0, 0.0]}
    texts = [text_block_score(h, gray) for h in halves]
    area_fracs = [a / total for a in areas]
    text_scores = [t[0] for t in texts]

    by_area = 0 if areas[0] >= areas[1] else 1
    tmax = max(text_scores)
    by_text = 0 if text_scores[0] >= text_scores[1] else 1

    choice = by_area
    reason = "area"
    if p["selector"] == "text":
        choice = by_text
        reason = "text"
        if tmax > 0.0 and by_text != by_area and (min(text_scores) / tmax) < p["text_override_ratio"]:
            choice = by_text
            reason = "text"
        else:
            choice = by_area
            reason = "area(text_tie)"
    return choice, {
        "reason": reason,
        "selector": p["selector"],
        "area_fracs": area_fracs,
        "text_scores": text_scores,
        "ink_fracs": [t[1] for t in texts],
    }


def apply(quad, segments, gray, cfg=None, splitter=None):
    """Run the rule. Returns `(quad_out, info)`; never raises on bad input."""
    p = _cfg(cfg)
    info = {"applied": False, "reason": "no_input"}
    if quad is None:
        return quad, info
    if segments is None or len(segments) == 0:
        info["reason"] = "no_segments"
        return quad, info
    if splitter is None:
        splitter = M.split_quad_by_line

    q = np.asarray(quad, dtype=np.float64)
    width = float(np.linalg.norm((q[1] - q[0] + q[2] - q[3]) / 2.0))
    best = find_merge_lines(q, segments, p)
    if best["left"] is None and best["right"] is None:
        info["reason"] = "no_interior_line"
        return quad, info

    # Choose the side whose strip is thinner: that is the add-on. If both sides
    # qualify, the thinner strip is still the more likely "extra page".
    candidates = [(side, best[side]) for side in ("left", "right") if best[side] is not None]
    candidates.sort(key=lambda kv: kv[1][1]["d_side"])
    side, (line, ev) = candidates[0]

    halves = list(splitter(q, line))
    if halves[0] is None or halves[1] is None:
        info.update({"reason": "line_misses", "side": side, "evidence": ev})
        return quad, info

    choice, choice_info = choose_page_side(halves, gray, p)
    chosen = C.order_corners(halves[choice])
    strip = halves[1 - choice]
    strip_area = C.polygon_area(strip)
    chosen_area = C.polygon_area(chosen)
    strip_frac = strip_area / max(strip_area + chosen_area, 1e-9)

    frame_area = float(gray.shape[0] * gray.shape[1])
    if strip_frac < p["min_strip_frac"] or strip_frac > p["max_strip_frac"]:
        info.update({"reason": "strip_size", "side": side, "evidence": ev,
                     "strip_frac": strip_frac})
        return quad, info
    if chosen_area / frame_area < p["min_half_area_frac"]:
        info.update({"reason": "half_too_small", "side": side, "evidence": ev,
                     "strip_frac": strip_frac})
        return quad, info
    ok, why = C.is_plausible_page_quad(chosen, gray.shape[1], gray.shape[0])
    if not ok:
        info.update({"reason": "half_implausible:" + why, "side": side, "evidence": ev,
                     "strip_frac": strip_frac})
        return quad, info

    info.update({
        "applied": True,
        "reason": "split",
        "side": side,
        "evidence": ev,
        "choice": choice_info,
        "strip_frac": strip_frac,
        "chosen_quad": chosen,
    })
    return chosen, info


def choose_side_by_area_only(halves):
    """Area-only selector (used by the offline A/B to isolate the text signal)."""
    areas = [C.polygon_area(h) for h in halves]
    return 0 if areas[0] >= areas[1] else 1
