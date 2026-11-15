#!/usr/bin/env python3
"""
H48d - SAM reference by POINT prompts instead of one frame-sized box.

Why this exists
---------------
`h36_sam.run_one` prompts SAM with a box covering the whole frame. On owner110 that worked
because the book sat on a table with margin around it. The holdout photos are framed tightly on
the book, so the box prompt covers the entire scene and SAM returns a sparse scribble of bright
paper-ish pixels across the whole frame (measured: largest component bbox = 1400x1046 on a
1400x1050 photo, mask fill only 28 % of that box). Every oracle quad then came back as the image
border.

Point prompts do not have that failure mode: seed many points, keep the masks that actually look
like a sheet, and dedupe. This is the standard SAM automatic-mask-generation loop, restricted to
a sane area band.

Per photo:
  1. one image embedding (the expensive part);
  2. a grid of point prompts, each decoded independently (cheap);
  3. candidate kept when area is 4-85 % of the frame and the mask is sheet-like
     (rectangularity, convexity, paper ring-contrast);
  4. dedupe near-identical masks, rank, and draw the top three on an overlay.

Usage:
  python tooling\\h48_pointgrid.py --set holdout42 --images 20261115_192319,20261115_192403
  python tooling\\h48_pointgrid.py --set holdout42 --all      # every photo, overlays for each
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from h36_sam import SamOracle, mask_contour, min_area_rect, paper_score, quad_from_mask  # noqa: E402

OUT = Path("tooling/out/h48")

MIN_AREA = 0.04
MAX_AREA = 0.85

# Sheet shape bands. These are deliberately loose: a curved page photographed at an angle is
# not a rectangle, and tightening them starts rejecting real pages (owner110 quads go down to
# 0.64 min-area-rect fill on photos that are correct).
RECT_MIN = 0.55
CONV_MIN = 0.80

# Share of the mask that must sit on image edges for it to count as printed matter. Measured on
# this holdout: desk/background masks score at most 0.013, real pages at least 0.056 - a 4x gap,
# so the threshold sits in the middle of empty space rather than on a fitted boundary.
INK_MIN = 0.03

# The owner's spread rule: the facing leaf counts as part of the target when it is at least this
# fraction of the leaf being read. Below it the photo is a deliberate single-page shot.
SECOND_LEAF_MIN = 0.25

# Cap for the adaptive grid retry (see analyse_photo). 12x12 = 144 decodes is still ~20 s, and it
# only runs when the coarse pass found no page at all.
GRID_MAX = 12


def decode_point(oracle: SamOracle, emb, pt_xy, wh) -> np.ndarray:
    """masks + iou for a single positive point prompt, in ORIGINAL image pixels."""
    w, h = wh
    sx, sy = (1024.0 / w, 1024.0 / h) if oracle.resize == "squash" else \
             (1024.0 / max(w, h),) * 2
    feed = {
        "input_points": np.asarray([[[[pt_xy[0] * sx, pt_xy[1] * sy]]]], np.float32),
        "input_labels": np.ones((1, 1, 1), np.int64),
        "input_boxes": np.zeros((1, 0, 4), np.float32),
        "image_embeddings.0": emb[0],
        "image_embeddings.1": emb[1],
        "image_embeddings.2": emb[2],
    }
    iou, masks, _ = oracle.dec.run(["iou_scores", "pred_masks", "object_score_logits"], feed)
    return iou[0][0], masks[0][0]  # (3,) scores, (3, 256, 256) candidates


def fill_holes(mask: np.ndarray) -> np.ndarray:
    """Close interior holes (print, stickers, shadows) before judging sheet shape.

    A page mask that keeps every dark letter as a hole has low rectangularity even when its
    outline is a perfect sheet, so shape is judged on the filled mask.

    The flood fill MUST start outside the mask. Padding by one pixel guarantees that: filling
    from (0, 0) when the mask itself touches (0, 0) marks the mask as "background" and treats
    everything else as a hole, which inflates a thin border sliver into the whole frame and
    makes it pass every shape test.
    """
    m = mask.astype(np.uint8)
    h, w = m.shape
    padded = np.zeros((h + 2, w + 2), np.uint8)
    padded[1:-1, 1:-1] = m
    canvas = np.zeros((h + 4, w + 4), np.uint8)
    cv2.floodFill(padded, canvas, (0, 0), 1)
    holes = (padded == 0).astype(np.uint8)[1:-1, 1:-1]
    return (m.astype(bool) | holes.astype(bool))


def shape_stats(mask: np.ndarray, gray: np.ndarray, edges: np.ndarray) -> dict:
    area = float(mask.sum())
    filled = fill_holes(mask)
    contour = mask_contour(filled)
    if len(contour) < 3:
        return {"area": 0.0}
    rect = min_area_rect(contour)
    if rect is None:
        return {"area": 0.0}
    e0 = np.linalg.norm(rect[1] - rect[0])
    e1 = np.linalg.norm(rect[3] - rect[0])
    rect_area = max(1e-6, e0 * e1)
    hull = cv2.convexHull(contour.astype(np.float32))
    hull_area = max(1e-6, float(cv2.contourArea(hull)))
    flat = filled.astype(bool)
    return {
        "area": area,
        "rect_area": float(rect_area),
        "rectangularity": float(filled.sum()) / rect_area,
        "convexity": float(filled.sum()) / hull_area,
        "aspect": float(max(e0, e1) / max(1e-6, min(e0, e1))),
        "paper": float(paper_score(filled, gray)),
        # Printed matter has edges; a table, a wall and the dark gap beside a book do not. This
        # is what separates "a sheet with text on it" from "the background that happens to be
        # large and rectangular", which shape alone cannot do.
        "ink": float((flat & edges).sum()) / max(1.0, float(flat.sum())),
        "rect_quad": np.asarray(rect, np.float64),
    }


def point_in_quad(px: float, py: float, quad: np.ndarray) -> bool:
    """Even-odd ray cast."""
    inside = False
    n = len(quad)
    for i in range(n):
        x1, y1 = quad[i]
        x2, y2 = quad[(i + 1) % n]
        if (y1 > py) != (y2 > py):
            xin = x1 + (py - y1) * (x2 - x1) / (y2 - y1)
            if px < xin:
                inside = not inside
    return inside


def pad(mask: np.ndarray, grow: int = 8) -> np.ndarray:
    k = np.ones((2 * grow + 1, 2 * grow + 1), np.uint8)
    return cv2.dilate(mask.astype(np.uint8), k).astype(bool)


def iou(a: np.ndarray, b: np.ndarray) -> float:
    inter = np.logical_and(a, b).sum()
    union = np.logical_or(a, b).sum()
    return float(inter / union) if union else 0.0


def _group(cands: list) -> list:
    """Cluster candidates that describe the same sheet.

    SAM returns the left leaf, the right leaf and sometimes the whole spread for the same photo.
    A leaf also comes back as several near-identical masks. Anything that shares > 80 % of its
    area with another candidate, or that is mostly contained in it, is the same sheet.
    """
    n = len(cands)
    parent = list(range(n))

    def find(a: int) -> int:
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    for i in range(n):
        for j in range(i + 1, n):
            a, b = cands[i]["mask"], cands[j]["mask"]
            inter = int(np.logical_and(a, b).sum())
            small = min(int(a.sum()), int(b.sum()))
            if small and inter / small > 0.80:
                ra, rb = find(i), find(j)
                if ra != rb:
                    parent[rb] = ra

    groups: dict[int, list[int]] = {}
    for i in range(n):
        groups.setdefault(find(i), []).append(i)
    return list(groups.values())


def _describe(mask: np.ndarray, gray: np.ndarray, edges: np.ndarray) -> dict:
    """Shape/ink stats for an arbitrary mask (used after unions)."""
    st = shape_stats(mask, gray, edges)
    if not st.get("area"):
        return {"area": 0.0}
    st["mask"] = mask
    st["frac"] = float(mask.sum()) / float(mask.size)
    return st


def _truncated(mask: np.ndarray, tol_frac: float = 0.012) -> bool:
    """True when the sheet runs into the picture border (it continues outside the frame).

    A border band counts as reached only when it is substantially covered, so one stray
    segmenter pixel does not mark a complete page as truncated.
    """
    h, w = mask.shape
    tol = max(2, int(tol_frac * min(w, h)))
    return bool(mask[:tol, :].mean() > 0.25 or mask[-tol:, :].mean() > 0.25
                or mask[:, :tol].mean() > 0.25 or mask[:, -tol:].mean() > 0.25)


def _sheet_axes(sheet: dict):
    """Unit vectors (width, height) of a sheet's min-area rect, in image coordinates."""
    rect = sheet.get("rect_quad")
    if rect is None or len(rect) != 4:
        return (1.0, 0.0), (0.0, 1.0)
    e0 = rect[1] - rect[0]
    e1 = rect[3] - rect[0]
    n0 = float(np.hypot(*e0))
    n1 = float(np.hypot(*e1))
    if n0 < 1e-6 or n1 < 1e-6:
        return (1.0, 0.0), (0.0, 1.0)
    u = (float(e0[0]) / n0, float(e0[1]) / n0)
    v = (float(e1[0]) / n1, float(e1[1]) / n1)
    # u must be the SHORT axis (the page's width): facing leaves are displaced along it.
    if n0 > n1:
        u, v = v, u
    return u, v


def build_target(page: list, gray: np.ndarray, edges: np.ndarray, w: int, h: int) -> dict:
    """Turn page-like candidates into the single mask the detector quad should match.

    Owner's rule (2026-11-15): both leaves centred -> one spread; single page only when the other
    leaf is under ~25 % visible. Mechanically:

      1. group candidates into distinct sheets, describing each by its union;
      2. the largest sheet is the base (that is the page the user is looking at);
      3. a second sheet joins it only if it is a *facing leaf* - at least 25 % of the base area,
         touching it, and the union still looks like one sheet (convex, decent rect fill).

    Step 3's shape test is what stops two books lying side by side from being mistaken for a
    spread: there is a visible gap, so the union is concave and fails.
    """
    groups = _group(page)
    sheets = []
    for idx in groups:
        m = np.zeros((h, w), bool)
        for i in idx:
            m |= page[i]["mask"]
        st = _describe(m, gray, edges)
        if not st.get("area"):
            continue
        st["members"] = len(idx)
        sheets.append(st)
    if not sheets:
        return {"target": None, "spread": False, "second": 0.0, "sheets": 0}
    sheets.sort(key=lambda s: -s["area"])
    base = sheets[0]

    def _gap(a: np.ndarray, b: np.ndarray, grow: int) -> bool:
        k = np.ones((2 * grow + 1, 2 * grow + 1), np.uint8)
        return bool(np.logical_and(cv2.dilate(a.astype(np.uint8), k).astype(bool), b).any())

    best = None
    base_u, base_v = _sheet_axes(base)
    for other in sheets[1:]:
        ratio = float(other["area"]) / max(1.0, float(base["area"]))
        if ratio < SECOND_LEAF_MIN or ratio > 3.0:
            continue
        if not _gap(base["mask"], other["mask"], max(2, int(0.02 * min(w, h)))):
            continue
        # A sheet that runs off the picture border is only PARTLY seen, so it is not something the
        # user was trying to capture - the owner's "<25 % visible" case. Measured on this holdout:
        # `192327`, `192333` and `192334` each return a second sheet with trunc=1, and merging them
        # is what dragged a correct front-page quad down to recall 0.52.
        if _truncated(other["mask"]):
            continue
        merged = np.logical_or(base["mask"], other["mask"])
        st = _describe(merged, gray, edges)
        if not st.get("area"):
            continue
        # One sheet, not two: the union has to stay convex with a sane rect fill.
        if st["convexity"] < CONV_MIN or st["rectangularity"] < RECT_MIN:
            continue
        if best is None or st["area"] > best[0]:
            best = (st["area"], st, ratio)

    if best is None:
        return {"target": base["mask"], "spread": False, "second": 0.0,
                "sheets": len(sheets)}
    return {"target": best[1]["mask"], "spread": True,
            "second": round(float(best[2]), 3), "sheets": len(sheets)}


def _seed_candidates(oracle: SamOracle, emb, wh, w: int, h: int, grid: int,
                     gray: np.ndarray, edges: np.ndarray, cands: list) -> None:
    """Run a `grid x grid` point-prompt sweep and append novel sheet-like masks to [cands]."""
    for gy in np.linspace(0.12, 0.88, grid):
        for gx in np.linspace(0.12, 0.88, grid):
            scores, masks = decode_point(oracle, emb, (gx * w, gy * h), wh)
            k = int(np.argmax(scores))
            m = oracle._mask_to_image(masks[k] > 0.0, wh)
            frac = float(m.sum()) / (w * h)
            if not (MIN_AREA <= frac <= MAX_AREA):
                continue
            st = shape_stats(m, gray, edges)
            if not st.get("area"):
                continue
            st.update({"score": float(scores[k]), "frac": frac})
            # Dedupe by keeping the BEST description of a region, not the first one seen.
            #
            # Keeping the first was a real bug: a coarse grid can return the page with a ragged
            # outline (measured on `20261115_192328`: grid 6 -> same page at rectangularity 0.69 /
            # convexity 0.78, i.e. rejected as not sheet-like), and a finer grid then returns the
            # same region cleanly (0.88 / 0.96). That clean mask is >0.80 IoU with the ragged one,
            # so first-wins deleted exactly the candidate the retry existed to find.
            dup = next((c for c in cands if iou(m, c["mask"]) > 0.80), None)
            if dup is not None:
                if _shape_quality(st) > _shape_quality(dup):
                    dup.update(st)
                    dup["mask"] = m
                continue
            st["mask"] = m
            cands.append(st)


def _shape_quality(c: dict) -> float:
    """How much a candidate looks like a printed sheet; used only to pick between duplicates."""
    return float(c.get("convexity", 0.0)) + float(c.get("rectangularity", 0.0)) \
        + min(1.0, float(c.get("ink", 0.0)) * 4.0)


def _page_candidates(cands: list) -> list:
    keep = [c for c in cands
            if c["rectangularity"] >= RECT_MIN and c["convexity"] >= CONV_MIN]
    return [c for c in keep if c["ink"] >= INK_MIN]


def analyse_photo(oracle: SamOracle, path: Path, grid: int, overlay: bool,
                  debug: bool = False, save_masks: bool = False) -> dict:
    pil = Image.open(path)
    w, h = pil.size
    gray = np.asarray(pil.convert("L"), dtype=np.float32)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    edges = cv2.Canny(blurred.astype(np.uint8), 40, 120) > 0
    edges = cv2.dilate(edges.astype(np.uint8), np.ones((3, 3), np.uint8)).astype(bool)
    emb, wh = oracle.encode(pil)

    cands: list = []
    _seed_candidates(oracle, emb, wh, w, h, grid, gray, edges, cands)
    page = _page_candidates(cands)

    # ADAPTIVE RETRY. A coarse point grid can simply never land a seed on the page: measured on
    # `20261115_192328`, 6x6 returned a 262 px desk strip (ink 0.004) and nothing else, while 9x9
    # found the real page (area 0.53, ink 0.174). The image embedding is already computed, so the
    # retry costs decode time only, and it runs solely when the first pass found no page at all -
    # photos that already work are untouched.
    refined_from = grid
    if not page and grid < GRID_MAX:
        for g2 in (grid + 3, grid + 6):
            if page or g2 > GRID_MAX:
                break
            _seed_candidates(oracle, emb, wh, w, h, g2, gray, edges, cands)
            page = _page_candidates(cands)
            if page:
                refined_from = g2

    cx, cy = w / 2.0, h / 2.0
    tol = 0.015 * min(w, h)
    for c in cands:
        q = c["rect_quad"]
        c["centre"] = point_in_quad(cx, cy, q)
        c["inside"] = bool(np.all(q[:, 0] >= -tol) and np.all(q[:, 0] <= w - 1 + tol)
                           and np.all(q[:, 1] >= -tol) and np.all(q[:, 1] <= h - 1 + tol))

    # Pick the PAGE, not "the big rectangle near the middle".
    #
    # The first version of this preferred whatever contained the frame centre. On the holdout
    # that is often the table: the book sits low-left, the frame centre lands on empty desk, and
    # a desk is large, rectangular and convex - so it won every shape test. Measured consequence:
    # 8 of 42 masks were desk, and scoring a detector quad against the desk is meaningless.
    #
    # Printed matter is the discriminator that shape cannot supply. `ink` is the share of the
    # mask sitting on image edges; a page full of text has it, a desk and a wall do not. Colour
    # is deliberately not used - one holdout page is a green workbook cover, which a
    # paper-brightness test rejected outright in an earlier pass.
    #
    # No page-like candidate now emits NO mask. Falling back to "the biggest rectangular thing"
    # was measured to hand back the desk on `20261115_192328`. A missing reference costs one
    # photo; a desk reference silently scores the detector at 0.00 recall, which is worse than
    # useless because it looks like data.
    page.sort(key=lambda c: -c["area"])

    built = build_target(page, gray, edges, w, h)
    target = built["target"]

    quad, meta = (None, {})
    if target is not None:
        quad, meta = quad_from_mask(target, pil)
        if save_masks:
            (OUT / "masks").mkdir(parents=True, exist_ok=True)
            np.save(OUT / "masks" / f"{path.stem}.npy", target)

    if overlay:
        img = cv2.imread(str(path), cv2.IMREAD_COLOR)
        colours = [(0, 220, 0), (0, 160, 255), (255, 120, 0)]
        for c, col in zip(page[:3], colours):
            q, _ = quad_from_mask(c["mask"], pil)
            if q is None:
                continue
            cv2.polylines(img, [np.array([[int(x), int(y)] for x, y in q], np.int32)],
                          True, col, 3)
        if quad is not None:
            cv2.polylines(img, [np.array([[int(x), int(y)] for x, y in quad], np.int32)],
                          True, (0, 0, 255), 2)
        cv2.putText(img, f"{path.stem}  cands={len(cands)} page={len(page)} "
                         f"sheets={built['sheets']} spread={built['spread']}",
                    (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2, cv2.LINE_AA)
        OUT.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(OUT / f"pg_{path.stem}.png"), img)

    fields = ("frac", "aspect", "rectangularity", "convexity", "paper", "ink", "score")
    top = [{k: c.get(k) for k in fields} for c in page[:5]]
    rec = {"image": path.name, "width": w, "height": h, "candidates": len(cands),
           "page_like": len(page), "top": top,
           "grid_used": refined_from,
           "spread": bool(built["spread"]), "second_leaf": built["second"],
           "sheets": built["sheets"],
           "quad": [[round(float(x), 1), round(float(y), 1)] for x, y in quad]
           if quad is not None else None, "meta": meta}
    if debug:
        rec["all"] = [{k: c.get(k) for k in fields + ("centre", "inside")} for c in cands]
    return rec


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", default="holdout42")
    ap.add_argument("--images", default="")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--grid", type=int, default=6)
    ap.add_argument("--no-overlay", action="store_true")
    ap.add_argument("--debug", action="store_true", help="print every candidate, not just the pool")
    ap.add_argument("--save-masks", action="store_true", help="write the chosen page mask per photo")
    ap.add_argument("--out", default="pointgrid.json")
    args = ap.parse_args()

    root = Path("app/src/androidTest/assets") / args.set
    if args.all:
        names = sorted(p.name for p in root.glob("*.jpg"))
    else:
        names = [n.strip() if n.strip().endswith(".jpg") else n.strip() + ".jpg"
                 for n in args.images.split(",") if n.strip()]

    oracle = SamOracle()
    print(f"providers: {oracle.providers}, grid {args.grid}x{args.grid}")
    rows = []
    for i, name in enumerate(names, 1):
        t0 = time.time()
        rec = analyse_photo(oracle, root / name, args.grid, not args.no_overlay, args.debug,
                            args.save_masks)
        rec["ms"] = int((time.time() - t0) * 1000)
        rows.append(rec)
        t = rec["top"][0] if rec["top"] else {}
        print(f"[{i}/{len(names)}] {name}: cands={rec['candidates']} page={rec['page_like']} "
              f"sheets={rec['sheets']} spread={rec['spread']} grid={rec['grid_used']} "
              f"best area={t.get('frac') or 0:.2f} rect={t.get('rectangularity') or 0:.2f} "
              f"ink={t.get('ink') or 0:.3f} ({rec['ms']} ms)", flush=True)

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / args.out).write_text(json.dumps(rows, indent=1), encoding="utf-8")
    print(f"-> {OUT / args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
