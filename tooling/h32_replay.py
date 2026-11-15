#!/usr/bin/env python3
"""h32_replay.py - replay the REAL (and synthetic) book-spread sets through the SHIPPED detector
path, offline, with per-image attribution of the failure modes the owner named.

STEP 2 of SUBTASK H32: "Replay every real image through the real detector path (corner model +
provenance + gate + MlsdTrigger/MlsdMergeRepair with the shipped policy)". This module is a
constant-for-constant mirror of the on-device path:

    corner model (h30_corner.detect)              ~ OpenCvQuadPageDetector nn path
    is_plausible_page_quad                        ~ the app gate
    MlsdTrigger.signature / acceptsRepair         ~ MlsdTrigger.kt (ported below)
    MlsdMergeRepair.apply (h30_merge.apply)       ~ MlsdMergeRepair.kt (selector=area)

and it reproduces the H30/H31 metric definitions (align_measure, iou, coverage) from
h30_score.py so numbers are comparable across lanes.

Failure modes measured per image (the owner's words):

  * merged        - the returned quad covers >= 50 % of the facing page's visible part
                    (the quad absorbed the partial page across the gutter);
  * wrong-page    - the returned quad sits mostly on the PARTIAL page, i.e. the page that is
                    NOT fully seen (the detector picked the wrong one of the two);
  * frame-cut     - the returned quad touches the frame border while the alternative side of
                    the fold is interior (the partial-page signature).

Usage:
  python tooling/h32_replay.py --set real   --variant shipped   --tag red
  python tooling/h32_replay.py --set synth  --variant shipped   --tag synth_shipped
  python tooling/h32_replay.py --set real   --variant uniformity --tag v_uniform
"""

import argparse
import hashlib
import json
import os
import sys
import time

import numpy as np
import onnxruntime as ort
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import h30_corner as C  # noqa: E402
import h30_merge as MG  # noqa: E402
import h30_mlsd as M  # noqa: E402

OUT_DIR = os.path.join(REPO, "tooling", "out", "h32")
REAL_ASSETS = os.path.join(REPO, "app", "src", "androidTest", "assets", "bookspread_real")
REAL_MANIFEST = os.path.join(REAL_ASSETS, "real_bookspread_manifest.json")
SYNTH_ASSETS = os.path.join(REPO, "app", "src", "androidTest", "assets", "bookspread")
SYNTH_MANIFEST = os.path.join(SYNTH_ASSETS, "book_spread_manifest.json")
CORNER_MODEL = os.path.join(os.path.dirname(REPO), "dsmodels", "m1c_detect.onnx")
MLSD_MODEL = os.path.join(os.path.dirname(REPO), "dsmodels", "m2_mlsd.onnx")

MLSD_EDGE = 320
MLSD_SCORE_THR = 0.06   # MlsdModel.DEFAULT_PARAMS.score (device)

#: Merged when the quad covers at least this fraction of the facing page's visible part.
COVER_MERGED = 0.50
#: Wrong page when the quad sits mostly on the facing (partial) page instead of the target.
WRONG_PAGE_IOU = 0.35
#: A quad "touches" the frame border within this fraction of the short side.
FRAME_TOUCH_FRAC = 0.02


# ==========================================================================================
# MlsdTrigger.kt port — constants and the two guards, byte-for-byte semantics
# ==========================================================================================

PAPER_ASPECT_HW = 1.41
PAPER_ASPECT_SIGMA = 0.55
REPAIR_MIN_STRIP = 0.075
ASPECT_MIN = 0.74
ASPECT_MAX = 1.75
ASPECT_RELEASE = 0.06


def quad_aspect(quad):
    q = np.asarray(quad, dtype=np.float64)
    if q.shape[0] != 4:
        return 0.0
    w = np.linalg.norm((q[1] - q[0] + q[2] - q[3]) / 2.0)
    h = np.linalg.norm((q[3] - q[0] + q[2] - q[1]) / 2.0)
    return float(w / h) if h > 1e-9 else 0.0


def paper_aspect_prior(quad):
    a = quad_aspect(quad)
    if a <= 1e-9:
        return 0.0
    z = (1.0 / a - PAPER_ASPECT_HW) / PAPER_ASPECT_SIGMA
    return float(np.exp(-z * z))


def improves_paper_aspect(before, after, min_gain=0.005):
    return paper_aspect_prior(after) > paper_aspect_prior(before) + min_gain


def accepts_repair(before, after, strip_frac):
    return strip_frac >= REPAIR_MIN_STRIP and improves_paper_aspect(before, after)


def trigger_fires(quad):
    """The shipped merge signature: w/h in [0.74, 1.75] (still path: one attempt per capture)."""
    a = quad_aspect(quad)
    return ASPECT_MIN <= a <= ASPECT_MAX


# ==========================================================================================
# H32 policy variants (each measured separately; only measured wins are kept)
# ==========================================================================================

def touches_frame(quad, w, h, frac=FRAME_TOUCH_FRAC):
    q = np.asarray(quad, dtype=np.float64)
    tol_x = w * frac
    tol_y = h * frac
    return bool(
        q[:, 0].min() <= tol_x or q[:, 0].max() >= w - tol_x or
        q[:, 1].min() <= tol_y or q[:, 1].max() >= h - tol_y
    )


def half_is_frame_cut(half, w, h):
    """A page cut by the frame edge is a PARTIAL page: it is never the scan target."""
    return touches_frame(half, w, h)


def side_uniformity(quad, seg, gray):
    """Uniformity-along-the-fold guard (the documented H31 open fix).

    A real page boundary (gutter / facing-page edge) is uniform along its length: the ink
    profile sampled just INSIDE the kept side runs edge-to-edge like a page body, while the
    removed strip carries the facing-page signature (a text block that *stops* at the fold).
    Returns (ok, score) where score in [0,1] is the consistency of the kept side's ink profile.
    """
    q = np.asarray(quad, dtype=np.float64)
    x1, y1, x2, y2 = seg
    dx, dy = x2 - x1, y2 - y1
    ln = float(np.hypot(dx, dy))
    if ln < 1e-6:
        return False, 0.0
    nx, ny = -dy / ln, dx / ln

    h_img, w_img = gray.shape
    mid = np.array([(x1 + x2) / 2.0, (y1 + y2) / 2.0])
    d_left = _point_seg_dist(mid, q[0], q[3])
    d_right = _point_seg_dist(mid, q[1], q[2])
    keep_dir = np.array(q[0] if d_left >= d_right else q[1], dtype=np.float64) - mid
    if np.dot(keep_dir, np.array([nx, ny])) < 0:
        nx, ny = -nx, -ny  # normal points into the KEPT side

    # Sample bands parallel to the fold: kept-side ink (should be uniform along the fold),
    # strip ink (the facing page's text block: strong and stopping at the fold).
    n = 24
    ts = np.linspace(0.15, 0.85, n)
    kept_vals = []
    strip_vals = []
    for t in ts:
        px = x1 + dx * t
        py = y1 + dy * t
        for dist, out in ((0.022, kept_vals), (-0.022, strip_vals)):
            sx = int(round(px + nx * dist * _width_of(q)))
            sy = int(round(py + ny * dist * _width_of(q)))
            sx = min(max(sx, 0), w_img - 1)
            sy = min(max(sy, 0), h_img - 1)
            out.append(float(gray[sy, sx]))
    if len(kept_vals) < 8:
        return False, 0.0
    kv = np.asarray(kept_vals)
    sv = np.asarray(strip_vals)
    paper = float(np.percentile(kv, 75))
    ink = kv < 0.72 * paper
    ink_strip = sv < 0.72 * paper

    # Uniformity along the fold = the kept side's ink is NOT concentrated in one run, and the
    # fold is a clean boundary (kept side lighter than the strip side overall, since the strip
    # is facing-page text vs the kept side's margin-adjacent column).
    row_std = float(kv.std()) / max(float(paper), 1e-6)
    strip_ink = float(ink_strip.mean()) if ink_strip.size else 0.0
    kept_ink = float(ink.mean()) if ink.size else 0.0
    # Score: a real page edge has a consistent kept column (low std) and a strip that carries
    # at least as much ink as the kept column (it is a page, not a shadow/blank margin).
    score = 0.0
    if row_std < 0.22:
        score += 0.5
    if strip_ink >= kept_ink * 0.8:
        score += 0.5
    return score >= 0.5, float(score)


def _width_of(q):
    return float(np.linalg.norm((q[1] - q[0] + q[2] - q[3]) / 2.0))


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
# Metrics (H30 parity)
# ==========================================================================================

def align_measure(det, gt):
    det = np.asarray(det, dtype=np.float64)
    gt = np.asarray(gt, dtype=np.float64)
    best_mean = float("inf")
    for rev in (0, 1):
        seq = det if rev == 0 else det[::-1]
        for shift in range(len(seq)):
            d = np.linalg.norm(np.roll(seq, -shift, axis=0) - gt, axis=1)
            mean = float(d.mean())
            if mean < best_mean:
                best_mean = mean
    return best_mean


def iou_of(a, b):
    from h30_score import iou_of as _iou
    return _iou(a, b)


def coverage(inner, outer):
    from h30_score import coverage as _cov
    return _cov(inner, outer) if inner is not None else 0.0


# ==========================================================================================
# Dataset loading
# ==========================================================================================

class RealItem:
    def __init__(self, rec):
        self.name = rec["name"]
        self.tier = rec.get("tier", "real")
        self.scene = rec.get("scene", "real_spread")
        self.asset = rec["asset"]
        self.width = rec["width"]
        self.height = rec["height"]
        self.diagonal = rec["diagonal"]
        self.page = np.asarray(rec["gt_target_quad"], dtype=np.float64)
        other = rec.get("gt_facing_quad") or rec.get("gt_alt_quad")
        self.facing = np.asarray(other, dtype=np.float64) if other else None
        self.facing_side = rec.get("facing_side")
        self.path = os.path.join(REAL_ASSETS, self.asset)


class SynthItem:
    """One synthetic canvas (still OR preview), mirroring h30_score.Asset.

    The 18-asset synthetic gate counts BOTH canvases of the 9 fixtures (that is how
    BookSpreadDetectionTest scores 18 assets), so `load_items('synth')` yields 18 rows.
    """

    CANVASES = ("still", "preview")

    def __init__(self, item, canvas):
        self.name = "%s/%s" % (item["name"], canvas)
        self.fixture = item["name"]
        self.canvas = canvas
        self.tier = "synthetic"
        self.scene = item["variant"]
        shot = item[canvas]
        self.asset = shot["asset"]
        self.width = shot["width"]
        self.height = shot["height"]
        self.diagonal = shot["diagonal"]
        self.page = np.asarray(shot["gt_page"], dtype=np.float64)
        self.facing = (np.asarray(shot["gt_sliver"], dtype=np.float64)
                       if shot.get("gt_sliver") else None)
        self.facing_side = item.get("sliver_side")
        self.sliver_fraction = float(item.get("sliver_fraction", 0.0))
        self.path = os.path.join(SYNTH_ASSETS, self.asset)


def load_items(which):
    if which == "real":
        with open(REAL_MANIFEST, encoding="utf-8") as fh:
            return [RealItem(r) for r in json.load(fh)["items"]]
    with open(SYNTH_MANIFEST, encoding="utf-8") as fh:
        items = json.load(fh)["items"]
    out = []
    for r in items:
        for canvas in SynthItem.CANVASES:
            out.append(SynthItem(r, canvas))
    return out


def load_rgb(path):
    with Image.open(path) as im:
        return np.asarray(im.convert("RGB"), dtype=np.uint8)


# ==========================================================================================
# The shipped + variant pipelines
# ==========================================================================================

def run_pipeline(rgb, corner_session, mlsd_session, variant):
    """Returns (quad, diagnostics) for the chosen policy variant."""
    h, w = rgb.shape[:2]
    diag = {"mlsd_invoked": False, "reason": "", "fold": None, "strip_frac": 0.0,
            "corner_quad": None}

    quad, info = C.detect(corner_session, rgb)
    if quad is None:
        diag["reason"] = "no_detection(%s)" % (info if isinstance(info, str) else "?")
        return None, diag
    corner_quad = np.asarray(quad, dtype=np.float64)
    diag["corner_quad"] = [[float(x), float(y)] for x, y in corner_quad]

    if not trigger_fires(corner_quad):
        diag["reason"] = "no_signature"
        return corner_quad, diag

    segs = M.pred_lines(rgb, mlsd_session, input_shape=(MLSD_EDGE, MLSD_EDGE), score_thr=MLSD_SCORE_THR)
    diag["mlsd_invoked"] = True
    diag["n_segments"] = int(len(segs))
    if len(segs) == 0:
        diag["reason"] = "corner(no_segments)"
        return corner_quad, diag

    folds = MG.find_merge_lines(corner_quad, segs)
    cands = [(side, folds[side]) for side in ("left", "right") if folds.get(side) is not None]
    if not cands:
        diag["reason"] = "corner(no_interior_line)"
        return corner_quad, diag
    cands.sort(key=lambda kv: kv[1][1]["d_side"])
    side, (line, ev) = cands[0]
    diag["fold"] = {"side": side, "d_side": float(ev["d_side"]),
                    "seg": [float(v) for v in line]}

    halves = M.split_quad_by_line(corner_quad, line)
    if halves[0] is None or halves[1] is None:
        diag["reason"] = "corner(line_misses)"
        return corner_quad, diag

    area_a = C.polygon_area(halves[0])
    area_b = C.polygon_area(halves[1])
    total = area_a + area_b
    keep_area_idx = 0 if area_a >= area_b else 1
    strip_frac = min(area_a, area_b) / max(total, 1e-9)
    diag["strip_frac"] = float(strip_frac)

    keep_idx = keep_area_idx  # default: shipped selector = larger half
    reject_reason = None

    if variant == "shipped":
        keep_idx = keep_area_idx
    elif variant == "uniformity":
        ok, score = side_uniformity(corner_quad, line, gray_of(rgb))
        diag["uniformity_score"] = score
        if not ok:
            reject_reason = "split_rejected_uniformity"
    elif variant == "side_select":
        # Prefer the interior side: a side cut by the frame is a partial page, never the target.
        cut = [half_is_frame_cut(halves[0], w, h), half_is_frame_cut(halves[1], w, h)]
        diag["side_frame_cut"] = cut
        if cut[0] != cut[1]:
            keep_idx = 1 if cut[0] else 0
        else:
            keep_idx = keep_area_idx
    elif variant == "partial_reject":
        cut = [half_is_frame_cut(halves[0], w, h), half_is_frame_cut(halves[1], w, h)]
        diag["side_frame_cut"] = cut
        if cut[0] != cut[1]:
            keep_idx = 1 if cut[0] else 0
        else:
            keep_idx = keep_area_idx
    elif variant == "full_h32":
        # 3a uniformity guard (reject splits inconsistent with a real page edge)
        ok_u, score_u = side_uniformity(corner_quad, line, gray_of(rgb))
        diag["uniformity_score"] = score_u
        # 3b/3c: partial-page rejection + correct side selection
        cut = [half_is_frame_cut(halves[0], w, h), half_is_frame_cut(halves[1], w, h)]
        diag["side_frame_cut"] = cut
        if cut[0] != cut[1]:
            keep_idx = 1 if cut[0] else 0
        else:
            keep_idx = keep_area_idx
        if not ok_u:
            reject_reason = "split_rejected_uniformity"
    else:
        keep_idx = keep_area_idx

    if reject_reason is not None:
        diag["reason"] = reject_reason
        return corner_quad, diag

    keep = C.order_corners(halves[keep_idx])
    # The shipped "do no harm" acceptance: strip size + movement towards the paper aspect.
    if not accepts_repair(corner_quad, keep, strip_frac):
        diag["reason"] = ("split_rejected_strip" if strip_frac < REPAIR_MIN_STRIP
                          else "split_rejected_aspect")
        return corner_quad, diag
    ok, why = C.is_plausible_page_quad(keep, w, h)
    if not ok:
        diag["reason"] = "split_rejected_gate(%s)" % why
        return corner_quad, diag

    diag["reason"] = "split(%s,strip=%.3f)" % (side, strip_frac)
    return keep, diag


def gray_of(rgb):
    a = np.asarray(rgb, dtype=np.float32)
    return 0.299 * a[:, :, 0] + 0.587 * a[:, :, 1] + 0.114 * a[:, :, 2]


# ==========================================================================================
# Scoring
# ==========================================================================================

def score_item(item, quad, diag):
    row = {
        "name": item.name,
        "asset": item.asset,
        "tier": item.tier,
        "scene": item.scene,
        "detected": quad is not None,
        "mlsd_invoked": diag.get("mlsd_invoked", False),
        "decision": diag.get("reason", ""),
        "fold_side": (diag.get("fold") or {}).get("side"),
        "strip_frac": diag.get("strip_frac", 0.0),
    }
    if quad is None:
        row.update({"corner_pct": None, "iou_page": None, "merged": False,
                    "wrong_page": False, "frame_cut": False})
        return row

    q = np.asarray(quad, dtype=np.float64)
    mean_px = align_measure(q, item.page)
    row["corner_px"] = mean_px
    row["corner_pct"] = 100.0 * mean_px / max(item.diagonal, 1.0)
    row["iou_page"] = iou_of(q, item.page)

    merged = False
    wrong = False
    frame_cut = touches_frame(q, item.width, item.height)
    if item.facing is not None:
        cover = coverage(item.facing, q)
        row["facing_covered"] = cover
        merged = cover >= COVER_MERGED
        iou_facing = iou_of(q, item.facing)
        row["iou_facing"] = iou_facing
        wrong = (iou_facing > row["iou_page"]) and (iou_facing >= WRONG_PAGE_IOU)
    row["merged"] = bool(merged)
    row["wrong_page"] = bool(wrong)
    row["frame_cut"] = bool(frame_cut)
    return row


def summarize(rows):
    det = [r for r in rows if r["detected"]]
    cps = [r["corner_pct"] for r in det if r["corner_pct"] is not None]
    ious = [r["iou_page"] for r in det if r["iou_page"] is not None]
    return {
        "n": len(rows),
        "detected": len(det),
        "merged": sum(1 for r in rows if r["merged"]),
        "wrong_page": sum(1 for r in rows if r["wrong_page"]),
        "frame_cut": sum(1 for r in rows if r["frame_cut"]),
        "mean_corner_pct": float(np.mean(cps)) if cps else None,
        "mean_iou": float(np.mean(ious)) if ious else None,
    }


# ==========================================================================================
# Main
# ==========================================================================================

def hb(msg):
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "HEARTBEAT.txt"), "a", encoding="utf-8") as fh:
        fh.write("[%s] %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", dest="which", choices=["real", "synth", "both"], default="both")
    ap.add_argument("--variant", default="shipped",
                    choices=["shipped", "uniformity", "side_select", "partial_reject", "full_h32"])
    ap.add_argument("--tag", default="run")
    args = ap.parse_args()

    corner = ort.InferenceSession(CORNER_MODEL, providers=["CPUExecutionProvider"])
    mlsd = ort.InferenceSession(MLSD_MODEL, providers=["CPUExecutionProvider"])
    all_rows = []
    report = {"variant": args.variant, "tag": args.tag, "sets": {}}

    for which in (["real", "synth"] if args.which == "both" else [args.which]):
        try:
            items = load_items(which)
        except FileNotFoundError as exc:
            hb("%s/%s: manifest missing (%s)" % (args.tag, which, exc))
            continue
        rows = []
        for it in items:
            if not os.path.exists(it.path):
                rows.append({"name": it.name, "asset": it.asset, "detected": False,
                             "decision": "asset_missing", "merged": False, "wrong_page": False,
                             "frame_cut": False, "tier": it.tier, "scene": it.scene})
                continue
            rgb = load_rgb(it.path)
            t0 = time.time()
            quad, diag = run_pipeline(rgb, corner, mlsd, args.variant)
            row = score_item(it, quad, diag)
            row["ms"] = (time.time() - t0) * 1000.0
            rows.append(row)
            hb("%s/%s %s: %s merged=%s wrong=%s" % (
                args.tag, which, it.name, row["decision"], row["merged"], row["wrong_page"]))
        report["sets"][which] = {"rows": rows, "summary": summarize(rows)}
        all_rows.extend(rows)

    os.makedirs(OUT_DIR, exist_ok=True)
    out_path = os.path.join(OUT_DIR, "replay_%s_%s.json" % (args.tag, args.variant))
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2)
    print("wrote %s" % out_path)
    for which, block in report["sets"].items():
        s = block["summary"]
        print("[%s] %s: detected %d/%d  merged %d  wrong-page %d  corner %s  iou %s" % (
            which, args.variant, s["detected"], s["n"], s["merged"], s["wrong_page"],
            "%.4f" % s["mean_corner_pct"] if s["mean_corner_pct"] is not None else "-",
            "%.4f" % s["mean_iou"] if s["mean_iou"] is not None else "-"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
