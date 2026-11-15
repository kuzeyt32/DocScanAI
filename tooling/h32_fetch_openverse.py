#!/usr/bin/env python3
"""h32_fetch_openverse.py - second, licence-safe discovery channel for REAL open-book photos.

Wikimedia Commons alone produced too few photographs with the exact composition SUBTASK H32
needs (camera aimed at ONE page, facing page partially cut at the frame edge). This tool queries
the Openverse API (openverse.org, CC search across Flickr/Wikimedia/etc.) for more candidates,
keeping the SAME licence discipline as h32_fetch_real.py:

  * only CC0 / CC-BY / CC-BY-SA / PDM;
  * every NC/"non-commercial" hit is REJECTED and logged;
  * per-file record: source page URL (foreign_landing_url), direct file URL (url), licence
    name + version + URL, attribution/creator, byte size, SHA-256.

Thumbnails are downloaded to the SAME pool (E:\\DocScanAI-cache\\bookspread_real\\thumb\\) and
merged into provenance_thumbs.json + pool_index.json by `h32_fetch_real.py sheet` (which reads
the directory), so the selection/annotation pipeline is unchanged.

Usage:
  python tooling/h32_fetch_openverse.py search
  python tooling/h32_fetch_openverse.py download --limit 60
"""

import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
OUT_DIR = os.path.join(REPO, "tooling", "out", "h32")
THUMB_DIR = r"E:\DocScanAI-cache\bookspread_real\thumb"

API = "https://api.openverse.org/v1/images/"
UA = "DocScanAI-H32-evaluation/1.0 (local research build; one-off dataset fetch)"

QUERIES = [
    "open book page",
    "open book pages photograph",
    "reading open book",
    "book spread photo",
    "open book close up",
    "book page photograph",
    "open book on table",
    "open textbook pages",
    "open novel pages",
    "person reading book pages",
]

#: Openverse license slugs we accept (no NC/ND).
ALLOWED_LICENSES = ("cc0", "pdm", "by", "by-sa")
NC_RE = re.compile(r"non[- ]?commercial|\bnc\b|no[- ]?deriv", re.I)


def hb(msg):
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "HEARTBEAT.txt"), "a", encoding="utf-8") as fh:
        fh.write("[%s] %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg))


def log(msg):
    print(msg, flush=True)


def api_get(query, page_size=40, page=1):
    params = {
        "q": query,
        "page_size": str(page_size),
        "page": str(page),
        "license": ",".join(ALLOWED_LICENSES),
        "mature": "false",
    }
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def fetch_bytes(url, max_bytes, tries=3):
    delay = 5.0
    last = None
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=90) as resp:
                return resp.read(max_bytes + 1), None
        except Exception as exc:  # noqa: BLE001
            last = str(exc)
            if attempt < tries - 1:
                time.sleep(delay)
                delay *= 2.0
    return None, last


def license_ok(lic, version):
    blob = ("%s %s" % (lic or "", version or "")).lower()
    if NC_RE.search(blob):
        return False, "non-commercial/no-derivs"
    for hint in ALLOWED_LICENSES:
        if hint in blob:
            return True, "ok"
    return False, "licence not in allowed set: %r" % lic


def license_url(lic, version):
    slug = (lic or "").lower().strip()
    ver = (version or "").strip()
    if slug in ("cc0", "pdm"):
        return {"cc0": "https://creativecommons.org/publicdomain/zero/1.0/",
                "pdm": "https://creativecommons.org/publicdomain/mark/1.0/"}.get(slug, "")
    if slug.startswith("by"):
        tail = slug.replace("by", "by/").strip("/")
        base = "https://creativecommons.org/licenses/%s/%s/" % (slug, ver) if ver else \
               "https://creativecommons.org/licenses/%s/" % slug
        return base
    return ""


def search_all():
    os.makedirs(OUT_DIR, exist_ok=True)
    by_key = {}
    for query in QUERIES:
        try:
            data = api_get(query)
        except Exception as exc:  # noqa: BLE001
            hb("openverse search %r ERROR %s" % (query, exc))
            continue
        results = data.get("results") or []
        hb("openverse search %r -> %d" % (query, len(results)))
        log("search %-28r -> %d results" % (query, len(results)))
        for r in results:
            key = r.get("id") or r.get("url") or ""
            if not key or key in by_key:
                continue
            lic = r.get("license")
            ver = r.get("license_version") or ""
            ok, why = license_ok(lic, ver)
            title = (r.get("title") or "untitled")[:120]
            width = int(r.get("width") or 0)
            height = int(r.get("height") or 0)
            rec = {
                "title": "OV:" + title,
                "source_page_url": r.get("foreign_landing_url") or "",
                "direct_url": r.get("url") or "",
                "thumb_url": r.get("thumbnail") or r.get("url") or "",
                "width": width,
                "height": height,
                "mime": "image/jpeg",
                "licence_short": ("CC " + lic.upper()) if lic else "",
                "licence_url": license_url(lic, ver),
                "usage_terms": "%s %s" % (lic or "", ver or ""),
                "attribution": (r.get("attribution") or r.get("creator") or "")[:400],
                "commons_sha1": "",
                "found_by": ["openverse:" + query],
                "provider": r.get("source") or "",
                "license_ok": ok,
                "license_note": why,
                "title_ok": True,
                "size_ok": width >= 900 and height >= 700,
            }
            by_key[key] = rec
    cands = [r for r in by_title_sorted(by_key.values()) if r["license_ok"] and r["size_ok"]]
    with open(os.path.join(OUT_DIR, "openverse_candidates.json"), "w", encoding="utf-8") as fh:
        json.dump(cands, fh, indent=2)
    hb("openverse search done: %d candidates" % len(cands))
    log("openverse candidates: %d" % len(cands))
    return 0


def by_title_sorted(items):
    return sorted(items, key=lambda r: r["title"])


def download_thumbs(limit=60):
    os.makedirs(THUMB_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, "openverse_candidates.json")
    if not os.path.exists(path):
        log("run search first")
        return 2
    with open(path, encoding="utf-8") as fh:
        cands = json.load(fh)
    prov_path = os.path.join(OUT_DIR, "provenance_thumbs.json")
    prov = {}
    if os.path.exists(prov_path):
        with open(prov_path, encoding="utf-8") as fh:
            prov = json.load(fh)
    done = 0
    for rec in cands:
        if limit and done >= limit:
            break
        title = rec["title"]
        if prov.get(title, {}).get("sha256"):
            continue
        url = rec.get("thumb_url") or rec.get("direct_url") or ""
        if not url:
            continue
        safe = re.sub(r"[^A-Za-z0-9._-]+", "_", title.replace("OV:", "OV_"))[:110]
        if not safe.lower().endswith((".jpg", ".jpeg")):
            safe += ".jpg"
        dest = os.path.join(THUMB_DIR, safe)
        if os.path.exists(dest):
            with open(dest, "rb") as fh:
                blob = fh.read()
        else:
            blob, err = fetch_bytes(url, 12 * 1024 * 1024)
            if blob is None:
                prov[title] = dict(rec, error=err)
                hb("openverse thumb ERROR %s: %s" % (title, err))
                with open(prov_path, "w", encoding="utf-8") as fh:
                    json.dump(prov, fh, indent=2)
                continue
            with open(dest, "wb") as fh:
                fh.write(blob)
        out = dict(rec)
        out["local_path"] = dest
        out["bytes"] = len(blob)
        out["sha256"] = hashlib.sha256(blob).hexdigest()
        out["fetched_kind"] = "openverse-thumb"
        out["fetched_url"] = url
        prov[title] = out
        done += 1
        hb("openverse thumb %s (%d B)" % (safe, len(blob)))
        log("thumb %-52s %8d B  [%s]" % (safe[:52], len(blob), rec.get("provider", "")))
        with open(prov_path, "w", encoding="utf-8") as fh:
            json.dump(prov, fh, indent=2)
        time.sleep(1.0)
    log("openverse thumbs saved: %d" % done)
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["search", "download", "all"])
    ap.add_argument("--limit", type=int, default=60)
    args = ap.parse_args()
    if args.mode in ("search", "all"):
        search_all()
    if args.mode in ("download", "all"):
        download_thumbs(args.limit)
    return 0


if __name__ == "__main__":
    sys.exit(main())
