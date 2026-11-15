#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H30 — which PIL resize mode reproduces Android's `Bitmap.createScaledBitmap`?

The offline replica of the corner model has to resize the 3024×4032 still to
256² exactly the way the device does, or the per-asset quads differ and the
merge rule is measured against the wrong baseline. Android's
`createScaledBitmap(src, w, h, true)` does not document its kernel, so this
probe scores every plausible filter against the **device's published per-asset
numbers** (H29 `HEADTOHEAD.md` §3, produced by the real detector on the real
emulator) and reports the mismatch for each mode.

This is not a way to "pick a mode that looks good": it is a calibration whose
residual is reported. If no mode matches the device, the offline numbers are
labelled as a proxy and the on-device lane is the authority (which it is in any
case).

Usage:
  python tooling\\h30_resize_probe.py
"""

import json
import os
import sys

import numpy as np
import onnxruntime as ort

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import h30_corner as C  # noqa: E402
import h30_score as S  # noqa: E402

OUT_DIR = os.path.join(REPO, "tooling", "out", "h30")

#: Device measurements, copied verbatim from tooling/out/h29/HEADTOHEAD.md §3
#: (run A, shipped LCNet100, emulator docscan_dbg_api35, CPU EP).
DEVICE = {
    ("bs01_control_clean", "still"): (7.7866, False),
    ("bs01_control_clean", "preview"): (0.1733, False),
    ("bs02_sliver_left_thin", "still"): (5.0941, False),
    ("bs02_sliver_left_thin", "preview"): (1.4448, True),
    ("bs03_sliver_right_thin", "still"): (7.5415, False),
    ("bs03_sliver_right_thin", "preview"): (1.2236, True),
    ("bs04_sliver_left_medium", "still"): (7.6700, False),
    ("bs04_sliver_left_medium", "preview"): (3.0592, True),
    ("bs05_sliver_right_large", "still"): (7.4160, True),
    ("bs05_sliver_right_large", "preview"): (5.5961, True),
    ("bs06_gutter_only", "still"): (7.8586, False),
    ("bs06_gutter_only", "preview"): (0.4939, False),
    ("bs07_curved_spine", "still"): (4.3673, False),
    ("bs07_curved_spine", "preview"): (1.9556, True),
    ("bs08_sliver_left_lowlight", "still"): (2.6601, False),
    ("bs08_sliver_left_lowlight", "preview"): (1.4212, True),
    ("bs09_control_strong_persp", "still"): (5.2724, False),
    ("bs09_control_strong_persp", "preview"): (0.1763, False),
}


def main():
    model = os.path.join(os.path.dirname(REPO), "dsmodels", "m1c_detect.onnx")
    sess = ort.InferenceSession(model, providers=["CPUExecutionProvider"])
    items = S.load_manifest()
    assets = []
    for item in items:
        for canvas in ("still", "preview"):
            a = S.Asset(item, canvas)
            a.rgb = S.load_image(a.asset)
            assets.append(a)

    print("%-21s %-8s %8s %8s   %s" % ("mode", "canvas", "mean%", "device%", "per-asset abs delta (pp)"))
    results = {}
    for mode in C.RESIZE_MODES:
        rows = []
        for a in assets:
            quad, _info = C.detect(sess, a.rgb, mode=mode)
            score = S.score_quad(a, quad)
            dev_pct, dev_merged = DEVICE[(a.name, a.canvas)]
            rows.append({
                "name": a.name,
                "canvas": a.canvas,
                "pct": score["corner_pct"],
                "merged": score["merged"],
                "dev_pct": dev_pct,
                "dev_merged": dev_merged,
            })
        deltas = [abs(r["pct"] - r["dev_pct"]) for r in rows if np.isfinite(r["pct"])]
        mean_pct = float(np.mean([r["pct"] for r in rows if np.isfinite(r["pct"])]))
        dev_mean = float(np.mean([r["dev_pct"] for r in rows]))
        merged = sum(1 for r in rows if r["merged"] and r["name"] != "bs01" and r["name"] != "bs06" and r["name"] != "bs09")
        dev_merged = sum(1 for r in rows if r["dev_merged"])
        results[mode] = {
            "mean_corner_pct": mean_pct,
            "device_mean_corner_pct": dev_mean,
            "mean_abs_delta_pp": float(np.mean(deltas)),
            "max_abs_delta_pp": float(np.max(deltas)),
            "merged_slivers": merged,
            "device_merged_slivers": dev_merged,
            "per_asset": rows,
        }
        print("%-21s %-8s %8.4f %8.4f   mean=%.4f max=%.4f merged=%d(dev %d)" % (
            mode, "all", mean_pct, dev_mean, float(np.mean(deltas)), float(np.max(deltas)),
            merged, dev_merged))

    best = min(results.items(), key=lambda kv: kv[1]["mean_abs_delta_pp"])
    print("\nclosest mode: %s (mean |delta| %.4f pp, max %.4f pp)" % (
        best[0], best[1]["mean_abs_delta_pp"], best[1]["max_abs_delta_pp"]))
    worst = sorted(best[1]["per_asset"], key=lambda r: -abs(r["pct"] - r["dev_pct"]))[:5]
    print("largest residuals for %s:" % best[0])
    for r in worst:
        print("  %-24s %-8s offline %7.3f  device %7.3f  delta %+6.3f" % (
            r["name"], r["canvas"], r["pct"], r["dev_pct"], r["pct"] - r["dev_pct"]))

    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "resize_probe.json"), "w", encoding="utf-8") as f:
        json.dump({"device_reference": {str(k): v for k, v in DEVICE.items()},
                   "modes": results, "closest": best[0]}, f, indent=2)
    print("\nwrote %s" % os.path.join(OUT_DIR, "resize_probe.json"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
