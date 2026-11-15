#!/usr/bin/env python3
"""
H45b - draw the oracle page quad and the detected quad on the photo, so the failure can be SEEN.

Oracle page = green, detected quad = red, corner dots = yellow.
Reads the quads from the H45 trace lane, so it overlays exactly what the current build returned.

Usage: python tooling/h45_overlay.py [traceTag]
Output: tooling/out/h45/overlay_<photo>.png (local only; these are private photos)
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from oracle_quality import reason as oracle_reason  # noqa: E402

ASSETS = Path("app/src/androidTest/assets/owner110")
TAG = sys.argv[1] if len(sys.argv) > 1 else "h44trace"
TRACE = Path("tooling/out/h39") / TAG
OUT = Path("tooling/out/h45")

# The four quads that sit entirely inside the page, plus the worst overshoots among the
# owner's green workbook photos.
WANTED = [
    "20261024_185405", "20261024_185404", "20261024_185450", "20261024_190141",
    "20261024_190145", "20261024_190146", "20261024_190133",
]


def main() -> None:
    items = {i["image"]: i for i in
             json.loads((ASSETS / "oracle_manifest.json").read_text(encoding="utf-8"))["items"]}
    OUT.mkdir(parents=True, exist_ok=True)
    for stem in WANTED:
        name = f"{stem}.jpg"
        tp = TRACE / stem / "trace.json"
        if not tp.exists() or name not in items:
            print(f"{stem}: skipped (no trace or no oracle)")
            continue
        img = cv2.imread(str(ASSETS / name), cv2.IMREAD_COLOR)
        if img is None:
            print(f"{stem}: decode failed")
            continue
        h, w = img.shape[:2]
        t = json.loads(tp.read_text(encoding="utf-8"))

        if not oracle_reason(items[name]):
            pts = np.array([items[name]["corners_px"][k]
                            for k in ("tl", "tr", "br", "bl")], np.int32)
            cv2.polylines(img, [pts], True, (0, 220, 0), 3)

        if t.get("quad"):
            q = np.array([[int(round(p["x"])), int(round(p["y"]))] for p in t["quad"]], np.int32)
            cv2.polylines(img, [q], True, (0, 0, 255), 3)
            for p in q:
                cv2.circle(img, (int(p[0]), int(p[1])), 10, (0, 220, 255), -1)

        label = f"{stem}  {t.get('provenance')}"
        cv2.rectangle(img, (0, 0), (w, 60), (0, 0, 0), -1)
        cv2.putText(img, label, (18, 42), cv2.FONT_HERSHEY_SIMPLEX, 1.1, (255, 255, 255), 2,
                    cv2.LINE_AA)
        cv2.putText(img, "green = annotated page   red = detected", (18, h - 22),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.9, (255, 255, 255), 2, cv2.LINE_AA)

        out = OUT / f"overlay_{stem}.png"
        cv2.imwrite(str(out), cv2.resize(img, (w // 2, h // 2), interpolation=cv2.INTER_AREA))
        print(f"{stem} -> {out}")


if __name__ == "__main__":
    main()
