#!/usr/bin/env python3
"""
H40 — INK EVIDENCE REPORT: a small, GitHub-friendly page that shows, per photo,

    AI input (corner tensor) | AI corner heat         | final quad
    rectified page           | INK map (gate)         | glyph boxes

plus the measured numbers, and — the point of the page — the SAME photo re-rectified with the
oracle (ground-truth) quad so you can compare the ink map of the right page against the ink map
of the quad the detector actually returned.

Run it after `tooling/h40_ink_probe.py` (it consumes `probe.json`):

    python tooling/h40_ink_probe.py <h39-dump> --out <probe-dir>
    python tooling/h40_ink_report.py <h39-dump> <probe-dir> --out docs/evidence/h39-ink

Outputs:
    <out>/index.html      self-contained page (images referenced relatively)
    <out>/img/*.jpg       one card per photo + the oracle-quad ink comparison
    <out>/README.md       plain-text legend + the measured verdict
"""

from __future__ import annotations

import html
import json
import sys
from pathlib import Path

import cv2
import numpy as np

TILE_H = 240
TILE_W = 320
PAD = 8
LABEL_H = 20
HEADER_H = 44
BG = (24, 24, 28)
FG = (238, 238, 242)
DIM = (158, 158, 170)
ACCENT = (90, 220, 130)

CARD_ROWS = [
    [("corner_input.png", "AI input (model tensor)"),
     ("heat_montage.png", "AI corner heat: TL TR / BR BL"),
     ("final_quad.png", "final quad + provenance")],
    [("page_interior.png", "rectified interior (gate input)"),
     ("page_ink.png", "INK map (adaptive threshold)"),
     ("page_glyphs.png", "glyph components")],
]

UPDATE_RULE = """# H40 — the ink evidence, and what it says

What is in this folder
----------------------
`index.html` shows, for 12 owner photos from the H39 trace dump, the detector's own maps:
the tensor handed to the corner model, the four AI corner heat maps, the final quad, and the
gate's rectified page + INK map + glyph boxes.

**The INK map is not produced by the AI.** It is `cv2.adaptiveThreshold(MEAN_C, block 25, C 10)`
on the rectified page (`PagePlausibility.analyseRectifiedInterior`). The AI only produces the
four corner heat maps; the INK map is the *evidence* the gate uses to decide whether the quad
was a page. So the question "is the AI ink map the problem?" is really "is the gate's ink
evidence bad, or is the quad it was given bad?".

The experiment
--------------
For every photo the same image was rectified twice: once with the quad the detector returned,
once with the oracle (ground-truth) quad. Same photo, same INK extractor, same 300 px/7 % ROI.

Measured result (12 photos, `probe.json`)
-----------------------------------------
* **No-detection photos** (`20261024_185245`, `20261024_185748`): no quad at all, but the
  oracle-quad INK map is clean (64 and 116 glyphs, text 0.86 / 0.93). The ink evidence would
  have been fine — detection never produced a quad.
* **Merged / wrong-page photos** (`20261025_130821`, `20261025_131201`, `20261025_131249`,
  `20261025_130818`): the INK map of the *wrong* quad scores text 0.71–0.95, i.e. as high as —
  sometimes higher than — the *correct* quad (0.75–0.93). The ink map does not get "worse" when
  the quad is wrong; it just describes the wrong region. The gate therefore cannot tell.
* **Good detections**: ink and quad agree; nothing to fix.

Whole-set result (116 owner photos, `../quad-vs-ink/`)
------------------------------------------------------
* Mean text-score change when the bad quads are replaced by the oracle quad: **-0.183** (the
  wrong region scores *higher*). The text score saturates at 0.85–0.95 on any textured region.
* No INK-side feature separates bad from good quads: `border_ink` AUC 0.462, `ink_ratio` 0.529,
  paper-mode fraction 0.431 (`quad-vs-ink/SUMMARY.md`).
* All 34 bad quads were `CLASSICAL_FALLBACK`; all 31 `CORNER_MODEL` quads were correct.

Conclusion: the ink map is not the root cause. On curved/dyed pages the failure is upstream
(no quad, or a quad merged with the table/background), and the INK score is too easily satisfied
by texture that is not text, so the merged quad is accepted instead of rejected.

How to read a card
------------------
* `heat_*` near-black on a real page -> the corner model did not see corners (input/domain
  problem).
* bright heat blob away from the page corner -> the model is looking at the wrong thing.
* INK map good but the quad is visibly not the page -> postprocess/rank/gate problem; the ink
  evidence cannot save it.
* INK map empty/noisy while the page clearly has text -> genuinely an INK-map problem (not
  observed in this sample).

Regenerate
----------
    tooling\\h39_dump.cmd <Tag> [stems|all]                      # phone lane, writes the trace
    python tooling\\h40_ink_probe.py  tooling\\out\\h39\\<Tag>    # quad-vs-oracle ink probe
    python tooling\\h40_ink_report.py tooling\\out\\h39\\<Tag> tooling\\out\\h40_ink_probe
"""


def load_tile(path: Path, height: int = TILE_H):
    if not path.exists():
        return None
    im = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if im is None:
        return None
    scale = min(TILE_W / im.shape[1], height / im.shape[0])
    resized = cv2.resize(im, (max(1, round(im.shape[1] * scale)), max(1, round(im.shape[0] * scale))),
                         interpolation=cv2.INTER_AREA)
    canvas = np.full((height, TILE_W, 3), BG, dtype=np.uint8)
    y0 = (height - resized.shape[0]) // 2
    x0 = (TILE_W - resized.shape[1]) // 2
    canvas[y0:y0 + resized.shape[0], x0:x0 + resized.shape[1]] = resized
    return canvas


def build_card(scene: Path, trace: dict, probe: dict, destination: Path) -> Path:
    tiles = []
    for row in CARD_ROWS:
        built = [(load_tile(scene / name), caption) for name, caption in row]
        tiles.append(built)

    col_w = [TILE_W] * 3
    width = PAD + sum(w + PAD for w in col_w)
    height = HEADER_H + len(tiles) * (TILE_H + LABEL_H + PAD) + PAD
    card = np.full((height, width, 3), BG, dtype=np.uint8)

    name = scene.name
    prov = trace.get("provenance", "-")
    gate = trace.get("gate_passed")
    paper = (trace.get("gate") or {}).get("paper", trace.get("paper_score"))
    text = (trace.get("gate") or {}).get("text", trace.get("text_score"))
    glyphs = (trace.get("gate") or {}).get("glyphs", trace.get("glyphs"))
    title = f"{name}  detected={trace.get('detected')}  prov={prov}  gate={gate}"
    sub = f"paper={fmt(paper)}  text={fmt(text)}  glyphs={glyphs}  detect_ms={fmt(trace.get('detect_ms'), 1)}"
    cv2.putText(card, title, (PAD, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.5, ACCENT, 1, cv2.LINE_AA)
    cv2.putText(card, sub, (PAD, 36), cv2.FONT_HERSHEY_SIMPLEX, 0.42, DIM, 1, cv2.LINE_AA)

    y = HEADER_H
    for row in tiles:
        x = PAD
        for c, (im, caption) in enumerate(row):
            if im is not None:
                card[y:y + TILE_H, x:x + im.shape[1]] = im
                cv2.putText(card, caption, (x, y + TILE_H + 14),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.36, FG, 1, cv2.LINE_AA)
            else:
                cv2.rectangle(card, (x, y), (x + col_w[c] - 1, y + TILE_H - 1), (70, 70, 78), 1)
                cv2.putText(card, f"missing: {caption}", (x + 6, y + 18),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.34, (120, 120, 128), 1, cv2.LINE_AA)
            x += col_w[c] + PAD
        y += TILE_H + LABEL_H + PAD

    destination.parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(destination), card, [cv2.IMWRITE_JPEG_QUALITY, 82])
    return destination


def build_oracle_panel(scene_name: str, probe_row: dict, asset_dir: Path, destination: Path) -> Path | None:
    """Right side of the comparison: the same photo rectified with the oracle quad."""
    src = asset_dir / (scene_name + ".jpg")
    src_oracle = probe_row.get("sources", {}).get("oracle")
    if not src.exists() or not src_oracle:
        return None
    img = cv2.imread(str(src), cv2.IMREAD_COLOR)
    if img is None:
        return None
    rect, roi = rectify_like_gate(img, src_oracle["quad"])
    ink = cv2.adaptiveThreshold(roi, 255, cv2.ADAPTIVE_THRESH_MEAN_C,
                                cv2.THRESH_BINARY_INV, 25, 10.0)
    left = cv2.cvtColor(roi, cv2.COLOR_GRAY2BGR)
    right = cv2.cvtColor(ink, cv2.COLOR_GRAY2BGR)
    tile = np.hstack([left, right])
    scale = TILE_H / tile.shape[0]
    tile = cv2.resize(tile, None, fx=scale, fy=scale, interpolation=cv2.INTER_NEAREST)
    cv2.putText(tile, "ORACLE quad: interior + INK", (4, 14),
                cv2.FONT_HERSHEY_SIMPLEX, 0.4, ACCENT, 1, cv2.LINE_AA)
    cv2.imwrite(str(destination), tile, [cv2.IMWRITE_JPEG_QUALITY, 85])
    return destination


def rectify_like_gate(img, quad):
    rect_long, margin = 300, 0.07
    tl, tr, br, bl = [np.array(p, dtype=np.float64) for p in quad]
    page_w = (np.linalg.norm(tr - tl) + np.linalg.norm(br - bl)) / 2.0
    page_h = (np.linalg.norm(bl - tl) + np.linalg.norm(br - tr)) / 2.0
    s = rect_long / max(page_w, page_h)
    pw = int(min(max(round(page_w * s), 16), rect_long))
    ph = int(min(max(round(page_h * s), 16), rect_long))
    src = np.array([tl, tr, br, bl], dtype=np.float32)
    dst = np.array([[0, 0], [pw - 1, 0], [pw - 1, ph - 1], [0, ph - 1]], dtype=np.float32)
    m = cv2.getPerspectiveTransform(src, dst)
    rect = cv2.warpPerspective(img, m, (pw, ph), flags=cv2.INTER_LINEAR,
                               borderMode=cv2.BORDER_REPLICATE)
    gray = cv2.cvtColor(rect, cv2.COLOR_BGR2GRAY)
    iy0, ix0 = int(round(ph * margin)), int(round(pw * margin))
    iy1, ix1 = int(round(ph - ph * margin)), int(round(pw - pw * margin))
    iy0, ix0 = min(max(iy0, 0), ph - 8), min(max(ix0, 0), pw - 8)
    iy1, ix1 = min(max(iy1, iy0 + 8), ph), min(max(ix1, ix0 + 8), pw)
    return rect, gray[iy0:iy1, ix0:ix1]


def fmt(v, digits: int = 3) -> str:
    if v is None:
        return "-"
    try:
        return f"{float(v):.{digits}f}"
    except (TypeError, ValueError):
        return str(v)


def verdict(det: dict | None, orc: dict | None) -> str:
    if not det:
        return "no quad — detection failed (ink map would be fine: see oracle panel)"
    if not orc:
        return "no oracle quad for this photo"
    gain = orc["text"] - det["text"]
    if gain > 0.10:
        return "quad is wrong — the correct quad gives a much better ink map"
    if gain < -0.02:
        return ("ink score is not discriminative — the WRONG region scores higher than "
                "the correct page (saturation), so the gate cannot reject this quad")
    return "ink score is the same for right and wrong quad — saturated, not discriminative"


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(__doc__)
        return 2
    dump = Path(argv[1]).resolve()
    probe_dir = Path(argv[2]).resolve()
    out = Path(argv[4]).resolve() if len(argv) > 4 and argv[3] == "--out" else dump.parent / "h40_ink_report"
    img_dir = out / "img"
    img_dir.mkdir(parents=True, exist_ok=True)

    probe = json.loads((probe_dir / "probe.json").read_text(encoding="utf-8"))
    probe_by_image = {r["image"]: r for r in probe}
    asset_dir = Path("app/src/androidTest/assets/owner110")

    scenes = sorted(p for p in dump.iterdir() if p.is_dir())
    cards = []
    for scene in scenes:
        tpath = scene / "trace.json"
        if not tpath.exists():
            continue
        trace = json.loads(tpath.read_text(encoding="utf-8"))
        row = probe_by_image.get(scene.name + ".jpg", {})
        card = build_card(scene, trace, row, img_dir / f"{scene.name}_card.jpg")
        oracle_img = build_oracle_panel(scene.name, row, asset_dir, img_dir / f"{scene.name}_oracle.jpg")

        det = (row.get("sources", {}).get("detected") or {}).get("baseline")
        orc = (row.get("sources", {}).get("oracle") or {}).get("baseline")
        det_paper = (row.get("sources", {}).get("detected") or {}).get("paper", {})
        cards.append(f"""
<section class="card">
  <h2>{html.escape(scene.name)}</h2>
  <p class="meta">detected <b>{trace.get('detected')}</b> &middot;
     provenance <b>{html.escape(str(trace.get('provenance', '-')))}</b> &middot;
     gate <b>{trace.get('gate_passed')}</b> (score {fmt(trace.get('gate_score'))}) &middot;
     paper {fmt((trace.get('gate') or {}).get('paper', trace.get('paper_score')))} &middot;
     text {fmt((trace.get('gate') or {}).get('text', trace.get('text_score')))} &middot;
     ink_frac {fmt((trace.get('gate') or {}).get('ink_frac'))} &middot;
     glyphs {(trace.get('gate') or {}).get('glyphs', trace.get('glyphs'))} &middot;
     detect {fmt(trace.get('detect_ms'), 1)} ms</p>
  <div class="pair">
    <figure><a href="img/{scene.name}_card.jpg"><img src="img/{scene.name}_card.jpg"></a>
      <figcaption>detector maps + gate evidence (quad it actually used)</figcaption></figure>
    {f'<figure><a href="img/{scene.name}_oracle.jpg"><img src="img/{scene.name}_oracle.jpg"></a><figcaption>same photo, oracle quad &rarr; INK</figcaption></figure>' if oracle_img else ''}
  </div>
  <table class="num">
    <tr><th></th><th>glyphs</th><th>text</th><th>ink_frac</th></tr>
    <tr><td>detected quad</td><td>{det['glyphs'] if det else '-'}</td><td>{fmt(det['text']) if det else '-'}</td><td>{fmt(det_paper.get('ink_frac'))}</td></tr>
    <tr><td>oracle quad</td><td>{orc['glyphs'] if orc else '-'}</td><td>{fmt(orc['text']) if orc else '-'}</td><td>{fmt((row.get('sources', {}).get('oracle') or {}).get('paper', {}).get('ink_frac'))}</td></tr>
  </table>
  <p class="verdict"><b>Verdict:</b> {html.escape(verdict(det, orc))}</p>
</section>""")

    doc = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>DocScan AI — INK evidence (H40)</title>
<style>
  body {{ background:#151518; color:#e8e8ee; font:14px/1.55 system-ui, sans-serif; margin:22px auto; max-width:1180px; padding:0 16px; }}
  h1 {{ font-size:21px; margin:0 0 4px; }}
  .sub {{ color:#9a9aa8; margin-bottom:6px; }}
  .note {{ background:#20202a; border:1px solid #2e2e3a; border-radius:10px; padding:12px 14px; margin:16px 0 22px; }}
  .note b {{ color:#8fd3ff; }}
  .card {{ background:#1d1d22; border:1px solid #2c2c34; border-radius:10px; padding:14px; margin:0 0 18px; }}
  h2 {{ font-size:15px; margin:0 0 6px; color:#5adc82; }}
  .meta {{ color:#b6b6c2; margin:0 0 10px; font-size:12.5px; }}
  .meta b {{ color:#fff; }}
  .pair {{ display:flex; gap:12px; flex-wrap:wrap; }}
  figure {{ margin:0; }}
  img {{ max-width:100%; border-radius:6px; display:block; }}
  figcaption {{ color:#9a9aa8; font-size:12px; margin-top:4px; }}
  table.num {{ border-collapse:collapse; margin:10px 0 4px; font-size:12.5px; }}
  table.num th, table.num td {{ border:1px solid #2c2c34; padding:3px 10px; text-align:right; }}
  table.num th:first-child, table.num td:first-child {{ text-align:left; color:#b6b6c2; }}
  .verdict {{ margin:6px 0 0; font-size:13px; color:#ffd479; }}
  a {{ color:#7cc4ff; }}
  code {{ background:#26262e; padding:1px 5px; border-radius:4px; }}
</style></head><body>
<h1>DocScan AI — INK evidence</h1>
<div class="sub">12 owner photos replayed through the real detector (<code>DetectorTraceTest</code>, tag <code>{html.escape(dump.name)}</code>).</div>
<div class="note">
<b>Read this first.</b> The corner heat maps are the AI. The INK map is <b>not</b> the AI: it is
<code>adaptiveThreshold(MEAN_C, block 25, C 10)</code> on the rectified page, i.e. the gate's own
text evidence. Each card is the detector's evidence; the second figure re-rectifies the
<b>same photo with the oracle (ground-truth) quad</b> so you can compare the right page's ink
map with the one the detector produced. Numbers under each card are from the identical extractor.
</div>
{''.join(cards)}
</body></html>
"""
    (out / "index.html").write_text(doc, encoding="utf-8")
    (out / "README.md").write_text(UPDATE_RULE, encoding="utf-8")
    print(f"cards: {len(cards)} -> {out}")
    print(f"index: {out / 'index.html'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
