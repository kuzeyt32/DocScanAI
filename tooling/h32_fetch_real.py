#!/usr/bin/env python3
"""h32_fetch_real.py - fetch REAL photographs of open books for the H32 page-pick set.

The owner's instruction for H32 is that the new claim ("the detector picks the ONE page that is
fully seen when the facing page is cut off at the frame edge") must be proven on REAL photographs
of real open books, not on the H29 synthetic renders. This tool is the provenance lane, modelled
on `h23_fetch_real.py`:

  1. `search`    - runs an explicit, recorded set of Wikimedia Commons queries (category members
                   + intitle searches) and writes tooling/out/h32/commons_candidates.json with
                   title, source page URL, direct file URL, licence name, licence URL,
                   attribution, size, dimensions. No licence is ever guessed: anything that
                   cannot be established from Commons extmetadata is REJECTED and logged.
  2. `thumbs`    - downloads the 1280 px standard thumbnail of every candidate to
                   E:\\DocScanAI-cache\\bookspread_real\\thumb\\ with SHA-256 provenance.
                   Wikimedia's 429 guidance explicitly asks for standard thumbnail sizes
                   instead of full-size originals in bulk (see w.wiki/GHai).
  3. `sheet`     - writes labelled contact sheets of the pool so a human/agent can select the
                   images where ONE page is fully seen and the facing page is cut off at the
                   frame edge (selection.json).
  4. `originals` - fetches the ORIGINAL file for the selected finalists only (small, polite
                   request count) into E:\\DocScanAI-cache\\bookspread_real\\original\\, with
                   SHA-256 of both the original and the bundled rendition recorded.

Licence discipline (standing owner rule: no fabricated licences, no non-commercial assets):
  * only CC0 / CC-BY / CC-BY-SA / public-domain files are kept;
  * any "NC" / "non-commercial" licence is REJECTED and logged to commons_rejected.json;
  * attribution is preserved verbatim for the manifest.

Discovery is recorded two ways: the Commons API queries below (reproducible) and a Tavily
search log kept in tooling/out/h32/tavily_discovery.md (the owner asked for Tavily-assisted
discovery; the fetch itself goes straight to Commons so the licence metadata is machine-readable).

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
import urllib.error
import urllib.parse
import urllib.request

API = "https://commons.wikimedia.org/w/api.php"
UA = "DocScanAI-H32-evaluation/1.0 (local research build; one-off dataset fetch)"
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
OUT_DIR = os.path.join(REPO, "tooling", "out", "h32")
CACHE_DIR = r"E:\DocScanAI-cache\bookspread_real"
THUMB_DIR = os.path.join(CACHE_DIR, "thumb")
ORIG_DIR = os.path.join(CACHE_DIR, "original")

THUMB_WIDTH = 1280  # a pre-rendered standard size; Wikimedia asks bulk fetchers to use these

# --- search terms, all recorded -------------------------------------------------------------
# Category names are DISCOVERED via the API first (namespace 14), then used as generators.
CATEGORY_PROBES = ["open books", "books in use", "reading books", "book spreads", "people reading"]
#: Categories whose members are book/reading photographs BY ORIGIN, so a non-English title is
#: not a reason to reject (e.g. "Godnataesning" = bedtime reading).
BOOK_CATEGORY_RE = re.compile(r"open book|reading|books in use|book page|book spread|book in use", re.I)
# Intitle searches keep the pool about *photographs of open books*.
INTITLE_SEARCHES = [
    'intitle:open intitle:book filemime:image/jpeg',
    'intitle:book intitle:page filemime:image/jpeg',
    'intitle:reading intitle:book filemime:image/jpeg',
    'intitle:book intitle:spread filemime:image/jpeg',
    'intitle:book filemime:image/jpeg incategory:"Open books"',
    'intitle:bible filemime:image/jpeg incategory:"Open books"',
    'intitle:book filemime:image/jpeg incategory:"Reading"',
    'intitle:pages filemime:image/jpeg intitle:book',
    'unsplash intitle:book filemime:image/jpeg',
    'intitle:bücher filemime:image/jpeg intitle:offen',
    'intitle:livre intitle:ouvert filemime:image/jpeg',
]

# Licence short-names we accept. Anything else is rejected (and logged).
ALLOWED_LICENCE_HINTS = ("cc0", "cc by", "cc-by", "public domain", "pd-", "copyrighted free use")
NC_RE = re.compile(r"non[- ]?commercial|\bnc\b", re.I)
#: A candidate must look like a book/page/reading photograph by title OR come from a
#: book-relevant category (kills the wildlife/landscape hits keyword search drags in, but
#: keeps non-English titles from book categories).
BOOK_TITLE_RE = re.compile(
    r"book|page|pages|reading|read|spread|bible|manuscript|novel|textbook|dictionary|"
    r"encyclop|catalog|catalogue|magazine|newspaper|letter|diary|quran|koran|torah|talmud|"
    r"scripture|lexicon|volume|tome|paper|text|printed|print|buch|bücher|taschenbuch|"
    r"livre|libro|knjiga|kniha|książka|kitap|bok|böcker|bøger|lectura|lecture|lesen|"
    r"godnat|roman|codex|gospel|psalm|psalter|lese|bibel|kathechism|katechism",
    re.I,
)
BAD_TITLE_RE = re.compile(
    r"icon|logo|clipart|clip art|diagram|\bmap\b|drawing|painting|illustration|engraving|"
    r"lithograph|woodcut|poster|cartoon|seal|emblem|coat of arms|mockup|render|svg",
    re.I,
)

MIN_WIDTH = 900
MIN_HEIGHT = 700
MAX_ORIGINAL_BYTES = 30 * 1024 * 1024


def log(msg):
    print(msg, flush=True)


def hb(msg):
    """Append a timestamped line to the H32 heartbeat file (long-lane discipline)."""
    try:
        os.makedirs(OUT_DIR, exist_ok=True)
        with open(os.path.join(OUT_DIR, "HEARTBEAT.txt"), "a", encoding="utf-8") as fh:
            fh.write("[%s] %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg))
    except OSError:
        pass


def ensure_dirs():
    for d in (OUT_DIR, THUMB_DIR, ORIG_DIR):
        os.makedirs(d, exist_ok=True)


#: Global pacing: Wikimedia throttles bursts (HTTP 429). One request every N seconds minimum.
LAST_API_CALL = [0.0]
API_GAP_SECONDS = 4.0


def api_get(params):
    """GET the Commons API with pacing + exponential backoff on HTTP 429."""
    params = dict(params)
    params.setdefault("format", "json")
    params.setdefault("formatversion", "2")
    url = API + "?" + urllib.parse.urlencode(params)
    delay = 15.0
    for attempt in range(5):
        gap = API_GAP_SECONDS - (time.time() - LAST_API_CALL[0])
        if gap > 0:
            time.sleep(gap)
        LAST_API_CALL[0] = time.time()
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=60) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            if exc.code == 429 and attempt < 4:
                hb("api 429, backing off %.0fs (attempt %d)" % (delay, attempt + 1))
                time.sleep(delay)
                delay *= 2.0
                continue
            raise
    raise RuntimeError("api_get exhausted retries")


def fetch_bytes(url, max_bytes, tries=4):
    """GET with backoff on HTTP 429 (Wikimedia throttles bulk originals; retry politely)."""
    delay = 8.0
    last = None
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=120) as resp:
                blob = resp.read(max_bytes + 1)
            return blob, None
        except urllib.error.HTTPError as exc:
            last = "HTTP %s: %s" % (exc.code, exc.reason)
            if exc.code == 429 and attempt < tries - 1:
                time.sleep(delay)
                delay *= 2.0
                continue
            return None, last
        except Exception as exc:  # noqa: BLE001
            last = str(exc)
            return None, last
    return None, last


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


def allowed_licence(short, url, usage):
    blob = " ".join(x for x in (short, url, usage) if x).lower()
    if not short:
        return False, "no licence metadata"
    if NC_RE.search(blob):
        return False, "non-commercial licence"
    for hint in ALLOWED_LICENCE_HINTS:
        if hint in blob:
            return True, "ok"
    return False, "licence not in allowed set: %r" % short


def info_to_rec(page, categories):
    title = page.get("title") or ""
    info = (page.get("imageinfo") or [{}])[0]
    meta = info.get("extmetadata") or {}
    short, lic_url, usage = licence_of(meta)
    ok, why = allowed_licence(short, lic_url, usage)
    width = int(info.get("width") or 0)
    height = int(info.get("height") or 0)
    mime = info.get("mime") or ""
    return {
        "title": title,
        "source_page_url": info.get("descriptionurl")
        or ("https://commons.wikimedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_"))),
        "direct_url": info.get("url") or "",
        "thumb_url": info.get("thumburl") or "",
        "thumb_width": int(info.get("thumbwidth") or 0),
        "width": width,
        "height": height,
        "mime": mime,
        "licence_short": short,
        "licence_url": lic_url,
        "usage_terms": usage,
        "attribution": attribution_of(meta),
        "commons_sha1": info.get("sha1") or "",
        "categories": categories,
        "license_ok": ok,
        "license_note": why,
        "title_ok": bool(BOOK_TITLE_RE.search(title)) and not BAD_TITLE_RE.search(title),
        "size_ok": mime == "image/jpeg" and width >= MIN_WIDTH and height >= MIN_HEIGHT,
    }


def discover_categories():
    """Real Commons category names for open-book imagery (namespace 14)."""
    found = []
    for probe in CATEGORY_PROBES:
        try:
            data = api_get({
                "action": "query",
                "list": "search",
                "srsearch": probe,
                "srnamespace": "14",
                "srlimit": "8",
            })
        except Exception as exc:  # noqa: BLE001
            hb("category probe %r ERROR %s" % (probe, exc))
            continue
        for hit in ((data.get("query") or {}).get("search")) or []:
            title = hit.get("title") or ""
            if title.startswith("Category:") and re.search(r"book|reading|page", title, re.I):
                if title not in found:
                    found.append(title)
    return found[:10]


def search_all():
    ensure_dirs()
    queries_log = []
    by_title = {}

    def absorb(pages, origin):
        for page in pages:
            title = page.get("title") or ""
            if not title.startswith("File:"):
                continue
            if title in by_title:
                by_title[title]["found_by"] = sorted(set(by_title[title]["found_by"] + [origin]))
                continue
            rec = info_to_rec(page, [])
            rec["found_by"] = [origin]
            rec["category_ok"] = any(
                BOOK_CATEGORY_RE.search(o.split(":", 1)[1] if ":" in o else o)
                for o in rec["found_by"] if o.startswith("category:")
            )
            by_title[title] = rec

    info_props = "url|size|mime|extmetadata|sha1"

    categories = discover_categories()
    queries_log.append({"kind": "category_discovery", "categories": categories})
    hb("categories discovered: %s" % categories)
    for cat in categories:
        cont = None
        total = 0
        for _page_no in range(3):  # up to 150 files per category
            params = {
                "action": "query",
                "generator": "categorymembers",
                "gcmtitle": cat,
                "gcmtype": "file",
                "gcmlimit": "50",
                "prop": "imageinfo",
                "iiprop": info_props,
                "iiurlwidth": str(THUMB_WIDTH),
            }
            if cont:
                params["gcmcontinue"] = cont
            started = time.time()
            try:
                data = api_get(params)
                pages = ((data.get("query") or {}).get("pages")) or []
            except Exception as exc:  # noqa: BLE001
                queries_log.append({"kind": "category", "query": cat, "error": str(exc)})
                break
            absorb(pages, "category:" + cat)
            total += len(pages)
            cont = (data.get("continue") or {}).get("gcmcontinue")
            queries_log.append({"kind": "category", "query": cat, "page": _page_no, "results": len(pages), "seconds": round(time.time() - started, 2)})
            if not cont:
                break
        hb("category %s -> %d files" % (cat, total))

    for query in INTITLE_SEARCHES:
        params = {
            "action": "query",
            "generator": "search",
            "gsrsearch": query,
            "gsrnamespace": "6",
            "gsrlimit": "40",
            "prop": "imageinfo",
            "iiprop": info_props,
            "iiurlwidth": str(THUMB_WIDTH),
        }
        started = time.time()
        try:
            data = api_get(params)
            pages = ((data.get("query") or {}).get("pages")) or []
        except Exception as exc:  # noqa: BLE001
            queries_log.append({"kind": "search", "query": query, "error": str(exc)})
            continue
        absorb(pages, "search:" + query)
        queries_log.append({"kind": "search", "query": query, "results": len(pages), "seconds": round(time.time() - started, 2)})
        hb("search %r -> %d files" % (query, len(pages)))

    def accepted(r):
        if not (r["license_ok"] and r["size_ok"]):
            return False
        # Either the title says "book/reading/page..." in any of the tracked languages, or the
        # file was found via a book-relevant Commons category (non-English titles survive).
        return r["title_ok"] or r.get("category_ok")

    candidates = [r for r in by_title.values() if accepted(r)]
    rejected = [r for r in by_title.values() if not accepted(r)]
    candidates.sort(key=lambda r: r["title"])
    rejected.sort(key=lambda r: r["title"])
    with open(os.path.join(OUT_DIR, "queries.json"), "w", encoding="utf-8") as fh:
        json.dump(queries_log, fh, indent=2)
    with open(os.path.join(OUT_DIR, "commons_candidates.json"), "w", encoding="utf-8") as fh:
        json.dump(candidates, fh, indent=2)
    with open(os.path.join(OUT_DIR, "commons_rejected.json"), "w", encoding="utf-8") as fh:
        json.dump(rejected, fh, indent=2)
    hb("search done: %d candidates, %d rejected" % (len(candidates), len(rejected)))
    log("candidates=%d rejected=%d" % (len(candidates), len(rejected)))


def safe_name(title, ext=".jpg"):
    name = re.sub(r"[^A-Za-z0-9._-]+", "_", title.replace("File:", ""))[:110]
    return name if name.lower().endswith((".jpg", ".jpeg")) else name + ext


def _prov_rec(rec, dest, blob, kind, url):
    out = dict(rec)
    out["fetched_url"] = url
    out["fetched_kind"] = kind
    out["local_path"] = dest
    out["bytes"] = len(blob)
    out["sha256"] = hashlib.sha256(blob).hexdigest()
    return out


def load_json(path, default):
    if os.path.exists(path):
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    return default


def download_thumbs(limit):
    ensure_dirs()
    candidates = load_json(os.path.join(OUT_DIR, "commons_candidates.json"), None)
    if not candidates:
        log("run `search` first")
        return 2
    prov_path = os.path.join(OUT_DIR, "provenance_thumbs.json")
    provenance = load_json(prov_path, {})
    done = 0
    for rec in candidates:
        if limit and done >= limit:
            break
        title = rec["title"]
        if provenance.get(title, {}).get("sha256"):
            continue
        url = rec.get("thumb_url") or ""
        if not url:
            provenance[title] = dict(rec, error="no thumb url")
            continue
        dest = os.path.join(THUMB_DIR, safe_name(title))
        if os.path.exists(dest):
            with open(dest, "rb") as fh:
                blob = fh.read()
            provenance[title] = _prov_rec(rec, dest, blob, "commons-thumb-%d" % THUMB_WIDTH, url)
            continue
        blob, err = fetch_bytes(url, 12 * 1024 * 1024, tries=4)
        if blob is None:
            provenance[title] = dict(rec, error=err)
            hb("thumb ERROR %s: %s" % (title, err))
            with open(prov_path, "w", encoding="utf-8") as fh:
                json.dump(provenance, fh, indent=2)
            if err and "429" in err:
                time.sleep(20.0)
            continue
        with open(dest, "wb") as fh:
            fh.write(blob)
        provenance[title] = _prov_rec(rec, dest, blob, "commons-thumb-%d" % THUMB_WIDTH, url)
        done += 1
        hb("thumb %s (%d B)" % (os.path.basename(dest), len(blob)))
        log("thumb %-58s %8d B" % (os.path.basename(dest)[:58], len(blob)))
        # Persist every file so an interrupted lane keeps its provenance.
        with open(prov_path, "w", encoding="utf-8") as fh:
            json.dump(provenance, fh, indent=2)
        time.sleep(1.5)
    with open(prov_path, "w", encoding="utf-8") as fh:
        json.dump(provenance, fh, indent=2)
    got = [k for k, v in provenance.items() if v.get("sha256")]
    hb("thumbs done: %d on disk" % len(got))
    log("thumbs on disk: %d" % len(got))
    return 0


def download_originals():
    """Originals for the SELECTED finalists only (selection.json written after `sheet`)."""
    ensure_dirs()
    selection = load_json(os.path.join(OUT_DIR, "selection.json"), None)
    if not selection:
        log("run `sheet` and write selection.json first")
        return 2
    prov_path = os.path.join(OUT_DIR, "provenance.json")
    provenance = load_json(prov_path, {})
    for sel in selection:
        title = sel["title"]
        if provenance.get(title, {}).get("sha256_original"):
            continue
        rec = sel.get("record") or {}
        url = rec.get("direct_url") or ""
        if not url:
            provenance[title] = dict(rec, error="no direct url")
            continue
        dest = os.path.join(ORIG_DIR, safe_name(title))
        if not os.path.exists(dest):
            blob, err = fetch_bytes(url, MAX_ORIGINAL_BYTES, tries=4)
            if blob is None:
                provenance[title] = dict(rec, error=err)
                hb("original ERROR %s: %s" % (title, err))
                if err and "429" in err:
                    time.sleep(25.0)
                continue
            with open(dest, "wb") as fh:
                fh.write(blob)
        else:
            with open(dest, "rb") as fh:
                blob = fh.read()
        out = dict(rec)
        out["local_path_original"] = dest
        out["bytes_original"] = len(blob)
        out["sha256_original"] = hashlib.sha256(blob).hexdigest()
        out["fetched_kind"] = "commons-original"
        out["fetched_url"] = url
        provenance[title] = out
        hb("original %s (%d B)" % (os.path.basename(dest), len(blob)))
        log("original %-56s %9d B" % (os.path.basename(dest)[:56], len(blob)))
        time.sleep(2.0)
    with open(prov_path, "w", encoding="utf-8") as fh:
        json.dump(provenance, fh, indent=2)
    got = [k for k, v in provenance.items() if v.get("sha256_original")]
    hb("originals done: %d on disk" % len(got))
    log("originals on disk: %d" % len(got))
    return 0


def write_sheets():
    """Contact sheets (JPEG) of the thumb pool, 4 cols, labelled with the pool index."""
    from PIL import Image, ImageDraw

    ensure_dirs()
    prov_path = os.path.join(OUT_DIR, "provenance_thumbs.json")
    provenance = load_json(prov_path, {})
    items = [(t, v) for t, v in sorted(provenance.items()) if v.get("sha256")]
    if not items:
        log("nothing on disk yet")
        return 2

    index_map = []
    cols, cell = 4, 360
    per_sheet = 16
    for page_no, start in enumerate(range(0, len(items), per_sheet), 1):
        chunk = items[start:start + per_sheet]
        rows = (len(chunk) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * cell, rows * (cell + 22)), "white")
        draw = ImageDraw.Draw(sheet)
        for i, (title, rec) in enumerate(chunk):
            pool_index = start + i + 1
            index_map.append({
                "pool_index": pool_index,
                "title": title,
                "local_path": rec["local_path"],
                "licence_short": rec.get("licence_short", ""),
                "licence_url": rec.get("licence_url", ""),
                "attribution": rec.get("attribution", ""),
                "source_page_url": rec.get("source_page_url", ""),
                "direct_url": rec.get("direct_url", ""),
                "thumb_url": rec.get("thumb_url", ""),
                "width": rec.get("width", 0),
                "height": rec.get("height", 0),
                "sha256_thumb": rec.get("sha256", ""),
                "bytes_thumb": rec.get("bytes", 0),
                "found_by": rec.get("found_by", []),
            })
            try:
                im = Image.open(rec["local_path"]).convert("RGB")
            except Exception:  # noqa: BLE001
                continue
            im.thumbnail((cell, cell))
            x = (i % cols) * cell
            y = (i // cols) * (cell + 22)
            sheet.paste(im, (x + (cell - im.width) // 2, y + (cell - im.height) // 2))
            draw.text((x + 4, y + cell + 4), "#%d %s" % (pool_index, title.replace("File:", "")[:44]), fill="black")
        out = os.path.join(OUT_DIR, "sheet_real_%02d.jpg" % page_no)
        sheet.save(out, quality=88)
        log("wrote %s (%d images)" % (os.path.basename(out), len(chunk)))
    with open(os.path.join(OUT_DIR, "pool_index.json"), "w", encoding="utf-8") as fh:
        json.dump(index_map, fh, indent=2)
    hb("sheets written, pool=%d" % len(items))
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["search", "thumbs", "sheet", "originals", "all"])
    ap.add_argument("--limit", type=int, default=0, help="max downloads (0 = all candidates)")
    args = ap.parse_args()
    if args.mode in ("search", "all"):
        search_all()
    if args.mode in ("thumbs", "all"):
        download_thumbs(args.limit)
    if args.mode in ("sheet", "all"):
        write_sheets()
    if args.mode in ("originals", "all"):
        download_originals()
    return 0


if __name__ == "__main__":
    sys.exit(main())
