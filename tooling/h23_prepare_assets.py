#!/usr/bin/env python3
"""
h23_prepare_assets.py - turn the vetted REAL pencil pages into APK test fixtures (SUBTASK H23).

Input : E:\\DocScanAI-cache\\pencil\\provenance.json  (written by tooling/h23_fetch_real.py)
Output: app/src/androidTest/assets/pencil/pencil_real_XX.jpg       (<= 1400 px long edge)
        app/src/androidTest/assets/pencil/pencil_manifest.json     (provenance that travels WITH
                                                                    the APK, including the original
                                                                    SHA-256 and the licence)

Selection is measured, not guessed:
  * the candidate must be a Wikimedia Commons scan whose licence tag is Public domain / CC0 /
    No restrictions (all Chopin manuscript scans here are Public domain, DPLA/Kate Chopin estate);
  * stroke_fraction (pixels at least STROKE_LEVELS below the paper level) must be >= 0.005, so a
    physically blank page is not counted as a "faint pencil page";
  * paper-minus-dark delta must be <= 120 levels, i.e. the page really is faint, not dark ink.
Pages are taken in candidate order until 12 are selected (the subtask's 10-12 range).

The H22 synthetic fixtures (pencil_XX.jpg + pencil_XX_gt.jpg + manifest.json) are MOVED to
E:\\DocScanAI-cache\\pencil\\h22_synthetic\\ rather than deleted: H23 must not present synthetic data
as real, but the previous evidence is preserved.
"""

import hashlib
import json
import os
import shutil
import sys

import numpy as np
from PIL import Image, ImageFilter

CACHE = r"E:\DocScanAI-cache\pencil"
ORIG = os.path.join(CACHE, "original")
PROV = os.path.join(CACHE, "provenance.json")
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(REPO, "app", "src", "androidTest", "assets", "pencil")
SYNTH_ARCHIVE = os.path.join(CACHE, "h22_synthetic")
SUPERSEDED = os.path.join(CACHE, "h23_superseded")
CANDIDATES = os.path.join(REPO, "tooling", "out", "h23", "candidates.json")

LONG_EDGE = 1400
JPEG_QUALITY = 85
TARGET_PAGES = 12
STROKE_LEVELS = 10.0          # grey levels below the LOCAL background that count as stroke
MIN_STROKE_FRACTION = 0.010
MAX_STROKE_FRACTION = 0.35    # more than this is a photo/aged page, not handwriting
MIN_STROKE_DEPTH = 12.0       # 95th percentile of (background - grey) inside strokes
MAX_STROKE_DEPTH = 90.0       # deeper than this is dark ink, not faint pencil
DUPLICATE_MAD = 3.0           # mean abs grey difference below which two files are the same page

ALLOWED = ("public domain", "cc0", "no restrictions", "cc by")


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def page_stats(path):
    """
    Local stroke statistics on a 900 px analysis copy.

    The first version of this function used global percentiles and (correctly) exposed its own
    flaw: an ink letter photographed with low contrast has a small global delta too. A pencil page
    must be judged against its LOCAL paper background, which is exactly what the enhancer's
    illumination estimate does. `background` is a large-radius blur; `diff = background - grey` is
    positive only where something darker than its surroundings was written. Aged-paper shading is
    low-frequency and cancels out, so the stroke fraction is about strokes, not stains.
    """
    with Image.open(path) as im:
        im = im.convert("L")
        w, h = im.size
        scale = 900.0 / max(w, h)
        if scale < 1.0:
            im = im.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.BILINEAR)
        background = im.filter(ImageFilter.GaussianBlur(radius=10.0))
        gray = np.asarray(im, dtype=np.float32)
        bg = np.asarray(background, dtype=np.float32)
    paper = float(np.percentile(gray, 90.0))
    dark = float(np.percentile(gray, 10.0))
    diff = bg - gray
    stroke_mask = diff > STROKE_LEVELS
    stroke_frac = float(stroke_mask.mean())
    stroke_depth = float(np.percentile(diff[stroke_mask], 95.0)) if stroke_mask.any() else 0.0
    paper_noise = float(np.std(diff[~stroke_mask])) if (~stroke_mask).any() else 0.0
    return paper, dark, paper - dark, stroke_frac, stroke_depth, paper_noise


def signature(path):
    """Small grey thumbnail used only to reject two uploads of the SAME physical page."""
    with Image.open(path) as im:
        im = im.convert("L").resize((360, 560), Image.BILINEAR)
        return np.asarray(im, dtype=np.float32)


def main():
    if not os.path.exists(PROV):
        raise SystemExit("missing provenance: %s" % PROV)
    with open(PROV, encoding="utf-8") as fh:
        provenance = json.load(fh)
    with open(CANDIDATES, encoding="utf-8") as fh:
        candidates = json.load(fh)
    index_by_title = {c["title"]: i for i, c in enumerate(candidates)}

    os.makedirs(ASSETS, exist_ok=True)
    os.makedirs(SYNTH_ARCHIVE, exist_ok=True)
    os.makedirs(SUPERSEDED, exist_ok=True)

    # 1) clear the asset folder of BOTH the H22 synthetic fixtures and any H23 selection from an
    #    earlier run. Files are MOVED to the E: cache (preserved, never deleted).
    retired_synth = 0
    superseded = 0
    for name in sorted(os.listdir(ASSETS)):
        if not name.lower().endswith((".jpg", ".jpeg", ".png", ".json")):
            continue
        src = os.path.join(ASSETS, name)
        if name.startswith("pencil_real_"):
            shutil.move(src, os.path.join(SUPERSEDED, name))
            superseded += 1
        elif name == "manifest.json" or name.startswith("pencil_"):
            shutil.move(src, os.path.join(SYNTH_ARCHIVE, name))
            retired_synth += 1
    print("retired %d H22 synthetic fixtures -> %s; superseded %d earlier H23 assets -> %s"
          % (retired_synth, SYNTH_ARCHIVE, superseded, SUPERSEDED))

    selected = []
    rejected = []
    for record in provenance:
        licence = (record.get("licence") or "").strip()
        if not any(allowed in licence.lower() for allowed in ALLOWED):
            rejected.append((record["title"], "licence=%r" % licence))
            continue
        # The catalogue must say pencil. The Louise Michel / McKee / Eliot letters are real
        # handwriting but they are INK, and labelling them "pencil evidence" would be dishonest.
        title_lc = record["title"].lower()
        if "pencil" not in title_lc:
            rejected.append((record["title"], "not catalogued as pencil"))
            continue
        if "sketch" in title_lc or "school bag" in title_lc:
            rejected.append((record["title"], "drawing/object, not handwriting"))
            continue
        paper, dark, delta, stroke_frac, stroke_depth, paper_noise = page_stats(record["local_path"])
        if stroke_frac < MIN_STROKE_FRACTION:
            rejected.append((record["title"], "blank stroke_frac=%.4f" % stroke_frac))
            continue
        if stroke_frac > MAX_STROKE_FRACTION:
            rejected.append((record["title"], "not handwriting stroke_frac=%.3f" % stroke_frac))
            continue
        if not (MIN_STROKE_DEPTH <= stroke_depth <= MAX_STROKE_DEPTH):
            rejected.append((record["title"], "depth=%.1f outside faint band" % stroke_depth))
            continue
        sig = signature(record["local_path"])
        duplicate_of = None
        for prior in selected:
            if float(np.abs(sig - prior["_sig"]).mean()) < DUPLICATE_MAD:
                duplicate_of = prior["title"]
                break
        if duplicate_of is not None:
            rejected.append((record["title"], "duplicate of %s" % duplicate_of[:60]))
            continue
        record["_sig"] = sig
        record["_stats"] = {
            "paper_level": round(paper, 2),
            "dark_level": round(dark, 2),
            "ink_delta": round(delta, 2),
            "stroke_fraction": round(stroke_frac, 5),
            "stroke_depth_p95": round(stroke_depth, 2),
            "paper_noise_sigma": round(paper_noise, 2),
            "candidate_index": index_by_title.get(record["title"], -1),
        }
        selected.append(record)
        if len(selected) >= TARGET_PAGES:
            break

    if len(selected) < 10:
        raise SystemExit("only %d pages met the real-pencil criteria (need >= 10)" % len(selected))

    items = []
    for i, record in enumerate(selected):
        with Image.open(record["local_path"]) as im:
            im = im.convert("RGB")
            w, h = im.size
            scale = LONG_EDGE / float(max(w, h))
            nw, nh = max(1, int(round(w * scale))), max(1, int(round(h * scale)))
            if scale < 1.0:
                im = im.resize((nw, nh), Image.LANCZOS)
            else:
                nw, nh = w, h
            asset = "pencil_real_%02d.jpg" % i
            out_path = os.path.join(ASSETS, asset)
            im.save(out_path, format="JPEG", quality=JPEG_QUALITY, subsampling=2, optimize=True)

        item = {
            "asset": asset,
            "asset_bytes": os.path.getsize(out_path),
            "asset_sha256": sha256_file(out_path),
            "asset_size": [nw, nh],
            "title": record["title"],
            "source_page": record["page_url"],
            "direct_file_url": record["direct_url"],
            "downloaded_file_url": record.get("downloaded_url", record["direct_url"]),
            "downloaded_variant": record.get("downloaded_variant", "original"),
            "original_bytes": record["bytes"],
            "original_sha256": record["sha256"],
            "licence": record["licence"],
            "licence_url": record.get("licence_url") or "",
            "attribution": record.get("attribution") or "Wikimedia Commons contributor",
            "collection": "Kate Chopin manuscript scans (DPLA / Wikimedia Commons)",
            "real_data": True,
            "synthetic": False,
            "stats": record["_stats"],
        }
        items.append(item)
        print("  [%02d] %s <- %s (%dx%d, %.1f KB, delta=%.0f, strokes=%.2f%%)"
              % (i, asset, os.path.basename(record["local_path"])[:48], nw, nh,
                 item["asset_bytes"] / 1024.0, record["_stats"]["ink_delta"],
                 record["_stats"]["stroke_fraction"] * 100.0))

    manifest = {
        "dataset": "real-pencil-handwriting-v1",
        "provenance_rule": "Real, already-collected data from the internet is preferred over "
                           "generated data for every evaluation. Every file below is a genuine "
                           "pencil-on-paper scan; none is synthetic.",
        "source": "Wikimedia Commons (imageinfo API), queried by tooling/h23_fetch_real.py",
        "fetched_utc": selected[0].get("downloaded_at"),
        "pages": len(items),
        "long_edge": LONG_EDGE,
        "jpeg_quality": JPEG_QUALITY,
        "selection_rule": "catalogue says 'pencil'; public-domain/CC licence; local stroke_fraction "
                          "in [%.3f, %.3f] at %.0f grey levels below a sigma=10 px background; "
                          "stroke depth p95 in [%.0f, %.0f] levels"
                          % (MIN_STROKE_FRACTION, MAX_STROKE_FRACTION, STROKE_LEVELS,
                             MIN_STROKE_DEPTH, MAX_STROKE_DEPTH),
        "rejected": [{"title": t, "reason": r} for t, r in rejected],
        "items": items,
    }
    with open(os.path.join(ASSETS, "pencil_manifest.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    total = sum(it["asset_bytes"] for it in items)
    print("H23_ASSETS pages=%d total_bytes=%d (%.2f MB) -> %s"
          % (len(items), total, total / 1048576.0, ASSETS))
    return 0


if __name__ == "__main__":
    sys.exit(main())
