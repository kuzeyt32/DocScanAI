#!/usr/bin/env python3
"""
h23_fetch_real.py - fetch REAL pencil / faint-handwriting page scans (SUBTASK H23).

The owner's instruction for H23 replaces H22's synthetic data plan: evaluation must use real,
already-collected images from the internet, with the licence recorded. This tool is the whole
provenance lane:

  1. `search`   - runs an explicit, recorded list of Wikimedia Commons queries and writes
                  tooling/out/h23/queries.json + candidates.json (title, page URL, direct URL,
                  licence name, licence URL, attribution, size, dimensions).
  2. `download` - downloads a bounded pool of candidates to E:\\DocScanAI-cache\\pencil\\original\\
                  and records the SHA-256 + byte size of every file (provenance.json).
  3. `sheet`    - writes a labelled contact sheet of the downloaded pool so a human can reject
                  non-pencil images before anything is bundled into the APK.

Only Wikimedia Commons is used here because it is the one source found in this task that serves
*public-domain / CC-licensed, already-scanned paper* directly, with machine-readable licence
metadata. Hugging Face/Zenodo/OpenML hits are recorded in docs/HANDWRITING_ENHANCEMENT.md but the
pencil-specific ones either did not exist or were registration-gated (IAM) / synthetic.

No new dependency: urllib + PIL (both already present on this machine).
"""

import argparse
import hashlib
import html
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

API = "https://commons.wikimedia.org/w/api.php"
UA = "DocScanAI-H23-evaluation/1.0 (local research build; one-off dataset fetch)"
OUT_DIR = os.path.join("tooling", "out", "h23")
CACHE_DIR = r"E:\DocScanAI-cache\pencil"
ORIG_DIR = os.path.join(CACHE_DIR, "original")

# Every query that was actually run is written to queries.json. The list is deliberately explicit:
# "pencil" alone returns pencil *drawings* / product photos, so each query asks for a page artefact.
QUERIES = [
    "pencil handwriting",
    "handwritten in pencil",
    "pencil written letter",
    "pencil notebook page",
    "pencil manuscript page",
    "pencil exam paper",
    "pencil school exercise book",
    "faint pencil writing",
    "pencil notes page",
    "pencil draft manuscript",
]

ALLOWED_LICENCE_HINTS = ("cc0", "cc by", "cc-by", "public domain", "pd-", "copyrighted free use")


def log(msg):
    print(msg, flush=True)


def ensure_dirs():
    for d in (OUT_DIR, ORIG_DIR):
        os.makedirs(d, exist_ok=True)


def api_get(params):
    params = dict(params)
    params.setdefault("format", "json")
    params.setdefault("formatversion", "2")
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=45) as resp:
        return json.loads(resp.read().decode("utf-8"))


def strip_html(value):
    if not value:
        return ""
    text = re.sub(r"<[^>]+>", "", str(value))
    return html.unescape(text).strip()


def licence_of(meta):
    """Return (short_name, licence_url, usage_terms). Missing values are explicit, never guessed."""
    def val(key):
        node = meta.get(key) or {}
        return strip_html(node.get("value")) if isinstance(node, dict) else ""

    short = val("LicenseShortName") or val("License")
    url = val("LicenseUrl")
    usage = val("UsageTerms")
    return short, url, usage


def attribution_of(meta):
    artist = strip_html((meta.get("Artist") or {}).get("value"))
    credit = strip_html((meta.get("Credit") or {}).get("value"))
    return (artist or credit or "")[:400]


def search_all():
    ensure_dirs()
    queries_log = []
    by_title = {}
    for query in QUERIES:
        params = {
            "action": "query",
            "generator": "search",
            "gsrsearch": "filetype:bitmap " + query,
            "gsrnamespace": "6",
            "gsrlimit": "50",
            "prop": "imageinfo",
            "iiprop": "url|size|mime|extmetadata|sha1",
            "iiurlwidth": "1200",
        }
        started = time.time()
        try:
            data = api_get(params)
        except Exception as exc:  # network/API failure is recorded, not hidden
            queries_log.append({"query": query, "error": repr(exc), "results": 0})
            log("  query failed: %r -> %r" % (query, exc))
            continue
        pages = ((data.get("query") or {}).get("pages") or [])
        kept = 0
        for page in pages:
            infos = page.get("imageinfo") or []
            if not infos:
                continue
            info = infos[0]
            mime = info.get("mime") or ""
            if mime not in ("image/jpeg", "image/png"):
                continue
            width = int(info.get("width") or 0)
            height = int(info.get("height") or 0)
            size = int(info.get("size") or 0)
            if width < 900 or height < 900 or size > 12 * 1024 * 1024:
                continue
            meta = info.get("extmetadata") or {}
            short, lic_url, usage = licence_of(meta)
            title = page.get("title") or ""
            page_url = "https://commons.wikimedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_"))
            entry = {
                "title": title,
                "page_url": page_url,
                "direct_url": info.get("url"),
                "thumb_url": info.get("thumburl"),
                "mime": mime,
                "width": width,
                "height": height,
                "bytes": size,
                "licence": short,
                "licence_url": lic_url,
                "usage_terms": usage,
                "attribution": attribution_of(meta),
                "commons_sha1": info.get("sha1"),
                "description": strip_html((meta.get("ImageDescription") or {}).get("value"))[:500],
                "queries": [query],
            }
            existing = by_title.get(title)
            if existing:
                existing["queries"].append(query)
            else:
                by_title[title] = entry
                kept += 1
        queries_log.append({
            "query": query,
            "api": API,
            "gsrsearch": "filetype:bitmap " + query,
            "results": len(pages),
            "kept": kept,
            "seconds": round(time.time() - started, 2),
        })
        log("  %-32s results=%d kept=%d" % (query, len(pages), kept))

    candidates = sorted(by_title.values(), key=lambda e: (e["width"] * e["height"]), reverse=True)
    with open(os.path.join(OUT_DIR, "queries.json"), "w", encoding="utf-8") as fh:
        json.dump(queries_log, fh, indent=2)
    with open(os.path.join(OUT_DIR, "candidates.json"), "w", encoding="utf-8") as fh:
        json.dump(candidates, fh, indent=2)
    log("wrote %d unique candidates" % len(candidates))


def safe_name(index, entry):
    base = os.path.basename(urllib.parse.urlparse(entry["direct_url"]).path)
    base = re.sub(r"[^A-Za-z0-9._-]+", "_", base)
    return "cand_%02d_%s" % (index, base)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(limit, per_file_bytes, indices=None):
    ensure_dirs()
    with open(os.path.join(OUT_DIR, "candidates.json"), encoding="utf-8") as fh:
        candidates = json.load(fh)
    provenance = []
    taken = 0
    for i, entry in enumerate(candidates):
        if indices is not None and i not in indices:
            continue
        if taken >= limit:
            break
        if entry["bytes"] > per_file_bytes:
            continue
        # The 1200 px Commons thumbnail is used instead of the multi-megabyte camera original:
        # it is a real re-render of the same public-domain scan (not synthetic), it is already
        # the size the evaluation consumes, and it keeps the cache far below the 60 MB cap.
        url = entry.get("thumb_url") or entry["direct_url"]
        entry["downloaded_url"] = url
        entry["downloaded_variant"] = "commons-thumb-1200" if entry.get("thumb_url") else "original"
        name = safe_name(i, entry)
        path = os.path.join(ORIG_DIR, name)
        if not os.path.exists(path):
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            try:
                with urllib.request.urlopen(req, timeout=90) as resp:
                    data = resp.read()
            except Exception as exc:
                log("  download failed %s: %r" % (entry["title"], exc))
                continue
            with open(path, "wb") as fh:
                fh.write(data)
            time.sleep(0.4)  # be polite to the API/CDN
        record = dict(entry)
        record["local_path"] = path
        record["bytes"] = os.path.getsize(path)
        record["sha256"] = sha256_file(path)
        record["downloaded_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        provenance.append(record)
        taken += 1
        log("  [%02d] %s (%d KB, %s)" % (taken, name, record["bytes"] // 1024, record["licence"] or "no licence tag"))
    with open(os.path.join(CACHE_DIR, "provenance.json"), "w", encoding="utf-8") as fh:
        json.dump(provenance, fh, indent=2)
    log("downloaded %d files -> %s" % (len(provenance), ORIG_DIR))


def sheet(columns=4, cell=360):
    from PIL import Image, ImageDraw
    with open(os.path.join(CACHE_DIR, "provenance.json"), encoding="utf-8") as fh:
        records = json.load(fh)
    if not records:
        log("no downloads to sheet")
        return
    rows = (len(records) + columns - 1) // columns
    pad = 6
    label_h = 26
    out = Image.new("RGB", (columns * (cell + pad) + pad, rows * (cell + label_h + pad) + pad), (245, 245, 245))
    draw = ImageDraw.Draw(out)
    for index, record in enumerate(records):
        col = index % columns
        row = index // columns
        x = pad + col * (cell + pad)
        y = pad + row * (cell + label_h + pad)
        try:
            img = Image.open(record["local_path"])
            img.thumbnail((cell, cell))
            out.paste(img.convert("RGB"), (x + (cell - img.width) // 2, y + (cell - img.height) // 2))
        except Exception as exc:
            draw.text((x + 4, y + 4), "ERR %r" % (exc,), fill=(200, 0, 0))
        draw.rectangle([x, y + cell, x + cell, y + cell + label_h], fill=(255, 255, 255))
        draw.text((x + 3, y + cell + 4), "%02d %s" % (index, record["licence"][:34] or "?"), fill=(0, 0, 0))
        draw.text((x + 3, y + cell + 14), "%dx%d" % (record["width"], record["height"]), fill=(70, 70, 70))
    path = os.path.join(OUT_DIR, "real_pool_contact_sheet.jpg")
    out.save(path, quality=88)
    log("contact sheet -> %s" % path)


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["search", "download", "sheet"])
    parser.add_argument("--limit", type=int, default=32)
    parser.add_argument("--per-file-mb", type=int, default=10)
    parser.add_argument("--indices", default="",
                        help="comma-separated candidate indices from candidates.json")
    args = parser.parse_args(argv[1:])
    if args.mode == "search":
        search_all()
    elif args.mode == "download":
        indices = set(int(x) for x in args.indices.split(",") if x.strip()) if args.indices else None
        download(args.limit, args.per_file_mb * 1024 * 1024, indices)
    else:
        sheet()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
