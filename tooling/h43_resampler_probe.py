#!/usr/bin/env python3
"""Quick probe: which host resampler reproduces the DEVICE's corner peaks?

Android `Bitmap.createScaledBitmap(bitmap, w, h, true)` is a filtered (bilinear) scale, which
for a large downscale aliases rather than averages. cv2.INTER_AREA averages. They are NOT the
same input, and the corner model is sensitive to it.
"""
import json
import re
import sys
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

MODEL = r"E:\DocScanAI-cache\tooling-out\h4_models\m1c_detect.onnx"
ASSETS = Path("app/src/androidTest/assets/owner110")
TRACE = Path("tooling/out/h39/h43full")


def peaks(sess, img, interp):
    small = cv2.resize(img, (256, 256), interpolation=interp)
    rgb = cv2.cvtColor(small, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
    x = np.transpose(rgb, (2, 0, 1))[None, ...]
    out = sess.run(None, {sess.get_inputs()[0].name: x})[0]
    h = np.asarray(out).reshape(4, -1)
    return [round(float(h[c].max()), 3) for c in range(4)]


def dev_peaks():
    dev = {}
    for p in TRACE.glob("*/trace.json"):
        t = json.loads(p.read_text(encoding="utf-8"))
        ch = (t.get("corner_heat") or {}).get("channels") or []
        if len(ch) == 4:
            dev[p.parent.name] = [round(c["peak"], 3) for c in ch]
    return dev


def main():
    sess = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
    dev = dev_peaks()
    by_stem = {re.sub(r"[^A-Za-z0-9_-]", "_", f.stem): f for f in ASSETS.glob("*.jpg")}

    interps = {
        "AREA": cv2.INTER_AREA,
        "LINEAR": cv2.INTER_LINEAR,
        "NEAREST": cv2.INTER_NEAREST,
        "CUBIC": cv2.INTER_CUBIC,
    }
    # Check on the photos where the two disagreed most.
    keys = ["20261020_195348", "20261024_185255", "20261018_210910", "20261024_185245"]
    for k in keys:
        f = by_stem.get(k)
        if not f:
            continue
        img = cv2.imread(str(f), cv2.IMREAD_COLOR)
        print(f"\n{k}  DEVICE {dev.get(k)}")
        for name, interp in interps.items():
            print(f"   {name:8s} {peaks(sess, img, interp)}")

    # Full agreement score per resampler.
    print("\nagreement with device over all photos (mean abs error, photos within 0.10):")
    for name, interp in interps.items():
        errs, close = [], 0
        for k, f in by_stem.items():
            if k not in dev:
                continue
            img = cv2.imread(str(f), cv2.IMREAD_COLOR)
            if img is None:
                continue
            p = peaks(sess, img, interp)
            e = np.mean([abs(a - b) for a, b in zip(p, dev[k])])
            errs.append(e)
            if e <= 0.10:
                close += 1
        print(f"  {name:8s} mean abs err {np.mean(errs):.3f}   within 0.10: {close}/{len(errs)}")


if __name__ == "__main__":
    main()
