#!/usr/bin/env python3
"""
H43 - RESAMPLER vs CORNER ACCURACY (the possible fix).

Validated finding: the device's peaks are reproduced by bilinear resampling (mean abs error
0.010 vs 0.160 for area-averaging) - see h43_resampler_probe.py. So the app currently feeds the
model a BILINEAR-squashed 256x256.

Observation that motivates the test: on the photos where the model is blind, sharper resamplers
(INTER_NEAREST / INTER_CUBIC) raise the peaks from ~0.42 to ~0.85. That is either
  (a) the model finally seeing the page, or
  (b) the model confidently latching onto aliasing artifacts.
Those have opposite fixes, so this script measures ACCURACY, not confidence:

for every photo x resampler it builds the quad from the four channel peaks and scores it against
the oracle page quad (IoU). A resampler only counts as a win if it increases the number of
photos whose MODEL QUAD MATCHES THE PAGE, without adding confident-but-wrong quads.

Usage:
    python tooling/h43_resampler_accuracy.py
"""
from __future__ import annotations

import json
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

MODEL = r"E:\DocScanAI-cache\tooling-out\h4_models\m1c_detect.onnx"
ASSETS = Path("app/src/androidTest/assets/owner110")
ORACLE = Path("app/src/androidTest/assets/owner110/oracle_manifest.json")
OUT = Path("tooling/out/h43")
EDGE = 256
GATE = 0.30
MATCH = 0.50

def squash(img, interp):
    return cv2.resize(img, (EDGE, EDGE), interpolation=interp)


def letterbox(img, interp=cv2.INTER_CUBIC, pad=114):
    """Aspect-preserving resize + pad to square. The device SQUASHES; this does not.

    Padding is a flat grey. For the peak -> frame mapping this matters: a peak inside the pad
    band is not a page corner, so a peak in the pad is treated as "no detection" for that channel.
    """
    h, w = img.shape[:2]
    s = EDGE / max(h, w)
    nh, nw = max(1, int(round(h * s))), max(1, int(round(w * s)))
    small = cv2.resize(img, (nw, nh), interpolation=interp)
    canvas = np.full((EDGE, EDGE, 3), pad, np.uint8)
    y0, x0 = (EDGE - nh) // 2, (EDGE - nw) // 2
    canvas[y0:y0 + nh, x0:x0 + nw] = small
    return canvas, (x0, y0, nw, nh)


INTERPS = {
    "LINEAR squash (shipped)": lambda im: (squash(im, cv2.INTER_LINEAR), None),
    "CUBIC squash": lambda im: (squash(im, cv2.INTER_CUBIC), None),
    "LETTERBOX cubic": lambda im: letterbox(im, cv2.INTER_CUBIC),
    "LETTERBOX linear": lambda im: letterbox(im, cv2.INTER_LINEAR),
}


def run(sess, img, builder):
    built = builder(img)
    if isinstance(built, tuple):
        small, pad = built
    else:
        small, pad = built, None
    rgb = cv2.cvtColor(small, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
    x = np.transpose(rgb, (2, 0, 1))[None, ...]
    out = np.asarray(sess.run(None, {sess.get_inputs()[0].name: x})[0]).reshape(4, -1)
    side = int(round((out.shape[1]) ** 0.5))
    peaks, pts = [], []
    for c in range(4):
        idx = int(out[c].argmax())
        peak = float(out[c][idx])
        py, px = divmod(idx, side)
        # Map the peak back to the source frame. With letterboxing the content occupies a
        # sub-rectangle of the 256 canvas; a peak outside it is a response to the PAD, not to the
        # photo, so the channel is recorded as weak (the pad is a flat grey, nothing to find).
        if pad is not None:
            x0, y0, nw, nh = pad
            cx = (px + 0.5) * EDGE / side
            cy = (py + 0.5) * EDGE / side
            if not (x0 <= cx < x0 + nw and y0 <= cy < y0 + nh):
                peak = 0.0
                peaks.append(peak)
                # Emit the frame centre so the quad stays geometrically valid for IoU scoring.
                pts.append([img.shape[1] / 2.0, img.shape[0] / 2.0])
                continue
            fx = (cx - x0) * img.shape[1] / nw
            fy = (cy - y0) * img.shape[0] / nh
            peaks.append(peak)
            pts.append([fx, fy])
            continue
        peaks.append(peak)
        pts.append([(px + 0.5) * img.shape[1] / side, (py + 0.5) * img.shape[0] / side])
    return peaks, np.array(pts, np.float64)


def order_quad(pts):
    c = pts.mean(axis=0)
    ang = np.arctan2(pts[:, 1] - c[1], pts[:, 0] - c[0])
    p = pts[np.argsort(ang)]
    s, d = p.sum(axis=1), p[:, 0] - p[:, 1]
    return np.array([p[np.argmin(s)], p[np.argmax(d)], p[np.argmax(s)], p[np.argmin(d)]])


def iou(a, b):
    a32, b32 = np.asarray(a, np.float32), np.asarray(b, np.float32)
    inter, _ = cv2.intersectConvexConvex(a32, b32)
    if inter <= 0:
        return 0.0
    u = abs(cv2.contourArea(a32)) + abs(cv2.contourArea(b32)) - inter
    return float(inter / u) if u > 0 else 0.0


def main():
    sess = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
    oracle = {i["image"]: np.array([i["corners_px"][k] for k in ("tl", "tr", "br", "bl")], np.float64)
              for i in json.loads(ORACLE.read_text(encoding="utf-8"))["items"]}
    files = sorted(ASSETS.glob("*.jpg"))

    rows = []
    for f in files:
        img = cv2.imread(str(f), cv2.IMREAD_COLOR)
        if img is None or f.name not in oracle:
            continue
        row = {"image": f.name, "res": {}}
        for label, builder in INTERPS.items():
            peaks, pts = run(sess, img, builder)
            quad = order_quad(pts)
            row["res"][label] = {
                "peaks": [round(p, 3) for p in peaks],
                "strong": sum(1 for p in peaks if p >= GATE),
                "iou": round(iou(quad, oracle[f.name]), 3),
            }
        rows.append(row)

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "resampler_accuracy.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")

    lines = ["# H43 - resampler vs corner ACCURACY", "",
             "`locked` = all four channels clear the 0.30 gate. `correct` = model quad matches the",
             f"page (IoU >= {MATCH}). `confident+wrong` = locked but IoU < 0.2, i.e. the model",
             "latched onto something that is not the page. Only a resampler that raises `correct`",
             "without raising `confident+wrong` is a real fix.", "",
             "| resampler | locked | correct | confident+wrong | mean IoU |", "|---|---|---|---|---|"]
    for label in INTERPS:
        g = [r["res"][label] for r in rows]
        locked = sum(1 for x in g if x["strong"] == 4)
        correct = sum(1 for x in g if x["strong"] == 4 and x["iou"] >= MATCH)
        bad = sum(1 for x in g if x["strong"] == 4 and x["iou"] < 0.2)
        mean_iou = float(np.mean([x["iou"] for x in g]))
        lines.append(f"| {label} | {locked} | **{correct}** | {bad} | {mean_iou:.3f} |")

    base = "LINEAR squash (shipped)"
    lines += ["", "## Per-photo change vs the shipped resampler", ""]
    for label in INTERPS:
        if label == base:
            continue
        gain = [r["image"] for r in rows
                if r["res"][base]["iou"] < MATCH and r["res"][label]["iou"] >= MATCH]
        loss = [r["image"] for r in rows
                if r["res"][base]["iou"] >= MATCH and r["res"][label]["iou"] < MATCH]
        lines.append(f"* **{label}**: +{len(gain)} correct, -{len(loss)} correct")
        if gain:
            lines.append(f"  * gained: {', '.join(f'`{x}`' for x in gain[:12])}")
        if loss:
            lines.append(f"  * lost: {', '.join(f'`{x}`' for x in loss[:12])}")

    (OUT / "RESAMPLER.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print(f"\n-> {OUT / 'RESAMPLER.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
