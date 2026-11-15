#!/usr/bin/env python3
"""
H43 - AI FAULT ATTRIBUTION: is the corner MODEL the problem, or the code around it?

The owner's claim, stated many times: "the model is not good enough - it detects the boxes on the
page but not the page, and it does not detect non-white pages". That is a falsifiable claim, and
this script falsifies or confirms it with the data already on disk.

For every owner photo it reconstructs WHAT THE MODEL ITSELF PRODUCED from the H40 trace
(`corner_heat.channels` = the four per-channel peaks in frame coordinates, `corner_post.plausible`
= whether the assembled quad survived `isPlausiblePageQuad`) and puts it in one of six buckets:

  A  model-quad  OK      model produced a plausibility-passing quad that MATCHES the page (IoU>=0.5)
  B  model-quad  WRONG   model produced a quad but on the wrong thing (IoU<0.5)
  C  rejected            model had 4 confident corners, code rejected the quad as implausible
  D  blind               fewer than 2 confident channels: the model saw nothing
  E  partial             exactly 2-3 confident channels: the model saw part of the page

Then it records what the shipped pipeline actually did (provenance + class), so the blame is
attributable: a photo in bucket A/D that still failed is a CODE problem; a photo in bucket B/E is a
MODEL problem.

Usage:
    python tooling/h43_ai_fault.py [--trace tooling/out/h39/h40all] [--out <dir>]
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from h40_ink_probe import ORACLE  # noqa: E402

TRACE = Path("tooling/out/h39/h40all")
LANE = Path("tooling/out/h38/report_h40relax.json")
PEAK_STRONG = 0.30
MATCH_IOU = 0.50


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def order_quad(pts: np.ndarray) -> np.ndarray:
    """Order 4 points TL, TR, BR, BL by angle around the centroid."""
    c = pts.mean(axis=0)
    ang = np.arctan2(pts[:, 1] - c[1], pts[:, 0] - c[0])
    p = pts[np.argsort(ang)]
    s = p.sum(axis=1)
    d = p[:, 0] - p[:, 1]
    return np.array([p[np.argmin(s)], p[np.argmax(d)], p[np.argmax(s)], p[np.argmin(d)]])


def iou(a, b) -> float:
    a32 = np.asarray(a, np.float32)
    b32 = np.asarray(b, np.float32)
    inter, _ = cv2.intersectConvexConvex(a32, b32)
    if inter <= 0:
        return 0.0
    union = abs(cv2.contourArea(a32)) + abs(cv2.contourArea(b32)) - inter
    return float(inter / union) if union > 0 else 0.0


def bucket(trace: dict, oracle_quad) -> tuple[str, dict]:
    heat = (trace.get("corner_heat") or {})
    chans = heat.get("channels") or []
    post = trace.get("corner_post") or {}
    info: dict = {}
    if len(chans) != 4:
        return "D blind", info
    peaks = [float(c.get("peak", 0.0)) for c in chans]
    strong = sum(1 for p in peaks if p >= PEAK_STRONG)
    info["peaks"] = [round(p, 3) for p in peaks]
    info["strong"] = strong

    pts = np.array([[float(c["frame_x"]), float(c["frame_y"])] for c in chans], np.float64)
    peak_quad = order_quad(pts)

    built = post.get("quad")
    plausible = bool(post.get("plausible"))
    info["plausible"] = plausible

    # IoU of the MODEL's own reconstruction against the oracle page.
    if oracle_quad is not None:
        model_quad = (np.array([[p["x"], p["y"]] for p in built], np.float64)
                      if built else peak_quad)
        info["model_iou"] = round(iou(model_quad, oracle_quad), 3)
        info["peaks_iou"] = round(iou(peak_quad, oracle_quad), 3)

    if strong <= 1:
        return "D blind", info
    if strong < 4:
        return "E partial", info
    miou = info.get("model_iou", 0.0)
    if not plausible:
        return "C rejected", info
    if miou >= MATCH_IOU:
        return "A model-quad OK", info
    return "B model-quad WRONG", info


def final_class(row: dict) -> str:
    if not row:
        return "?"
    if not row.get("detected"):
        return "no-detection"
    if row.get("wrong_page"):
        return "wrong-page"
    if row.get("merged"):
        return "merged"
    if row.get("correct_page"):
        return "correct"
    return "unlabelled"


def main(argv: list[str]) -> int:
    trace_dir = TRACE
    out_dir = Path("tooling/out/h43")
    for i, a in enumerate(argv):
        if a == "--trace" and i + 1 < len(argv):
            trace_dir = Path(argv[i + 1])
        if a == "--out" and i + 1 < len(argv):
            out_dir = Path(argv[i + 1])
    out_dir.mkdir(parents=True, exist_ok=True)

    oracle = {it["image"]: np.array([it["corners_px"][k] for k in ("tl", "tr", "br", "bl")], np.float64)
              for it in json.loads(ORACLE.read_text(encoding="utf-8"))["items"]}
    lane = load(LANE)
    lane_rows = {r["image"]: r for r in lane["rows"]} if lane else {}

    rows = []
    for scene in sorted(p for p in trace_dir.iterdir() if p.is_dir()):
        tj = scene / "trace.json"
        if not tj.exists():
            continue
        tr = json.loads(tj.read_text(encoding="utf-8"))
        # scene folder names are sanitised asset stems; match back to the lane by prefix.
        name = next((k for k in oracle if Path(k).stem.replace("(", "_").replace(")", "_")
                     .replace(" ", "_") == scene.name), None)
        if name is None:
            name = scene.name + ".jpg"
        b, info = bucket(tr, oracle.get(name))
        lr = lane_rows.get(name, {})
        rows.append({
            "image": name,
            "bucket": b,
            "final_class": final_class(lr),
            "final_prov": lr.get("prov"),
            "gate_pass": lr.get("gate_pass"),
            "final_iou": lr.get("iou"),
            **info,
        })

    (out_dir / "fault.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")

    order = ["A model-quad OK", "B model-quad WRONG", "C rejected", "D blind", "E partial"]
    lines = ["# H43 - is the corner MODEL the fault?", "",
             "Each photo classified by **what the model itself produced**, from the trace's own",
             "corner-heat peaks and the plausibility flag. `model_iou` is the model's quad against",
             "the oracle page.", "",
             "| model outcome | photos | of those, pipeline still failed |", "|---|---|---|"]
    for b in order:
        group = [r for r in rows if r["bucket"] == b]
        if not group:
            continue
        failed = sum(1 for r in group if r["final_class"] not in ("correct",))
        lines.append(f"| {b} | {len(group)} | {failed} |")

    lines += ["", "## What the pipeline did with each model outcome", "",
              "| model outcome | correct | merged | wrong-page | no-detection |", "|---|---|---|---|---|"]
    for b in order:
        group = [r for r in rows if r["bucket"] == b]
        if not group:
            continue
        cells = [
            sum(1 for r in group if r["final_class"] == k)
            for k in ("correct", "merged", "wrong-page", "no-detection")
        ]
        lines.append(f"| {b} | {cells[0]} | {cells[1]} | {cells[2]} | {cells[3]} |")

    blind = [r for r in rows if r["bucket"].startswith("D")]
    lines += ["", f"## The {len(blind)} photos where the model saw nothing", "",
              "If these are real pages, this bucket is the model-capability limit and no amount of",
              "postprocessing fixes it.", "",
              "| photo | heat peaks (TL/TR/BR/BL) | pipeline result |", "|---|---|---|"]
    for r in sorted(blind, key=lambda x: x["image"]):
        lines.append(f"| `{r['image']}` | {r.get('peaks')} | {r['final_class']} |")

    wrong = [r for r in rows if r["bucket"].startswith("B")]
    lines += ["", f"## The {len(wrong)} photos where the model produced a quad on the wrong thing", "",
              "| photo | peaks | model IoU | pipeline result |", "|---|---|---|---|"]
    for r in sorted(wrong, key=lambda x: x.get("model_iou", 0)):
        lines.append(f"| `{r['image']}` | {r.get('peaks')} | {r.get('model_iou')} | {r['final_class']} |")

    (out_dir / "FAULT.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines[:40]))
    print(f"\n-> {out_dir / 'FAULT.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
