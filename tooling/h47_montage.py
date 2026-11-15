#!/usr/bin/env python3
"""
H47b - contact sheet of the holdout with its SAM pseudo-GT quads.

42 overlays viewed one by one is slow and easy to rubber-stamp. This tiles them into a few sheets
of 6 x 4 thumbnails with an index, so a broken annotation is visible at a glance.

Usage: python tooling\\h47_montage.py [--set holdout42] [--cols 6] [--rows 4]
Output: tooling/out/h47/sheet_<set>_<n>.png  (local only; private photos)
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

ASSETS = Path("app/src/androidTest/assets")
OUT = Path("tooling/out/h47")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", default="holdout42")
    ap.add_argument("--cols", type=int, default=6)
    ap.add_argument("--rows", type=int, default=4)
    ap.add_argument("--width", type=int, default=420)
    ap.add_argument("--lane", default="", help="trace tag: also draw the DETECTED quad in red")
    ap.add_argument("--prefix", default="sheet", help="output file prefix")
    args = ap.parse_args()

    root = ASSETS / args.set
    oracle_path = root / "oracle_manifest.json"
    if not oracle_path.exists():
        print(f"missing {oracle_path}")
        return 1
    items = json.loads(oracle_path.read_text(encoding="utf-8"))["items"]
    quads = {i["image"]: i.get("corners_px") for i in items}

    names = sorted(p.name for p in root.glob("*.jpg"))
    per = args.cols * args.rows
    OUT.mkdir(parents=True, exist_ok=True)
    lane = Path("tooling/out/h39") / args.lane if args.lane else None

    for s in range(0, len(names), per):
        chunk = names[s:s + per]
        tiles = []
        for name in chunk:
            img = cv2.imread(str(root / name), cv2.IMREAD_COLOR)
            if img is None:
                continue
            h, w = img.shape[:2]
            scale = args.width / w
            img = cv2.resize(img, (args.width, max(1, int(h * scale))), interpolation=cv2.INTER_AREA)
            q = quads.get(name)
            if q and lane is None:
                pts = np.array([q[c] for c in ("tl", "tr", "br", "bl")], np.float64)
                pts = (pts * scale).astype(np.int32)
                cv2.polylines(img, [pts], True, (0, 220, 0), 3)
                for p in pts:
                    cv2.circle(img, (int(p[0]), int(p[1])), 7, (0, 220, 255), -1)
            if lane is not None:
                tp = lane / name[:-4] / "trace.json"
                if tp.exists():
                    t = json.loads(tp.read_text(encoding="utf-8"))
                    if t.get("quad"):
                        d = np.array([[p["x"] * scale, p["y"] * scale] for p in t["quad"]],
                                     np.int32)
                        cv2.polylines(img, [d], True, (0, 0, 255), 3)
                        tag = f"{t.get('provenance')}{'' if t.get('gate_passed') else ' GATE-FAIL'}"
                        cv2.putText(img, tag, (args.width - 200, 56),
                                    cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 0), 1,
                                    cv2.LINE_AA)
            cv2.rectangle(img, (0, 0), (args.width, 34), (0, 0, 0), -1)
            cv2.putText(img, f"{s + len(tiles) + 1}. {name[:-4]}", (8, 25),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.62, (255, 255, 255), 1, cv2.LINE_AA)
            tiles.append(img)

        if not tiles:
            continue
        th = max(t.shape[0] for t in tiles)
        tw = max(t.shape[1] for t in tiles)
        sheet = np.full((args.rows * th, args.cols * tw, 3), 32, np.uint8)
        for k, t in enumerate(tiles):
            r, c = divmod(k, args.cols)
            sheet[r * th:r * th + t.shape[0], c * tw:c * tw + t.shape[1]] = t
        out = OUT / f"{args.prefix}_{args.set}_{s // per + 1}.png"
        cv2.imwrite(str(out), sheet)
        print(f"{len(tiles)} tiles -> {out}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
