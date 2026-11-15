#!/usr/bin/env python3
"""
H47 - intake a fresh holdout set from corpus_owner/.

The 116-photo `owner110` set has been used for tuning, so measuring on it flatters the detector:
9 of its annotations are unusable and several of its green-workbook annotations disagree with the
photo. This builds a SEPARATE set so the next measurement is an honest one.

For each source photo:
  1. downscale (long edge <= 1400 px, JPEG q92) into app/src/androidTest/assets/<set>/
     (the same contract the app decodes: stored pixels, no EXIF transpose);
  2. generate a SAM pseudo-GT quad (sam2.1-hiera-large, full-frame box prompt, largest paper mask,
     min-area rect + corner refinement) - measurement only, never shipped;
  3. reject/flagg unusable quads with `oracle_quality.reason`;
  4. write review overlays so the pseudo-GT can be eyeballed before it is trusted.

Outputs
  app/src/androidTest/assets/<set>/*.jpg                  gitignored (private photos)
  app/src/androidTest/assets/<set>/<set>_manifest.json
  app/src/androidTest/assets/<set>/oracle_manifest.json
  tooling/out/h47/review/<image>.png                      local overlays, gitignored
  tooling/out/h47/intake.json                             per-photo SAM verdicts

Usage:
  python tooling\\h47_intake.py --set holdout42 --prefix 20261115_
  python tooling\\h47_intake.py --set holdout42 --skip-sam      # assets/manifest only
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from oracle_quality import reason as oracle_reason  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
SRC = REPO / "corpus_owner"
ASSETS = REPO / "app/src/androidTest/assets"
OUT = REPO / "tooling/out/h47"

MAX_EDGE = 1400
JPEG_QUALITY = 92


def sanitise(stem: str) -> str:
    """Filesystem-safe, trace-dir-safe name (parentheses broke an earlier trace pull)."""
    s = re.sub(r"[^A-Za-z0-9_-]+", "_", stem).strip("_")
    return s or "photo"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", default="holdout42")
    ap.add_argument("--prefix", default="20261115_")
    ap.add_argument("--skip-sam", action="store_true")
    args = ap.parse_args()

    dest = ASSETS / args.set
    dest.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    review = OUT / "review"
    review.mkdir(parents=True, exist_ok=True)

    files = sorted(p for p in SRC.iterdir()
                   if p.suffix.lower() in (".jpg", ".jpeg") and p.name.startswith(args.prefix))
    if not files:
        print(f"no source photos under {SRC} with prefix {args.prefix!r}")
        return 1

    used: set[str] = set()
    items: list[dict] = []
    kept: set[str] = set()
    for p in files:
        im = Image.open(p)
        im.load()
        w, h = im.size
        scale = min(1.0, MAX_EDGE / max(w, h))
        nw, nh = max(1, round(w * scale)), max(1, round(h * scale))
        if scale < 1.0:
            im = im.resize((nw, nh), Image.LANCZOS)
        stem = sanitise(p.stem)
        n = 2
        while stem in used:
            stem = f"{sanitise(p.stem)}_{n}"
            n += 1
        used.add(stem)
        name = f"{stem}.jpg"
        kept.add(name)
        im.convert("RGB").save(dest / name, quality=JPEG_QUALITY, optimize=True)
        items.append({
            "image": name,
            "width": nw,
            "height": nh,
            "diagonal": round(float((nw ** 2 + nh ** 2) ** 0.5), 1),
            "asset_bytes": (dest / name).stat().st_size,
            "src_name": p.name,
            "src_width": w,
            "src_height": h,
            "src_scale": round(nw / w, 4),
            "oracle_format": "xy",
        })

    # A deleted source photo must not survive as a stale asset, or it would still be measured.
    stale = [q for q in dest.glob("*.jpg") if q.name not in kept]
    for q in stale:
        q.unlink()
    if stale:
        print(f"pruned {len(stale)} stale asset(s): {', '.join(q.name for q in stale)}")

    manifest = {
        "dataset": args.set,
        "note": ("Fresh owner holdout, downscaled copies of corpus_owner/ (private, not committed). "
                 "Not used for any tuning."),
        "max_long_edge": MAX_EDGE,
        "jpeg_quality": JPEG_QUALITY,
        "count": len(items),
        "total_bytes": sum(i["asset_bytes"] for i in items),
        "items": items,
    }
    (dest / f"{args.set}_manifest.json").write_text(json.dumps(manifest, indent=1), encoding="utf-8")
    print(f"assets: {len(items)} photos -> {dest}")

    if args.skip_sam:
        return 0

    # --- SAM pseudo-GT -----------------------------------------------------------------------
    from h36_sam import SamOracle, run_one  # imported late: heavy onnxruntime import

    oracle = SamOracle()
    rows = []
    gt_items = []
    for k, it in enumerate(items, 1):
        path = dest / it["image"]
        t0 = time.time()
        try:
            quad, meta, wh = run_one(oracle, str(path), None)
        except Exception as exc:  # noqa: BLE001 - a bad photo must not kill the batch
            quad, meta, wh = None, {"error": str(exc)}, (it["width"], it["height"])
        rec = {"image": it["image"], "src_name": it["src_name"], "ok": quad is not None}
        if quad is not None:
            rec["corners_px"] = {k2: [round(float(v[0]), 1), round(float(v[1]), 1)]
                                 for k2, v in zip(("tl", "tr", "br", "bl"), quad)}
        rec["meta"] = {k2: v for k2, v in meta.items()
                       if isinstance(v, (int, float, str, bool))}
        rec["ms"] = int((time.time() - t0) * 1000)
        if quad is not None:
            problem = oracle_reason({"corners_px": rec["corners_px"],
                                     "width": it["width"], "height": it["height"]})
            rec["oracle_problem"] = problem
            gt_items.append({
                "image": it["image"],
                "width": it["width"],
                "height": it["height"],
                "src_scale": it["src_scale"],
                "oracle_format": "xy",
                "corners_px": rec["corners_px"],
            })
            # review overlay: photo + SAM quad, so a bad pseudo-GT is caught now, not later
            img = cv2.imread(str(path), cv2.IMREAD_COLOR)
            if img is not None:
                pts = np.array([rec["corners_px"][c] for c in ("tl", "tr", "br", "bl")], np.int32)
                cv2.polylines(img, [pts], True, (0, 220, 0), 4)
                for pnt in pts:
                    cv2.circle(img, (int(pnt[0]), int(pnt[1])), 9, (0, 220, 255), -1)
                cv2.putText(img, f"SAM {it['image']}", (14, 40), cv2.FONT_HERSHEY_SIMPLEX,
                            1.0, (255, 255, 255), 2, cv2.LINE_AA)
                cv2.imwrite(str(review / it["image"].replace(".jpg", ".png")), img)
        else:
            rec["oracle_problem"] = "SAM returned no mask"
        rows.append(rec)
        print(f"[{k}/{len(items)}] {it['image']}: "
              f"{'quad' if quad is not None else 'NO MASK'}"
              f"{' PROBLEM: ' + rec['oracle_problem'] if rec.get('oracle_problem') else ''}"
              f" ({rec['ms']} ms)", flush=True)

    gt = {
        "dataset": args.set,
        "source": "sam2.1-hiera-large (onnx-community ONNX), measurement-only",
        "frame": "bundled-asset pixels (no EXIF transpose)",
        "note": ("SAM pseudo-GT. Generated after the detector was frozen, never used for tuning. "
                 "It segments the largest paper region, so it follows a physical sheet rather than "
                 "a printed frame - which is what the owner's 'box inside the page' report needs."),
        "count": len(gt_items),
        "items": gt_items,
    }
    (dest / "oracle_manifest.json").write_text(json.dumps(gt, indent=1), encoding="utf-8")
    (OUT / "intake.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")

    bad = [r for r in rows if r.get("oracle_problem")]
    print(f"\n{len(gt_items)}/{len(rows)} with a SAM quad, {len(bad)} flagged")
    for r in bad:
        print(f"  {r['image']}: {r['oracle_problem']}")
    print(f"-> {dest / 'oracle_manifest.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
