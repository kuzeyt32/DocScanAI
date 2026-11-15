#!/usr/bin/env python3
"""
H44 - SMALL-PAGE RESCUE: multi-crop inference for pages that fill little of the frame.

The blind photos (green books among white paper, page ~15 % of frame) are not a colour problem:
H44 measured neutral paper missing at 24 % vs coloured paper at 15 %. What they share is SIZE -
the page occupies a small part of a frame full of competing white sheets, and the corner model
returns peaks of ~0.001 (nothing at all).

So: run the SAME model on the full (letterboxed) frame, then on overlapping crops. Each crop is
letterboxed to 256, so the page arrives much larger. A crop result is mapped back to frame
coordinates, and the pipeline takes the first crop that gives a full 4-channel lock AND a
plausible quad.

This measures whether that recovers the blind photos - and, just as important, whether it
introduces confident-but-wrong quads on photos the plain path already handled.

Usage: python tooling/h44_multicrop.py
"""
from __future__ import annotations

import json
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

MODEL = r"E:\DocScanAI-cache\tooling-out\h4_models\m1c_detect.onnx"
ASSETS = Path("app/src/androidTest/assets/owner110")
ORACLE = ASSETS / "oracle_manifest.json"
LANE = Path("tooling/out/h38/report_h43letter.json")
OUT = Path("tooling/out/h44")
EDGE = 256
GATE = 0.30
MATCH = 0.50
PAD = 114


def letterbox(img, interp=cv2.INTER_CUBIC):
    h, w = img.shape[:2]
    s = EDGE / max(h, w)
    nw, nh = max(1, int(round(w * s))), max(1, int(round(h * s)))
    small = cv2.resize(img, (nw, nh), interpolation=interp)
    canvas = np.full((EDGE, EDGE, 3), PAD, np.uint8)
    x0, y0 = (EDGE - nw) // 2, (EDGE - nh) // 2
    canvas[y0:y0 + nh, x0:x0 + nw] = small
    return canvas, (x0, y0, nw, nh)


def infer(sess, img, box):
    """Run on img[box] and return (peaks, frame-space points or None)."""
    x0, y0, x1, y1 = box
    crop = img[y0:y1, x0:x1]
    if crop.size == 0:
        return [0.0] * 4, None
    canvas, (px, py, nw, nh) = letterbox(crop)
    rgb = cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
    t = np.transpose(rgb, (2, 0, 1))[None, ...]
    out = np.asarray(sess.run(None, {sess.get_inputs()[0].name: t})[0]).reshape(4, -1)
    side = int(round(out.shape[1] ** 0.5))
    ch, cw = crop.shape[:2]
    peaks, pts = [], []
    for c in range(4):
        idx = int(out[c].argmax())
        peaks.append(float(out[c][idx]))
        hy, hx = divmod(idx, side)
        cx = (hx + 0.5) * EDGE / side
        cy = (hy + 0.5) * EDGE / side
        if not (px <= cx < px + nw and py <= cy < py + nh):
            peaks[-1] = 0.0
            pts.append([-1.0, -1.0])
            continue
        pts.append([x0 + (cx - px) * cw / nw, y0 + (cy - py) * ch / nh])
    if any(p[0] < 0 for p in pts):
        return peaks, None
    return peaks, np.array(pts, np.float64)


def order_quad(p):
    c = p.mean(axis=0)
    ang = np.arctan2(p[:, 1] - c[1], p[:, 0] - c[0])
    q = p[np.argsort(ang)]
    s, d = q.sum(axis=1), q[:, 0] - q[:, 1]
    return np.array([q[np.argmin(s)], q[np.argmax(d)], q[np.argmax(s)], q[np.argmin(d)]])


def iou(a, b):
    a32, b32 = np.asarray(a, np.float32), np.asarray(b, np.float32)
    inter, _ = cv2.intersectConvexConvex(a32, b32)
    if inter <= 0:
        return 0.0
    u = abs(cv2.contourArea(a32)) + abs(cv2.contourArea(b32)) - inter
    return float(inter / u) if u > 0 else 0.0


def crops_for(w, h):
    """Full frame, then 2x2 quadrants with a 15 % overlap, then a centre crop."""
    boxes = [(0, 0, w, h)]
    ox, oy = int(w * 0.15), int(h * 0.15)
    mx, my = w // 2, h // 2
    boxes += [
        (0, 0, min(w, mx + ox), min(h, my + oy)),
        (max(0, mx - ox), 0, w, min(h, my + oy)),
        (0, max(0, my - oy), min(w, mx + ox), h),
        (max(0, mx - ox), max(0, my - oy), w, h),
    ]
    boxes.append((int(w * 0.15), int(h * 0.15), int(w * 0.85), int(h * 0.85)))
    return boxes


def plau(quad, w, h):
    """Cheap stand-in for isPlausiblePageQuad: convex, sane area, sane aspect."""
    if quad is None:
        return False
    q = order_quad(quad)
    area = abs(cv2.contourArea(q.astype(np.float32)))
    frac = area / (w * h)
    if not (0.04 <= frac <= 0.97):
        return False
    if not cv2.isContourConvex(q.astype(np.int32)):
        return False
    return True


def main():
    sess = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
    oracle = {i["image"]: [i["corners_px"][k] for k in ("tl", "tr", "br", "bl")]
              for i in json.loads(ORACLE.read_text(encoding="utf-8"))["items"]}
    lane = {r["image"]: r for r in json.loads(LANE.read_text(encoding="utf-8"))["rows"]}

    rows = []
    for name, gt in oracle.items():
        f = ASSETS / name
        img = cv2.imread(str(f), cv2.IMREAD_COLOR)
        if img is None:
            continue
        h, w = img.shape[:2]
        if not plau(np.array(gt), w, h):
            continue
        gtq = order_quad(np.array(gt, np.float64))
        row = {"image": name, "crops": []}
        for bi, box in enumerate(crops_for(w, h)):
            peaks, pts = infer(sess, img, box)
            ok = pts is not None and sum(1 for p in peaks if p >= GATE) == 4 and plau(pts, w, h)
            row["crops"].append({
                "box": list(box),
                "peaks": [round(p, 3) for p in peaks],
                "lock": ok,
                "iou": round(iou(order_quad(pts), gtq), 3) if ok else None,
            })
        best = max((c for c in row["crops"] if c["lock"]), key=lambda c: c["iou"] or 0,
                   default=None)
        row["best_iou"] = best["iou"] if best else None
        row["best_box"] = best["box"] if best else None
        row["full_lock"] = row["crops"][0]["lock"]
        row["full_iou"] = row["crops"][0]["iou"]
        row["pipeline"] = "correct" if lane.get(name, {}).get("iou") and \
            lane[name]["iou"] >= MATCH else "not-correct"
        rows.append(row)

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "multicrop.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")

    full_ok = [r for r in rows if r["full_lock"] and (r["full_iou"] or 0) >= MATCH]
    multi_ok = [r for r in rows if r["best_iou"] is not None and r["best_iou"] >= MATCH]
    gained = [r for r in rows
              if not (r["full_lock"] and (r["full_iou"] or 0) >= MATCH)
              and r["best_iou"] is not None and r["best_iou"] >= MATCH]
    lost = [r for r in rows
            if r["full_lock"] and (r["full_iou"] or 0) >= MATCH
            and not (r["best_iou"] is not None and r["best_iou"] >= MATCH)]

    lines = ["# H44 - multi-crop inference for small pages", "",
             f"{len(rows)} photos with a valid oracle page.", "",
             "| strategy | model quads matching the page (IoU>=0.5) |", "|---|---|",
             f"| full frame only | {len(full_ok)} |",
             f"| **best of 6 crops** | **{len(multi_ok)}** |", "",
             f"* gained by cropping: **{len(gained)}**", f"* lost by cropping: **{len(lost)}**", ""]
    if gained:
        lines += ["| gained photo | full peaks | best crop IoU |", "|---|---|---|"]
        for r in gained[:40]:
            lines.append(f"| `{r['image']}` | {r['crops'][0]['peaks']} | {r['best_iou']} |")
    if lost:
        lines += ["", "| lost photo | full IoU | best crop |", "|---|---|---|"]
        for r in lost[:40]:
            lines.append(f"| `{r['image']}` | {r['full_iou']} | {r['best_iou']} |")

    (OUT / "MULTICROP.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print(f"\n-> {OUT / 'MULTICROP.md'}")


if __name__ == "__main__":
    main()
