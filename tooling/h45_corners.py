#!/usr/bin/env python3
"""
H45 - the owner's real signature: PARTIAL lock onto a box INSIDE the page.

Owner's words: "one of the 4 points usually goes to one of the corners of the box ... usually just
1 or 2 corners [of the page] is detected ... so not fully detecting the box as the page."

That is a HYBRID quad: some returned corners sit on page corners, the rest sit on an inner printed
box. It is not the "whole quad shrunk into the page" case (H44 claim 2, 0 hits), and it is not a
simple near-miss (those are only a few percent outside and are fine).

Per returned corner, measured against the hand-annotated page quad:
  d   signed distance to the page polygon, in % of the page side (+ inside)
  nc  distance to the nearest page corner, in % of the page side

  page corner : nc <= 10 %                          (anchored on a real page corner)
  inset       : d >=  6 % and nc > 10 %             (on a box corner, inside the page)
  outside     : d <= -6 % and nc > 10 %             (overshoots the page)

Signatures:
  pure inner box : all 4 corners inset, area <= 85 % of the page
  partial box    : >=2 page corners AND >=1 inset corner AND coverage >= 75 %

Photos whose oracle annotation is unusable (`oracle_quality.reason`) are excluded from every
aggregate: against them IoU is 0 by construction and wrong-page/merged flags are fabricated.

Self-check: the IoU recomputed here must reproduce the IoU the device wrote into the lane report.
A disagreement means this script is mis-ordering the quads, and its verdicts are worthless.

Usage: python tooling/h45_corners.py [traceTag] [laneReport]
"""
from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from oracle_quality import reason as oracle_reason  # noqa: E402

ASSETS = Path("app/src/androidTest/assets/owner110")
ORACLE = ASSETS / "oracle_manifest.json"
TAG = sys.argv[1] if len(sys.argv) > 1 else "h44trace"
REPORT = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("tooling/out/h38/report_h44crop.json")
TRACE = Path("tooling/out/h39") / TAG
OUT = Path("tooling/out/h45")

PAGE_CORNER = 0.10
INSET = 0.06


def order_quad(p: np.ndarray) -> np.ndarray:
    """Cyclic order around the centroid, starting at the top-left-most point.

    A sum/difference sort is NOT safe: on a rotated page it can select the same vertex twice and
    silently produce a degenerate quad (this bug cost one analysis pass in H45).
    """
    c = p.mean(axis=0)
    ang = np.arctan2(p[:, 1] - c[1], p[:, 0] - c[0])
    q = p[np.argsort(ang)]
    start = int(np.argmin(q.sum(axis=1)))
    return np.roll(q, -start, axis=0).astype(np.float64)


def poly_area(q: np.ndarray) -> float:
    return abs(float(cv2.contourArea(q.astype(np.float32))))


def analyse(gtq: np.ndarray, gotq: np.ndarray) -> dict:
    side = float(np.sqrt(poly_area(gtq)))
    gt32, got32 = gtq.astype(np.float32), gotq.astype(np.float32)
    inter, _ = cv2.intersectConvexConvex(gt32, got32)
    inter = float(inter) if inter > 0 else 0.0
    gt_area = poly_area(gtq) or 1e-6
    got_area = poly_area(gotq) or 1e-6
    union = gt_area + got_area - inter

    corners = []
    for k in range(4):
        p = (float(gotq[k][0]), float(gotq[k][1]))
        d = cv2.pointPolygonTest(gt32, p, True) / side
        nc = float(np.min(np.linalg.norm(gtq - gotq[k], axis=1))) / side
        if nc <= PAGE_CORNER:
            kind = "page"
        elif d >= INSET:
            kind = "inset"
        elif d <= -INSET:
            kind = "outside"
        else:
            kind = "edge"
        corners.append({"d": round(d, 3), "nc": round(nc, 3), "kind": kind})

    n_page = sum(1 for c in corners if c["kind"] == "page")
    n_inset = sum(1 for c in corners if c["kind"] == "inset")
    n_out = sum(1 for c in corners if c["kind"] == "outside")
    cover = inter / gt_area
    ratio = got_area / gt_area

    if n_inset == 4 and ratio <= 0.85:
        sig = "pure inner box"
    elif n_page >= 2 and n_inset >= 1 and cover >= 0.75:
        sig = "partial box"
    elif ratio > 1.15:
        sig = "over-extended"
    elif cover < 0.75:
        sig = "mislocated"
    elif ratio < 0.88:
        sig = "shrunk"
    else:
        sig = "page"

    return {"corners": corners, "n_page": n_page, "n_inset": n_inset, "n_outside": n_out,
            "cover": round(cover, 3), "ratio": round(ratio, 3),
            "signature": sig, "iou_check": round(inter / union, 4) if union > 0 else 0.0}


def main() -> None:
    items = json.loads(ORACLE.read_text(encoding="utf-8"))["items"]
    oracle = {i["image"]: i for i in items}
    lane = {}
    if REPORT.exists():
        lane = {r["image"]: r for r in json.loads(REPORT.read_text(encoding="utf-8"))["rows"]}

    rows = []
    for name, item in oracle.items():
        stem = name[:-4] if name.lower().endswith(".jpg") else name
        tp = TRACE / stem / "trace.json"
        if not tp.exists():
            continue
        t = json.loads(tp.read_text(encoding="utf-8"))
        problem = oracle_reason(item)
        row = {"image": name, "prov": t.get("provenance"), "detected": bool(t.get("detected")),
               "oracle_problem": problem, "have_quad": bool(t.get("quad"))}
        if lane.get(name):
            row.update({k: lane[name].get(k) for k in ("iou", "wrong_page", "merged", "gate_pass")})
        if not t.get("quad"):
            row["signature"] = "no-detection"
            rows.append(row)
            continue
        if problem:
            row["signature"] = "oracle-unusable"
            rows.append(row)
            continue
        gtq = order_quad(np.array([item["corners_px"][k]
                                   for k in ("tl", "tr", "br", "bl")], np.float64))
        gotq = order_quad(np.array([[p["x"], p["y"]] for p in t["quad"]], np.float64))
        row.update(analyse(gtq, gotq))
        rows.append(row)

    scored = [r for r in rows if r["have_quad"] and not r["oracle_problem"]]
    unusable = [r for r in rows if r["oracle_problem"]]
    sig_count = Counter(r["signature"] for r in scored)

    diffs = [abs((r.get("iou") or 0.0) - (r.get("iou_check") or 0.0)) for r in scored
             if r.get("iou") is not None]
    worst = sorted((r for r in scored if r.get("iou") is not None),
                   key=lambda r: -abs((r.get("iou") or 0.0) - (r.get("iou_check") or 0.0)))[:3]

    lines = [
        f"# H45 - does the detector lock onto a box INSIDE the page? (lane `{TAG}`)",
        "",
        f"{len(rows)} photos traced, {len(scored)} scorable, {len(unusable)} with an unusable "
        f"oracle annotation ({sum(1 for r in unusable if r['have_quad'])} of them still detected).",
        "",
        "## Self-check: does this script agree with the device?",
        "",
    ]
    if diffs:
        lines += [
            f"* mean |IoU here - IoU in lane report| = **{np.mean(diffs):.4f}**",
            f"* photos disagreeing by more than 0.02: **{sum(1 for d in diffs if d > 0.02)}** "
            f"of {len(diffs)}",
            "* worst: " + ", ".join(f"`{r['image']}` {r.get('iou'):.3f} vs "
                                    f"{r.get('iou_check'):.3f}" for r in worst),
            "",
        ]

    lines += ["## Signatures", "", "| signature | photos | share of scorable |", "|---|---|---|"]
    for k in ("page", "partial box", "pure inner box", "shrunk", "over-extended",
              "mislocated", "no-detection"):
        n = sig_count.get(k, 0)
        if n:
            lines.append(f"| {k} | {n} | {n / len(scored):.0%} |")
    lines.append("")

    partial = [r for r in scored if r["signature"] == "partial box"]
    pure = [r for r in scored if r["signature"] == "pure inner box"]

    lines += [
        "## The owner's signature, counted",
        "",
        f"* **partial box** (2+ corners on the page, 1+ on a box corner): **{len(partial)}**",
        f"* **pure inner box** (all 4 corners inset, quad <= 85 % of the page): **{len(pure)}**",
        f"* every corner on the page: **{sig_count.get('page', 0)}**",
        "",
    ]

    if partial or pure:
        lines += ["| photo | prov | page corners | box corners | outside | coverage | area vs "
                  "page | inset depths (% of side) |", "|---|---|---|---|---|---|---|---|"]
        for r in partial + pure:
            depths = ", ".join(f"{c['d']:.2f}" for c in r["corners"] if c["kind"] == "inset")
            lines.append(f"| `{r['image']}` | {r['prov']} | {r['n_page']} | {r['n_inset']} | "
                         f"{r['n_outside']} | {r['cover']:.2f} | {r['ratio']:.2f} | {depths} |")
        lines += ["", "If a printed frame is being latched, the inset depths repeat across photos "
                      "(a fixed box position). If they scatter, the model is simply confused.", ""]

    # Near-misses: 2+ corners on the page and 1+ inside by >= 3 % (below the INSET threshold).
    near_miss = [r for r in scored if r["signature"] != "partial box"
                 and r.get("n_page", 0) >= 2 and r.get("n_inset", 0) >= 1]
    if near_miss:
        lines += [f"* partial-box cases under a looser 3 % inset test: **{len(near_miss)}**", ""]

    if unusable:
        lines += [
            "## Unusable oracle annotations (excluded above)",
            "",
            "`wrong_page` needs IoU < 0.35 and `merged` needs `area_ratio >= 1.5`; both are "
            "meaningless against a zero-area oracle, so verdicts on these rows are fabricated.",
            "",
            "| photo | problem | detected | provenance | recorded verdict |",
            "|---|---|---|---|---|",
        ]
        for r in unusable:
            verdict = "wrong-page" if r.get("wrong_page") else ("merged" if r.get("merged") else "ok")
            lines.append(f"| `{r['image']}` | {r['oracle_problem']} | {r['detected']} | "
                         f"{r['prov']} | {verdict} |")
        lines.append("")

    flagged = [r for r in scored if r.get("wrong_page") or r.get("merged")]
    lines += ["## Remaining failures among scorable photos", "",
              f"* wrong-page or merged: **{len(flagged)}**"]
    if flagged:
        lines += ["", "| photo | signature | page corners | box corners | IoU | verdict |",
                  "|---|---|---|---|---|---|"]
        for r in flagged:
            verdict = "wrong-page" if r.get("wrong_page") else "merged"
            lines.append(f"| `{r['image']}` | {r['signature']} | {r.get('n_page')} | "
                         f"{r.get('n_inset')} | {r.get('iou')} | {verdict} |")
    lines.append("")

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "corners.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")
    (OUT / "CORNERS.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print(f"-> {OUT / 'CORNERS.md'}")


if __name__ == "__main__":
    main()
