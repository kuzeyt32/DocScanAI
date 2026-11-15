#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""H29 book-spread fixture generator.

Deterministic, host-side, Pillow + numpy only (no OpenCV on this machine).

Why synthetic: the failure under test is "one page of an open book, with the facing
page still visible as a sliver across a shadowed gutter". Real photographs of that
scene do not carry exact corner coordinates, and the whole point of the lane is to
measure corner error against ground truth. So every fixture is *rendered*, which makes
the GT exact by construction:

  * the spread is a texture: [facing page][gutter][target page]  (or the mirror)
  * the texture rectangle is mapped into the frame by a homography H
  * GT page corners   = H applied to the target page's texture corners
  * GT spread corners = H applied to the whole texture rectangle
  * GT sliver corners = H applied to the facing page's texture corners
  * curvature / gutter foreshortening are multiplied by sin(pi*v), so they vanish at
    v = 0 and v = 1 => the four page corners are never moved, even in curved variants.

Each fixture is rendered twice, from one composed geometry expressed as `spread_fill`
(the spread's width as a fraction of the frame width), because the two canvases have
different aspect ratios:

  * still   3024x4032 - 12.2 MP, what ImageCapture(CAPTURE_MODE_MAXIMIZE_QUALITY)
              delivers on a current phone; this is the import path the owner reported
  * preview 1280x720  - the ImageAnalysis ResolutionSelector target; the live path

GT coordinates are recomputed per canvas, so both are exact.

Usage:
  python tooling\\h29_fixtures.py            # generate assets + manifest + overlays
  python tooling\\h29_fixtures.py --list     # print the layout table only
"""

import argparse
import hashlib
import json
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
ASSET_DIR = os.path.join(REPO, "app", "src", "androidTest", "assets", "bookspread")
OUT_DIR = os.path.join(HERE, "out", "h29", "fixtures")
MANIFEST = os.path.join(ASSET_DIR, "book_spread_manifest.json")

STILL_WH = (3024, 4032)
PREVIEW_WH = (1280, 720)
PAGE_ASPECT = 0.7071      # page width / page height (A4/Letter-ish portrait)
BAND = 256
GUTTER_INSET = 0.035      # gutter-only: shadow sits this far inside the page edge

# ---------------------------------------------------------------- layout table
# side        : which side of the frame the *facing page sliver* appears on
# sliver_frac : visible facing-page width, as a fraction of the page width
# fill_still / fill_preview : spread width as a fraction of the frame width
# gut_dark    : gutter shadow depth (0..1)
# curl        : outline bend amplitude in texture-u units (bends the page edge)
# squeeze     : content compression toward the spine (foreshortening)
# persp       : corner jitter, in units of page height
# illum       : uneven-lighting amplitude
LAYOUTS = [
    dict(name="bs01_control_clean", variant="control_clean", side=None, sliver_frac=0.0,
         fill_still=0.849, fill_preview=0.366, gut_dark=0.0, curl=0.0, squeeze=0.0,
         persp=0.010, illum=0.08, seed=2901,
         note="single page, no gutter, mild perspective"),
    dict(name="bs02_sliver_left_thin", variant="sliver_left_thin", side="left",
         sliver_frac=0.08, fill_still=0.896, fill_preview=0.395, gut_dark=0.55,
         curl=0.0, squeeze=0.10, persp=0.012, illum=0.10, seed=2902,
         note="8% facing-page sliver on the left"),
    dict(name="bs03_sliver_right_thin", variant="sliver_right_thin", side="right",
         sliver_frac=0.08, fill_still=0.896, fill_preview=0.395, gut_dark=0.55,
         curl=0.0, squeeze=0.10, persp=0.012, illum=0.10, seed=2903,
         note="8% facing-page sliver on the right"),
    dict(name="bs04_sliver_left_medium", variant="sliver_left_medium", side="left",
         sliver_frac=0.18, fill_still=0.934, fill_preview=0.431, gut_dark=0.65,
         curl=0.0, squeeze=0.12, persp=0.014, illum=0.12, seed=2904,
         note="18% sliver on the left"),
    dict(name="bs05_sliver_right_large", variant="sliver_right_large", side="right",
         sliver_frac=0.35, fill_still=0.942, fill_preview=0.494, gut_dark=0.70,
         curl=0.0, squeeze=0.12, persp=0.014, illum=0.12, seed=2905,
         note="35% sliver on the right"),
    dict(name="bs06_gutter_only", variant="gutter_only", side="right", sliver_frac=0.0,
         fill_still=0.830, fill_preview=0.366, gut_dark=0.62, curl=0.0, squeeze=0.14,
         persp=0.012, illum=0.10, seed=2906,
         note="no facing page; strong gutter shadow inside the right edge"),
    dict(name="bs07_curved_spine", variant="curved_spine", side="left", sliver_frac=0.12,
         fill_still=0.908, fill_preview=0.410, gut_dark=0.60, curl=0.030, squeeze=0.16,
         persp=0.014, illum=0.12, seed=2907,
         note="12% sliver + visibly curved page outline"),
    dict(name="bs08_sliver_left_lowlight", variant="sliver_left_lowlight", side="left",
         sliver_frac=0.08, fill_still=0.876, fill_preview=0.395, gut_dark=0.70,
         curl=0.0, squeeze=0.10, persp=0.016, illum=0.30, seed=2908,
         note="thin sliver, uneven dim lighting, low contrast"),
    dict(name="bs09_control_strong_persp", variant="control_strong_persp", side=None,
         sliver_frac=0.0, fill_still=0.811, fill_preview=0.366, gut_dark=0.0, curl=0.0,
         squeeze=0.0, persp=0.035, illum=0.12, seed=2909,
         note="clean control with strong perspective"),
]


# ------------------------------------------------------------------ utilities
def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def homography_square_to_quad(q):
    """H mapping (u,v) in the unit square to quad q = [TL,TR,BR,BL]."""
    (x0, y0), (x1, y1), (x2, y2), (x3, y3) = q
    a11 = x1 - x2
    a12 = x3 - x2
    b1 = x2 - x1 - x3 + x0
    a21 = y1 - y2
    a22 = y3 - y2
    b2 = y2 - y1 - y3 + y0
    det = a11 * a22 - a12 * a21
    if abs(det) < 1e-12:
        raise ValueError("degenerate quad")
    g = (b1 * a22 - b2 * a12) / det
    h = (a11 * b2 - a21 * b1) / det
    c = x0
    f = y0
    a = x1 * (g + 1.0) - x0
    d = y1 * (g + 1.0) - y0
    b = x3 * (h + 1.0) - x0
    e = y3 * (h + 1.0) - y0
    return np.array([[a, b, c], [d, e, f], [g, h, 1.0]], dtype=np.float64)


def smoothstep(edge0, edge1, x):
    t = np.clip((x - edge0) / max(1e-9, (edge1 - edge0)), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def lowres_field(w, h, seed, cells=24, sigma=0.6):
    rng = np.random.default_rng(seed)
    small = rng.normal(0.0, 1.0, (cells, cells)).astype(np.float32)
    arr = np.asarray(Image.fromarray(small, mode="F").resize((w, h), Image.BILINEAR),
                     dtype=np.float32)
    m = float(arr.mean())
    s = float(arr.std()) or 1.0
    arr = np.clip(0.5 + sigma * (arr - m) / s, 0.0, 1.0)
    return arr


# -------------------------------------------------------------- page texture
def page_bounds(geo):
    """Texture-space (s0, s1) of the target page and the spine position."""
    f = geo["sliver_frac"]
    side = geo["side"]
    spread_w = 1.0 + f
    if f > 0.0 and side == "left":
        # facing page sits on the LEFT of the frame -> left of the texture
        s0, s1 = f / spread_w, 1.0
        spine = s0
    elif f > 0.0 and side == "right":
        s0, s1 = 0.0, 1.0 / spread_w
        spine = s1
    else:
        s0, s1 = 0.0, 1.0
        spine = (1.0 - GUTTER_INSET) if side == "right" else GUTTER_INSET
    return s0, s1, spine


def build_texture(geo):
    s0, s1, spine = page_bounds(geo)
    f = geo["sliver_frac"]
    side = geo["side"]
    spread_w = 1.0 + f

    tex_w = 2048
    tex_h = int(round(tex_w / (spread_w * PAGE_ASPECT)))
    rng = np.random.default_rng(geo["seed"] + 100)
    base = rng.normal(233.0, 2.0, (tex_h, tex_w)).astype(np.float32)
    fibre = lowres_field(tex_w, tex_h, geo["seed"] + 101, cells=48, sigma=0.55) - 0.5
    base = base + 5.0 * fibre
    img = np.clip(np.repeat(base[:, :, None], 3, axis=2), 0, 255).astype(np.uint8)
    pil = Image.fromarray(img, mode="RGB")
    draw = ImageDraw.Draw(pil)

    ph = tex_h
    lh = max(5, int(round(ph * 0.0105)))
    stroke = max(1, int(round(lh * 0.34)))

    def draw_page(pa, pb, seed, ink0):
        rng2 = np.random.default_rng(seed)
        narrow = (pb - pa) < 0.12 * tex_w
        x0 = pa + (0.030 if narrow else 0.085) * (pb - pa)
        x1 = pb - (0.025 if narrow else 0.075) * (pb - pa)
        if x1 - x0 < 8:
            return
        y = int(0.080 * ph)
        y_end = int(0.930 * ph)
        draw.rectangle([x0, y, x0 + 0.38 * (x1 - x0), y + max(2, int(lh * 0.55))],
                       fill=max(0, ink0 - 22))
        y += int(lh * 2.4)
        while y < y_end:
            para = int(rng2.integers(4, 11))
            for i in range(para):
                if y >= y_end:
                    break
                if i == para - 1 and rng2.random() < 0.5:
                    frac = float(rng2.uniform(0.22, 0.58))
                else:
                    frac = float(rng2.uniform(0.90, 1.0))
                ink = int(np.clip(ink0 + rng2.normal(0.0, 7.0), 30, 150))
                draw.rectangle([x0, y, x0 + frac * (x1 - x0), y + stroke], fill=ink)
                y += int(lh * 1.38)
            y += int(lh * 0.62)

    ink_target = int(rng.integers(56, 78))
    ink_other = int(rng.integers(56, 78))
    draw_page(int(s0 * tex_w), int(s1 * tex_w), geo["seed"] + 200, ink_target)
    if f > 0.0:
        if side == "left":
            draw_page(0, int(s0 * tex_w), geo["seed"] + 300, ink_other)
        else:
            draw_page(int(s1 * tex_w), tex_w, geo["seed"] + 300, ink_other)

    arr = np.asarray(pil, dtype=np.float32).copy()

    # Gutter shadow, centred on the spine. A narrow sliver must stay visibly bright
    # paper on its far side, otherwise the scene is not the failure we are testing.
    if geo["gut_dark"] > 0.0:
        xs = (np.arange(tex_w, dtype=np.float32) + 0.5) / tex_w
        d = np.abs(xs - spine) * spread_w         # in page-width units
        sig = 0.050 if f == 0.0 else 0.018
        shade = 1.0 - geo["gut_dark"] * np.exp(-(d / sig) ** 2)
        arr *= shade[None, :, None]

    # the facing page is a different page: very slightly cooler paper stock
    if f > 0.0:
        if side == "left":
            arr[:, : int(s0 * tex_w)] *= np.array([0.990, 0.995, 1.0], np.float32)
        else:
            arr[:, int(s1 * tex_w):] *= np.array([0.990, 0.995, 1.0], np.float32)

    return np.clip(arr, 0, 255).astype(np.uint8), s0, s1, spine


# ----------------------------------------------------------------- background
def build_table(w, h, geo):
    rng = np.random.default_rng(geo["seed"] + 11)
    base = np.array([96.0, 68.0, 48.0], np.float32) * rng.uniform(0.85, 1.05)
    fields = lowres_field(w, h, geo["seed"] + 12, cells=16, sigma=0.9)
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    wood = 0.06 * np.sin((xs / max(1.0, w)) * 34.0 + 3.0 * fields)
    mult = np.clip(1.0 + 0.16 * (fields - 0.5) + wood, 0.4, 1.6)
    img = base[None, None, :] * mult[:, :, None]
    img += rng.normal(0.0, 1.8, (h, w, 1)).astype(np.float32)
    return np.clip(img, 0, 255)


# -------------------------------------------------------------------- render
def render(w, h, geo, fill):
    tex, s0, s1, spine = build_texture(geo)
    th, tw = tex.shape[:2]
    f = geo["sliver_frac"]
    side = geo["side"]

    spread_w = fill * w
    page_w = spread_w / (1.0 + f)
    page_h = page_w / PAGE_ASPECT
    if page_h > 0.97 * h:
        raise SystemExit("%s: page does not fit at fill=%.3f on %dx%d"
                         % (geo["name"], fill, w, h))

    cx, cy = w / 2.0, h / 2.0
    sx0, sx1 = cx - spread_w / 2.0, cx + spread_w / 2.0
    sy0, sy1 = cy - page_h / 2.0, cy + page_h / 2.0

    corners = np.array([[sx0, sy0], [sx1, sy0], [sx1, sy1], [sx0, sy1]], np.float64)
    rng = np.random.default_rng(geo["seed"] + 5)
    corners = corners + rng.uniform(-1.0, 1.0, (4, 2)) * geo["persp"] * page_h

    Hm = homography_square_to_quad(corners)
    Hinv = np.linalg.inv(Hm)

    out = build_table(w, h, geo)
    mask_full = np.zeros((h, w), np.float32)

    span_px = float(np.hypot(*(corners[1] - corners[0])))
    edge_u = 1.4 / max(1.0, span_px)
    sig_spine = 0.16
    sig_edge = 0.16
    outer = s1 if abs(spine - s0) < 1e-9 else s0

    for y0 in range(0, h, BAND):
        y1 = min(h, y0 + BAND)
        yy, xx = np.mgrid[y0:y1, 0:w]
        px = xx.astype(np.float64) + 0.5
        py = yy.astype(np.float64) + 0.5
        den = Hinv[2, 0] * px + Hinv[2, 1] * py + Hinv[2, 2]
        den = np.where(np.abs(den) > 1e-12, den, 1.0)
        u = (Hinv[0, 0] * px + Hinv[0, 1] * py + Hinv[0, 2]) / den
        v = (Hinv[1, 0] * px + Hinv[1, 1] * py + Hinv[1, 2]) / den

        amp = np.sin(np.pi * np.clip(v, 0.0, 1.0))
        u_s = u.copy()
        if geo["squeeze"] > 0.0:
            u_s = spine + (u_s - spine) * (
                1.0 - geo["squeeze"] * amp * np.exp(-((u - spine) / sig_spine) ** 2))
        if geo["curl"] > 0.0:
            u_s = u_s - geo["curl"] * amp * np.exp(-((u - outer) / sig_edge) ** 2)

        # The paper is the whole texture -- target page AND facing page -- so the mask
        # must span the spread. Masking to [s0, s1] silently deleted the facing page
        # while the manifest still advertised a sliver, i.e. it faked the exact failure
        # this dataset exists to measure.
        alpha = (smoothstep(-edge_u, edge_u, u_s)
                 * (1.0 - smoothstep(1.0 - edge_u, 1.0 + edge_u, u_s))
                 * smoothstep(-edge_u, edge_u, v)
                 * (1.0 - smoothstep(1.0 - edge_u, 1.0 + edge_u, v)))

        tx = np.clip(u_s, 0.0, 1.0) * (tw - 1)
        ty = np.clip(v, 0.0, 1.0) * (th - 1)
        x0i = np.clip(np.floor(tx).astype(np.int32), 0, tw - 2)
        y0i = np.clip(np.floor(ty).astype(np.int32), 0, th - 2)
        fx = (tx - x0i)[..., None].astype(np.float32)
        fy = (ty - y0i)[..., None].astype(np.float32)
        col = (tex[y0i, x0i].astype(np.float32) * (1 - fx) * (1 - fy)
               + tex[y0i, x0i + 1].astype(np.float32) * fx * (1 - fy)
               + tex[y0i + 1, x0i].astype(np.float32) * (1 - fx) * fy
               + tex[y0i + 1, x0i + 1].astype(np.float32) * fx * fy)

        # The gutter shadow is already baked into the texture (physically correct: it
        # is the paper that is shadowed, so it must follow the paper), so only the
        # falloff at the two outer cut edges of the spread is applied here.
        d_edge = np.minimum(np.abs(u_s), np.abs(1.0 - u_s))
        shade = (1.0 - 0.12 * np.exp(-(d_edge / 0.012) ** 2)).astype(np.float32)
        col = col * shade[:, :, None]

        a = alpha[:, :, None].astype(np.float32)
        out[y0:y1] = out[y0:y1] * (1.0 - a) + col * a
        mask_full[y0:y1] = alpha.astype(np.float32)

    # contact shadow: the book darkens the table around it (every detector uses this)
    small = Image.fromarray((np.clip(mask_full, 0, 1) * 255).astype(np.uint8)).resize(
        (max(2, w // 6), max(2, h // 6)), Image.BILINEAR).filter(
        ImageFilter.GaussianBlur(3.0))
    sh = np.asarray(small.resize((w, h), Image.BILINEAR), np.float32) / 255.0
    out *= (1.0 - 0.32 * sh * (1.0 - mask_full))[:, :, None]

    # uneven lighting
    if geo["illum"] > 0.0:
        rng2 = np.random.default_rng(geo["seed"] + 21)
        small = np.zeros((16, 16), np.float32)
        ys, xs = np.mgrid[0:16, 0:16].astype(np.float32)
        for _ in range(3):
            gx, gy = rng2.uniform(0, 16, 2)
            s = rng2.uniform(2.5, 5.5)
            small += rng2.uniform(0.6, 1.0) * np.exp(
                -(((xs - gx) ** 2 + (ys - gy) ** 2) / (2 * s * s)))
        small /= max(1e-6, small.max())
        field = np.asarray(Image.fromarray(small, mode="F").resize((w, h), Image.BILINEAR))
        out *= (1.0 + geo["illum"] * (field - 0.55))[:, :, None]

    # vignette + sensor noise
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    rx = (xs - w / 2.0) / (w / 2.0)
    ry = (ys - h / 2.0) / (h / 2.0)
    out *= (1.0 - 0.18 * np.clip(rx * rx + ry * ry, 0.0, 1.6))[:, :, None]
    out += np.random.default_rng(geo["seed"] + 31).normal(0.0, 1.6, (h, w, 1)).astype(np.float32)
    img = Image.fromarray(np.clip(out, 0, 255).astype(np.uint8), mode="RGB").filter(
        ImageFilter.GaussianBlur(0.6))

    def hpt(s, t):
        p = Hm @ np.array([s, t, 1.0])
        return [float(p[0] / p[2]), float(p[1] / p[2])]

    gt_page = [hpt(s0, 0.0), hpt(s1, 0.0), hpt(s1, 1.0), hpt(s0, 1.0)]
    gt_spread = [hpt(0.0, 0.0), hpt(1.0, 0.0), hpt(1.0, 1.0), hpt(0.0, 1.0)]
    if f > 0.0:
        gt_sliver = ([hpt(0.0, 0.0), hpt(s0, 0.0), hpt(s0, 1.0), hpt(0.0, 1.0)]
                     if side == "left" else
                     [hpt(s1, 0.0), hpt(1.0, 0.0), hpt(1.0, 1.0), hpt(s1, 1.0)])
    else:
        gt_sliver = None
    meta = dict(w=w, h=h, diagonal=float(np.hypot(w, h)), fill=fill, gt_page=gt_page,
                gt_spread=gt_spread, gt_sliver=gt_sliver)
    return img, meta


# -------------------------------------------------------------------- output
def draw_overlay(img, meta):
    im = img.copy()
    d = ImageDraw.Draw(im)

    def poly(pts, col, wd):
        d.line([tuple(p) for p in pts] + [tuple(pts[0])], fill=col, width=wd)

    poly(meta["gt_spread"], (0, 255, 255), 3)
    if meta["gt_sliver"]:
        poly(meta["gt_sliver"], (255, 0, 255), 3)
    poly(meta["gt_page"], (255, 32, 32), 4)
    for p in meta["gt_page"]:
        d.ellipse([p[0] - 6, p[1] - 6, p[0] + 6, p[1] + 6], outline=(255, 32, 32), width=3)
    return im


# -------------------------------------------------------------------- verify
def _shrink_quad(pts, k):
    cx = sum(p[0] for p in pts) / float(len(pts))
    cy = sum(p[1] for p in pts) / float(len(pts))
    return [(cx + (x - cx) * k, cy + (y - cy) * k) for x, y in pts]


def _quad_mask(shape, pts, k=0.86):
    h, w = shape
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).polygon(_shrink_quad(pts, k), fill=255)
    return np.asarray(m) > 127


def verify_assets():
    """The manifest is a claim about pixels, so check the pixels against it.

    Guards the failure mode that was actually hit while building this dataset: a
    geometry bug that renders the facing page as bare tabletop while the manifest
    still reports a sliver there. Anything downstream would then "score" a scene
    that is not in the image.
    """
    with open(MANIFEST, "r", encoding="utf-8") as f:
        man = json.load(f)

    rows = []
    bad = 0
    for it in man["items"]:
        for tag in ("still", "preview"):
            e = it[tag]
            fp = os.path.join(ASSET_DIR, e["asset"])
            size = os.path.getsize(fp)
            if size != e["asset_bytes"]:
                rows.append((it["name"], tag, "FAIL",
                             "bytes %d != manifest %d" % (size, e["asset_bytes"])))
                bad += 1
                continue
            if sha256_file(fp) != e["asset_sha256"]:
                rows.append((it["name"], tag, "FAIL", "sha256 does not match manifest"))
                bad += 1
                continue

            grey = np.asarray(Image.open(fp).convert("L"), dtype=np.float32)
            page = _quad_mask(grey.shape, e["gt_page"])
            sliver = _quad_mask(grey.shape, e["gt_sliver"]) if e["gt_sliver"] else None
            outside = ~_quad_mask(grey.shape, e["gt_spread"], 1.02)

            page_l = float(grey[page].mean()) if page.any() else 0.0
            bg_l = float(grey[outside].mean()) if outside.any() else 0.0
            sl_l = float("nan")
            problems = []
            if page_l < 110.0:
                problems.append("page mean %.1f < 110" % page_l)
            if page_l < 1.55 * max(1.0, bg_l):
                problems.append("page %.1f is not 1.55x tabletop %.1f" % (page_l, bg_l))
            if sliver is not None:
                sl_l = float(grey[sliver].mean()) if sliver.any() else 0.0
                if sl_l < 0.72 * page_l:
                    problems.append("sliver %.1f < 0.72x page %.1f (facing page missing?)"
                                    % (sl_l, page_l))
                if sl_l < 1.45 * max(1.0, bg_l):
                    problems.append("sliver %.1f is not 1.45x tabletop %.1f" % (sl_l, bg_l))
            for (x, y) in e["gt_page"]:
                if not (-2.0 <= x <= e["width"] + 2.0 and -2.0 <= y <= e["height"] + 2.0):
                    problems.append("gt_page corner off-canvas at %.1f,%.1f" % (x, y))

            if problems:
                bad += 1
                rows.append((it["name"], tag, "FAIL", "; ".join(problems)))
            else:
                rows.append((it["name"], tag, "ok", "page %.1f  sliver %s  tabletop %.1f" % (
                    page_l, ("%.1f" % sl_l) if sliver is not None else "-", bg_l)))

    for r in rows:
        print("%-27s %-7s %-4s %s" % r)
    print("\n%s: %d asset(s) checked, %d failed" % (
        "VERIFY FAIL" if bad else "VERIFY PASS", len(rows), bad))
    return 1 if bad else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--verify", action="store_true",
                    help="check the generated assets against the manifest (no rendering)")
    args = ap.parse_args()

    if args.verify:
        return verify_assets()

    if args.list:
        for g in LAYOUTS:
            print("%-27s %-21s side=%-5s f=%.2f curl=%.3f fill=%.3f/%.3f" % (
                g["name"], g["variant"], str(g["side"]), g["sliver_frac"], g["curl"],
                g["fill_still"], g["fill_preview"]))
        return 0

    os.makedirs(ASSET_DIR, exist_ok=True)
    os.makedirs(OUT_DIR, exist_ok=True)

    items = []
    tiles = []
    for g in LAYOUTS:
        entry = dict(name=g["name"], variant=g["variant"], sliver_side=g["side"],
                     sliver_fraction=g["sliver_frac"], note=g["note"], seed=g["seed"],
                     expects=dict(page_visible=True,
                                  sliver_merge_risk=(g["sliver_frac"] > 0.0)))
        for tag, wh, fill in (("still", STILL_WH, g["fill_still"]),
                              ("preview", PREVIEW_WH, g["fill_preview"])):
            img, meta = render(wh[0], wh[1], g, fill)
            fn = "%s_%s.jpg" % (g["name"], tag)
            fp = os.path.join(ASSET_DIR, fn)
            img.save(fp, format="JPEG", quality=90, optimize=True)
            entry[tag] = dict(
                asset=fn, asset_bytes=os.path.getsize(fp), asset_sha256=sha256_file(fp),
                width=meta["w"], height=meta["h"], diagonal=round(meta["diagonal"], 3),
                spread_fill=fill,
                gt_page=[[round(x, 3), round(y, 3)] for x, y in meta["gt_page"]],
                gt_spread=[[round(x, 3), round(y, 3)] for x, y in meta["gt_spread"]],
                gt_sliver=([[round(x, 3), round(y, 3)] for x, y in meta["gt_sliver"]]
                           if meta["gt_sliver"] else None))
            if tag == "preview":
                tiles.append((g["name"], draw_overlay(img, meta)))
        items.append(entry)
        print("rendered %s" % g["name"])

    tw, th_ = 480, 360
    cols = 3
    rows = (len(tiles) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * tw, rows * th_), (24, 24, 24))
    d = ImageDraw.Draw(sheet)
    for i, (name, ov) in enumerate(tiles):
        sheet.paste(ov.resize((tw, th_), Image.LANCZOS), ((i % cols) * tw, (i // cols) * th_))
        d.text(((i % cols) * tw + 8, (i // cols) * th_ + th_ - 20), name, fill=(255, 255, 255))
    sheet_path = os.path.join(OUT_DIR, "preview_overlay_sheet.jpg")
    sheet.save(sheet_path, quality=88)

    manifest = {
        "dataset": "bookspread-synthetic-v1",
        "provenance_rule": (
            "Synthetic by construction, and stated as such. The failure under test is "
            "'one page of an open book with the facing page still visible as a sliver "
            "across a shadowed gutter'; real photographs of that scene do not carry exact "
            "corner coordinates, so ground truth here is exact by construction instead of "
            "estimated. No real photo is passed off as synthetic, and no synthetic frame "
            "is presented as real camera data."),
        "generator": "tooling/h29_fixtures.py",
        "generator_sha256": sha256_file(os.path.abspath(__file__)),
        "determinism": "numpy default_rng(seed) per fixture, no timestamps; re-running "
                       "regenerates byte-identical assets",
        "still": {"width": STILL_WH[0], "height": STILL_WH[1],
                  "why": "12.2 MP, what ImageCapture(CAPTURE_MODE_MAXIMIZE_QUALITY) "
                         "delivers on a current phone sensor; this is the import path"},
        "preview": {"width": PREVIEW_WH[0], "height": PREVIEW_WH[1],
                    "why": "ImageAnalysis ResolutionSelector target (1280x720); live path"},
        "corner_order": "TL, TR, BR, BL of the target page, in image pixels",
        "geometry": "one scene per fixture, composed as spread_fill (spread width / frame "
                    "width) so both canvases hold the same page at the same relative size",
        "items": items,
    }
    with open(MANIFEST, "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")

    total = sum(it[t]["asset_bytes"] for it in items for t in ("still", "preview"))
    print("\n%d fixtures, %d assets, %.2f MB total" % (len(items), 2 * len(items),
                                                       total / 1e6))
    print("manifest: %s" % MANIFEST)
    print("overlay sheet: %s" % sheet_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
