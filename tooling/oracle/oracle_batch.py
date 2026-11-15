#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H33 — oracle batch runner: a folder of images -> combined pseudo-GT manifest.

Loops over every image in --dir (sorted), calls `oracle_query.run_query` per
image (via import, no subprocess), and writes ONE combined manifest:

    {
      "dataset": "oracle_pseudo_gt",
      "model": "...", "server": "...",
      "items": [ <per-image record from oracle_query>, ... ]
    }

Selftest mode (`--selftest`) fabricates deterministic per-image records from a
canned reply WITHOUT touching the network or the disk (except the final manifest
if --out is given) — proof the loop and manifest schema work offline.

Usage:
  python tooling\\oracle\\oracle_batch.py --dir corpus_owner/originals --out manifest.json
  python tooling\\oracle\\oracle_batch.py --selftest --out manifest.json
"""

from __future__ import annotations

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import oracle_query  # noqa: E402

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp"}

FAKE_SIZES = [(4032, 3024), (3024, 4032), (4000, 3000)]


def list_images(folder: str):
    if not os.path.isdir(folder):
        return []
    out = []
    for name in sorted(os.listdir(folder)):
        if os.path.splitext(name)[1].lower() in IMAGE_EXTS:
            out.append(os.path.join(folder, name))
    return out


def run_batch(folder, server, model, target_page, out, timeout):
    images = list_images(folder)
    items = []
    for img in images:
        record = {
            "image": os.path.abspath(img),
            "width": None,
            "height": None,
            "target_page": target_page,
            "server": server,
            "model": model,
            "corners": None,
            "raw": None,
            "ok": False,
        }
        try:
            b64, _mime = oracle_query.image_data_url(img)
            size = oracle_query.probe_size(img)
            record["width"] = size[0] if size else None
            record["height"] = size[1] if size else None
            body = oracle_query.build_request(b64, model, target_page)
            response = oracle_query.post_json(server, body, timeout)
            content = oracle_query.extract_reply_content(response)
            record["raw"] = content
            corners = oracle_query.extract_corners(content)
            if corners is not None:
                record["corners"] = oracle_query.normalize_corners(corners)
                record["ok"] = True
            else:
                record["error"] = "no strict-JSON corners in reply"
        except Exception as exc:  # noqa: BLE001
            record["error"] = str(exc)
        items.append(record)

    manifest = {
        "dataset": "oracle_pseudo_gt",
        "model": model,
        "server": server,
        "count": len(items),
        "ok_count": sum(1 for r in items if r.get("ok")),
        "items": items,
    }
    if out:
        with open(out, "w", encoding="utf-8") as fh:
            json.dump(manifest, fh, indent=2)
    print(json.dumps({k: manifest[k] for k in ("dataset", "count", "ok_count")}, indent=2))
    return 0


def run_selftest(out):
    """Offline: deterministic fake records, schema check, no network."""
    items = []
    for i in range(3):
        w, h = FAKE_SIZES[i % len(FAKE_SIZES)]
        corners = oracle_query.extract_corners(oracle_query.CANNED_REPLY)
        items.append({
            "image": "/fake/img%d.jpg" % i,
            "width": w,
            "height": h,
            "target_page": "the fully visible page",
            "server": "selftest://offline",
            "model": "selftest-canned",
            "corners": oracle_query.normalize_corners(corners),
            "raw": oracle_query.CANNED_REPLY,
            "ok": True,
        })
    manifest = {
        "dataset": "oracle_pseudo_gt",
        "model": "selftest-canned",
        "server": "selftest://offline",
        "count": len(items),
        "ok_count": sum(1 for r in items if r["ok"]),
        "items": items,
    }
    # Schema sanity
    assert manifest["count"] == 3
    assert manifest["ok_count"] == 3
    for r in manifest["items"]:
        assert set(r["corners"].keys()) == {"tl", "tr", "br", "bl"}
    if out:
        with open(out, "w", encoding="utf-8") as fh:
            json.dump(manifest, fh, indent=2)
    print(json.dumps({"count": 3, "ok_count": 3, "schema": "ok"}, indent=2))
    print("SELFTEST OK  (batch loop, manifest schema, 3 canned items)")
    return 0


def main():
    ap = argparse.ArgumentParser(description="Oracle batch: folder -> combined pseudo-GT manifest")
    ap.add_argument("--dir", help="folder of images")
    ap.add_argument("--server", default=oracle_query.DEFAULT_SERVER)
    ap.add_argument("--model", default=oracle_query.DEFAULT_MODEL)
    ap.add_argument("--target-page", default="the fully visible page")
    ap.add_argument("--out", help="output manifest.json path")
    ap.add_argument("--timeout", type=float, default=180.0)
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return run_selftest(args.out)
    if not args.dir:
        ap.error("--dir is required (or --selftest)")
    return run_batch(args.dir, args.server, args.model, args.target_page, args.out, args.timeout)


if __name__ == "__main__":
    sys.exit(main())
