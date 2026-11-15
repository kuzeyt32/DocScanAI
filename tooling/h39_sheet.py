#!/usr/bin/env python3
"""
H39 — build human-viewable contact sheets + an index for a detector trace dump.

Reads the folder produced by ``tooling/h39_dump.cmd`` (one sub-folder per photo,
each holding the TraceDump PNGs + ``trace.json``, plus a top-level
``manifest.json``) and writes:

  <scene>/sheet.png   one labelled sheet per photo: AI input + heat maps, the
                      gate's ink evidence, the classical path, and the final quad
  index.html          every photo with its numbers and all sheet images inline
  README.txt          a plain-text legend (what each tile means)

Usage:  python tooling/h39_sheet.py tooling/out/h39/<Tag>
"""

from __future__ import annotations

import html
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

TILE_H = 300
PAD = 10
LABEL_H = 22
HEADER_H = 54
BG = (22, 22, 26)
FG = (235, 235, 240)
DIM = (150, 150, 160)
ACCENT = (90, 220, 130)

# (file, caption) in reading order. Missing files become a labelled placeholder,
# so a sheet still shows which stage produced nothing.
ROWS = [
    [
        ("corner_input.png", "AI input (model tensor)"),
        ("heat_montage.png", "AI corner heat: TL TR / BR BL"),
        ("heat_max.png", "AI heat: max of 4 channels"),
    ],
    [
        ("page_rectified.png", "rectified page (gate input)"),
        ("page_interior.png", "interior ROI (paper stats)"),
        ("page_ink.png", "INK map (text evidence)"),
    ],
    [
        ("page_glyphs.png", "glyph components (line proof)"),
        ("classical_edges.png", "classical edge map"),
        ("classical_candidates.png", "classical candidates (score)"),
    ],
    [
        ("page_edges.png", "Canny on interior"),
        ("final_quad.png", "final quad + provenance"),
        (None, None),
    ],
]

LEGEND = """H39 detector trace — what you are looking at
================================================

corner_input.png         Exactly the resized image handed to the ONNX corner model.
heat_c0..c3.png          The four raw corner heat-map channels, per-channel normalised
                         (c0=TL, c1=TR, c2=BR, c3=BL). Bright = the model's response.
heat_max.png             Max over the four channels: where ANY corner is predicted.
heat_montage.png         2x2 labelled montage of the four channels (the sheet tile).
classical_gray.png       Working grayscale of the classical fallback (~480 px long edge).
classical_edges.png      Union edge map: Canny + Otsu Sobel (+ shadow band when enabled).
classical_candidates.png Every scored quad candidate, colour = source, label = source + score.
                         The thickest outline is the winner by rank.
page_rectified.png       The page warped to a rectangle: what the gate actually scores.
page_interior.png        The interior ROI (7 % margin) used for the paper/ink statistics.
page_ink.png             Adaptive-threshold INK map (inverted): the text evidence.
page_edges.png           Canny edges on the interior (the "sane edge band" signal).
page_glyphs.png          Ink map + the glyph components that feed text score / line coherence.
final_quad.png           The quad returned by detect(), drawn on the input, with provenance.
trace.json               Every scalar: heat peaks + frame coordinates, candidate scores,
                         gate breakdown (paper/text/edge/glyphs/coherence), quad, timings.

How to read a failure
---------------------
* heat_* nearly black on a real page  -> the MODEL did not see corners at all
  (input/frame/domain problem, not a threshold problem).
* heat_* has a bright blob away from the page corner -> the model is looking at the
  wrong thing; check corner_input.png for orientation/skew.
* page_ink.png empty/noisy while the page has text -> the INK MAP is the problem
  (this is exactly the "ink map was not good enough" case); look at page_interior.png
  for glare/shadow/curvature, because the mask is computed on that ROI.
* page_ink.png good but page_glyphs.png shows huge components -> potential merge
  problem: adaptive block size too large for the rectified scale.
* final_quad.png wrong but heat/ink good -> the failure is in postprocess/gate/rank,
  not in the AI.
"""


def load(path: Path, height: int) -> Image.Image | None:
    if not path.exists():
        return None
    try:
        im = Image.open(path)
        im.load()
        if im.mode not in ("RGB", "RGBA"):
            im = im.convert("RGB")
        w = max(1, round(im.width * height / im.height))
        return im.resize((w, height), Image.LANCZOS)
    except Exception as exc:  # noqa: BLE001 - a broken PNG must not kill the report
        print(f"  ! {path.name}: {exc}", file=sys.stderr)
        return None


def read_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return {}


def build_sheet(scene: Path, trace: dict) -> Path:
    tiles: list[list[tuple[Image.Image | None, str]]] = []
    for row in ROWS:
        built = []
        for name, caption in row:
            if name is None:
                built.append((None, ""))
                continue
            built.append((load(scene / name, TILE_H), caption))
        tiles.append(built)

    col_w = [0, 0, 0]
    for row in tiles:
        for c, (im, _) in enumerate(row):
            if im is not None:
                col_w[c] = max(col_w[c], im.width)
    col_w = [w if w else TILE_H for w in col_w]
    width = PAD + sum(w + PAD for w in col_w)
    height = HEADER_H + sum(TILE_H + LABEL_H + PAD for _ in tiles) + PAD
    sheet = Image.new("RGB", (width, height), BG)
    draw = ImageDraw.Draw(sheet)

    name = trace.get("label", scene.name)
    detected = trace.get("detected")
    prov = trace.get("provenance", "-")
    gate = trace.get("gate_passed")
    paper = trace.get("gate", {}).get("paper", trace.get("paper_score"))
    text = trace.get("gate", {}).get("text", trace.get("text_score"))
    glyphs = trace.get("gate", {}).get("glyphs", trace.get("glyphs"))
    ms = trace.get("detect_ms")
    title = f"{name}  |  detected={detected}  prov={prov}  gate={gate}"
    sub = (
        f"paper={fmt(paper)}  text={fmt(text)}  glyphs={glyphs}  "
        f"detect_ms={fmt(ms, 1)}  ink={fmt(trace.get('gate', {}).get('ink_frac'))}"
    )
    draw.text((PAD, 8), title, fill=ACCENT)
    draw.text((PAD, 30), sub, fill=DIM)

    y = HEADER_H
    for row in tiles:
        x = PAD
        for c, (im, caption) in enumerate(row):
            if im is not None:
                sheet.paste(im, (x, y))
                draw.text((x, y + TILE_H + 3), caption, fill=FG)
            elif caption:
                draw.rectangle([x, y, x + col_w[c] - 1, y + TILE_H - 1], outline=(70, 70, 78))
                draw.text((x + 8, y + 8), f"missing: {caption}", fill=(110, 110, 120))
            x += col_w[c] + PAD
        y += TILE_H + LABEL_H + PAD

    out = scene / "sheet.png"
    sheet.save(out)
    return out


def fmt(v, digits: int = 3) -> str:
    if v is None:
        return "-"
    try:
        return f"{float(v):.{digits}f}"
    except (TypeError, ValueError):
        return str(v)


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    root = Path(argv[1]).resolve()
    if not root.is_dir():
        print(f"not a directory: {root}")
        return 2

    manifest = read_json(root / "manifest.json")
    scenes = sorted(p for p in root.iterdir() if p.is_dir() and not p.name.startswith("raw"))
    if not scenes:
        print(f"no scene folders under {root}")
        return 2

    (root / "README.txt").write_text(LEGEND, encoding="utf-8")
    (root / "pipeline.txt").write_text(
        "capture frame -> corner model (corner_input.png) -> heat maps -> peaks/quad\n"
        "             -> gate rectifies page (page_rectified.png) -> interior ROI\n"
        "             -> ink map + glyphs (page_ink.png, page_glyphs.png) -> scores\n"
        "             -> final quad (final_quad.png)\n",
        encoding="utf-8",
    )
    if manifest.get("tag"):
        (root / "RUN.txt").write_text(
            f"tag={manifest.get('tag')}\n"
            f"mode={manifest.get('mode')}\n"
            f"mlsd_mode={manifest.get('mlsd_mode')}\n"
            f"models={manifest.get('presence')}\n"
            f"images_ok={manifest.get('images_ok')} images_failed={manifest.get('images_failed')}\n",
            encoding="utf-8",
        )

    built = 0
    cards = []
    for scene in scenes:
        trace = read_json(scene / "trace.json")
        try:
            build_sheet(scene, trace)
            built += 1
        except Exception as exc:  # noqa: BLE001
            print(f"  ! sheet failed for {scene.name}: {exc}", file=sys.stderr)
        img = f"{scene.name}/sheet.png"
        t = trace.get("gate", {})
        cards.append(
            f"""<section class="card">
  <h2>{html.escape(scene.name)}</h2>
  <p class="meta">
    detected <b>{trace.get('detected')}</b> ·
    provenance <b>{html.escape(str(trace.get('provenance', '-')))}</b> ·
    gate <b>{trace.get('gate_passed')}</b>
    (score {fmt(trace.get('gate_score'))}) ·
    paper {fmt(t.get('paper'))} · text {fmt(t.get('text'))} ·
    ink {fmt(t.get('ink_frac'))} · glyphs {t.get('glyphs', '-')} ·
    detect {fmt(trace.get('detect_ms'), 1)} ms
  </p>
  <a href="{html.escape(img)}"><img src="{html.escape(img)}" alt="{html.escape(scene.name)}"></a>
  <p class="links"><a href="{html.escape(scene.name)}/trace.json">trace.json</a></p>
</section>"""
        )

    doc = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>DocScanAI H39 detector trace — {html.escape(root.name)}</title>
<style>
  body {{ background:#151518; color:#e8e8ee; font:14px/1.5 system-ui, sans-serif; margin:24px; }}
  h1 {{ font-size:20px; margin:0 0 4px; }}
  .sub {{ color:#9a9aa8; margin-bottom:20px; }}
  .card {{ background:#1d1d22; border:1px solid #2c2c34; border-radius:10px; padding:14px; margin:0 0 18px; }}
  h2 {{ font-size:15px; margin:0 0 6px; color:#5adc82; }}
  .meta {{ color:#b6b6c2; margin:0 0 10px; font-size:13px; }}
  .meta b {{ color:#fff; }}
  img {{ max-width:100%; border-radius:6px; display:block; }}
  .links {{ font-size:12px; margin:8px 0 0; }}
  a {{ color:#7cc4ff; }}
</style></head><body>
<h1>{html.escape(root.name)} — detector trace</h1>
<div class="sub">{built} photo(s). Generated by <code>tooling/h39_sheet.py</code>.
Legend: <code>README.txt</code>. The tile order is documented there.</div>
{''.join(cards)}
</body></html>
"""
    (root / "index.html").write_text(doc, encoding="utf-8")
    print(f"sheets: {built}/{len(scenes)} -> {root}")
    print(f"index:  {root / 'index.html'}")
    return 0 if built else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
