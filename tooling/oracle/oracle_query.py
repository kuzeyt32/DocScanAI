#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H33 — oracle harness, single image -> pseudo ground truth (Python stdlib ONLY).

Queries an OpenAI-compatible chat-completions server (LM Studio hosting
`Qwen3-VL-8B-Instruct`, GGUF Q8_0) with ONE image and asks for the four corners
of the **fully visible page** as strict JSON, normalized 0..1 (TL, TR, BR, BL).

This script NEVER downloads models and never writes outside --out. The response
is parsed leniently but the OUTPUT schema is strict:

    {
      "image": "<path>",
      "width": int, "height": int,
      "target_page": "<free text from --target-page>",
      "corners": {"tl": [x, y], "tr": [x, y], "br": [x, y], "bl": [x, y]},
      "raw": "<first JSON object found in the model reply>",
      "server": "...", "model": "...",
      "ok": true|false
    }

Usage:
  python tooling\\oracle\\oracle_query.py --image photo.jpg --target-page "the left page" \\
      --out pseudo_gt.json
  python tooling\\oracle\\oracle_query.py --selftest     # offline; no network, no image read

Selftest uses a canned server reply and proves the request/reply plumbing and
the strict-JSON corner extraction without touching LM Studio.
"""

from __future__ import annotations

import argparse
import base64
import json
import mimetypes
import os
import re
import sys
import urllib.error
import urllib.request

DEFAULT_SERVER = "http://localhost:1234/v1/chat/completions"
DEFAULT_MODEL = "qwen3-vl-8b-instruct"

#: The prompt. Deliberately asks for ONE json object and normalized corners.
PROMPT = (
    "You are a document-scanning ground-truth annotator. Find the FULLY VISIBLE "
    "page in this photo (target: {target}). A book spread may show a second page "
    "partially - do NOT include it; only annotate the target page that is fully "
    "visible. Return EXACTLY one JSON object and nothing else, with this schema: "
    '{{"tl": [x, y], "tr": [x, y], "br": [x, y], "bl": [x, y]}} where the keys are '
    "the four corners top-left, top-right, bottom-right, bottom-left of the page, "
    "each normalized to 0..1 of the image width (x) and height (y). "
    "No markdown, no commentary, no other keys."
)

CANNED_REPLY = (
    'Here is the annotation:\n'
    '{"tl": [0.12, 0.10], "tr": [0.88, 0.11], "br": [0.87, 0.90], "bl": [0.13, 0.89]}'
)

CORNER_KEYS = ("tl", "tr", "br", "bl")


def build_prompt(target_page: str) -> str:
    return PROMPT.format(target=target_page or "the fully visible page")


def build_request(image_b64: str, model: str, target_page: str) -> dict:
    """The exact JSON body that would be POSTed to the server (selftest inspects it)."""
    return {
        "model": model,
        "temperature": 0.0,
        "max_tokens": 512,
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": build_prompt(target_page)},
                    {
                        "type": "image_url",
                        "image_url": {"url": "data:image/jpeg;base64," + image_b64},
                    },
                ],
            }
        ],
    }


def post_json(url: str, body: dict, timeout: float) -> dict:
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        url, data=data, headers={"Content-Type": "application/json"}, method="POST"
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:  # nosec B310 (fixed URL)
        return json.loads(resp.read().decode("utf-8"))


def extract_reply_content(response: dict) -> str:
    try:
        return str(response["choices"][0]["message"]["content"])
    except (KeyError, IndexError, TypeError):
        return ""


def extract_corners(text: str):
    """First strict-JSON object in [text] whose keys cover tl/tr/br/bl -> corners dict."""
    if not text:
        return None
    # Try whole text first, then progressively shorter object slices from the first '{'.
    candidates = [text]
    idx = text.find("{")
    while idx >= 0:
        candidates.append(text[idx:])
        idx = text.find("{", idx + 1)
    for cand in candidates:
        end = cand.rfind("}")
        if end < 0:
            continue
        for stop in range(end + 1, max(len(cand), end) + 1):
            frag = cand[:stop]
            try:
                obj = json.loads(frag)
            except ValueError:
                continue
            if isinstance(obj, dict) and all(k in obj for k in CORNER_KEYS):
                corners = {k: obj[k] for k in CORNER_KEYS}
                if all(
                    isinstance(corners[k], (list, tuple))
                    and len(corners[k]) == 2
                    and all(isinstance(c, (int, float)) for c in corners[k])
                    for k in CORNER_KEYS
                ):
                    return corners
    return None


def clamp01(value: float) -> float:
    return max(0.0, min(1.0, float(value)))


def normalize_corners(corners: dict) -> dict:
    out = {}
    for k in CORNER_KEYS:
        x, y = corners[k]
        out[k] = [clamp01(x), clamp01(y)]
    return out


def image_data_url(image_path: str):
    mime = mimetypes.guess_type(image_path)[0] or "image/jpeg"
    with open(image_path, "rb") as fh:
        b64 = base64.b64encode(fh.read()).decode("ascii")
    return b64, mime


def probe_size(image_path: str):
    """width, height of a PNG/JPEG/GIF/BMP via header sniffing (stdlib only)."""
    with open(image_path, "rb") as fh:
        head = fh.read(64)
        if head.startswith(b"\x89PNG\r\n\x1a\n"):
            fh.seek(16)
            w = int.from_bytes(fh.read(4), "big")
            h = int.from_bytes(fh.read(4), "big")
            return w, h
        if head.startswith(b"\xff\xd8"):
            fh.seek(2)
            while True:
                marker = fh.read(2)
                if len(marker) < 2 or marker[0] != 0xFF:
                    return None
                code = marker[1]
                if code in (0xD8, 0xD9, 0x01) or (0xD0 <= code <= 0xD7):
                    continue
                seg = int.from_bytes(fh.read(2), "big")
                if seg < 2:
                    return None
                if code in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
                            0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                    fh.read(1)
                    h = int.from_bytes(fh.read(2), "big")
                    w = int.from_bytes(fh.read(2), "big")
                    return w, h
                fh.seek(seg - 2, os.SEEK_CUR)
        if head.startswith(b"GIF8"):
            w = int.from_bytes(head[6:8], "little")
            h = int.from_bytes(head[8:10], "little")
            return w, h
        if head.startswith(b"BM"):
            fh.seek(18)
            w = int.from_bytes(fh.read(4), "little")
            h = abs(int.from_bytes(fh.read(4), "little", signed=True))
            return w, h
    return None


def run_query(image: str, server: str, model: str, target_page: str, out: str, timeout: float) -> int:
    b64, _mime = image_data_url(image)
    size = probe_size(image)
    body = build_request(b64, model, target_page)
    record = {
        "image": os.path.abspath(image),
        "width": size[0] if size else None,
        "height": size[1] if size else None,
        "target_page": target_page,
        "server": server,
        "model": model,
        "corners": None,
        "raw": None,
        "ok": False,
    }
    try:
        response = post_json(server, body, timeout)
        content = extract_reply_content(response)
        record["raw"] = content
        corners = extract_corners(content)
        if corners is not None:
            record["corners"] = normalize_corners(corners)
            record["ok"] = True
        else:
            record["error"] = "no strict-JSON tl/tr/br/bl object in reply"
    except (urllib.error.URLError, OSError, ValueError) as exc:
        record["error"] = "server error: %s" % exc

    if out:
        with open(out, "w", encoding="utf-8") as fh:
            json.dump(record, fh, indent=2)
    print(json.dumps(record, indent=2))
    return 0 if record["ok"] else 1


def run_selftest() -> int:
    """Offline plumbing check: canned reply in, strict corners out. No network, no file IO."""
    failures = []

    # 1) request body shape (what would be POSTed)
    body = build_request("<B64>", DEFAULT_MODEL, "the left page")
    content = body["messages"][0]["content"]
    if body["model"] != DEFAULT_MODEL:
        failures.append("request model wrong")
    if not (isinstance(content, list) and content[0]["type"] == "text"
            and content[1]["type"] == "image_url"):
        failures.append("request multimodal shape wrong")
    if "the left page" not in content[0]["text"]:
        failures.append("target-page not injected into prompt")
    if not content[1]["image_url"]["url"].startswith("data:image/"):
        failures.append("image_url data URL missing")

    # 2) reply extraction from a canned, slightly chatty response
    corners = extract_corners(CANNED_REPLY)
    if corners is None:
        failures.append("canned reply not parsed")
    else:
        norm = normalize_corners(corners)
        expect = {"tl": [0.12, 0.10], "tr": [0.88, 0.11], "br": [0.87, 0.90], "bl": [0.13, 0.89]}
        for k in CORNER_KEYS:
            if [round(v, 4) for v in norm[k]] != [round(v, 4) for v in expect[k]]:
                failures.append("corner %s = %s, expected %s" % (k, norm[k], expect[k]))

    # 3) markdown-fenced reply must still parse
    fenced = "```json\n" + CANNED_REPLY.split("\n", 1)[1] + "\n```"
    if extract_corners(fenced) is None:
        failures.append("fenced reply not parsed")

    # 4) clamping keeps output normalized
    wild = extract_corners('{"tl": [-2, 3], "tr": [1.5, 0.2], "br": [0.5, 0.5], "bl": [0, 1]}')
    if wild is None:
        failures.append("wild reply not parsed")
    else:
        norm = normalize_corners(wild)
        if norm["tl"] != [0.0, 1.0] or norm["tr"] != [1.0, 0.2]:
            failures.append("clamping wrong: %s" % norm)

    # 5) garbage reply -> None (caller marks ok=false)
    if extract_corners("sorry, I cannot help") is not None:
        failures.append("garbage reply produced corners")

    if failures:
        print("SELFTEST FAIL")
        for f in failures:
            print("  - " + f)
        return 1
    print("SELFTEST OK  (request shape, strict-JSON corners, fenced reply, clamping)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Oracle: one image -> pseudo GT corners (LM Studio)")
    ap.add_argument("--image", help="input image (any common format)")
    ap.add_argument("--server", default=DEFAULT_SERVER, help="chat completions URL")
    ap.add_argument("--model", default=DEFAULT_MODEL, help="model name as served by LM Studio")
    ap.add_argument("--target-page", default="the fully visible page",
                    help="which page to annotate (free text)")
    ap.add_argument("--out", help="output pseudo_gt.json path")
    ap.add_argument("--timeout", type=float, default=180.0, help="HTTP timeout seconds")
    ap.add_argument("--selftest", action="store_true", help="offline canned-response test")
    args = ap.parse_args()

    if args.selftest:
        return run_selftest()
    if not args.image:
        ap.error("--image is required (or --selftest)")
    if not os.path.isfile(args.image):
        ap.error("image not found: %s" % args.image)
    out = args.out or os.path.splitext(args.image)[0] + ".pseudo_gt.json"
    return run_query(args.image, args.server, args.model, args.target_page, out, args.timeout)


if __name__ == "__main__":
    sys.exit(main())
