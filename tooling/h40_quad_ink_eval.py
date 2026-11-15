#!/usr/bin/env python3
"""
H40 — QUAD vs INK, over the whole owner set (116 photos, offline).

Input is the H38 real-path evidence (`tooling/out/h38/h38fixd/owner110_realpath.json`: width,
height, detected quad, provenance, gate flags, and the H38 `wrong_page` / `merged` labels) plus
the bundled oracle quads. For every photo this script rectifies the SAME image twice — with the
detected quad and with the oracle quad — and runs the gate's exact INK extractor on both.

It then looks for an INK-side feature that separates accepted-but-wrong quads (merged / wrong
page) from correct ones. The gate's `text` score is expected to be saturated (see
`h40_ink_report.py`); the interesting candidates are paper/ink margin statistics:

  border_ink   fraction of INK pixels in the outer 12 % band of the rectified page
  center_ink   fraction of INK pixels in the central 76 %
  ink_ratio    center_ink / (border_ink + center_ink)
  band_luma    mean luma of the outer band (a real page has a plain paper margin)
  mode_band    share of the outer band inside the histogram paper mode

A correct quad of a page photographed on a table has a low-ink, paper-coloured border. A quad
that swallowed the table has texture / luma gradient in that border.

Usage:
    python tooling/h40_quad_ink_eval.py <owner110_realpath.json> [--out <dir>]

Default out: <json-dir>/h40_quad_ink.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from h40_ink_probe import (  # noqa: E402
    ASSET_DIR, ORACLE, glyph_boxes, ink_baseline, paper_stats, rectify, text_score,
)

BAND = 0.12


def band_stats(roi: np.ndarray, ink: np.ndarray, paper: dict) -> dict:
    h, w = roi.shape
    by, bx = int(round(h * BAND)), int(round(w * BAND))
    mask = np.zeros((h, w), dtype=bool)
    mask[:by, :] = mask[-by:, :] = True
    mask[:, :bx] = mask[:, -bx:] = True
    border_ink = float((ink[mask] > 0).mean())
    center_ink = float((ink[~mask] > 0).mean())
    band_luma = float(roi[mask].mean())
    mode = paper["p50"]
    tol = max(18.0, 0.18 * (paper["p90"] - paper["p10"]))
    mode_band = float((np.abs(roi[mask].astype(np.float64) - mode) <= tol).mean())
    return {
        "border_ink": border_ink,
        "center_ink": center_ink,
        "ink_ratio": center_ink / (border_ink + center_ink + 1e-6),
        "band_luma": band_luma,
        "mode_band": mode_band,
    }


def measure(img: np.ndarray, quad: list[list[float]]) -> dict | None:
    pts = np.array(quad, dtype=np.float64)
    if (np.linalg.norm(pts[1] - pts[0]) < 8 or np.linalg.norm(pts[3] - pts[0]) < 8):
        return None
    _, roi = rectify(img, quad)
    ink = ink_baseline(roi)
    boxes = glyph_boxes(ink)
    text, detail = text_score(ink, len(boxes))
    paper = paper_stats(roi)
    return {
        "glyphs": len(boxes),
        "text": text,
        "ink_frac": float((ink > 0).mean()),
        "paper": paper,
        "bands": band_stats(roi, ink, paper),
        **detail,
    }


def auc(pos: list[float], neg: list[float]) -> float:
    """Probability that a positive scores above a negative (Mann-Whitney)."""
    if not pos or not neg:
        return float("nan")
    wins = 0.0
    for p in pos:
        for n in neg:
            wins += 1.0 if p > n else 0.5 if p == n else 0.0
    return wins / (len(pos) * len(neg))


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    src = Path(argv[1]).resolve()
    out = Path(argv[3]).resolve() if len(argv) > 3 and argv[2] == "--out" else src.parent / "h40_quad_ink"
    out.mkdir(parents=True, exist_ok=True)

    data = json.loads(src.read_text(encoding="utf-8"))
    labels_path = Path(argv[5]).resolve() if len(argv) > 5 and argv[4] == "--labels" else src.parent.parent / "report_h38fixd.json"
    labels = {}
    if labels_path.exists():
        for r in json.loads(labels_path.read_text(encoding="utf-8"))["rows"]:
            labels[r["image"]] = r
    else:
        print(f"! labels not found: {labels_path} (wrong_page/merged will be absent)")
    oracle = {i["image"]: [i["corners_px"][k] for k in ("tl", "tr", "br", "bl")]
              for i in json.loads(ORACLE.read_text(encoding="utf-8"))["items"]}

    rows = []
    missing_assets = 0
    for r in data["rows"]:
        img_path = ASSET_DIR / r["image"]
        img = cv2.imread(str(img_path), cv2.IMREAD_COLOR)
        if img is None:
            missing_assets += 1
            continue
        row = {k: r.get(k) for k in ("image", "prov", "gate_pass", "iou", "corner_pct",
                                     "covers_oracle", "area_ratio", "our_frame_touch")}
        lab = labels.get(r["image"], {})
        for k in ("wrong_page", "merged", "correct_page", "is_new"):
            row[k] = lab.get(k)
        quad = (r.get("quad") or {}).get("pts")
        oracle_quad = oracle.get(r["image"])
        row["detected"] = measure(img, quad) if quad else None
        row["oracle"] = measure(img, oracle_quad) if oracle_quad else None
        rows.append(row)

    (out / "quad_ink.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")

    detected = [r for r in rows if r["detected"]]
    bad = [r for r in detected if r["merged"] or r["wrong_page"]]
    good = [r for r in detected if r["correct_page"] and not r["wrong_page"]]

    # Does a better ink map (oracle quad) help the bad ones? (i.e. is the ink map to blame?)
    gains = []
    for r in bad:
        if r["oracle"]:
            gains.append(r["oracle"]["text"] - r["detected"]["text"])
    text_gain = float(np.mean(gains)) if gains else float("nan")

    features = ["border_ink", "ink_ratio", "band_luma", "mode_band", "text", "paper"]
    summary = {
        "photos": len(rows),
        "missing_assets": missing_assets,
        "detected": len(detected),
        "merged": sum(1 for r in detected if r["merged"]),
        "wrong_page": sum(1 for r in detected if r["wrong_page"]),
        "correct": len(good),
        "mean_text_gain_oracle_on_bad": text_gain,
        "auc": {},
        "medians": {},
    }
    for f in features:
        def value(r, src_name):
            m = r[src_name]
            if not m:
                return float("nan")
            return m["paper"]["p50"] if f == "paper" else m["bands"][f] if f in m["bands"] else m[f]
        pos = [value(r, "detected") for r in bad]
        neg = [value(r, "detected") for r in good]
        summary["auc"][f] = auc(pos, neg)
        summary["medians"][f] = {
            "bad": float(np.nanmedian(pos)) if pos else float("nan"),
            "good": float(np.nanmedian(neg)) if neg else float("nan"),
        }

    (out / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")

    lines = [
        "# H40 — quad vs ink, owner110 (offline)",
        "",
        f"* photos measured: **{len(rows)}** (detected {len(detected)}, correct {len(good)}, "
        f"merged {summary['merged']}, wrong-page {summary['wrong_page']})",
        f"* mean text gain when the SAME photo is re-rectified with the oracle quad, over the "
        f"bad quads: **{text_gain:+.3f}**",
        "",
        "## Does an INK-side feature separate bad quads from good ones?",
        "",
        "AUC = chance that a bad quad scores higher than a good one (0.5 = no separation).",
        "",
        "| feature | median bad | median good | AUC |",
        "|---|---|---|---|",
    ]
    for f in features:
        m = summary["medians"][f]
        lines.append(f"| {f} | {m['bad']:.3f} | {m['good']:.3f} | {summary['auc'][f]:.3f} |")
    lines += [
        "",
        "Reading: `border_ink` / `ink_ratio` describe how much ink/texture sits in the outer "
        "12 % band of the rectified page. A page that is correctly cropped has a plain paper "
        "margin there; a quad that swallowed the table does not.",
        "",
    ]
    (out / "SUMMARY.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(json.dumps(summary, indent=2))
    print(f"-> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
