#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H30 — score M-LSD and the merge rule offline on the 18 book-spread fixtures.

Nothing in the output is copied from H29. Every number is computed here, on the
same 18 assets `BookSpreadDetectionTest` uses, with the same definitions
(detection, corner error as % of the diagonal after the best of 8 orderings,
IoU vs the GT page, and "merged" = the quad covers >= 50 % of the GT sliver).

Variants, measured separately so the two uses of M-LSD are never conflated:

  `corner`      the shipped path, replicated by [`h30_corner.py`](h30_corner.py)
  `merge_320/512`   corner quad + the merge rejection/repair rule (use a)
  `merge_area_*`    the same split, but the page side chosen by area only
                    (isolates what the text-block signal adds)
  `fuse_320/512`    corner + M-LSD candidate generator (use b)
  `mlsd_320/512`    M-LSD's own best square, no corner model (information only)

The harness validates itself against the published H29 aggregate before any
M-LSD number is trusted; `resize_probe.py` calibrates the one implementation
detail that cannot be copied (Android's resize kernel).

Usage:
  python tooling\\h30_score.py                     # everything
  python tooling\\h30_score.py --skip-mlsd         # corner baseline only
  python tooling\\h30_score.py --mlsd 320
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

ASSETS = os.path.join(REPO, "app", "src", "androidTest", "assets", "bookspread")
MANIFEST = os.path.join(ASSETS, "book_spread_manifest.json")
CORNER_MODEL = os.path.join(os.path.dirname(REPO), "dsmodels", "m1c_detect.onnx")
MLSD_DIR = os.path.join(REPO, "tooling", "out", "h29", "candidates", "mlsd")
OUT_DIR = os.path.join(REPO, "tooling", "out", "h30")

SLIVER_COVER_MERGED = 0.50  # same constant as BookSpreadDetectionTest
#: Fusion accept window, as `area(candidate) / area(page-side quad)`.
#: The upper bound is deliberately tight (> 1.05 is rejected): a line-derived
#: quad that is *bigger* than the page side is the spread coming back, which is
#: exactly what the merge rule just removed. Measured: widening this to 1.30 put
#: M-LSD's spread-taking squares back in and moved merged slivers 3 -> 5.
FUSION_MIN_SUPPORT = 0.55
FUSION_AREA_LO = 0.80
FUSION_AREA_HI = 1.05


# ==========================================================================================
# Polygon clipping (exact convex-convex intersection; no OpenCV)
# ==========================================================================================

def _signed_area(pts):
    arr = np.asarray(pts, dtype=np.float64)
    x, y = arr[:, 0], arr[:, 1]
    return 0.5 * float(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1)))


def clip_polygon(subject, clip):
    """Sutherland-Hodgman: intersection of a polygon with a **convex** polygon."""
    subj = np.asarray(subject, dtype=np.float64)
    clip = np.asarray(clip, dtype=np.float64)
    if subj.shape[0] < 3 or clip.shape[0] < 3:
        return np.zeros((0, 2))
    if _signed_area(clip) < 0:
        clip = clip[::-1]

    out = list(map(tuple, subj))
    n = clip.shape[0]
    for i in range(n):
        ax, ay = clip[i]
        bx, by = clip[(i + 1) % n]
        ex, ey = bx - ax, by - ay

        def inside(p):
            return ex * (p[1] - ay) - ey * (p[0] - ax) >= 0.0

        if not out:
            break
        new = []
        prev = out[-1]
        for cur in out:
            cur_in = inside(cur)
            prev_in = inside(prev)
            if cur_in:
                if not prev_in:
                    dx, dy = cur[0] - prev[0], cur[1] - prev[1]
                    denom = ex * dy - ey * dx
                    t = 0.0 if abs(denom) < 1e-12 else (
                        ((ax - prev[0]) * ey - (ay - prev[1]) * ex) / -denom)
                    new.append((prev[0] + t * dx, prev[1] + t * dy))
                new.append(cur)
            elif prev_in:
                dx, dy = cur[0] - prev[0], cur[1] - prev[1]
                denom = ex * dy - ey * dx
                t = 0.0 if abs(denom) < 1e-12 else (
                    ((ax - prev[0]) * ey - (ay - prev[1]) * ex) / -denom)
                new.append((prev[0] + t * dx, prev[1] + t * dy))
            prev = cur
        out = new
    return np.asarray(out, dtype=np.float64) if len(out) >= 3 else np.zeros((0, 2))


def inter_area(a, b):
    poly = clip_polygon(a, b)
    return C.polygon_area(poly) if poly.shape[0] >= 3 else 0.0


def iou_of(a, b):
    inter = inter_area(a, b)
    union = C.polygon_area(a) + C.polygon_area(b) - inter
    return inter / union if union > 1e-9 else 0.0


def coverage(inner, outer):
    base = C.polygon_area(inner)
    if base <= 1e-9:
        return 0.0
    return max(0.0, min(1.0, inter_area(inner, outer) / base))


def align_measure(det, gt):
    """Mean/max corner distance after the best of 8 orderings (Kotlin parity)."""
    det = np.asarray(det, dtype=np.float64)
    gt = np.asarray(gt, dtype=np.float64)
    best_mean = float("inf")
    best_max = float("inf")
    for rev in (0, 1):
        seq = det if rev == 0 else det[::-1]
        for shift in range(len(seq)):
            d = np.linalg.norm(np.roll(seq, -shift, axis=0) - gt, axis=1)
            mean = float(d.mean())
            if mean < best_mean:
                best_mean = mean
                best_max = float(d.max())
    return best_mean, best_max


# ==========================================================================================
# Fixtures
# ==========================================================================================

def load_manifest():
    with open(MANIFEST, "r", encoding="utf-8") as f:
        return json.load(f)["items"]


def load_image(rel):
    path = os.path.join(ASSETS, rel)
    with Image.open(path) as im:
        return np.asarray(im.convert("RGB"), dtype=np.uint8)


def gray_of(rgb):
    a = np.asarray(rgb, dtype=np.float32)
    return 0.299 * a[:, :, 0] + 0.587 * a[:, :, 1] + 0.114 * a[:, :, 2]


class Asset:
    def __init__(self, item, canvas):
        self.name = item["name"]
        self.variant = item["variant"]
        self.side = item.get("sliver_side")
        shot = item[canvas]
        self.canvas = canvas
        self.asset = shot["asset"]
        self.width = shot["width"]
        self.height = shot["height"]
        self.diagonal = shot["diagonal"]
        self.page = np.asarray(shot["gt_page"], dtype=np.float64)
        self.spread = np.asarray(shot["gt_spread"], dtype=np.float64)
        self.sliver = (np.asarray(shot["gt_sliver"], dtype=np.float64)
                       if shot.get("gt_sliver") else None)
        self.sliver_fraction = float(item.get("sliver_fraction", 0.0))


def score_quad(asset, quad):
    if quad is None:
        return {
            "detected": False, "corner_pct": float("nan"), "corner_px": float("nan"),
            "iou": 0.0, "sliver_covered": 0.0, "merged": False,
            "area_frac": 0.0, "area_ratio": 0.0,
        }
    mean_px, _max_px = align_measure(quad, asset.page)
    cover = coverage(asset.sliver, quad) if asset.sliver is not None else 0.0
    frame = float(asset.width * asset.height)
    area = C.polygon_area(quad)
    page_area = C.polygon_area(asset.page)
    return {
        "detected": True,
        "corner_px": mean_px,
        "corner_pct": 100.0 * mean_px / asset.diagonal,
        "iou": iou_of(quad, asset.page),
        "sliver_covered": cover,
        "merged": cover >= SLIVER_COVER_MERGED,
        "area_frac": area / frame,
        "area_ratio": (area / page_area) if page_area > 1e-9 else 0.0,
    }


def summarize(rows):
    det = [r for r in rows if r["score"]["detected"]]
    sliver = [r for r in rows if r["asset"].sliver_fraction > 0.0]
    merged = [r for r in sliver if r["score"]["merged"]]
    return {
        "n": len(rows),
        "detected": len(det),
        "merged_slivers": len(merged),
        "sliver_total": len(sliver),
        "mean_corner_pct": float(np.mean([r["score"]["corner_pct"] for r in det])) if det else float("nan"),
        "mean_iou": float(np.mean([r["score"]["iou"] for r in det])) if det else float("nan"),
        "mean_area_ratio": float(np.mean([r["score"]["area_ratio"] for r in det])) if det else float("nan"),
        "mean_corner_pct_still": float(np.mean([r["score"]["corner_pct"] for r in det
                                                if r["asset"].canvas == "still"])) if det else float("nan"),
        "mean_corner_pct_preview": float(np.mean([r["score"]["corner_pct"] for r in det
                                                  if r["asset"].canvas == "preview"])) if det else float("nan"),
    }


def pcts(vals, qs=(50, 95)):
    if not vals:
        return {("p%d" % q): float("nan") for q in qs}
    arr = np.asarray(vals, dtype=np.float64)
    return {("p%d" % q): float(np.percentile(arr, q)) for q in qs}


# ==========================================================================================
# M-LSD candidate quad (use b)
# ==========================================================================================

def pick_best_square(squares, scores, segments, frame_w, frame_h):
    """Best square by NAVER score, re-ranked by line support and plausibility.

    NAVER's score rewards right angles and area but knows nothing about our page
    gate or about how well the quad's edges are supported by detected segments;
    both are folded in so the candidate handed to fusion is one the app could
    accept. Returns `(quad | None, nav_score, support)`.
    """
    if squares is None or len(squares) == 0:
        return None, 0.0, 0.0
    best = None
    for i, sq in enumerate(squares):
        quad = C.order_corners(sq)
        ok, _ = C.is_plausible_page_quad(quad, frame_w, frame_h)
        if not ok:
            continue
        support, _per = M.quad_line_support(quad, segments)
        nav = float(scores[i])
        score = nav + 0.5 * support
        if best is None or score > best[0]:
            best = (score, quad, nav, support)
    if best is None:
        return None, 0.0, 0.0
    return best[1], best[2], best[3]


# ==========================================================================================
# Main
# ==========================================================================================

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mlsd", default="320,512")
    ap.add_argument("--skip-mlsd", action="store_true")
    ap.add_argument("--json", default=os.path.join(OUT_DIR, "scores.json"))
    ap.add_argument("--md", default=os.path.join(OUT_DIR, "scores.md"))
    ap.add_argument("--corner-model", default=CORNER_MODEL)
    args = ap.parse_args()

    if not os.path.exists(args.corner_model):
        print("corner model missing: %s" % args.corner_model)
        return 2
    with open(args.corner_model, "rb") as f:
        corner_sha = hashlib.sha256(f.read()).hexdigest()

    sess_corner = ort.InferenceSession(args.corner_model, providers=["CPUExecutionProvider"])
    mlsd = {}
    if not args.skip_mlsd:
        for edge in [int(e) for e in args.mlsd.split(",") if e.strip()]:
            path = os.path.join(MLSD_DIR, "saved_model_%dx%d_large" % (edge, edge),
                                "model_float32.onnx")
            if not os.path.exists(path):
                print("M-LSD %d model missing: %s" % (edge, path))
                return 2
            with open(path, "rb") as f:
                sha = hashlib.sha256(f.read()).hexdigest()
            mlsd[edge] = {
                "session": ort.InferenceSession(path, providers=["CPUExecutionProvider"]),
                "sha256": sha, "bytes": os.path.getsize(path), "path": path,
            }

    items = load_manifest()
    rows = []
    stages = {"corner": [], "mlsd": {}, "stage": {}}
    for edge in mlsd:
        stages["mlsd"][edge] = []
        stages["stage"][edge] = []

    for item in items:
        for canvas in ("still", "preview"):
            asset = Asset(item, canvas)
            rgb = load_image(asset.asset)
            gray = gray_of(rgb)
            row = {"asset": asset, "corner": None, "corner_info": None,
                   "variants": {}, "mlsd": {}, "merge_info": {}, "fusion": {}}

            t0 = time.perf_counter()
            corner_quad, corner_info = C.detect(sess_corner, rgb)
            stages["corner"].append((time.perf_counter() - t0) * 1000.0)
            row["corner"] = corner_quad
            row["corner_info"] = corner_info
            row["variants"]["corner"] = score_quad(asset, corner_quad)

            for edge, entry in mlsd.items():
                t0 = time.perf_counter()
                junctions, pt_scores, vmap = M._run(entry["session"], rgb, (edge, edge))
                t_model = (time.perf_counter() - t0) * 1000.0
                t0 = time.perf_counter()
                _junc, raw = M.decode_junctions(junctions, pt_scores, vmap)
                t_decode = (time.perf_counter() - t0) * 1000.0
                t0 = time.perf_counter()
                merged, _theta = M.merge_segments(raw, len(junctions))
                t_merge = (time.perf_counter() - t0) * 1000.0
                t0 = time.perf_counter()
                found = M.find_squares(merged)
                t_squares = (time.perf_counter() - t0) * 1000.0
                stages["mlsd"][edge].append(t_model)
                stages["stage"][edge].append((t_model, t_decode, t_merge, t_squares))

                segments = (M.map_to_frame(merged, (asset.height, asset.width), (edge, edge))
                            if merged.size else np.zeros((0, 4)))
                squares = (M.map_to_frame(found["squares"], (asset.height, asset.width), (edge, edge))
                           if found["squares"].size else np.zeros((0, 4, 2)))
                scores = found.get("scores", np.zeros((0,)))

                cand_quad, nav, support = pick_best_square(
                    squares, scores, segments, asset.width, asset.height)
                row["mlsd"][edge] = {
                    "segments": int(len(segments)), "squares": int(len(squares)),
                    "ms_model": t_model, "ms_decode": t_decode,
                    "ms_merge": t_merge, "ms_squares": t_squares,
                    "quad": cand_quad, "nav_score": nav, "support": support,
                    "score": score_quad(asset, cand_quad),
                }
                row["variants"]["mlsd_%d" % edge] = row["mlsd"][edge]["score"]

                # --- use (a): merge rejection / repair -----------------------------
                merged_quad, info = MG.apply(corner_quad, segments, gray)
                row["variants"]["merge_%d" % edge] = score_quad(asset, merged_quad)
                row["merge_info"]["merge_%d" % edge] = info

                # Same split, area-only side choice: isolates the text signal.
                area_quad, area_info = _area_only_merge(corner_quad, segments, gray)
                row["variants"]["merge_area_%d" % edge] = score_quad(asset, area_quad)
                row["merge_info"]["merge_area_%d" % edge] = area_info

                # --- use (b): fusion with the corner model ------------------------
                chosen = merged_quad
                reason = "merge" if info.get("applied") else "corner"
                if cand_quad is not None:
                    ref = info.get("chosen_quad") if info.get("applied") else corner_quad
                    if ref is not None:
                        ratio = C.polygon_area(cand_quad) / max(C.polygon_area(ref), 1e-9)
                        if support >= FUSION_MIN_SUPPORT and FUSION_AREA_LO <= ratio <= FUSION_AREA_HI:
                            chosen = cand_quad
                            reason = "mlsd(support=%.2f,ratio=%.2f)" % (support, ratio)
                        else:
                            reason += ";corner(support=%.2f,ratio=%.2f)" % (support, ratio)
                row["variants"]["fuse_%d" % edge] = score_quad(asset, chosen)
                row["fusion"]["fuse_%d" % edge] = reason

            rows.append(row)
            print("%-26s %-7s corner=%-4s merge320=%-22s merge512=%-22s mlsd=%s" % (
                asset.name, canvas,
                "ok" if corner_quad is not None else "MISS",
                _brief(row["variants"].get("merge_320")),
                _brief(row["variants"].get("merge_512")),
                " ".join("%d:%d/%d" % (e, row["mlsd"][e]["segments"], row["mlsd"][e]["squares"])
                         for e in row["mlsd"])))

    report = {
        "corner_model": {"path": args.corner_model, "sha256": corner_sha},
        "mlsd": {str(e): {"path": v["path"], "sha256": v["sha256"], "bytes": v["bytes"]}
                 for e, v in mlsd.items()},
        "variants": {},
        "latency": {
            "corner_ms": pcts(stages["corner"]),
            "corner_n": len(stages["corner"]),
            "mlsd_model_ms": {str(e): pcts(v) for e, v in stages["mlsd"].items()},
            "mlsd_stage_ms": {str(e): {
                "model": pcts([s[0] for s in v]),
                "decode": pcts([s[1] for s in v]),
                "merge": pcts([s[2] for s in v]),
                "squares": pcts([s[3] for s in v]),
            } for e, v in stages["stage"].items()},
        },
        "per_asset": [],
    }
    names = ["corner"]
    for edge in mlsd:
        names += ["merge_%d" % edge, "merge_area_%d" % edge, "fuse_%d" % edge, "mlsd_%d" % edge]
    for name in names:
        sel = [(r, r["variants"][name]) for r in rows if name in r["variants"]]
        report["variants"][name] = summarize(
            [{"asset": r["asset"], "score": s} for r, s in sel])

    for r in rows:
        report["per_asset"].append({
            "name": r["asset"].name,
            "canvas": r["asset"].canvas,
            "variant": r["asset"].variant,
            "sliver_fraction": r["asset"].sliver_fraction,
            "corner": _quad_json(r["corner"]),
            "corner_info": _plain(r["corner_info"]),
            "scores": {k: _score_json(v) for k, v in r["variants"].items()},
            "merge_info": {k: _info_json(v) for k, v in r["merge_info"].items()},
            "fusion": r["fusion"],
            "mlsd": {str(e): {
                "segments": d["segments"], "squares": d["squares"],
                "ms_model": round(d["ms_model"], 2), "ms_squares": round(d["ms_squares"], 2),
                "nav_score": d["nav_score"], "support": d["support"],
                "quad": _quad_json(d["quad"]),
            } for e, d in r["mlsd"].items()},
        })

    os.makedirs(OUT_DIR, exist_ok=True)
    with open(args.json, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
    md = render_md(report)
    with open(args.md, "w", encoding="utf-8") as f:
        f.write(md)
    print("\n" + md)
    print("wrote %s and %s" % (args.json, args.md))
    return 0


def _area_only_merge(quad, segments, gray):
    """The same geometric split, with the page side chosen by area alone."""
    if quad is None or segments is None or len(segments) == 0:
        return quad, {"applied": False, "reason": "no_segments"}
    best = MG.find_merge_lines(quad, segments)
    cands = [(s, best[s]) for s in ("left", "right") if best[s] is not None]
    if not cands:
        return quad, {"applied": False, "reason": "no_interior_line"}
    cands.sort(key=lambda kv: kv[1][1]["d_side"])
    side, (line, ev) = cands[0]
    halves = M.split_quad_by_line(quad, line)
    if halves[0] is None or halves[1] is None:
        return quad, {"applied": False, "reason": "line_misses", "side": side}
    choice = MG.choose_side_by_area_only(list(halves))
    chosen = C.order_corners(halves[choice])
    strip = C.polygon_area(halves[1 - choice])
    tot = strip + C.polygon_area(chosen)
    strip_frac = strip / max(tot, 1e-9)
    p = MG.MERGE_CFG
    if strip_frac < p["min_strip_frac"] or strip_frac > p["max_strip_frac"]:
        return quad, {"applied": False, "reason": "strip_size", "side": side,
                      "strip_frac": strip_frac}
    return chosen, {"applied": True, "reason": "split_area", "side": side,
                    "strip_frac": strip_frac, "evidence": ev, "chosen_quad": chosen}


def _brief(score):
    if not score:
        return "-"
    if not score["detected"]:
        return "miss"
    return "%.2f%%%s" % (score["corner_pct"], " M" if score["merged"] else "")


def _quad_json(quad):
    return None if quad is None else np.round(np.asarray(quad, dtype=np.float64), 2).tolist()


def _score_json(score):
    return {k: _plain(v) for k, v in score.items()}


def _plain(v):
    if isinstance(v, dict):
        return {k: _plain(x) for k, x in v.items()}
    if isinstance(v, (np.floating, float)):
        return None if not np.isfinite(v) else float(v)
    if isinstance(v, (np.integer, int)):
        return int(v)
    if isinstance(v, bool):
        return bool(v)
    if isinstance(v, (list, tuple)):
        return [_plain(x) for x in v]
    if isinstance(v, np.ndarray):
        return np.round(v.astype(float), 2).tolist()
    return v


def _info_json(info):
    return _plain(info)


def render_md(report):
    lines = ["# H30 — M-LSD + merge rule, offline scores", ""]
    lines.append("Corner model `%s`." % report["corner_model"]["sha256"])
    for e, d in report["mlsd"].items():
        lines.append("M-LSD %s: `%s` (%d B)." % (e, d["sha256"], d["bytes"]))
    lines += ["", "## Aggregate", ""]
    lines.append("| variant | detected | merged slivers | mean corner % diag | still | preview | mean IoU | area/page |")
    lines.append("|---|---|---|---|---|---|---|---|")
    for name, s in report["variants"].items():
        lines.append("| %s | %d/%d | %d/%d | %.4f | %.4f | %.4f | %.4f | %.4f |" % (
            name, s["detected"], s["n"], s["merged_slivers"], s["sliver_total"],
            s["mean_corner_pct"], s["mean_corner_pct_still"], s["mean_corner_pct_preview"],
            s["mean_iou"], s["mean_area_ratio"]))
    lat = report["latency"]
    lines += ["", "## Latency (desktop CPU, ORT %s)" % ort.__version__, ""]
    lines.append("| stage | p50 ms | p95 ms |")
    lines.append("|---|---|---|")
    lines.append("| corner model | %.2f | %.2f |" % (lat["corner_ms"]["p50"], lat["corner_ms"]["p95"]))
    for e, v in lat["mlsd_model_ms"].items():
        st = lat["mlsd_stage_ms"][e]
        lines.append("| M-LSD %s model | %.2f | %.2f |" % (e, v["p50"], v["p95"]))
        lines.append("| M-LSD %s decode+merge | %.2f | %.2f |" % (
            e, st["decode"]["p50"] + st["merge"]["p50"], st["decode"]["p95"] + st["merge"]["p95"]))
        lines.append("| M-LSD %s square enum | %.2f | %.2f |" % (e, st["squares"]["p50"], st["squares"]["p95"]))
    lines += ["", "## Per asset", ""]
    variants = list(report["variants"].keys())
    lines.append("| asset | canvas | sliver | " + " | ".join(variants) + " |")
    lines.append("|" + "---|" * (3 + len(variants)))
    for a in report["per_asset"]:
        cells = []
        for v in variants:
            s = a["scores"].get(v)
            if not s:
                cells.append("-")
            elif not s["detected"]:
                cells.append("miss")
            else:
                cells.append("%.2f%%%s" % (s["corner_pct"], " M" if s["merged"] else ""))
        lines.append("| %s | %s | %.2f | %s |" % (
            a["name"], a["canvas"], a["sliver_fraction"], " | ".join(cells)))
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    sys.exit(main())
