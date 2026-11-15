#!/usr/bin/env python3
"""
H43 - MODEL HEAD-TO-HEAD: LCNet100 (shipped) vs FastViT-SA24 (documented upgrade).

Both graphs take `img` float32 [1,3,256,256] and emit `heatmap` [1,4,H,W], and both are fed the
same tensor by the same production preprocessing (squash to 256, RGB/255). So this is a pure
model comparison on the owner corpus: how many photos does each one give a full 4-channel lock on
(all peaks >= the production 0.30 gate), and how confident is it overall.

Motivation: H43 measured that the shipped model produces ZERO confident corners on 68 of 116
photos, and that no input construction rescues them. If FastViT locks those photos, the model is
the limitation and the upgrade is the fix.

Usage:
    python tooling/h43_model_ab.py [--limit N]
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

ASSETS = Path("app/src/androidTest/assets/owner110")
SHIPPED = Path(r"E:\DocScanAI-cache\tooling-out\h4_models\m1c_detect.onnx")
UPGRADE = Path(r"E:\DocScanAI-cache\tooling-out\h29\candidates\fastvit_sa24.onnx")
OUT = Path("tooling/out/h43")
GATE = 0.30
EDGE = 256


def tensor(img_bgr):
    rgb = cv2.cvtColor(cv2.resize(img_bgr, (EDGE, EDGE), interpolation=cv2.INTER_AREA),
                       cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
    return np.transpose(rgb, (2, 0, 1))[None, ...]


def peaks_of(sess, x):
    out = sess.run(None, {sess.get_inputs()[0].name: x})[0]
    h = np.asarray(out).reshape(4, -1)
    return [float(h[c].max()) for c in range(4)]


def main(argv):
    limit = None
    for i, a in enumerate(argv):
        if a == "--limit" and i + 1 < len(argv):
            limit = int(argv[i + 1])
    OUT.mkdir(parents=True, exist_ok=True)

    models = {}
    for label, path in (("lcnet100", SHIPPED), ("fastvit_sa24", UPGRADE)):
        if not path.exists():
            print(f"! missing {path}")
            continue
        s = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
        shape = s.get_inputs()[0].shape
        models[label] = (s, shape)
        print(f"{label}: input {s.get_inputs()[0].name} {shape}  out {s.get_outputs()[0].shape}")

    files = sorted(ASSETS.glob("*.jpg"))
    if limit:
        files = files[:limit]

    rows = []
    for n, f in enumerate(files, 1):
        img = cv2.imread(str(f), cv2.IMREAD_COLOR)
        if img is None:
            continue
        x = tensor(img)
        row = {"image": f.name, "models": {}}
        for label, (sess, _) in models.items():
            t0 = time.time()
            try:
                pk = peaks_of(sess, x)
            except Exception as exc:  # noqa: BLE001
                row["models"][label] = {"error": str(exc)[:120]}
                continue
            row["models"][label] = {
                "peaks": [round(p, 3) for p in pk],
                "max": round(max(pk), 3),
                "strong": sum(1 for p in pk if p >= GATE),
                "ms": round((time.time() - t0) * 1000, 1),
            }
        rows.append(row)
        if n % 20 == 0:
            print(f"  {n}/{len(files)}")

    (OUT / "model_ab.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")

    lines = ["# H43 - corner model A/B: LCNet100 (shipped) vs FastViT-SA24 (upgrade)", "",
             f"Same {len(rows)} owner photos, same production input (squash 256, RGB/255),",
             f"same gate (4 channels >= {GATE}).", "",
             "| model | photos w/ 4 strong | >=3 strong | blind (0) | mean max peak | median ms |",
             "|---|---|---|---|---|---|"]
    for label in models:
        ok = [r["models"][label] for r in rows if "error" not in r["models"].get(label, {"error": 1})]
        four = sum(1 for r in ok if r["strong"] == 4)
        three = sum(1 for r in ok if r["strong"] >= 3)
        zero = sum(1 for r in ok if r["strong"] == 0)
        lines.append(
            f"| **{label}** | **{four}** | {three} | {zero} | "
            f"{np.mean([r['max'] for r in ok]):.3f} | {np.median([r['ms'] for r in ok]):.0f} |"
        )

    if len(models) == 2:
        rescued = [r for r in rows
                   if r["models"]["lcnet100"]["strong"] < 4
                   and r["models"]["fastvit_sa24"]["strong"] == 4]
        lost = [r for r in rows
                if r["models"]["lcnet100"]["strong"] == 4
                and r["models"]["fastvit_sa24"]["strong"] < 4]
        lines += ["", f"* FastViT gives a full lock where LCNet100 does not: **{len(rescued)} photos**",
                  f"* FastViT LOSES a full lock LCNet100 had: **{len(lost)} photos**", ""]
        if rescued:
            lines += ["| rescued photo | lcnet peaks | fastvit peaks |", "|---|---|---|"]
            for r in rescued[:50]:
                lines.append(f"| `{r['image']}` | {r['models']['lcnet100']['peaks']} | "
                             f"{r['models']['fastvit_sa24']['peaks']} |")

    (OUT / "MODEL_AB.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print(f"\n-> {OUT / 'MODEL_AB.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
