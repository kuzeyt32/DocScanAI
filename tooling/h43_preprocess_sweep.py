#!/usr/bin/env python3
"""
H43 - PREPROCESSING SWEEP: can the corner model be un-blinded WITHOUT a new model?

Measured fact that motivates this (tooling/h43/FAULT.md): on 68 of 116 owner photos the shipped
corner model produces NO confident corner at all (all four channel peaks < 0.30), so detection
falls back to the classical path - which is where every merged/wrong-page failure comes from.
Only 31 photos ever get a usable model quad, and those 31 are all handled correctly.

So the question is not "is the model good", it is "is our INPUT the reason it is bad". The app
feeds the model a fixed 256x256 SQUASH (aspect not preserved), RGB /255. This script runs the
real ONNX graph on the host with several input constructions and reports, per photo, the max
channel peak and how many channels clear the production 0.30 gate.

Variants
  baseline    squash 256x256, RGB/255            <- exactly what production does
  letterbox   aspect-preserving resize + pad      <- no aspect distortion
  multiscale  squash at 512 then downscale        <- more detail before the squeeze
  gray        squash, grayscale replicated to RGB <- removes colour cast
  clahe       squash, per-channel CLAHE           <- local contrast on low-contrast paper
  desat       squash, pull 60% toward luma        <- kill strong paper colour
  deskew-ish  256 + 2% zoom-out margin            <- page near the border still fully inside

Usage:
    python tooling/h43_preprocess_sweep.py [--limit N] [--out DIR]
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

ASSETS = Path("app/src/androidTest/assets/owner110")
MODEL = Path(r"E:\DocScanAI-cache\tooling-out\h4_models\m1c_detect.onnx")
OUT = Path("tooling/out/h43")
EDGE = 256
GATE = 0.30


def to_tensor(img_bgr: np.ndarray) -> np.ndarray:
    """BGR uint8 -> NCHW float32 RGB/255, exactly as the app builds it."""
    rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
    return np.transpose(rgb, (2, 0, 1))[None, ...]


def v_baseline(img):
    # INTER_LINEAR is the VALIDATED host equivalent of Android's
    # Bitmap.createScaledBitmap(..., filter = true): mean abs peak error 0.010 vs the device,
    # 115/116 photos within 0.10 (see h43_resampler_probe.py). NOT INTER_AREA, which averages
    # instead of aliasing and does not reproduce the device at all (error 0.160).
    return cv2.resize(img, (EDGE, EDGE), interpolation=cv2.INTER_LINEAR)


def v_letterbox(img):
    h, w = img.shape[:2]
    s = EDGE / max(h, w)
    nh, nw = max(1, int(round(h * s))), max(1, int(round(w * s)))
    small = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_LINEAR)
    canvas = np.full((EDGE, EDGE, 3), 114, np.uint8)
    y0, x0 = (EDGE - nh) // 2, (EDGE - nw) // 2
    canvas[y0:y0 + nh, x0:x0 + nw] = small
    return canvas


def v_multiscale(img):
    h, w = img.shape[:2]
    s = 512 / max(h, w)
    mid = cv2.resize(img, (max(1, int(w * s)), max(1, int(h * s))), interpolation=cv2.INTER_LINEAR)
    return cv2.resize(mid, (EDGE, EDGE), interpolation=cv2.INTER_LINEAR)


def v_gray(img):
    g = cv2.cvtColor(v_baseline(img), cv2.COLOR_BGR2GRAY)
    return cv2.cvtColor(g, cv2.COLOR_GRAY2BGR)


def v_clahe(img):
    small = v_baseline(img)
    lab = cv2.cvtColor(small, cv2.COLOR_BGR2LAB)
    l, a, b = cv2.split(lab)
    l = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(l)
    return cv2.cvtColor(cv2.merge([l, a, b]), cv2.COLOR_LAB2BGR)


def v_desat(img, amount=0.6):
    small = v_baseline(img)
    f = small.astype(np.float32)
    luma = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY).astype(np.float32)[..., None]
    return (f * (1 - amount) + luma * amount).clip(0, 255).astype(np.uint8)


def v_zoomout(img, factor=1.04):
    small = v_baseline(img)
    h, w = small.shape[:2]
    m = cv2.getRotationMatrix2D((w / 2, h / 2), 0, 1 / factor)
    m[0, 2] += (w * (factor - 1)) / 2
    m[1, 2] += (h * (factor - 1)) / 2
    return cv2.warpAffine(small, m, (w, h), borderMode=cv2.BORDER_REPLICATE)


VARIANTS = {
    "baseline": v_baseline,
    "letterbox": v_letterbox,
    "multiscale": v_multiscale,
    "gray": v_gray,
    "clahe": v_clahe,
    "desat": v_desat,
    "zoomout": v_zoomout,
}


def run(sess, img_bgr):
    out = sess.run(None, {sess.get_inputs()[0].name: to_tensor(img_bgr)})[0]
    heat = np.asarray(out).reshape(4, -1)
    return [float(heat[c].max()) for c in range(4)]


def saturation(img) -> float:
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    return float(hsv[..., 1].mean())


def main(argv):
    limit = None
    out = OUT
    for i, a in enumerate(argv):
        if a == "--limit" and i + 1 < len(argv):
            limit = int(argv[i + 1])
        if a == "--out" and i + 1 < len(argv):
            out = Path(argv[i + 1])
    out.mkdir(parents=True, exist_ok=True)

    sess = ort.InferenceSession(str(MODEL), providers=["CPUExecutionProvider"])
    files = sorted(ASSETS.glob("*.jpg"))
    if limit:
        files = files[:limit]
    print(f"model: {MODEL.name}  photos: {len(files)}  variants: {len(VARIANTS)}")

    rows = []
    for n, f in enumerate(files, 1):
        img = cv2.imread(str(f), cv2.IMREAD_COLOR)
        if img is None:
            continue
        row = {"image": f.name, "sat": round(saturation(img), 1), "variants": {}}
        for name, fn in VARIANTS.items():
            peaks = run(sess, fn(img))
            row["variants"][name] = {
                "peaks": [round(p, 3) for p in peaks],
                "max": round(max(peaks), 3),
                "strong": sum(1 for p in peaks if p >= GATE),
            }
        rows.append(row)
        if n % 20 == 0:
            print(f"  {n}/{len(files)}")

    (out / "sweep.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")

    names = list(VARIANTS)
    lines = ["# H43 - corner-model input sweep (host, real ONNX graph)", "",
             f"Model `{MODEL.name}` on {len(rows)} owner photos. `strong` = channels >= {GATE}",
             "(the production gate). 4 strong channels are needed for the model path.",
             "",
             "| variant | photos w/ 4 strong | photos w/ >=3 | photos blind (0) | mean max peak |",
             "|---|---|---|---|---|"]
    for v in names:
        four = sum(1 for r in rows if r["variants"][v]["strong"] == 4)
        three = sum(1 for r in rows if r["variants"][v]["strong"] >= 3)
        zero = sum(1 for r in rows if r["variants"][v]["strong"] == 0)
        meanmax = float(np.mean([r["variants"][v]["max"] for r in rows]))
        lines.append(f"| {v} | **{four}** | {three} | {zero} | {meanmax:.3f} |")

    base_four = sum(1 for r in rows if r["variants"]["baseline"]["strong"] == 4)
    lines += ["", "## Does paper saturation explain blindness?", ""]
    blind = [r for r in rows if r["variants"]["baseline"]["strong"] == 0]
    seen = [r for r in rows if r["variants"]["baseline"]["strong"] == 4]
    if blind and seen:
        lines += [
            f"* blind photos (baseline, 0 strong): **{len(blind)}**, mean saturation "
            f"**{np.mean([r['sat'] for r in blind]):.1f}**",
            f"* photos the model nails (baseline, 4 strong): **{len(seen)}**, mean saturation "
            f"**{np.mean([r['sat'] for r in seen]):.1f}**",
            "",
            "If those two numbers are close, colour is NOT the cause of blindness.",
        ]

    rescued = [r for r in rows
               if r["variants"]["baseline"]["strong"] == 0
               and max(r["variants"][v]["strong"] for v in names if v != "baseline") >= 4]
    lines += ["", f"## Photos a variant rescues to a full 4-channel lock ({len(rescued)})", ""]
    if rescued:
        lines += ["| photo | sat | best variant | peaks |", "|---|---|---|---|"]
        for r in rescued[:40]:
            best = max((v for v in names if v != "baseline"),
                       key=lambda v: r["variants"][v]["strong"])
            lines.append(f"| `{r['image']}` | {r['sat']} | {best} | {r['variants'][best]['peaks']} |")
    else:
        lines.append("_none_ - no input variant recovers these photos; the limitation is the model.")

    (out / "SWEEP.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print(f"\n-> {out / 'SWEEP.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
