#!/usr/bin/env python3
"""h32_annotate.py - draft + render the ground-truth annotation for the REAL book-spread set.

Annotation method (recorded in real_bookspread_manifest.json, reproducible):

  1. **Draft** (`draft` mode): run the SAME offline detector stack the red baseline replays
     (LCNet100 corner model -> M-LSD 320 -> fold search) on each pooled thumbnail and write the
     draft spread quad + fold candidate to tooling/out/h32/draft_annotations.json.
  2. **Render** (`render` mode): draw an overlay per image (draft spread quad in orange, proposed
     target-page quad in green, fold line in blue, facing-side label) into
     tooling/out/h32/annot/NNN_overlay.jpg so a human/agent can SEE what was drafted.
  3. **Correct** (manual step): the agent inspects every overlay with vision and writes
     tooling/out/h32/h32_selection.json, either confirming the draft (`"gt": "draft"`) or giving
     explicit corrected corner coordinates (`"gt": {"target_quad": [[x,y] x4], ...}`). Images that
     are not photographs of a real open book, or where the intended target page cannot be
     established, are EXCLUDED with a reason.
  4. **Finalize** (`finalize` mode): resolve every selection to absolute GT quads in the bundled
     image's pixel space and write tooling/out/h32/gt_final.json.

Ground truth is an ANNOTATION, not a measurement: this is stated in the manifest. The annotation
target is "the one page that is fully seen"; the facing page (partially cut at the frame edge) is
recorded as `facing_side` + `facing_quad_visible` where it can be delimited.
"""

import argparse
import json
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import h30_corner as C  # noqa: E402
import h30_merge as MG  # noqa: E402
import h30_mlsd as M  # noqa: E402

import onnxruntime as ort  # noqa: E402

OUT_DIR = os.path.join(REPO, "tooling", "out", "h32")
THUMB_DIR = r"E:\DocScanAI-cache\bookspread_real\thumb"
CORNER_MODEL = os.path.join(os.path.dirname(REPO), "dsmodels", "m1c_detect.onnx")
MLSD_MODEL = os.path.join(os.path.dirname(REPO), "dsmodels", "m2_mlsd.onnx")
ANNOT_DIR = os.path.join(OUT_DIR, "annot")


def hb(msg):
    import time
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "HEARTBEAT.txt"), "a", encoding="utf-8") as fh:
        fh.write("[%s] %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg))


def load_pool():
    with open(os.path.join(OUT_DIR, "pool_index.json"), encoding="utf-8") as fh:
        return json.load(fh)


def draft_all(limit=0, only=None):
    """Detector-draft the target page for every pooled thumbnail."""
    pool = load_pool()
    corner = ort.InferenceSession(CORNER_MODEL, providers=["CPUExecutionProvider"])
    mlsd = ort.InferenceSession(MLSD_MODEL, providers=["CPUExecutionProvider"])

    out = []
    for item in pool:
        idx = item["pool_index"]
        if only and idx not in only:
            continue
        if limit and len(out) >= limit:
            break
        path = item["local_path"]
        if not os.path.exists(path):
            continue
        with Image.open(path) as im:
            rgb = np.asarray(im.convert("RGB"), dtype=np.uint8)
        h, w = rgb.shape[:2]

        rec = {
            "pool_index": idx,
            "title": item["title"],
            "local_path": path,
            "width": w,
            "height": h,
            "diagonal": float(np.hypot(w, h)),
        }

        quad, info = C.detect(corner, rgb)
        rec["corner_quad"] = None if quad is None else [[float(x), float(y)] for x, y in quad]
        rec["corner_info"] = info if isinstance(info, str) else float(info)

        if quad is None:
            rec["fold"] = None
            rec["draft_target"] = None
            rec["draft_reason"] = "no_detection"
            out.append(rec)
            hb("draft #%d: no_detection" % idx)
            continue

        # M-LSD at the shipped 320 edge, decode threshold = device params.score (0.06).
        segs = M.pred_lines(rgb, mlsd, input_shape=(320, 320), score_thr=0.06)
        rec["n_segments"] = int(len(segs))
        folds = MG.find_merge_lines(np.asarray(quad, dtype=np.float64), segs)
        fold_rec = None
        for side in ("left", "right"):
            if folds.get(side) is not None:
                seg, ev = folds[side]
                fold_rec = {
                    "side": side,
                    "seg": [float(v) for v in seg],
                    "d_side": float(ev["d_side"]),
                }
        rec["fold"] = fold_rec

        if fold_rec is None:
            rec["draft_target"] = rec["corner_quad"]
            rec["draft_reason"] = "no_fold_full_spread"
        else:
            seg = np.asarray(fold_rec["seg"], dtype=np.float64)
            halves = M.split_quad_by_line(np.asarray(quad, dtype=np.float64), seg)
            if halves[0] is None or halves[1] is None:
                rec["draft_target"] = rec["corner_quad"]
                rec["draft_reason"] = "fold_misses"
            else:
                # Draft picks the LARGER half (the shipped selector) — the visual check decides.
                areas = [C.polygon_area(halves[0]), C.polygon_area(halves[1])]
                keep = 0 if areas[0] >= areas[1] else 1
                rec["draft_target"] = [[float(x), float(y)] for x, y in C.order_corners(halves[keep])]
                rec["draft_strip_area_frac"] = float(min(areas) / max(sum(areas), 1e-9))
                rec["draft_reason"] = "split_larger"

        out.append(rec)
        hb("draft #%d: %s fold=%s" % (idx, rec["draft_reason"], (fold_rec or {}).get("side")))

    with open(os.path.join(OUT_DIR, "draft_annotations.json"), "w", encoding="utf-8") as fh:
        json.dump(out, fh, indent=2)
    print("drafted %d images -> draft_annotations.json" % len(out))
    return 0


def render_overlays():
    """Overlay PNGs so the annotation can be visually verified/corrected."""
    os.makedirs(ANNOT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "draft_annotations.json"), encoding="utf-8") as fh:
        drafts = json.load(fh)
    for rec in drafts:
        path = rec["local_path"]
        if not os.path.exists(path):
            continue
        im = Image.open(path).convert("RGB")
        dr = ImageDraw.Draw(im)
        if rec.get("corner_quad"):
            dr.polygon([tuple(p) for p in rec["corner_quad"]], outline=(255, 140, 0), width=3)
        if rec.get("draft_target"):
            dr.polygon([tuple(p) for p in rec["draft_target"]], outline=(0, 200, 0), width=3)
        fold = rec.get("fold")
        if fold:
            x1, y1, x2, y2 = fold["seg"]
            dr.line([(x1, y1), (x2, y2)], fill=(0, 90, 255), width=3)
            dr.text((8, 8), "fold %s d=%.3f" % (fold["side"], fold["d_side"]), fill=(0, 90, 255))
        dr.text((8, rec["height"] - 20), "#%d %s | %s" % (
            rec["pool_index"], rec["title"].replace("File:", "")[:40], rec["draft_reason"]), fill=(255, 255, 255))
        out = os.path.join(ANNOT_DIR, "%03d_overlay.jpg" % rec["pool_index"])
        im.save(out, quality=88)
    print("rendered %d overlays -> %s" % (len(drafts), ANNOT_DIR))
    return 0


def finalize():
    """Resolve h32_selection.json (human/agent-corrected) to absolute GT quads."""
    sel_path = os.path.join(OUT_DIR, "h32_selection.json")
    if not os.path.exists(sel_path):
        print("write %s first (see module docstring)" % sel_path)
        return 2
    with open(sel_path, encoding="utf-8") as fh:
        selections = json.load(fh)
    with open(os.path.join(OUT_DIR, "draft_annotations.json"), encoding="utf-8") as fh:
        drafts = {r["pool_index"]: r for r in json.load(fh)}
    final = []
    for sel in selections:
        if sel.get("exclude"):
            continue
        idx = sel["pool_index"]
        d = drafts.get(idx)
        if d is None:
            continue
        gt = sel.get("gt")
        if gt == "draft":
            quad = d["draft_target"]
            method = "detector_draft_confirmed"
        elif isinstance(gt, dict) and gt.get("target_quad"):
            quad = gt["target_quad"]
            method = "detector_draft_corrected"
        else:
            continue
        if quad is None:
            continue
        final.append({
            "pool_index": idx,
            "title": d["title"],
            "local_path": d["local_path"],
            "width": d["width"],
            "height": d["height"],
            "diagonal": d["diagonal"],
            "gt_target_quad": quad,
            "facing_side": sel.get("facing_side", (d.get("fold") or {}).get("side")),
            "facing_quad_visible": gt.get("facing_quad_visible") if isinstance(gt, dict) else sel.get("facing_quad_visible"),
            "scene": sel.get("scene", "real_spread"),
            "annotation_method": method,
            "note": sel.get("note", ""),
        })
    with open(os.path.join(OUT_DIR, "gt_final.json"), "w", encoding="utf-8") as fh:
        json.dump(final, fh, indent=2)
    print("finalized %d GT quads -> gt_final.json" % len(final))
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["draft", "render", "finalize", "all"])
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()
    if args.mode in ("draft", "all"):
        draft_all(limit=args.limit)
    if args.mode in ("render", "all"):
        render_overlays()
    if args.mode in ("finalize", "all"):
        finalize()
    return 0


if __name__ == "__main__":
    sys.exit(main())
