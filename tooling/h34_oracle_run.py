#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H34 — live oracle runs (calibration material + owner-110) + page-choice check.

Phases
  probe      one live query on a corpus_owner photo: validates image input on the
             running LM Studio server and measures per-image latency.
  calib      oracle over the KNOWN-GT material (18 synthetic canvases + 16 real)
             -> tooling/out/h34/oracle_calib_manifest.json (oracle_batch schema)
  pagechoice the "fully visible page" instruction check: oracle quad vs
             gt_target_quad / gt_facing_quad on the 9 real_partial_left images,
             plus the synthetic sliver canvases -> page_choice.json
  owner      oracle over corpus_owner/ (ONE pass, ONE retry per failed strict-JSON
             parse) -> tooling/out/h34/pseudo_gt_owner110.json (oracle_batch schema)

The query loop mirrors tooling/oracle/oracle_batch.py::run_batch record-for-record
(same oracle_query calls, same manifest schema) with the two additions the brief
allows: one retry on a failed strict-JSON parse, and a per-image heartbeat line.
Stdlib only. Never writes into corpus_owner/.

Usage:
  python tooling\\h34_oracle_run.py --phase probe
  python tooling\\h34_oracle_run.py --phase calib
  python tooling\\h34_oracle_run.py --phase pagechoice
  python tooling\\h34_oracle_run.py --phase owner
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from concurrent.futures import ThreadPoolExecutor, as_completed

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(HERE, "oracle"))

import oracle_query as Q  # noqa: E402
import oracle_calibrate as OC  # noqa: E402  (stdlib metrics + the encoded verdict rule)

OUT = os.path.join(HERE, "out", "h34")
SYNTH_DIR = os.path.join(REPO, "app", "src", "androidTest", "assets", "bookspread")
REAL_DIR = os.path.join(REPO, "app", "src", "androidTest", "assets", "bookspread_real")
CORPUS_DIR = os.path.join(REPO, "corpus_owner")
SYNTH_MANIFEST = os.path.join(SYNTH_DIR, "book_spread_manifest.json")
REAL_MANIFEST = os.path.join(REAL_DIR, "real_bookspread_manifest.json")

SERVER = Q.DEFAULT_SERVER
MODEL = Q.DEFAULT_MODEL
TARGET_PAGE = "the fully visible page"
TIMEOUT = 300.0
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp"}


def hb(msg):
    os.makedirs(OUT, exist_ok=True)
    line = "[%s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg)
    with open(os.path.join(OUT, "HEARTBEAT.txt"), "a", encoding="utf-8") as fh:
        fh.write(line + "\n")
    print(line, flush=True)


def list_images(folder):
    if not os.path.isdir(folder):
        return []
    out = []
    for name in sorted(os.listdir(folder)):
        if os.path.splitext(name)[1].lower() in IMAGE_EXTS:
            out.append(os.path.join(folder, name))
    return out


def parse_corners_lenient(raw):
    """(corners, tag) from a model reply.

    Strict 2-point corners first ("xy"). Else the observed Qwen3-VL format
    deviation: 4-number corner-REGION boxes ("tl": [x0, y0, x1, y1]) -> the region
    midpoint ("box_mid"). Deterministic post-parse of the already-recorded raw
    reply; never re-queries the model.
    """
    c = Q.extract_corners(raw)
    if c is not None:
        return Q.normalize_corners(c), "xy"
    if not raw:
        return None, None
    idx = raw.find("{")
    while idx >= 0:
        end = raw.find("}", idx)
        if end < 0:
            break
        try:
            obj = json.loads(raw[idx:end + 1])
        except ValueError:
            obj = None
        if isinstance(obj, dict) and all(k in obj for k in OC.CORNER_KEYS):
            out, ok, boxed = {}, True, False
            for k in OC.CORNER_KEYS:
                v = obj[k]
                nums = v if isinstance(v, (list, tuple)) else []
                if len(nums) == 4 and all(isinstance(x, (int, float)) for x in nums):
                    out[k] = [Q.clamp01((nums[0] + nums[2]) / 2.0),
                              Q.clamp01((nums[1] + nums[3]) / 2.0)]
                    boxed = True
                elif len(nums) == 2 and all(isinstance(x, (int, float)) for x in nums):
                    out[k] = [Q.clamp01(nums[0]), Q.clamp01(nums[1])]
                else:
                    ok = False
                    break
            if ok:
                return out, ("box_mid" if boxed else "xy")
        idx = raw.find("{", idx + 1)
    return None, None


def rec_corners(rec):
    """(corners, format) for a manifest record: stored strict, else lenient from raw."""
    if not rec:
        return None, None
    if rec.get("corners"):
        return rec["corners"], rec.get("corner_format", "xy")
    if rec.get("corners_lenient"):
        return rec["corners_lenient"], rec.get("corner_format")
    return parse_corners_lenient(rec.get("raw"))


def query_one(img):
    """One oracle query; at most ONE retry, and only for a failed strict-JSON parse."""
    size = Q.probe_size(img)
    record = {
        "image": os.path.abspath(img),
        "width": size[0] if size else None,
        "height": size[1] if size else None,
        "target_page": TARGET_PAGE,
        "server": SERVER,
        "model": MODEL,
        "corners": None,
        "raw": None,
        "ok": False,
    }
    ms = 0.0
    for attempt in (1, 2):
        try:
            b64, _mime = Q.image_data_url(img)
            body = Q.build_request(b64, MODEL, TARGET_PAGE)
            t0 = time.time()
            response = Q.post_json(SERVER, body, TIMEOUT)
            ms = (time.time() - t0) * 1000.0
            content = Q.extract_reply_content(response)
            record["raw"] = content
            corners = Q.extract_corners(content)
            if corners is not None:
                record["corners"] = Q.normalize_corners(corners)
                record["corners_lenient"], record["corner_format"] = (
                    record["corners"], "xy")
                record["ok"] = True
                record.pop("error", None)
                return record, ms, attempt
            record["error"] = "no strict-JSON corners in reply"
        except Exception as exc:  # noqa: BLE001
            record["error"] = str(exc)
            return record, ms, attempt  # transport error: no retry (parse-retry only)
    # Strict parse failed after the ONE allowed retry: keep the raw reply and the
    # deterministic lenient fallback (box-region midpoints) for the offline report.
    record["corners_lenient"], record["corner_format"] = parse_corners_lenient(record.get("raw"))
    return record, ms, 2


def run_folder_phase(name, folders, out_name, workers=4):
    """One pass over every image, crash-safe (JSONL log), optionally pipelined.

    `workers` > 1 overlaps requests: the LM Studio server's vision prefill for
    image N+1 runs while image N generates its corners (continuous batching),
    which is where the per-image time actually goes (probe: 5 s @1280x720 vs
    16 s @3024x4032 -> prefill scales with pixels). Semantics per image are
    unchanged: ONE query, ONE retry only on a failed strict-JSON parse.
    """
    os.makedirs(OUT, exist_ok=True)
    log_path = os.path.join(OUT, out_name.replace(".json", "_log.jsonl"))
    done = {}
    if os.path.isfile(log_path):
        with open(log_path, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    rec = json.loads(line)
                except ValueError:
                    continue  # partial line from a crash mid-write
                done[os.path.basename(rec.get("image", ""))] = rec
        hb("%s resume: %d records already logged in %s (skipped, no re-query)"
           % (name, len(done), os.path.basename(log_path)))
    pending = []
    items = list(done.values())
    for folder in folders:
        for img in list_images(folder):
            key = os.path.basename(img)
            if key in done:  # crash-safe resume: still ONE query per image overall
                continue
            pending.append(img)
    t_start = time.time()
    if workers > 1 and len(pending) > 1:
        with ThreadPoolExecutor(max_workers=workers) as ex:
            futs = {ex.submit(query_one, img): img for img in pending}
            for fut in as_completed(futs):
                img = futs[fut]
                rec, ms, attempts = fut.result()
                items.append(rec)
                with open(log_path, "a", encoding="utf-8") as fh:
                    fh.write(json.dumps(rec) + "\n")
                hb("%s %s: ok=%s attempts=%d ms=%.0f %s" % (
                    name, os.path.basename(img), rec["ok"], attempts, ms,
                    rec.get("error", "")))
    else:
        for img in pending:
            rec, ms, attempts = query_one(img)
            items.append(rec)
            with open(log_path, "a", encoding="utf-8") as fh:
                fh.write(json.dumps(rec) + "\n")
            hb("%s %s: ok=%s attempts=%d ms=%.0f %s" % (
                name, os.path.basename(img), rec["ok"], attempts, ms,
                rec.get("error", "")))
    manifest = {
        "dataset": "oracle_pseudo_gt",
        "model": MODEL,
        "server": SERVER,
        "count": len(items),
        "ok_count": sum(1 for r in items if r.get("ok")),
        "items": items,
    }
    os.makedirs(OUT, exist_ok=True)
    out_path = os.path.join(OUT, out_name)
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2)
    hb("%s DONE: %d/%d ok in %.1f min -> %s" % (
        name, manifest["ok_count"], manifest["count"],
        (time.time() - t_start) / 60.0, os.path.relpath(out_path, REPO)))
    print(json.dumps({k: manifest[k] for k in ("dataset", "count", "ok_count")}, indent=2))
    return 0


def px_quad(corners, w, h):
    return [[corners[k][0] * w, corners[k][1] * h] for k in OC.CORNER_KEYS]


def load_json(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def phase_pagechoice():
    calib = load_json(os.path.join(OUT, "oracle_calib_manifest.json"))
    by_stem = {}
    for r in calib["items"]:
        stem = os.path.splitext(os.path.basename(r.get("image", "")))[0]
        by_stem[stem] = r

    rows = []
    for it in load_json(REAL_MANIFEST)["items"]:
        if it.get("scene") != "real_partial_left":
            continue
        rec = by_stem.get(os.path.splitext(it["asset"])[0])
        row = {"asset": it["asset"], "facing_side": it.get("facing_side")}
        corners, fmt = rec_corners(rec)
        if corners and rec and rec.get("width"):
            row["corner_format"] = fmt
            q = px_quad(corners, rec["width"], rec["height"])
            gt_t, gt_f = it["gt_target_quad"], it["gt_facing_quad"]
            iou_t = OC.iou_of(q, gt_t)
            iou_f = OC.iou_of(q, gt_f)
            cov_t = OC.inter_area(q, gt_t) / OC.poly_area(gt_t)
            cov_f = OC.inter_area(q, gt_f) / OC.poly_area(gt_f)
            row.update({
                "iou_target": round(iou_t, 4), "iou_facing": round(iou_f, 4),
                "covers_target": round(cov_t, 4), "covers_facing": round(cov_f, 4),
                "choice": "target(full page)" if iou_t > iou_f else "WRONG(facing/partial)",
                "correct": bool(iou_t > iou_f),
            })
        else:
            row.update({"correct": None, "choice": "no oracle corners"})
        rows.append(row)

    srows = []
    for it in load_json(SYNTH_MANIFEST)["items"]:
        if not it.get("sliver_side"):
            continue
        for canvas in ("still", "preview"):
            shot = it.get(canvas) or {}
            asset = shot.get("asset")
            gt_s = shot.get("gt_sliver")
            if not asset or not gt_s:
                continue
            rec = by_stem.get(os.path.splitext(asset)[0])
            row = {"asset": asset, "sliver_side": it.get("sliver_side")}
            corners, fmt = rec_corners(rec)
            if corners and rec and rec.get("width"):
                row["corner_format"] = fmt
                q = px_quad(corners, rec["width"], rec["height"])
                iou_p = OC.iou_of(q, shot["gt_page"])
                iou_s = OC.iou_of(q, gt_s)
                row.update({
                    "iou_page": round(iou_p, 4), "iou_sliver": round(iou_s, 4),
                    "choice": "page" if iou_p > iou_s else "WRONG(sliver)",
                    "correct": bool(iou_p > iou_s),
                })
            else:
                row.update({"correct": None, "choice": "no oracle corners"})
            srows.append(row)

    real_ok = sum(1 for r in rows if r.get("correct"))
    real_n = sum(1 for r in rows if r.get("correct") is not None)
    syn_ok = sum(1 for r in srows if r.get("correct"))
    syn_n = sum(1 for r in srows if r.get("correct") is not None)
    result = {
        "question": "does the oracle honour 'the fully visible page, not the "
                    "partially cut one' on the spread cases?",
        "real_partial_left": rows,
        "real_partial_score": "%d/%d correct page choice" % (real_ok, real_n),
        "synthetic_sliver": srows,
        "synthetic_sliver_score": "%d/%d correct page choice" % (syn_ok, syn_n),
    }
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "page_choice.json"), "w", encoding="utf-8") as fh:
        json.dump(result, fh, indent=2)
    for r in rows:
        print("real %s: %s (iou_t=%.3f iou_f=%.3f)" % (
            r["asset"], r["choice"], r.get("iou_target") or 0.0, r.get("iou_facing") or 0.0))
    for r in srows:
        print("synth %s: %s (iou_p=%.3f iou_s=%.3f)" % (
            r["asset"], r["choice"], r.get("iou_page") or 0.0, r.get("iou_sliver") or 0.0))
    print(result["real_partial_score"] + " | " + result["synthetic_sliver_score"])
    hb("pagechoice: %s | %s" % (result["real_partial_score"], result["synthetic_sliver_score"]))
    return 0


def phase_probe():
    imgs = list_images(CORPUS_DIR)
    if not imgs:
        print("no images in corpus_owner/")
        return 1
    pick = imgs[len(imgs) // 2]
    rec, ms, attempts = query_one(pick)
    hb("probe %s: ok=%s attempts=%d ms=%.0f %s" % (
        os.path.basename(pick), rec["ok"], attempts, ms, rec.get("error", "")))
    print(json.dumps({
        "image": rec["image"], "width": rec["width"], "height": rec["height"],
        "ok": rec["ok"], "ms": round(ms, 1), "attempts": attempts,
        "corners": rec["corners"], "raw_head": (rec["raw"] or "")[:200],
    }, indent=2))
    return 0 if rec["ok"] else 1


def phase_calib_report():
    """Known-GT calibration: strict-only vs strict+box-fallback, per the encoded rule."""
    calib = load_json(os.path.join(OUT, "oracle_calib_manifest.json"))
    gt_by = {}
    for gt in OC.load_synthetic_gt(SYNTH_MANIFEST) + OC.load_real_gt(REAL_MANIFEST):
        key = os.path.splitext(gt["name"].replace("\\", "_").replace("/", "_"))[0]
        gt_by[key] = gt
    by_stem = {}
    for r in calib["items"]:
        by_stem[os.path.splitext(os.path.basename(r.get("image", "")))[0]] = r

    rows = []
    for key in sorted(gt_by):
        gt = gt_by[key]
        rec = by_stem.get(key)
        row = {"name": gt["name"], "source": gt["source"]}
        corners, fmt = rec_corners(rec)
        if corners and rec and rec.get("width"):
            q = px_quad(corners, rec["width"], rec["height"])
            m = OC.evaluate_pair(corners, gt["gt_norm"])
            row["format"] = fmt
            row["iou"] = m["iou"]
            row["mean_err_norm"] = m["mean_corner_error"]
            row["err_pct_diag"] = round(100.0 * m["mean_corner_error"] / math.sqrt(2.0), 3)
        else:
            row.update({"format": None, "iou": None, "mean_err_norm": None,
                        "err_pct_diag": None})
        rows.append(row)

    def agg(rs):
        if not rs:
            return {"n": 0}
        errs = [r["mean_err_norm"] for r in rs]
        ious = [r["iou"] for r in rs]
        return {
            "n": len(rs),
            "mean_err_norm": round(sum(errs) / len(errs), 5),
            "mean_err_pct_diag": round(100.0 * sum(errs) / len(errs) / math.sqrt(2.0), 3),
            "mean_iou": round(sum(ious) / len(ious), 4),
            "min_iou": round(min(ious), 4),
            "max_err_norm": round(max(errs), 5),
        }

    def vdict(rs):
        return OC.verdict([{"mean_corner_error": r["mean_err_norm"], "iou": r["iou"]}
                           for r in rs])

    strict_rows = [r for r in rows if r.get("format") == "xy"]
    lenient_rows = [r for r in rows if r.get("iou") is not None]
    synth = [r for r in lenient_rows if r["source"] == "synthetic"]
    real = [r for r in lenient_rows if r["source"] == "real"]
    result = {
        "verdict_rule": ("PSEUDO_GT_USABLE: mean_err<=0.03 AND iou>=0.85 | "
                         "CANDIDATE_FILTER_ONLY: max_err<=0.08 AND min_iou>=0.50 | "
                         "else REJECT  (encoded in oracle_calibrate.py)"),
        "note": ("err_norm is in unit-square units; err_pct_diag = 100*err/sqrt(2) "
                 "since the frame diagonal is sqrt(2) in the unit square"),
        "counts": {
            "gt_images": len(rows),
            "strict_xy": len(strict_rows),
            "box_mid_fallback": len(lenient_rows) - len(strict_rows),
            "no_corners": len(rows) - len(lenient_rows),
        },
        "strict_xy_only": agg(strict_rows),
        "with_box_fallback": agg(lenient_rows),
        "synthetic_with_box_fallback": agg(synth),
        "real_with_box_fallback": agg(real),
        "verdict_strict_xy_only": vdict(strict_rows),
        "verdict_with_box_fallback": vdict(lenient_rows),
        "rows": rows,
    }
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "calibration_report.json"), "w", encoding="utf-8") as fh:
        json.dump(result, fh, indent=2)
    print(json.dumps({k: result[k] for k in (
        "counts", "strict_xy_only", "with_box_fallback",
        "synthetic_with_box_fallback", "real_with_box_fallback",
        "verdict_strict_xy_only", "verdict_with_box_fallback")}, indent=2))
    hb("calib_report: strict=%s lenient=%s" % (
        result["verdict_strict_xy_only"], result["verdict_with_box_fallback"]))
    return 0


def main():
    ap = argparse.ArgumentParser(description="H34 live oracle driver")
    ap.add_argument("--phase", required=True,
                    choices=["probe", "calib", "pagechoice", "owner", "calib_report"])
    ap.add_argument("--workers", type=int, default=4,
                    help="overlapped in-flight queries (server-side vision prefill "
                         "pipelining); per-image semantics unchanged")
    args = ap.parse_args()
    if args.phase == "probe":
        return phase_probe()
    if args.phase == "calib":
        return run_folder_phase("calib", [SYNTH_DIR, REAL_DIR],
                                "oracle_calib_manifest.json", args.workers)
    if args.phase == "owner":
        return run_folder_phase("owner", [CORPUS_DIR], "pseudo_gt_owner110.json",
                                args.workers)
    if args.phase == "calib_report":
        return phase_calib_report()
    return phase_pagechoice()


if __name__ == "__main__":
    sys.exit(main())
