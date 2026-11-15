#!/usr/bin/env python3
"""
H40 — INK-MAP PROBE: is the "bad ink map" the fault, or the quad it is given?

The H39 trace lane (``tooling/h39_dump.cmd``) dumps, per photo, the rectified page and the
adaptive-threshold INK map that the gate actually scores. The owner's read of the curved-page
failures was "the AI ink map is bad". This probe tests that claim offline, deterministically,
without the phone:

  * it re-implements the EXACT gate rectification + INK extraction from
    ``PagePlausibility.analyseRectifiedInterior`` (300 px long edge, 7 % interior margin,
    ``adaptiveThreshold(MEAN_C, block 25, C 10)``) in Python,
  * runs it **twice per photo** — once on the quad the detector returned, once on the oracle
    (ground-truth) quad — using the same photo,
  * reports glyph count / text score / ink fraction for both, and
  * probes a few improved INK variants so we can see whether a better mask would have helped.

If the oracle-quad INK map is clearly better than the detected-quad one on the same photo, the
INK map is NOT the problem: the QUAD is. That is the question this script exists to answer.

Usage
-----
    python tooling/h40_ink_probe.py <h39-dump-dir> [--out <dir>]

    python tooling/h40_ink_probe.py E:\\DocScanAI-cache\\tooling-out\\h39\\h39demo

Outputs (default ``<h39-dump-dir>/../h40_ink_probe/``):
    probe.json                 every scalar, per photo and per quad source
    <scene>_ink_compare.png    interior | INK baseline | INK variant | glyph boxes
    report.md                  the table, readable
"""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import cv2
import numpy as np

# --- gate constants (mirror PagePlausibility.kt) -------------------------------------------
RECT_LONG_EDGE = 300
INTERIOR_MARGIN = 0.07
ADAPT_BLOCK = 25
ADAPT_C = 10.0
GLYPH_MIN_AREA = 4
GLYPH_MAX_AREA = 600
GLYPH_MAX_W = 60
GLYPH_MAX_H = 45
GLYPH_TARGET = 50.0
TEXT_FLOOR = 0.30
CV_LO, CV_HI = 0.35, 0.95
BAND_RATIO = 1.25
BAND_LO, BAND_HI = 0.12, 0.37
CORR_LO, CORR_HI = 0.25, 0.60
PEAK_RATIO = 1.6
PEAK_SEPARATION = 3
PEAK_TARGET = 8.0
MAX_LAG = 48
HIST_BINS = 32
INK_FRAC_MID = 0.08

ASSET_DIR = Path("app/src/androidTest/assets/owner110")
ORACLE = ASSET_DIR / "oracle_manifest.json"


# --- gate port ------------------------------------------------------------------------------
def rectify(img: np.ndarray, quad: list[list[float]]) -> tuple[np.ndarray, np.ndarray]:
    """Return (rectified colour, interior gray ROI) exactly like the gate does."""
    tl, tr, br, bl = [np.array(p, dtype=np.float64) for p in quad]
    page_w = (np.linalg.norm(tr - tl) + np.linalg.norm(br - bl)) / 2.0
    page_h = (np.linalg.norm(bl - tl) + np.linalg.norm(br - tr)) / 2.0
    s = RECT_LONG_EDGE / max(page_w, page_h)
    pw = int(min(max(round(page_w * s), 16), RECT_LONG_EDGE))
    ph = int(min(max(round(page_h * s), 16), RECT_LONG_EDGE))
    src = np.array([tl, tr, br, bl], dtype=np.float32)
    dst = np.array([[0, 0], [pw - 1, 0], [pw - 1, ph - 1], [0, ph - 1]], dtype=np.float32)
    m = cv2.getPerspectiveTransform(src, dst)
    rect = cv2.warpPerspective(img, m, (pw, ph), flags=cv2.INTER_LINEAR,
                               borderMode=cv2.BORDER_REPLICATE)
    gray = cv2.cvtColor(rect, cv2.COLOR_BGR2GRAY) if rect.ndim == 3 else rect
    iy0, ix0 = int(round(ph * INTERIOR_MARGIN)), int(round(pw * INTERIOR_MARGIN))
    iy1, ix1 = int(round(ph - ph * INTERIOR_MARGIN)), int(round(pw - pw * INTERIOR_MARGIN))
    iy0, ix0 = min(max(iy0, 0), ph - 8), min(max(ix0, 0), pw - 8)
    iy1, ix1 = min(max(iy1, iy0 + 8), ph), min(max(ix1, ix0 + 8), pw)
    return rect, gray[iy0:iy1, ix0:ix1]


def ink_baseline(roi: np.ndarray) -> np.ndarray:
    return cv2.adaptiveThreshold(roi, 255, cv2.ADAPTIVE_THRESH_MEAN_C,
                                 cv2.THRESH_BINARY_INV, ADAPT_BLOCK, ADAPT_C)


def ink_flatfield(roi: np.ndarray, block: int = 25, c: float = 10.0) -> np.ndarray:
    """Remove page curvature / lighting gradient before thresholding.

    The curved-page failure is a low-frequency shading ramp: on a bowed page the local mean
    under a 25 px window sits lower near the spine, so faint glyphs there drop out of the mask
    while shadow bands at the edge get inked. Dividing by a large-window local mean flattens
    that ramp first.
    """
    bg = cv2.GaussianBlur(roi, (0, 0), 21)
    norm = cv2.divide(roi, np.maximum(bg, 1), scale=255)
    return cv2.adaptiveThreshold(norm, 255, cv2.ADAPTIVE_THRESH_MEAN_C,
                                 cv2.THRESH_BINARY_INV, block, c)


def ink_sauvola(roi: np.ndarray, window: int = 25, k: float = 0.2) -> np.ndarray:
    f = roi.astype(np.float32)
    mean = cv2.boxFilter(f, -1, (window, window), normalize=True)
    sq = cv2.boxFilter(f * f, -1, (window, window), normalize=True)
    std = np.sqrt(np.maximum(sq - mean * mean, 0))
    t = mean * (1.0 - k * (std / 128.0 - 1.0))
    return ((f < t).astype(np.uint8)) * 255


INK_VARIANTS = {
    "baseline": ink_baseline,
    "flatfield": ink_flatfield,
    "sauvola": ink_sauvola,
}


def glyph_boxes(ink: np.ndarray) -> list[tuple[int, int, int, int]]:
    n, labels, stats, _ = cv2.connectedComponentsWithStats(ink, 8, cv2.CV_32S)
    out = []
    for i in range(1, n):
        x, y, w, h, area = (int(stats[i, cv2.CC_STAT_LEFT]), int(stats[i, cv2.CC_STAT_TOP]),
                            int(stats[i, cv2.CC_STAT_WIDTH]), int(stats[i, cv2.CC_STAT_HEIGHT]),
                            int(stats[i, cv2.CC_STAT_AREA]))
        if area < GLYPH_MIN_AREA or area > GLYPH_MAX_AREA:
            continue
        if w > GLYPH_MAX_W or h > GLYPH_MAX_H:
            continue
        out.append((x, y, w, h))
    return out


def text_score(ink: np.ndarray, glyphs: int) -> tuple[float, dict]:
    profile = ink.sum(axis=1) / 255.0
    n = len(profile)
    if n < 16 or glyphs <= 0:
        return 0.0, {}
    mean = float(profile.mean())
    if mean <= 0.5:
        return 0.0, {}
    sd = float(profile.std())
    cv = sd / mean
    cv_score = clamp((cv - CV_LO) / (CV_HI - CV_LO))
    peaks, last = 0, -PEAK_SEPARATION
    for i in range(2, n - 2):
        v = profile[i]
        if v <= mean * PEAK_RATIO:
            continue
        if (v >= profile[i - 1] and v >= profile[i + 1]
                and v >= profile[i - 2] and v >= profile[i + 2]
                and i - last >= PEAK_SEPARATION):
            peaks += 1
            last = i
    peak_score = clamp(peaks / PEAK_TARGET)
    band_rows = int((profile > mean * BAND_RATIO).sum())
    band_score = clamp((band_rows / n - BAND_LO) / (BAND_HI - BAND_LO))
    corr = max_autocorr(profile, mean)
    corr_score = clamp((corr - CORR_LO) / (CORR_HI - CORR_LO))
    projection = clamp(0.30 * cv_score + 0.25 * peak_score + 0.25 * band_score + 0.20 * corr_score)
    gs = clamp(glyphs / GLYPH_TARGET)
    detail = {"cv": cv, "peaks": peaks, "corr": corr, "glyph_score": gs, "projection": projection}
    return clamp(gs * (0.55 + 0.45 * projection)), detail


def max_autocorr(profile: np.ndarray, mean: float) -> float:
    n = len(profile)
    best = 0.0
    for lag in range(3, min(MAX_LAG, n // 3) + 1):
        x = profile[:n - lag] - mean
        y = profile[lag:] - mean
        sxx, syy = float((x * x).sum()), float((y * y).sum())
        if sxx > 1e-9 and syy > 1e-9:
            best = max(best, float((x * y).sum()) / math.sqrt(sxx * syy))
    return best


def paper_stats(roi: np.ndarray) -> dict:
    hist = cv2.calcHist([roi], [0], None, [HIST_BINS], [0, 256]).ravel()
    total = roi.size
    peak_frac = float(hist[max(0, int(hist.argmax()) - 1):int(hist.argmax()) + 2].sum() / total)
    p10, p50, p90 = np.percentile(roi, [10, 50, 90])
    ink_cut = p50 - 0.45 * max(p90 - p10, 6.0)
    ink_frac = float((roi < ink_cut).mean())
    return {"peak_frac": peak_frac, "p10": p10, "p50": p50, "p90": p90, "ink_frac": ink_frac}


def clamp(v: float) -> float:
    return min(max(float(v), 0.0), 1.0)


# --- driver ---------------------------------------------------------------------------------
def load_quads(dump: Path, oracle: dict) -> dict:
    out = {}
    for scene in sorted(p for p in dump.iterdir() if p.is_dir()):
        tpath = scene / "trace.json"
        if not tpath.exists():
            continue
        t = json.loads(tpath.read_text(encoding="utf-8"))
        name = scene.name + ".jpg"
        o = oracle.get(name)
        q = t.get("quad") or []
        out[scene.name] = {
            "image": name,
            "detected": t.get("detected"),
            "provenance": t.get("provenance"),
            "gate_passed": t.get("gate_passed"),
            "quad": [[p["x"], p["y"]] for p in q] if q else None,
            "oracle": o,
            "recorded": {
                "text": t.get("text_score"),
                "glyphs": t.get("glyphs"),
                "paper": t.get("paper_score"),
                "ink_frac": (t.get("gate") or {}).get("ink_frac"),
            },
        }
    return out


def analyse_variant(roi: np.ndarray, fn) -> dict:
    ink = fn(roi)
    boxes = glyph_boxes(ink)
    score, detail = text_score(ink, len(boxes))
    return {
        "ink": ink,
        "glyphs": len(boxes),
        "text": score,
        "ink_frac_mask": float((ink > 0).mean()),
        **detail,
    }


def draw_panel(gray: np.ndarray, ink: np.ndarray, boxes, title: str, scale: int = 2) -> np.ndarray:
    base = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR) if gray.ndim == 2 else gray
    tile = np.hstack([base, cv2.cvtColor(ink, cv2.COLOR_GRAY2BGR)])
    for (x, y, w, h) in boxes:
        cv2.rectangle(tile, (x + gray.shape[1], y), (x + gray.shape[1] + w, y + h), (40, 40, 255), 1)
    tile = cv2.resize(tile, None, fx=scale, fy=scale, interpolation=cv2.INTER_NEAREST)
    cv2.putText(tile, title, (6, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 255, 0), 1, cv2.LINE_AA)
    return tile


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    dump = Path(argv[1]).resolve()
    out = Path(argv[3]).resolve() if len(argv) > 3 and argv[2] == "--out" else dump.parent / "h40_ink_probe"
    out.mkdir(parents=True, exist_ok=True)

    o = json.loads(ORACLE.read_text(encoding="utf-8"))
    oracle = {i["image"]: [i["corners_px"][k] for k in ("tl", "tr", "br", "bl")] for i in o["items"]}
    scenes = load_quads(dump, oracle)

    rows = []
    for stem, s in scenes.items():
        img = cv2.imread(str(ASSET_DIR / s["image"]), cv2.IMREAD_COLOR)
        if img is None:
            print(f"  ! cannot read {s['image']}")
            continue
        row = {"image": s["image"], "provenance": s["provenance"],
               "detected": s["detected"], "gate_passed": s["gate_passed"],
               "recorded": s["recorded"], "sources": {}}
        panels = []
        for label, quad in (("detected", s["quad"]), ("oracle", s["oracle"])):
            if not quad:
                continue
            rect, roi = rectify(img, quad)
            variants = {name: analyse_variant(roi, fn) for name, fn in INK_VARIANTS.items()}
            base = variants["baseline"]
            row["sources"][label] = {
                "quad": quad,
                "rect_w": int(rect.shape[1]), "rect_h": int(rect.shape[0]),
                "roi_w": int(roi.shape[1]), "roi_h": int(roi.shape[0]),
                "paper": paper_stats(roi),
                "baseline": {k: v for k, v in base.items() if k != "ink"},
                "variants": {n: {k: v for k, v in d.items() if k != "ink"} for n, d in variants.items()},
            }
            panels.append(draw_panel(roi, base["ink"], glyph_boxes(base["ink"]),
                                     f"{label}: glyphs={base['glyphs']} text={base['text']:.2f}"))
            best_name = max(variants, key=lambda n: variants[n]["text"])
            panels.append(draw_panel(roi, variants[best_name]["ink"],
                                     glyph_boxes(variants[best_name]["ink"]),
                                     f"{label} best={best_name} text={variants[best_name]['text']:.2f}"))
            if label == "detected":
                cv2.imwrite(str(out / f"{stem}_ink_compare.png"), np.vstack(panels))
        rows.append(row)

    (out / "probe.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")

    md = ["# H40 INK probe — the same photo, two quads", "",
          "Baseline = the exact gate mask (`adaptiveThreshold MEAN_C, block 25, C 10`).", "",
          "| photo | prov | quad | glyphs | text | ink_frac | verdict |",
          "|---|---|---|---|---|---|---|"]
    for r in rows:
        det = r["sources"].get("detected", {}).get("baseline")
        orc = r["sources"].get("oracle", {}).get("baseline")
        if det and orc:
            gain = orc["text"] - det["text"]
            verdict = ("quad is the problem" if gain > 0.10 else
                       "ink map is the problem" if gain < -0.02 else "inconclusive")
        else:
            verdict = "no quad"
        md.append(
            f"| {r['image']} | {r['provenance']} | detected | "
            f"{det['glyphs'] if det else '-'} | {(det['text'] if det else 0):.2f} | "
            f"{r['sources'].get('detected', {}).get('paper', {}).get('ink_frac', 0):.3f} | {verdict} |"
        )
        if orc:
            md.append(
                f"| {r['image']} | oracle | oracle | {orc['glyphs']} | {orc['text']:.2f} | "
                f"{r['sources']['oracle']['paper']['ink_frac']:.3f} | |"
            )
    (out / "report.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    print(f"probe: {len(rows)} photos -> {out}")
    for r in rows:
        d = r["sources"].get("detected", {}).get("baseline")
        oq = r["sources"].get("oracle", {}).get("baseline")
        print(f"{r['image']:24s} {r['provenance'] or '-':18s} "
              f"det glyphs={d['glyphs'] if d else '-':>4} text={d['text'] if d else 0:.2f}  "
              f"oracle glyphs={oq['glyphs'] if oq else '-':>4} text={oq['text'] if oq else 0:.2f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
