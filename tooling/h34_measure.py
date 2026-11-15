#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H34 — measure the SHIPPED small models against the oracle pseudo-GT (owner-110).

One instrumented host-side run: the shipped detector path (h32_replay.run_pipeline,
variant "shipped" = corner model + gate + MlsdTrigger/MlsdMergeRepair) over every
photo in corpus_owner/, scored against tooling/out/h34/pseudo_gt_owner110.json.

Per image:
  iou            IoU(our quad, oracle quad)            [h30_score.iou_of]
  corner_pct     mean corner error as % of frame diag  [h30_score.align_measure, 8 orderings]
  no_detection   our pipeline returned no quad
  wrong_page     IoU < 0.35 AND our quad touches the frame while the oracle quad
                 does not  -> ours sits on the cut/partial page, oracle on the
                 fully visible one (the h32 WRONG_PAGE_IOU constant, owner photos
                 carry no facing-page GT so the frame signature disambiguates)
  merged         our quad covers >= 80% of the oracle quad AND its area is >= 1.5x
                 the oracle's  -> it absorbed both pages of a spread

Failure category for the worst-10 (heuristic signatures ONLY - no hand annotation):
  second-page-showing   merged, or wrong-page with our quad smaller than the oracle's
  table-edge-coincidence wrong-page with our quad frame-hugging and large
  lighting              frame mean luma < 60 or luma std > 70
  other                 everything else (incl. no-page negatives)

Also writes annotated preview copies (oracle quad = GREEN, our quad = RED) for the
vision cross-check into tooling/out/h34/vision/ - corpus_owner/ is NEVER written.

Usage:
  python tooling\\h34_measure.py                # full run -> owner110_measure.json
  python tooling\\h34_measure.py --vision a,b,c  # annotated copies for named files
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import h32_replay as R  # noqa: E402  (shipped-pipeline mirror + metrics)
import h30_score as S  # noqa: E402  (align_measure returns (mean, max))
import h34_oracle_run as HR  # noqa: E402  (lenient box-mid parse of the raw reply)

OUT = os.path.join(HERE, "out", "h34")
VISION_DIR = os.path.join(OUT, "vision")
CORPUS_DIR = os.path.join(REPO, "corpus_owner")
PSEUDO_GT = os.path.join(OUT, "pseudo_gt_owner110.json")

WRONG_PAGE_IOU = 0.35        # h32_replay constant, reused verbatim
MERGED_COVER = 0.80          # ours must contain the oracle page ...
MERGED_AREA_RATIO = 1.50     # ... and extend well beyond it (both pages)


def hb(msg):
    line = "[%s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg)
    with open(os.path.join(OUT, "HEARTBEAT.txt"), "a", encoding="utf-8") as fh:
        fh.write(line + "\n")
    print(line, flush=True)


def corners_to_px(corners, w, h):
    return [[corners[k][0] * w, corners[k][1] * h] for k in ("tl", "tr", "br", "bl")]


def oracle_corners(rec):
    if not rec:
        return None, None
    if rec.get("corners"):
        return rec["corners"], rec.get("corner_format", "xy")
    if rec.get("corners_lenient"):
        return rec["corners_lenient"], rec.get("corner_format")
    # H34 fix (trivial harness bug): records logged before the lenient-parse patch
    # carry only `raw`; fall back to the deterministic box-mid parse from raw, exactly
    # like h34_oracle_run.rec_corners (which recovered all box replies on calibration).
    return HR.parse_corners_lenient(rec.get("raw"))


def frame_touches(quad, w, h, frac=0.02):
    return R.touches_frame(quad, w, h, frac)


def luma_stats(rgb):
    g = R.gray_of(rgb)
    return float(np.mean(g)), float(np.std(g))


def classify(row, luma_mean, luma_std):
    if row["no_detection"]:
        return "other"
    if row["merged"]:
        return "second-page-showing"
    if row["wrong_page"]:
        return "table-edge-coincidence" if row.get("area_ratio", 1.0) >= 0.7 else "second-page-showing"
    if luma_mean < 60.0 or luma_std > 70.0:
        return "lighting"
    return "other"


def annotate(img_path, oracle_q, our_q, out_path, label):
    with Image.open(img_path) as im:
        im = im.convert("RGB")
        d = ImageDraw.Draw(im)
        if oracle_q is not None:
            pts = [tuple(p) for p in oracle_q] + [tuple(oracle_q[0])]
            d.line(pts, fill=(0, 220, 0), width=6)
        if our_q is not None:
            pts = [tuple(p) for p in our_q] + [tuple(our_q[0])]
            d.line(pts, fill=(220, 0, 0), width=6)
        d.text((12, 12), label, fill=(255, 255, 0))
        im.save(out_path)


def main():
    ap = argparse.ArgumentParser(description="H34: shipped detector vs oracle pseudo-GT")
    ap.add_argument("--vision", help="comma-separated image basenames to annotate only")
    args = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)

    with open(PSEUDO_GT, encoding="utf-8") as fh:
        pgt = json.load(fh)
    by_name = {}
    for r in pgt["items"]:
        by_name[os.path.basename(r.get("image", ""))] = r

    corner = ort.InferenceSession(R.CORNER_MODEL, providers=["CPUExecutionProvider"])
    mlsd = ort.InferenceSession(R.MLSD_MODEL, providers=["CPUExecutionProvider"])

    names = sorted(n for n in os.listdir(CORPUS_DIR)
                   if os.path.splitext(n)[1].lower() in R.__dict__.get(
                       "IMAGE_EXTS", {".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp"}))
    if args.vision:
        want = set(args.vision.split(","))
        names = [n for n in names if n in want]

    rows = []
    for name in names:
        path = os.path.join(CORPUS_DIR, name)
        rec = by_name.get(name)
        ocorners, ofmt = oracle_corners(rec)
        rgb = R.load_rgb(path)
        h, w = rgb.shape[:2]
        t0 = time.time()
        quad, diag = R.run_pipeline(rgb, corner, mlsd, "shipped")
        ms = (time.time() - t0) * 1000.0

        # Normalized oracle corners are resolution-independent: always scale by the
        # PIL-loaded size (some records lack probe_size width/height).
        oq = corners_to_px(ocorners, w, h) if (ocorners and rec) else None
        luma_mean, luma_std = luma_stats(rgb)

        row = {
            "image": name,
            "width": w, "height": h,
            "diag": float(np.hypot(w, h)),
            "oracle_format": ofmt,
            "decision": diag.get("reason", ""),
            "ms": round(ms, 1),
            "luma_mean": round(luma_mean, 1),
            "luma_std": round(luma_std, 1),
            "no_detection": quad is None,
            "no_oracle_corners": oq is None,
            "iou": None, "corner_pct": None,
            "wrong_page": False, "merged": False,
            "area_ratio": None, "our_frame_touch": None, "oracle_frame_touch": None,
        }
        if quad is not None:
            q = np.asarray(quad, dtype=np.float64)
            row["our_frame_touch"] = frame_touches(q, w, h)
        if oq is not None:
            row["oracle_frame_touch"] = frame_touches(np.asarray(oq, dtype=np.float64), w, h)
        if quad is not None and oq is not None:
            oqa = np.asarray(oq, dtype=np.float64)
            q = np.asarray(quad, dtype=np.float64)
            row["iou"] = round(float(R.iou_of(q, oqa)), 4)
            # h30_score.align_measure returns (mean, max); h32_replay's wrapper
            # returns only the mean - call the score variant for both.
            mean_px, _max_px = S.align_measure(q, oqa)
            row["corner_pct"] = round(100.0 * mean_px / max(row["diag"], 1.0), 3)
            area_q = float(R.C.polygon_area(q))
            area_o = float(R.C.polygon_area(oqa))
            row["area_ratio"] = round(area_q / area_o, 3) if area_o > 1e-9 else None
            cover_o = R.coverage(oqa, q)  # fraction of the oracle page covered by ours
            row["covers_oracle"] = round(float(cover_o), 4)
            row["merged"] = bool(cover_o >= MERGED_COVER and (row["area_ratio"] or 0) >= MERGED_AREA_RATIO)
            row["wrong_page"] = bool(
                row["iou"] < WRONG_PAGE_IOU
                and row.get("our_frame_touch") and not row.get("oracle_frame_touch")
                and not row["merged"])
        row["category"] = classify(row, luma_mean, luma_std)
        rows.append(row)
        hb("measure %s: iou=%s corner=%s%% dec=%s" % (
            name, row["iou"], row["corner_pct"], row["decision"] or "-"))

    det = [r for r in rows if not r["no_detection"]]
    scored = [r for r in det if r["iou"] is not None]
    summary = {
        "images": len(rows),
        "no_detection": sum(1 for r in rows if r["no_detection"]),
        "no_oracle_corners": sum(1 for r in rows if r["no_oracle_corners"]),
        "wrong_page": sum(1 for r in rows if r["wrong_page"]),
        "merged": sum(1 for r in rows if r["merged"]),
        "mean_iou": round(float(np.mean([r["iou"] for r in scored])), 4) if scored else None,
        "mean_corner_pct": round(float(np.mean([r["corner_pct"] for r in scored])), 3) if scored else None,
        "median_iou": round(float(np.median([r["iou"] for r in scored])), 4) if scored else None,
        "oracle_format_xy": sum(1 for r in rows if r.get("oracle_format") == "xy"),
        "oracle_format_box_mid": sum(1 for r in rows if r.get("oracle_format") == "box_mid"),
    }
    cats = {}
    for r in rows:
        cats.setdefault(r["category"], 0)
        cats[r["category"]] += 1
    summary["category_counts"] = cats

    worst = sorted(
        (r for r in rows if not r["no_detection"] and r["iou"] is not None),
        key=lambda r: r["iou"])[:10]
    worst_list = [{"image": r["image"], "iou": r["iou"], "corner_pct": r["corner_pct"],
                   "category": r["category"], "decision": r["decision"]}
                  for r in worst]

    report = {"summary": summary, "worst10": worst_list, "rows": rows}
    out_path = os.path.join(OUT, "owner110_measure.json")
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2)
    print(json.dumps(summary, indent=2))
    print("worst10:")
    for w10 in worst_list:
        print("  %-28s iou=%.3f corner=%.2f%%  %s  (%s)" % (
            w10["image"], w10["iou"], w10["corner_pct"], w10["category"], w10["decision"]))
    hb("measure DONE -> %s" % os.path.relpath(out_path, REPO))
    return 0


if __name__ == "__main__":
    sys.exit(main())
