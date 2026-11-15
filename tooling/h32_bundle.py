#!/usr/bin/env python3
"""h32_bundle.py - bundle the SELECTED real book-spread images into androidTest assets.

STEP 1.3 of SUBTASK H32: cache originals on E:\\DocScanAI-cache\\ (large files stay off C:),
bundle a DOWN SCALED subset (<= 1400 px long edge, <= ~15 MB total) into
app/src/androidTest/assets/bookspread_real/ with real_bookspread_manifest.json carrying:

  * per-image provenance (source page URL, direct file URL, licence name + URL, attribution,
    byte size, SHA-256 of the ORIGINAL and of the bundled rendition),
  * the ground-truth quad of the intended target page (the FULLY-SEEN page),
  * the annotation method (detector draft + visual correction; recorded for reproducibility).

Inputs:
  tooling/out/h32/gt_final.json          (from `h32_annotate.py finalize`)
  tooling/out/h32/provenance.json        (originals: SHA-256, bytes, licence, URLs)
  E:\\DocScanAI-cache\\bookspread_real\\original\\  (the originals)

Output:
  app/src/androidTest/assets/bookspread_real/<name>.jpg
  app/src/androidTest/assets/bookspread_real/real_bookspread_manifest.json

Budget: long edge <= 1400 px, total bundle <= 15 MB. Images are ordered by pool_index and named
rbNNN_<slug>.jpg so the manifest and the files are stable.
"""

import argparse
import hashlib
import json
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
OUT_DIR = os.path.join(REPO, "tooling", "out", "h32")
ASSETS = os.path.join(REPO, "app", "src", "androidTest", "assets", "bookspread_real")
ORIG_DIR = r"E:\DocScanAI-cache\bookspread_real\original"
THUMB_DIR = r"E:\DocScanAI-cache\bookspread_real\thumb"

MAX_LONG_EDGE = 1400
MAX_TOTAL_BYTES = 15 * 1024 * 1024
JPEG_QUALITY = 86


def hb(msg):
    import time
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "HEARTBEAT.txt"), "a", encoding="utf-8") as fh:
        fh.write("[%s] %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg))


def sha256_bytes(blob):
    return hashlib.sha256(blob).hexdigest()


def load_json(path, default=None):
    if os.path.exists(path):
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    return default


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--max-images", type=int, default=0, help="0 = all in gt_final.json")
    args = ap.parse_args()

    gt_doc = load_json(os.path.join(OUT_DIR, "gt_final.json"))
    if not gt_doc:
        print("gt_final.json missing - run `h32_finalize.py` first")
        return 2
    gt = gt_doc["items"] if isinstance(gt_doc, dict) else gt_doc
    # Provenance: originals first (full resolution + original SHA-256), thumbs as fallback.
    prov = load_json(os.path.join(OUT_DIR, "provenance.json"), {}) or {}
    prov_thumbs = load_json(os.path.join(OUT_DIR, "provenance_thumbs.json"), {}) or {}
    for k, v in prov_thumbs.items():
        prov.setdefault(k, v)

    os.makedirs(ASSETS, exist_ok=True)
    items = []
    total_bytes = 0
    skipped = []

    # Stable order: pool_index ascending.
    gt = sorted(gt, key=lambda r: r["pool_index"])
    if args.max_images:
        gt = gt[: args.max_images]

    for rec in gt:
        title = rec["title"]
        name = "rb%03d" % rec["pool_index"]
        # Prefer the ORIGINAL file for quality; fall back to the 1280 thumb.
        src = os.path.join(ORIG_DIR, _safe(title))
        rendition_kind = "original"
        if not os.path.exists(src):
            src = os.path.join(THUMB_DIR, _safe(title))
            rendition_kind = "commons-thumb-1280"
        if not os.path.exists(src):
            skipped.append({"title": title, "why": "source file missing"})
            continue

        with Image.open(src) as im:
            im = im.convert("RGB")
            w0, h0 = im.size
            scale = MAX_LONG_EDGE / float(max(w0, h0))
            if scale < 1.0:
                im = im.resize((max(1, int(round(w0 * scale))), max(1, int(round(h0 * scale)))), Image.LANCZOS)
            out_path = os.path.join(ASSETS, "%s.jpg" % name)
            im.save(out_path, "JPEG", quality=JPEG_QUALITY, optimize=True)
            w, h = im.size

        with open(out_path, "rb") as fh:
            blob = fh.read()
        total_bytes += len(blob)
        if total_bytes > MAX_TOTAL_BYTES:
            skipped.append({"title": title, "why": "bundle budget exceeded at %d B" % total_bytes})
            os.remove(out_path)
            total_bytes -= len(blob)
            continue

        # Resolve the GT quads to the bundled rendition's pixel grid. Quads may arrive in
        # fraction space (0..1, from the visual annotation) or in the annotation rendition's
        # pixel space; both are handled, and the space used is recorded in the manifest.
        quad_space = rec.get("quad_space", "pixel")

        def scale_quad(q):
            if not q:
                return None
            if quad_space == "frac":
                return [[float(x) * w, float(y) * h] for x, y in q]
            scale_x = w / float(rec["width"])
            scale_y = h / float(rec["height"])
            return [[float(x) * scale_x, float(y) * scale_y] for x, y in q]

        p = prov.get(title, {})
        item = {
            "name": name,
            "tier": "real",
            "scene": rec.get("scene", "real_spread"),
            "asset": "%s.jpg" % name,
            "asset_bytes": len(blob),
            "asset_sha256": sha256_bytes(blob),
            "width": w,
            "height": h,
            "diagonal": float((w * w + h * h) ** 0.5),
            "gt_target_quad": scale_quad(rec["gt_target_quad"]),
            "gt_facing_quad": scale_quad(rec.get("facing_quad_visible")),
            "gt_alt_quad": scale_quad(rec.get("alt_quad_visible")),
            "facing_side": rec.get("facing_side"),
            "annotation": {
                "method": rec.get("annotation_method", "visual_fraction_annotation"),
                "annotated_on": ("fraction space 0..1 (grid overlay, reproducible)"
                                 if quad_space == "frac"
                                 else "%dx%d (pool rendition)" % (rec["width"], rec["height"])),
                "scaled_to": "%dx%d (bundled)" % (w, h),
                "quad_space": quad_space,
                "note": rec.get("note", ""),
            },
            "provenance": {
                "title": title,
                "source_page_url": p.get("source_page_url") or rec.get("source_page_url", ""),
                "direct_file_url": p.get("direct_url") or rec.get("direct_url", ""),
                "licence_name": p.get("licence_short", ""),
                "licence_url": p.get("licence_url", ""),
                "usage_terms": p.get("usage_terms", ""),
                "attribution": p.get("attribution", ""),
                "original_bytes": p.get("bytes_original") or p.get("bytes"),
                "original_sha256": p.get("sha256_original") or p.get("sha256"),
                "rendition": rendition_kind,
                "discovered_via": p.get("found_by", []),
            },
        }
        items.append(item)
        hb("bundle %s.jpg %dx%d %d B (%s)" % (name, w, h, len(blob), rendition_kind))

    manifest = {
        "dataset": "real_bookspread",
        "tier": "real",
        "provenance_rule": (
            "REAL photographs of real open books, downloaded from Wikimedia Commons (and "
            "Unsplash where noted). Per-file licence + attribution recorded; NC-licensed and "
            "licence-unknown files excluded. Originals cached at "
            "E:\\DocScanAI-cache\\bookspread_real\\original\\. The bundled renditions are "
            "downscaled to <= %d px long edge. Ground truth is an ANNOTATION: the target-page "
            "quad was drafted by the offline detector stack (tooling/h32_annotate.py) and then "
            "visually verified/corrected against the image; it is not a measured quantity."
            % MAX_LONG_EDGE
        ),
        "annotation_method": (
            "detector-draft + visual correction. h32_annotate.py `draft` runs the corner model + "
            "M-LSD fold search and proposes the target page (larger half of the fold split); the "
            "draft is rendered as an overlay; the agent verifies each overlay and confirms "
            "(gt='draft') or supplies corrected corner coordinates. Excluded images are recorded "
            "with a reason."
        ),
        "license_policy": (
            "CC0 / CC-BY / CC-BY-SA / public-domain only; non-commercial licences rejected; "
            "attribution preserved verbatim per image."
        ),
        "counts": {
            "images": len(items),
            "total_bytes": total_bytes,
            "skipped": len(skipped),
        },
        "items": items,
        "skipped": skipped,
    }
    out = os.path.join(ASSETS, "real_bookspread_manifest.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2)
    print("bundled %d images (%.2f MB) -> %s" % (len(items), total_bytes / 1e6, out))
    if skipped:
        print("skipped %d: %s" % (len(skipped), [s["why"] for s in skipped[:5]]))
    return 0


def _safe(title):
    import re
    name = re.sub(r"[^A-Za-z0-9._-]+", "_", title.replace("File:", ""))[:110]
    return name if name.lower().endswith((".jpg", ".jpeg")) else name + ".jpg"


if __name__ == "__main__":
    sys.exit(main())
