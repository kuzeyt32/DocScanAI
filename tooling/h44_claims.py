#!/usr/bin/env python3
"""
H44 - TWO CLAIMS TESTED PROPERLY.

The earlier H43 saturation check measured the WHOLE FRAME, which is mostly table/floor/dark
background, so it could not see the paper's colour. That was sloppy. This measures the colour of
the PAGE ITSELF (the oracle quad), and separates:

  1. COLOUR       - is the page coloured, and do coloured pages get missed?
  2. INNER BOX    - does the detector lock onto a frame/box INSIDE the page instead of the page?
                    Signature: final quad much SMALLER than the oracle page (area_ratio < 0.75)
                    while the quad sits mostly INSIDE the page.

Usage: python tooling/h44_claims.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import cv2
import numpy as np

ASSETS = Path("app/src/androidTest/assets/owner110")
ORACLE = ASSETS / "oracle_manifest.json"
LANE = Path(sys.argv[1] if len(sys.argv) > 1 else "tooling/out/h38/report_h44crop.json")
OUT = Path("tooling/out/h44")

sys.path.insert(0, str(Path(__file__).parent))
from oracle_quality import reason as oracle_reason  # noqa: E402


def page_mask(shape, quad):
    m = np.zeros(shape[:2], np.uint8)
    cv2.fillConvexPoly(m, np.asarray(quad, np.int32), 255)
    return m


def page_colour(img, quad):
    """Mean HSV saturation + hue of the paper, inside the oracle quad, trimmed 20 %."""
    pts = np.asarray(quad, np.float64)
    c = pts.mean(axis=0)
    inner = c + (pts - c) * 0.6  # trim toward the middle, skip the page border/shadow
    m = page_mask(img.shape, inner)
    if m.sum() == 0:
        return None
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    sat = float(hsv[..., 1][m > 0].mean())
    hue = float(hsv[..., 0][m > 0].mean())
    val = float(hsv[..., 2][m > 0].mean())
    return {"sat": sat, "hue": hue, "val": val}


def quad_area(q):
    return abs(cv2.contourArea(np.asarray(q, np.float32)))


def containment(inner, outer) -> float:
    """Fraction of `inner` that lies inside `outer`."""
    ai = quad_area(inner)
    if ai <= 0:
        return 0.0
    inter, _ = cv2.intersectConvexConvex(np.asarray(inner, np.float32),
                                         np.asarray(outer, np.float32))
    return float(inter / ai) if inter > 0 else 0.0


def main():
    oracle_items = {i["image"]: i for i in
                    json.loads(ORACLE.read_text(encoding="utf-8"))["items"]}
    oracle = {name: [it["corners_px"][k] for k in ("tl", "tr", "br", "bl")]
              for name, it in oracle_items.items()}
    lane = {r["image"]: r for r in json.loads(LANE.read_text(encoding="utf-8"))["rows"]}
    OUT.mkdir(parents=True, exist_ok=True)

    rows = []
    skipped_oracle = []
    for name, gt in oracle.items():
        # A collapsed/half-placed annotation gives a degenerate homography and garbage colour.
        problem = oracle_reason(oracle_items[name])
        if problem:
            skipped_oracle.append((name, problem))
            continue
        f = ASSETS / name
        img = cv2.imread(str(f), cv2.IMREAD_COLOR)
        if img is None:
            continue
        col = page_colour(img, gt)
        lr = lane.get(name, {})
        final = lr.get("quad") or {}
        pts = final.get("pts") if isinstance(final, dict) else None
        row = {
            "image": name,
            "page": col,
            "detected": lr.get("detected"),
            "prov": lr.get("prov"),
            "iou": lr.get("iou"),
            "area_ratio": lr.get("area_ratio"),
            "wrong_page": lr.get("wrong_page"),
            "merged": lr.get("merged"),
        }
        if pts:
            row["containment"] = round(containment(pts, gt), 3)
            row["area_ratio_calc"] = round(quad_area(pts) / max(quad_area(gt), 1e-6), 3)
        rows.append(row)

    (OUT / "claims.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")

    # ---------------- claim 1: colour ----------------
    lines = ["# H44 - two claims, measured", "",
             "## Claim 1: the detector misses coloured pages", "",
             "Paper colour measured INSIDE the oracle page quad (trimmed 20 %), not the whole frame.",
             "`val` is HSV brightness, so `sat` high + `val` high = tinted paper.", ""]

    lines.append(f"*{len(skipped_oracle)} photos excluded: unusable oracle annotation "
                 f"(see `tooling/oracle_quality.py`).*")
    lines.append("")

    def bucket(r):
        p = r["page"]
        if not p:
            return "unknown"
        # Saturation is the tint; a grey/white page reads < ~45 at this trim in mixed light.
        return "coloured (sat>=60)" if p["sat"] >= 60 else (
            "tinted (40-60)" if p["sat"] >= 40 else "neutral (<40)")

    groups = {}
    for r in rows:
        groups.setdefault(bucket(r), []).append(r)

    lines += ["| paper class | photos | detected | missed | miss rate | mean page sat |",
              "|---|---|---|---|---|---|"]
    for name in ("neutral (<40)", "tinted (40-60)", "coloured (sat>=60)"):
        g = groups.get(name) or []
        if not g:
            continue
        det = sum(1 for r in g if r["detected"])
        miss = len(g) - det
        sats = [r["page"]["sat"] for r in g if r["page"]]
        lines.append(f"| {name} | {len(g)} | {det} | {miss} | "
                     f"{100 * miss / len(g):.0f}% | {np.mean(sats):.1f} |")

    coloured_missed = [r for r in rows
                       if r["page"] and r["page"]["sat"] >= 55 and not r["detected"]]
    lines += ["", f"The most saturated pages that are still MISSED ({len(coloured_missed)}):", ""]
    if coloured_missed:
        lines += ["| photo | page sat | page val |", "|---|---|---|"]
        for r in sorted(coloured_missed, key=lambda x: -x["page"]["sat"])[:15]:
            lines.append(f"| `{r['image']}` | {r['page']['sat']:.0f} | {r['page']['val']:.0f} |")
    else:
        lines.append("_none_")

    # ---------------- claim 2: inner box ----------------
    lines += ["", "## Claim 2: it locks onto a frame/box INSIDE the page", "",
              "Signature: the returned quad is much smaller than the page but sits inside it.",
              "`inside` = fraction of the returned quad that lies within the page quad.", "",
              "| photo | area vs page | inside | IoU | class |", "|---|---|---|---|---|"]
    inner = [r for r in rows
             if r.get("area_ratio_calc") is not None
             and r["area_ratio_calc"] < 0.75
             and r.get("containment", 0) > 0.85]
    for r in sorted(inner, key=lambda x: x["area_ratio_calc"]):
        cls = ("wrong-page" if r["wrong_page"] else
               "merged" if r["merged"] else
               "correct" if r["detected"] else "no-detection")
        lines.append(f"| `{r['image']}` | {r['area_ratio_calc']:.2f}x | "
                     f"{r['containment']:.2f} | {(r['iou'] or 0):.3f} | {cls} |")
    lines.append("")
    lines.append(f"**{len(inner)} photos** show the inner-box signature out of "
                 f"{sum(1 for r in rows if r['detected'])} detected.")

    (OUT / "CLAIMS.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print(f"\n-> {OUT / 'CLAIMS.md'}")


if __name__ == "__main__":
    main()
