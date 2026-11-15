#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H30 — M-LSD post-processing ported to plain numpy (no OpenCV, no TensorFlow).

Why this file exists
--------------------
`tooling/out/h29/candidates/mlsd_utils_onnx.py` (NAVER M-LSD, Apache-2.0) is the
reference square decoder, but it cannot run on this machine: it imports `cv2`
(for `resize`) and `tensorflow` (for the 5x5 max-pool NMS and `top_k`). H29
therefore recorded M-LSD's contract and **no score**. This module is a faithful,
dependency-free port of the same geometry so the model can actually be scored,
and — because the algorithm also has to run on the device — so the Kotlin port
has a reference implementation that unit tests can pin.

What is ported, and from where
------------------------------
* `pred_lines`   (reference lines 12-61)  -> `pred_lines`
* `pred_squares` (reference lines 64-604) -> `pred_squares`, built from
    - `decode_junctions`  : junction + score + displacement maps -> segments
    - `merge_segments`    : Hough (distance, theta) quantisation + NMS + top-k
                            -> one merged segment per voted line
    - `find_squares`      : intersections, corner classification, junction
                            graph walk, NAVER's degree/overlap/length/area/centre
                            score
* `cv2.resize(..., INTER_AREA)` -> `resize_area` (exact box/area filter).

Documented deviations from the reference (all deliberate, none silent)
----------------------------------------------------------------------
1. **No uint8 rounding in the resize.** `INTER_AREA` on a uint8 image rounds to
   the nearest integer after averaging; we keep float32. The model input is
   float32 anyway, so this only removes a rounding step (<= 0.5/255 per pixel).
2. **Deterministic top-k.** `tf.math.top_k` leaves tie order unspecified; we use
   a stable descending argsort, so equal accumulator counts resolve to the
   lowest flat index. This makes the port reproducible run-to-run, which is a
   requirement on the device too.
3. **Bounds-safe neighbourhood reads.** The reference indexes
   `idx_map[y + dy, x + dx]` for a peak on the accumulator border, which would
   raise/wrap; we clamp.
4. **`hough[-1]` guard.** The reference can read `hough[indice]` when
   `indice == -1` (an empty accumulator cell with `cnt > 1`); we skip it.
5. **Identical merged segments are de-duplicated** and the corner-graph walk is
   bounded ([MAX_SQUARE_CANDIDATES]). The reference can emit the same square
   several times and, on a dense junction map, enumerate combinatorially; both
   are unbounded work on-device. The de-duplication changes no *distinct*
   square, only repeats.
6. **`inter_pts` truncation is kept** (`.astype(int32)`), because the corner
   angle classification is sensitive to it and we want to match NAVER, not
   "improve" them.

Nothing here is used by the shipped app directly: the app has the Kotlin port
(`MlsdSquareExtractor`), and this file is the offline scorer + the oracle the
Kotlin unit tests are checked against.
"""

import numpy as np

# ==========================================================================================
# NAVER's defaults (reference `pred_squares` signature)
# ==========================================================================================

DEFAULT_PARAMS = {
    "score": 0.06,
    "outside_ratio": 0.28,
    "inside_ratio": 0.45,
    "w_overlap": 0.0,
    "w_degree": 1.95,
    "w_length": 0.0,
    "w_area": 1.86,
    "w_center": 0.14,
}

#: Reference `dist_thr`: a junction whose two displacements are shorter than this
#: is not a usable line ("distance > 20.0" in map space).
DIST_THR = 20.0

#: Reference fixed accumulator sizing (512 // d_quant + 1, 360 // theta_quant + 1).
D_QUANT = 1
THETA_QUANT = 2
ACC_D_MAX = 512
ACC_THETA_MAX = 360

#: Hard bound on square candidates examined per frame (deviation 5).
MAX_SQUARE_CANDIDATES = 200000


# ==========================================================================================
# 1) Input preparation — cv2.resize(INTER_AREA) + alpha channel
# ==========================================================================================

def _area_weights(n_in, n_out):
    """`(n_out, n_in)` box-filter weight matrix: exact pixel-area overlap."""
    if n_out <= 0 or n_in <= 0:
        raise ValueError("resize to a non-positive size: %r -> %r" % (n_in, n_out))
    starts = np.arange(n_out, dtype=np.float64) * (float(n_in) / n_out)
    ends = (np.arange(n_out, dtype=np.float64) + 1.0) * (float(n_in) / n_out)
    m = np.zeros((n_out, n_in), dtype=np.float32)
    for i in range(n_out):
        lo = int(np.floor(starts[i]))
        hi = int(np.ceil(ends[i]))
        for j in range(lo, min(hi, n_in)):
            overlap = min(ends[i], j + 1.0) - max(starts[i], float(j))
            if overlap > 0.0:
                m[i, j] = overlap
    row = m.sum(axis=1, keepdims=True)
    row[row <= 0.0] = 1.0
    return m / row


def resize_area(image, size):
    """Separable exact-area resize, the numpy equivalent of `cv2.INTER_AREA`.

    `image` is `HxW`, `HxWxC` or `HxWxC` float; `size` is `(width, height)` to
    match the reference's `cv2.resize` argument order.
    """
    img = np.asarray(image, dtype=np.float32)
    if img.ndim == 2:
        img = img[:, :, None]
        squeeze = True
    else:
        squeeze = False
    h_in, w_in = img.shape[0], img.shape[1]
    w_out, h_out = int(size[0]), int(size[1])
    if h_in == h_out and w_in == w_out:
        out = img.copy()
    else:
        xw = _area_weights(w_in, w_out)
        yw = _area_weights(h_in, h_out)
        # (w_out, H, C): each output column is a weighted sum of input columns.
        tmp = np.tensordot(xw, img.transpose(1, 0, 2), axes=([1], [0]))
        tmp = tmp.transpose(1, 0, 2)
        # (h_out, W, C)
        out = np.tensordot(yw, tmp, axes=([1], [0]))
    return out[:, :, 0] if squeeze else out


def build_input(image, input_shape):
    """Resize to `input_shape` (square edge) and append the alpha channel = 1.

    The graph wants `[1, H, W, 4]` NHWC, 0-255, alpha 1 (H29 §6 / MODELS.md).
    """
    edge = int(input_shape[0])
    resized = resize_area(image, (edge, edge))
    if resized.ndim == 2:
        resized = np.repeat(resized[:, :, None], 3, axis=2)
    if resized.shape[2] == 4:
        resized = resized[:, :, :3]
    alpha = np.ones((edge, edge, 1), dtype=np.float32)
    return np.concatenate([resized, alpha], axis=-1)[None, ...].astype(np.float32)


# ==========================================================================================
# 2) Map decoding — reference `pred_lines` / `pred_squares` lines 99-126
# ==========================================================================================

def decode_junctions(junctions, scores, vmap, score_thr=0.10, dist_thr=DIST_THR):
    """Junction/score/displacement maps -> `(junctions[N,2], segments[N,4])`.

    Coordinates are **map space** (half the model input edge), exactly like the
    reference: a junction is `(y, x)` in `[0, H/2) x [0, W/2)`, and the
    displacement map stores *offsets* from that cell, so the segment endpoints
    are `x + disp_x_start` etc. (no doubling here; the doubling happens when the
    result is mapped back to the frame — see `map_to_frame`).
    """
    junc = np.asarray(junctions)
    sc = np.asarray(scores).reshape(-1)
    vm = np.asarray(vmap, dtype=np.float32)
    if junc.size == 0 or sc.size == 0 or vm.size == 0:
        return np.zeros((0, 2), dtype=np.float32), np.zeros((0, 4), dtype=np.float32)

    hm, wm = vm.shape[0], vm.shape[1]
    start = vm[:, :, :2]
    end = vm[:, :, 2:]
    dist_map = np.sqrt(np.sum((start - end) ** 2, axis=-1))

    pts = []
    segs = []
    for j, score in zip(junc.reshape(-1, 2), sc):
        y = int(j[0])
        x = int(j[1])
        if y < 0 or x < 0 or y >= hm or x >= wm:
            continue
        if not (score > score_thr and dist_map[y, x] > dist_thr):
            continue
        d0, d1, d2, d3 = vm[y, x, :]
        pts.append([float(x), float(y)])
        segs.append([x + float(d0), y + float(d1), x + float(d2), y + float(d3)])
    if not segs:
        return np.zeros((0, 2), dtype=np.float32), np.zeros((0, 4), dtype=np.float32)
    return np.asarray(pts, dtype=np.float32), np.asarray(segs, dtype=np.float32)


def _maxpool_same(a, k):
    """`MaxPool2D(pool_size=(k,k), strides=1, padding='same')` for a 2-D array."""
    pad = k // 2
    padded = np.pad(a, ((pad, pad), (pad, pad)), mode="constant", constant_values=-np.inf)
    win = np.lib.stride_tricks.sliding_window_view(padded, (k, k))
    return win.max(axis=(-1, -2))


def merge_segments(segments, topk):
    """Reference lines 127-215: Hough quantisation, 5x5 NMS, top-k, merge.

    Returns `(merged[N,4], theta_deg[M])` where row `i` of `merged` is one voted
    line `(x1, y1, x2, y2)` in map space and `theta_deg[i]` is its Hough angle
    (used to put the endpoints back in the right vertical order).
    """
    segments = np.asarray(segments, dtype=np.float64)
    if segments.shape[0] == 0:
        return np.zeros((0, 4), dtype=np.float64), np.zeros((0,), dtype=np.float64)

    start = segments[:, :2]
    end = segments[:, 2:]
    diff = start - end
    a = diff[:, 1]
    b = -diff[:, 0]
    c = a * start[:, 0] + b * start[:, 1]
    # Distance of the line from the origin (0, 0).
    d = np.abs(a * 0.0 + b * 0.0 - c) / np.sqrt(a ** 2 + b ** 2 + 1e-10)
    theta = np.arctan2(diff[:, 0], diff[:, 1]) * 180.0 / np.pi
    theta[theta < 0.0] += 180.0
    hough = np.concatenate([d[:, None], theta[:, None]], axis=-1)

    hq = np.floor(hough).astype(np.int64)
    hq[:, 0] //= D_QUANT
    hq[:, 1] //= THETA_QUANT

    _, indices, counts = np.unique(hq, axis=0, return_index=True, return_counts=True)

    acc_h = max(ACC_D_MAX // D_QUANT + 1, int(hq[:, 0].max()) + 1 if hq.size else 1)
    acc_w = max(ACC_THETA_MAX // THETA_QUANT + 1, int(hq[:, 1].max()) + 1 if hq.size else 1)
    acc = np.zeros((acc_h, acc_w), dtype=np.float32)
    idx_map = np.full((acc_h, acc_w), -1, dtype=np.int32)
    yx = hq[indices, :].astype(np.int64)
    acc[yx[:, 0], yx[:, 1]] = counts
    idx_map[yx[:, 0], yx[:, 1]] = indices

    # --- NMS: keep only the maximum of every 5x5 window -----------------------------
    peak = acc * (acc == _maxpool_same(acc, 5))

    # --- top-k (deterministic, deviation 2) -----------------------------------------
    flat = peak.reshape(-1)
    k = int(min(max(int(topk), 1), flat.size))
    order = np.argsort(-flat, kind="stable")[:k]
    width = peak.shape[1]

    # Rows sharing a quantised (distance, theta) cell, so the merge step can pull
    # the `counts - 1` extra duplicates the reference looks for.
    rows_by_hough = {}
    for i, row in enumerate(hq):
        rows_by_hough.setdefault((int(row[0]), int(row[1])), []).append(i)

    basis = 5 // 2
    merged = []
    merged_theta = []
    seen = set()
    for flat_idx in order:
        value = float(flat[flat_idx])
        if value <= 0.0:
            break
        yy = int(flat_idx) // width
        xx = int(flat_idx) % width
        max_indice = int(idx_map[yy, xx])
        if max_indice < 0:
            continue

        group = []
        for dy in range(-basis, basis + 1):
            for dx in range(-basis, basis + 1):
                ny = yy + dy
                nx = xx + dx
                if ny < 0 or nx < 0 or ny >= acc.shape[0] or nx >= acc.shape[1]:
                    continue  # deviation 3
                indice = int(idx_map[ny, nx])
                cnt = int(acc[ny, nx])
                if indice < 0:
                    continue  # deviation 4 (no hough[-1])
                group.append(indice)
                if cnt > 1:
                    key = (int(hq[indice, 0]), int(hq[indice, 1]))
                    duplicates = rows_by_hough.get(key, [])
                    check_cnt = 1
                    for new_indice in duplicates:
                        if new_indice == indice:
                            continue
                        group.append(new_indice)
                        check_cnt += 1
                        if check_cnt == cnt:
                            break
        if not group:
            continue
        pts = segments[group][:, :2].tolist() + segments[group][:, 2:].tolist()
        arr = np.asarray(pts, dtype=np.float64)
        x_min = float(np.min(arr[:, 0]))
        y_min = float(np.min(arr[:, 1]))
        x_max = float(np.max(arr[:, 0]))
        y_max = float(np.max(arr[:, 1]))

        deg = float(theta[max_indice])
        if deg >= 90.0:
            seg = (x_min, y_max, x_max, y_min)
        else:
            seg = (x_min, y_min, x_max, y_max)
        key = tuple(round(v, 1) for v in seg)
        if key in seen:
            continue  # deviation 5
        seen.add(key)
        merged.append(seg)
        merged_theta.append(deg)

    if not merged:
        return np.zeros((0, 4), dtype=np.float64), np.zeros((0,), dtype=np.float64)
    return np.asarray(merged, dtype=np.float64), np.asarray(merged_theta, dtype=np.float64)


# ==========================================================================================
# 3) Square enumeration — reference lines 216-410
# ==========================================================================================

def find_squares(merged, params=None, max_candidates=MAX_SQUARE_CANDIDATES):
    """Reference lines 216-410: intersections -> corner classes -> quads.

    Returns a dict with
      `squares`  `[n,4,2]` corners in the NAVER order 0(TL) 1(TR) 2(BR) 3(BL),
      `connect`  `[n,4]`   index of the merged segment on each side 01,12,23,30,
      `seg_idx`  `[n,4,2]` the `(i, j)` segment pair per corner,
      `inter`    `[m,2]`   all classified intersections,
      `truncated` bool     whether the candidate cap fired.
    """
    p = dict(DEFAULT_PARAMS)
    if params:
        p.update(params)

    merged = np.asarray(merged, dtype=np.float64)
    empty = {
        "squares": np.zeros((0, 4, 2)),
        "connect": np.zeros((0, 4), dtype=np.int64),
        "seg_idx": np.zeros((0, 4, 2), dtype=np.int64),
        "inter": np.zeros((0, 2)),
        "truncated": False,
    }
    if merged.shape[0] < 2:
        return empty

    start = merged[:, :2]
    end = merged[:, 2:]
    centers = (start + end) / 2.0
    diff = start - end
    dist_segments = np.sqrt(np.sum(diff ** 2, axis=-1))

    a = diff[:, 1]
    b = -diff[:, 0]
    c = a * start[:, 0] + b * start[:, 1]
    pre_det = a[:, None] * b[None, :]
    det = pre_det - np.transpose(pre_det)
    pre_inter_y = a[:, None] * c[None, :]
    inter_y = (pre_inter_y - np.transpose(pre_inter_y)) / (det + 1e-10)
    pre_inter_x = c[:, None] * b[None, :]
    inter_x = (pre_inter_x - np.transpose(pre_inter_x)) / (det + 1e-10)
    inter_pts = np.concatenate([inter_x[:, :, None], inter_y[:, :, None]], axis=-1)
    # Deviation 6 (kept from NAVER): the intersection is truncated to int32, not
    # rounded, because the corner-angle classification was tuned on that. The
    # only change here is that parallel/degenerate pairs produce a non-finite
    # value, which numpy casts to an arbitrary int *with a warning*; clamping to
    # a large finite sentinel is the same "far away" signal, deterministically.
    inter_pts = np.nan_to_num(inter_pts, nan=1e6, posinf=1e6, neginf=-1e6)
    inter_pts = np.clip(inter_pts, -1e6, 1e6).astype(np.int32)

    # 3.1 distances from the intersection to each segment's endpoints
    d1s = np.sqrt(np.sum((inter_pts - start[:, None, :]) ** 2, axis=-1, keepdims=True))
    d1e = np.sqrt(np.sum((inter_pts - end[:, None, :]) ** 2, axis=-1, keepdims=True))
    d2s = np.sqrt(np.sum((inter_pts - start[None, :, :]) ** 2, axis=-1, keepdims=True))
    d2e = np.sqrt(np.sum((inter_pts - end[None, :, :]) ** 2, axis=-1, keepdims=True))
    dist1 = np.sort(np.concatenate([d1s, d1e], axis=-1), axis=-1)
    dist2 = np.sort(np.concatenate([d2s, d2e], axis=-1), axis=-1)

    # 3.2 degrees from the intersection to each segment's centre
    to_start = centers[:, None, :] - inter_pts
    deg1 = np.arctan2(to_start[:, :, 1], to_start[:, :, 0]) * 180.0 / np.pi
    deg1[deg1 < 0.0] += 360.0
    to_end = centers[None, :, :] - inter_pts
    deg2 = np.arctan2(to_end[:, :, 1], to_end[:, :, 0]) * 180.0 / np.pi
    deg2[deg2 < 0.0] += 360.0
    deg_sort = np.sort(np.concatenate([deg1[:, :, None], deg2[:, :, None]], axis=-1), axis=-1)
    deg_diff = np.abs(deg1 - deg2)
    deg_diff[deg_diff > 180] = 360 - deg_diff[deg_diff > 180]

    outside_ratio = p["outside_ratio"]
    inside_ratio = p["inside_ratio"]

    corner_dict = {k: [] for k in range(4)}
    inter_points = []
    n = merged.shape[0]
    for i in range(n):
        for j in range(i + 1, n):
            x, y = int(inter_pts[i, j, 0]), int(inter_pts[i, j, 1])
            d1, d2 = float(deg_sort[i, j, 0]), float(deg_sort[i, j, 1])
            ddiff = float(deg_diff[i, j])
            if not (ddiff > 60.0 and ddiff < 120.0):
                continue

            check1 = (
                (dist1[i, j, 1] >= dist_segments[i] and dist1[i, j, 0] <= dist_segments[i] * outside_ratio)
                or (dist1[i, j, 1] <= dist_segments[i] and dist1[i, j, 0] <= dist_segments[i] * inside_ratio)
            )
            check2 = (
                (dist2[i, j, 1] >= dist_segments[j] and dist2[i, j, 0] <= dist_segments[j] * outside_ratio)
                or (dist2[i, j, 1] <= dist_segments[j] and dist2[i, j, 0] <= dist_segments[j] * inside_ratio)
            )
            if not (check1 and check2):
                continue

            if (0 <= d1 <= 45 and 45 <= d2 <= 120) or (d2 >= 315 and 45 <= d1 <= 120):
                corner_info = 0
            elif 45 <= d1 <= 125 and 125 <= d2 <= 225:
                corner_info = 1
            elif 125 <= d1 <= 225 and 225 <= d2 <= 315:
                corner_info = 2
            elif (0 <= d1 <= 45 and 225 <= d2 <= 315) or (d2 >= 315 and 225 <= d1 <= 315):
                corner_info = 3
            else:
                continue

            corner_dict[corner_info].append((x, y, i, j))
            inter_points.append((x, y))

    # --- junction graph walk (reference lines 354-410) --------------------------------
    # Index corners by the line they touch, so the walk only visits pairs that
    # actually share a line (the reference's nested `in` scans). A corner that
    # touches both of its neighbour's lines must be visited once, not twice.
    by_line = {}
    for k in range(4):
        for corner in corner_dict[k]:
            for line in corner[2:]:
                by_line.setdefault((k, line), []).append(corner)

    def _next(cls, a, b):
        out = []
        seen_ids = set()
        for c in by_line.get((cls, a), []) + by_line.get((cls, b), []):
            if id(c) in seen_ids:
                continue
            seen_ids.add(id(c))
            out.append(c)
        return out

    square_list = []
    connect_list = []
    segments_list = []
    evaluated = 0
    truncated = False

    def _walk():
        nonlocal evaluated, truncated
        for c0 in corner_dict[0]:
            for c1 in _next(1, c0[2], c0[3]):
                for c2 in _next(2, c1[2], c1[3]):
                    evaluated += 1
                    if evaluated > max_candidates:
                        truncated = True
                        return
                    for c3 in _next(3, c2[2], c2[3]):
                        shared = (c3[2] in c0[2:] or c3[3] in c0[2:])
                        if not shared:
                            continue
                        # The segment on each side, in NAVER's 01/12/23/30 order.
                        s01 = next((v for v in c0[2:] if v in c1[2:]), c0[2])
                        s12 = next((v for v in c1[2:] if v in c2[2:]), c1[2])
                        s23 = next((v for v in c2[2:] if v in c3[2:]), c2[2])
                        s30 = next((v for v in c3[2:] if v in c0[2:]), c3[2])
                        square_list.append(
                            list(c0[:2]) + list(c1[:2]) + list(c2[:2]) + list(c3[:2])
                        )
                        connect_list.append([s01, s12, s23, s30])
                        segments_list.append(list(c0[2:]) + list(c1[2:]) + list(c2[2:]) + list(c3[2:]))

    _walk()
    if not square_list:
        empty["inter"] = np.asarray(inter_points, dtype=np.float64).reshape(-1, 2)
        empty["truncated"] = truncated
        return empty

    squares = np.asarray(square_list, dtype=np.float64).reshape(-1, 4, 2)
    connect = np.asarray(connect_list, dtype=np.int64)
    seg_idx = np.asarray(segments_list, dtype=np.int64).reshape(-1, 4, 2)
    scores = square_scores(squares, connect, seg_idx, merged, dist_segments, p)

    order = np.argsort(-scores, kind="stable")
    return {
        "squares": squares[order],
        "connect": connect[order],
        "seg_idx": seg_idx[order],
        "scores": scores[order],
        "inter": np.asarray(inter_points, dtype=np.float64).reshape(-1, 2),
        "truncated": truncated,
    }


def square_scores(squares, connect, seg_idx, merged, dist_segments, params):
    """NAVER's square score (reference lines 436-560), with the same weights.

    The reference's `overlap_scores /= max(overlap_scores)` and its
    `score_w = [0, 1, 10, 0.5, 1]` are reproducible exactly; the only guard
    added is against division by zero (empty/degenerate input), which the
    reference would turn into NaNs.
    """
    n = squares.shape[0]
    if n == 0:
        return np.zeros((0,))
    start = merged[:, :2]
    end = merged[:, 2:]

    # Per-corner endpoint distances.
    #
    # The reference reads `dist_inter_to_segment1[i, j]` (or `...2[i, j]`) for the
    # corner built from segments `(i, j)`, picking the matrix by whether the
    # side's `connect` index equals `i` or `j`. In both cases the matrix holds
    # the distances from that corner to the endpoints of **the connect
    # segment**, so computing them directly is identical (and drops the need to
    # carry the `(i, j)` pair into the scoring pass).
    corner_dist = np.zeros((n, 4, 2, 2))
    for q in range(n):
        for corner in range(4):
            inter_pt = squares[q, corner]
            for side in range(2):
                which = corner if side == 0 else (corner + 1) % 4
                seg = connect[q, which]
                d_s = np.sqrt(np.sum((inter_pt - start[seg]) ** 2))
                d_e = np.sqrt(np.sum((inter_pt - end[seg]) ** 2))
                corner_dist[q, corner, side] = np.sort(np.asarray([d_s, d_e]))

    rollup = np.roll(squares, 1, axis=1)
    rolldown = np.roll(squares, -1, axis=1)
    v1 = rollup - squares
    v2 = rolldown - squares
    n1 = v1 / (np.linalg.norm(v1, axis=-1, keepdims=True) + 1e-10)
    n2 = v2 / (np.linalg.norm(v2, axis=-1, keepdims=True) + 1e-10)
    inner = np.sum(n1 * n2, axis=-1)
    deg_all = np.arccos(np.clip(inner, -1.0, 1.0)) * 180.0 / np.pi

    overlap = np.zeros(n)
    degree = np.zeros(n)
    length = np.zeros(n)
    for q in range(n):
        cover = 0.0
        peri = 0.0
        side_len = []
        for side in range(4):
            seg = connect[q, side]
            connect_dist = dist_segments[seg]
            # start corner = side, end corner = side+1 (reference order)
            s_min, s_max = corner_dist[q, side, 0]
            e_min, e_max = corner_dist[q, side, 1]
            if s_max > connect_dist:
                s_cov, s_peri = 0.0, 1.0
            else:
                s_cov, s_peri = -1.0, -1.0
            if e_max > connect_dist:
                e_cov, e_peri = 0.0, 1.0
            else:
                e_cov, e_peri = -1.0, -1.0
            cover += connect_dist + s_cov * s_min + e_cov * e_min
            peri += connect_dist + s_peri * s_min + e_peri * e_min
            side_len.append(connect_dist + s_peri * s_min + e_peri * e_min)

        overlap[q] = (cover / peri) if peri > 1e-9 else 0.0
        d0, d1, d2, d3 = deg_all[q]
        r1 = d0 / d2 if d2 > 1e-9 else 0.0
        if r1 > 1.0:
            r1 = 1.0 / r1 if r1 > 1e-9 else 0.0
        r2 = d1 / d3 if d3 > 1e-9 else 0.0
        if r2 > 1.0:
            r2 = 1.0 / r2 if r2 > 1e-9 else 0.0
        degree[q] = (r1 + r2) / 2.0

        l0, l1, l2, l3 = side_len
        lr1 = (l0 / l2) if l2 > l0 else (l2 / l0 if l0 > 1e-9 else 0.0)
        lr2 = (l1 / l3) if l3 > l1 else (l3 / l1 if l1 > 1e-9 else 0.0)
        length[q] = (lr1 + lr2) / 2.0

    mx = float(np.max(overlap)) if n else 0.0
    if mx > 0.0:
        overlap = overlap / mx

    # Area in map space, normalised by (input_edge / 2)^2 as the reference does.
    ax = squares[:, :, 0]
    ay = squares[:, :, 1]
    correction = ax[:, -1] * ay[:, 0] - ay[:, -1] * ax[:, 0]
    area = 0.5 * np.abs(
        np.sum(ax[:, :-1] * ay[:, 1:], axis=-1) - np.sum(ay[:, :-1] * ax[:, 1:], axis=-1) + correction
    )
    map_size = 256.0  # reference `map_size = input_shape[0] / 2` for 512 input
    area = area / (map_size * map_size)

    centers = np.asarray([[256 // 2, 256 // 2]], dtype=np.float64)
    sq_centers = np.mean(squares, axis=1)
    center_scores = np.sqrt(np.sum((centers - sq_centers) ** 2, axis=-1)) / (map_size / np.sqrt(2.0))

    return (
        params["w_overlap"] * overlap
        + params["w_degree"] * degree
        + params["w_area"] * area
        - params["w_center"] * center_scores
        + params["w_length"] * length
    )


# ==========================================================================================
# 4) Frame mapping
# ==========================================================================================

def map_to_frame(coords, original_shape, input_shape):
    """Map-space coordinates -> frame pixels.

    The maps are half the model input, so a map coordinate covers `2 * W / edge`
    frame pixels (H29 risk note: "easy to get wrong by a factor of two").
    """
    arr = np.asarray(coords, dtype=np.float64).copy()
    if arr.size == 0:
        return arr
    sx = 2.0 / float(input_shape[1]) * float(original_shape[1])
    sy = 2.0 / float(input_shape[0]) * float(original_shape[0])
    # Interleaved `(x1, y1, x2, y2)` rows vs. `(..., 2)` point arrays are
    # different layouts and must not share a branch: mapping only column 0 of a
    # segment scales the first endpoint and leaves the second in map space,
    # which silently turns every straight line into a diagonal.
    if arr.ndim == 1:
        arr[..., 0::2] *= sx
        arr[..., 1::2] *= sy
    elif arr.shape[-1] == 4:
        arr[..., 0::2] *= sx
        arr[..., 1::2] *= sy
    else:
        arr[..., 0] *= sx
        arr[..., 1] *= sy
    return arr


# ==========================================================================================
# 5) Public entry points (mirroring the reference signatures)
# ==========================================================================================

def _run(session, image, input_shape):
    batch = build_input(image, input_shape)
    input_name = session.get_inputs()[0].name
    outputs = session.get_outputs()
    names = [o.name for o in outputs[:3]]
    result = session.run(names, {input_name: batch})
    return result[0][0], result[1][0], result[2][0]


def pred_lines(image, session, input_shape=(512, 512), score_thr=0.10, dist_thr=DIST_THR):
    """Reference `pred_lines`: segments in **frame** pixels."""
    h, w = image.shape[0], image.shape[1]
    junctions, scores, vmap = _run(session, image, input_shape)
    _, segments = decode_junctions(junctions, scores, vmap, score_thr, dist_thr)
    if segments.shape[0] == 0:
        return np.zeros((0, 4))
    return map_to_frame(segments, (h, w), input_shape)


def pred_squares(image, session, input_shape=(512, 512), params=None):
    """Reference `pred_squares`: `(segments, squares, scores, inter_points)`.

    All coordinates in **frame** pixels. `segments` is `[m,4]` (x1,y1,x2,y2),
    `squares` is `[n,4,2]` in NAVER order (TL, TR, BR, BL).
    """
    p = dict(DEFAULT_PARAMS)
    if params:
        p.update(params)
    h, w = image.shape[0], image.shape[1]
    junctions, scores, vmap = _run(session, image, input_shape)

    _, raw = decode_junctions(junctions, scores, vmap, p["score"], DIST_THR)
    merged, _ = merge_segments(raw, len(junctions))
    found = find_squares(merged, p)

    segs = map_to_frame(merged, (h, w), input_shape) if merged.size else np.zeros((0, 4))
    squares = map_to_frame(found["squares"], (h, w), input_shape) if found["squares"].size else np.zeros((0, 4, 2))
    inter = map_to_frame(found["inter"], (h, w), input_shape) if found["inter"].size else np.zeros((0, 2))
    return segs, squares, found.get("scores", np.zeros((0,))), inter


# ==========================================================================================
# 6) Geometry helpers used by the H30 merge rule (also ported to Kotlin)
# ==========================================================================================

def polygon_area(pts):
    """Shoelace area (absolute) of an ordered polygon."""
    arr = np.asarray(pts, dtype=np.float64)
    if arr.shape[0] < 3:
        return 0.0
    x = arr[:, 0]
    y = arr[:, 1]
    return float(abs(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1))) / 2.0)


def segment_length(seg):
    return float(np.hypot(seg[2] - seg[0], seg[3] - seg[1]))


def point_in_convex_quad(p, quad):
    """True when `p` is inside (or on) the convex polygon `quad` (any order)."""
    pts = np.asarray(quad, dtype=np.float64)
    positive = negative = False
    n = pts.shape[0]
    for i in range(n):
        ax, ay = pts[i]
        bx, by = pts[(i + 1) % n]
        cross = (bx - ax) * (p[1] - ay) - (by - ay) * (p[0] - ax)
        if cross > 1e-6:
            positive = True
        if cross < -1e-6:
            negative = True
        if positive and negative:
            return False
    return True


def segment_quad_overlap_frac(seg, quad):
    """Fraction of `seg` that lies inside `quad` (sampled at 64 steps)."""
    if quad is None:
        return 0.0
    x0, y0, x1, y1 = [float(v) for v in seg]
    steps = 64
    inside = 0
    for s in range(steps):
        t = (s + 0.5) / steps
        p = (x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
        if point_in_convex_quad(p, quad):
            inside += 1
    return inside / float(steps)


def split_quad_by_line(quad, line):
    """Split a convex quad by an infinite line; return the two polygons.

    `line` is `(x1, y1, x2, y2)`. Returns `(left, right)` where `left` is the side
    the line's **normal** points away from (`cross < 0`) — the naming is
    arbitrary, the caller decides by content. Each polygon is an ordered
    `[k,2]` array (k >= 3) or `None` when the line misses the quad.
    """
    pts = np.asarray(quad, dtype=np.float64)
    x1, y1, x2, y2 = [float(v) for v in line]
    dx = x2 - x1
    dy = y2 - y1
    if abs(dx) < 1e-9 and abs(dy) < 1e-9:
        return None, None

    def side(p):
        return dx * (p[1] - y1) - dy * (p[0] - x1)

    sides = [side(p) for p in pts]
    if all(s >= -1e-9 for s in sides) or all(s <= 1e-9 for s in sides):
        return None, None  # line does not cross the interior

    def clip(sign):
        out = []
        n = len(pts)
        for i in range(n):
            cur = pts[i]
            nxt = pts[(i + 1) % n]
            sc = sides[i] * sign
            sn = sides[(i + 1) % n] * sign
            if sc >= -1e-9:
                out.append(cur)
            if (sc > 1e-9 and sn < -1e-9) or (sc < -1e-9 and sn > 1e-9):
                t = sc / (sc - sn)
                out.append(cur + (nxt - cur) * t)
        return np.asarray(out) if len(out) >= 3 else None

    return clip(1.0), clip(-1.0)


def line_through_quad(quad, seg, min_inside_frac=0.60):
    """True when `seg` runs across the quad's interior (merge evidence).

    Measured as **coverage of the quad's own extent along the line**: the
    longest contiguous inside-run of the segment, divided by the quad's
    projection onto the segment's direction. That is the question the merge rule
    asks ("does this line cut the page from one side to the other?"), and it is
    invariant to the segment sticking out of the quad, which M-LSD segments
    routinely do. A gutter/fold line that spans the page scores ~1.0; a whole
    line lying outside scores 0.
    """
    if quad is None:
        return False
    x0, y0, x1, y1 = [float(v) for v in seg]
    dx = x1 - x0
    dy = y1 - y0
    slen = float(np.hypot(dx, dy))
    if slen < 1e-6:
        return False
    ux, uy = dx / slen, dy / slen
    pts = np.asarray(quad, dtype=np.float64)
    ts = (pts[:, 0] - x0) * ux + (pts[:, 1] - y0) * uy
    extent = float(ts.max() - ts.min())
    if extent < 1e-6:
        return False

    steps = 128
    best = 0
    run = 0
    for s in range(steps):
        t = (s + 0.5) / steps
        if point_in_convex_quad((x0 + dx * t, y0 + dy * t), quad):
            run += 1
            if run > best:
                best = run
        else:
            run = 0
    covered = (best / float(steps)) * slen
    return covered >= min_inside_frac * extent


def quad_line_support(quad, segments, rel_tol=0.015, angle_tol_deg=10.0):
    """How well the detected segments support the quad's four edges.

    For each edge: the fraction of the edge covered by the projection of
    near-collinear segments (angle within `angle_tol_deg`, perpendicular offset
    within `rel_tol * edge length`). Returns `(mean_support, per_edge)`.
    """
    pts = np.asarray(quad, dtype=np.float64)
    if pts.shape[0] != 4 or len(segments) == 0:
        return 0.0, [0.0, 0.0, 0.0, 0.0]
    segs = np.asarray(segments, dtype=np.float64)
    per_edge = []
    for i in range(4):
        ax, ay = pts[i]
        bx, by = pts[(i + 1) % 4]
        ex = bx - ax
        ey = by - ay
        elen = float(np.hypot(ex, ey))
        if elen < 1e-6:
            per_edge.append(0.0)
            continue
        ux, uy = ex / elen, ey / elen
        # Normal from the edge to the segment; the offset test is symmetric.
        cover = []
        for s in segs:
            sx0, sy0, sx1, sy1 = s
            sdx, sdy = sx1 - sx0, sy1 - sy0
            slen = float(np.hypot(sdx, sdy))
            if slen < 1e-6:
                continue
            cosang = abs((sdx * ux + sdy * uy) / slen)
            if cosang < np.cos(np.radians(angle_tol_deg)):
                continue
            # Perpendicular offsets of both endpoints from the edge's line.
            off0 = abs((sx0 - ax) * uy - (sy0 - ay) * ux)
            off1 = abs((sx1 - ax) * uy - (sy1 - ay) * ux)
            if off0 > rel_tol * elen or off1 > rel_tol * elen:
                continue
            t0 = ((sx0 - ax) * ux + (sy0 - ay) * uy) / elen
            t1 = ((sx1 - ax) * ux + (sy1 - ay) * uy) / elen
            lo, hi = min(t0, t1), max(t0, t1)
            lo = max(lo, 0.0)
            hi = min(hi, 1.0)
            if hi > lo:
                cover.append((lo, hi))
        if not cover:
            per_edge.append(0.0)
            continue
        cover.sort()
        total = 0.0
        cur_lo, cur_hi = cover[0]
        for lo, hi in cover[1:]:
            if lo <= cur_hi:
                cur_hi = max(cur_hi, hi)
            else:
                total += cur_hi - cur_lo
                cur_lo, cur_hi = lo, hi
        total += cur_hi - cur_lo
        per_edge.append(min(1.0, total))
    return float(np.mean(per_edge)), per_edge
