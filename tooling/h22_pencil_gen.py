#!/usr/bin/env python3
"""
h22_pencil_gen.py - deterministic faint-pencil fixture generator (SUBTASK H22, repurposed by H23).

**H23 STATUS: augmentation tier only, never evidence.** H23 replaced H22's generated fixtures with
12 *real* public-domain pencil pages (`app/src/androidTest/assets/pencil/pencil_real_XX.jpg`,
provenance in `pencil_manifest.json`). This script survives with one job: take those real pages and
push them one step further ("very faint") so the enhancement can be stress-tested below the level of
the real scans. Every artefact it writes is labelled `"tier": "very faint"`, `"synthetic": true`,
`"real_data": false` and carries `derived_from` (the real asset name, its SHA-256 and its licence
URL) for each page, so no number produced from it can ever be mistaken for a measurement on real
data. Its default output is a cache directory outside the APK; writing into the androidTest assets
now requires an explicit `--allow-assets`.

Original H22 behaviour (printed pages -> faint pencil imitations + paired ground truth):

    <out>/pencil_XX.jpg      <- faint input (what the camera would see)
    <out>/pencil_XX_gt.jpg   <- ground truth (same page, no degradation)
    <out>/manifest.json      <- every parameter + per-page seed

WHY THIS MODEL (see docs/HANDWRITING_ENHANCEMENT.md S1):
  * A pencil stroke is a partial, pressure-dependent deposit: its luminance delta to the paper is
    25-45 % of a printed ink delta, and its edge is a ~1 px ramp, not a step.
  * The composite is multiplicative and derived from the page's OWN reflectance:
        coverage = (paper - lum) / paper            # 0 on paper, rel on ink
        out_lum  = paper * (1 - r * coverage_s)     # exactly r * (paper-ink) at full coverage
        out_c    = src_c * (out_lum / lum)          # preserves chroma ratios => not a flat fade
  * The model is applied **only inside the detected page** (Otsu page mask). Corpus photos contain
    desks, shadows and dark backgrounds; turning a desk into "faint pencil" would be wrong, and it
    would also poison the stroke/paper statistics used to define the metric masks.
  * Paper grain is added AFTER the stroke, because real grain sits on top of the graphite and is the
    thing a global contrast stretch would amplify. That is precisely what the gates measure.

DETERMINISM: numpy default_rng with per-page seed BASE_SEED + 1009*i. Pillow/numpy only (no OpenCV,
no new Gradle dependency).

Usage (H23):
    py -3 tooling/h22_pencil_gen.py                       # real pages -> E:\\DocScanAI-cache\\pencil\\very_faint
    py -3 tooling/h22_pencil_gen.py --src app/src/androidTest/assets/pencil --out <cache dir>
"""

import argparse
import io
import json
import os
import sys
from datetime import datetime, timezone

import numpy as np
from PIL import Image

# --- generator parameters (mirrored in docs/HANDWRITING_ENHANCEMENT.md S5 and in manifest.json) ---
BASE_SEED = 20261018
PAGE_SEED_STRIDE = 1009
DEFAULT_PAGES = 12
DEFAULT_LONG_EDGE = 1000

STROKE_RATIO_MIN = 0.25          # faint-pencil contrast ratio of the original ink-to-paper delta
STROKE_RATIO_MAX = 0.45
EDGE_SOFTEN_SIGMA = 1.1          # px, graphite tip is not a laser printer
GRAPHITE_DESAT = 0.35            # pull stroke pixels toward their own luminance (graphite is neutral)
COMPOSITE_FACTOR_MIN = 0.50      # guard rails on the multiplicative composite
COMPOSITE_FACTOR_MAX = 4.00

PAGE_MASK_BLUR = 6.0             # px, smooths the Otsu page mask over text holes
GRAIN_COARSE_SIGMA = 1.6         # px, spatial correlation of the coarse paper tooth
GRAIN_COARSE_AMP = 2.6           # 8-bit levels (std)
GRAIN_FINE_SIGMA = 0.55
GRAIN_FINE_AMP = 2.0

ILLUM_MIN = 0.09                 # total low-frequency illumination amplitude (fraction of paper)
ILLUM_MAX = 0.13

NOISE_LUMA_MIN = 1.3             # 8-bit levels (std)
NOISE_LUMA_MAX = 1.9
NOISE_CHROMA = 0.8

JPEG_QUALITY_INPUT = 88          # 4:2:0, like a phone's normal capture
JPEG_QUALITY_GT = 95
JPEG_SUBSAMPLING = 2             # 4:2:0


def log(msg):
    print(msg, flush=True)


# --- tiny deterministic image helpers (no scipy / no OpenCV on the host) -------------------------

def _box_blur(arr, radius):
    """Separable box blur with edge replication."""
    if radius < 1:
        return arr
    k = 2 * radius + 1
    out = arr
    padded = np.pad(out, ((0, 0), (radius, radius)), mode="edge")
    cs = np.cumsum(padded, axis=1, dtype=np.float64)
    cs = np.concatenate([np.zeros((cs.shape[0], 1), dtype=np.float64), cs], axis=1)
    out = ((cs[:, k:] - cs[:, :-k]) / float(k)).astype(np.float32)
    padded = np.pad(out, ((radius, radius), (0, 0)), mode="edge")
    cs = np.cumsum(padded, axis=0, dtype=np.float64)
    cs = np.concatenate([np.zeros((1, cs.shape[1]), dtype=np.float64), cs], axis=0)
    out = ((cs[k:, :] - cs[:-k, :]) / float(k)).astype(np.float32)
    return out


def _blur(arr, sigma):
    """Gaussian approximation by three box passes (the usual O(n) trick)."""
    if sigma <= 0.05:
        return arr.astype(np.float32)
    w = int(round((12.0 * sigma * sigma / 3.0 + 1.0) ** 0.5))
    if w % 2 == 0:
        w += 1
    radius = max(1, (w - 1) // 2)
    out = arr.astype(np.float32)
    for _ in range(3):
        out = _box_blur(out, radius)
    return out


def _gauss(arr, sigma):
    """Spatially-correlated gaussian field from white noise, std-scaled to 1.0."""
    out = _blur(arr, sigma)
    std = float(out.std())
    if std < 1e-6:
        return np.zeros_like(out)
    return (out - float(out.mean())) / std


def _luma(rgb):
    # Rec.601 luma, matching OpenCV's BGR2GRAY closely enough for a host-side model.
    return 0.299 * rgb[..., 0] + 0.587 * rgb[..., 1] + 0.114 * rgb[..., 2]


def _otsu(gray_u8):
    hist = np.bincount(gray_u8.ravel(), minlength=256).astype(np.float64)
    total = float(hist.sum())
    if total <= 0:
        return 127
    sum_all = float((np.arange(256) * hist).sum())
    w_b = 0.0
    sum_b = 0.0
    best = -1.0
    thr = 127
    for i in range(256):
        w_b += hist[i]
        if w_b <= 0.0:
            continue
        w_f = total - w_b
        if w_f <= 0.0:
            break
        sum_b += i * hist[i]
        m_b = sum_b / w_b
        m_f = (sum_all - sum_b) / w_f
        between = w_b * w_f * (m_b - m_f) ** 2
        if between > best:
            best = between
            thr = i
    return int(thr)


def page_mask(gray):
    """Otsu page mask, smoothed so text holes and noise do not punch through the page."""
    thr = _otsu(np.clip(np.rint(gray), 0, 255).astype(np.uint8))
    m = (gray > thr).astype(np.float32)
    return _blur(m, PAGE_MASK_BLUR) > 0.5


def page_stats(gray, mask):
    """Paper level and ink level measured INSIDE the page only (desks/shadows excluded)."""
    vals = gray[mask]
    if vals.size < 1000:
        return None
    paper = float(np.percentile(vals, 90.0))
    ink = float(np.percentile(vals, 10.0))
    if paper - ink < 20.0:
        paper = float(np.percentile(vals, 97.0))
        ink = max(1.0, paper - 40.0)
    if paper - ink < 10.0:
        return None
    rel = float(np.clip((paper - ink) / max(paper, 1.0), 0.15, 0.90))
    return paper, ink, rel


def degrade(gt_rgb, seed, page_index, source_name):
    """Return (faint_uint8_rgb, composite_uint8_rgb, params). Deterministic for (gt_rgb, seed)."""
    rng = np.random.default_rng(seed)
    h, w = gt_rgb.shape[:2]
    src = gt_rgb.astype(np.float32)

    lum = _luma(src)
    mask = page_mask(lum)
    stats = page_stats(lum, mask)
    if stats is None:
        raise RuntimeError("page %d (%s): no analysable page region" % (page_index, source_name))
    paper, ink, rel = stats
    delta = paper - ink
    ratio = float(rng.uniform(STROKE_RATIO_MIN, STROKE_RATIO_MAX))

    # --- coverage (0 paper .. rel ink), restricted to the page, softened to a graphite edge ----
    coverage = np.clip((paper - lum) / max(paper, 1.0), 0.0, rel)
    coverage = (coverage * mask.astype(np.float32)).astype(np.float32)
    coverage_s = _blur(coverage, EDGE_SOFTEN_SIGMA)

    # --- multiplicative, brightness-preserving composite --------------------------------------
    out_lum = paper * (1.0 - ratio * coverage_s)
    factor = out_lum / np.maximum(lum, 1.0)
    factor = np.clip(factor, COMPOSITE_FACTOR_MIN, COMPOSITE_FACTOR_MAX)
    out = src * factor[..., None]

    # Graphite is neutral: pull stroke pixels toward their own luminance.
    stroke_w = np.clip(coverage_s / max(rel, 1e-3), 0.0, 1.0)[..., None] * GRAPHITE_DESAT
    out = out * (1.0 - stroke_w) + _luma(out)[..., None] * stroke_w

    # Snapshot of the pure stroke-contrast model BEFORE grain/illumination/noise. This is the image
    # the 25-45 % contract is about; the sensor simulation that follows is allowed to change
    # *measured* contrast, because that is what makes the fixture realistic.
    composite = np.clip(np.rint(out), 0, 255).astype(np.uint8)

    # --- paper grain (two octaves), on top of the graphite -------------------------------------
    coarse = _gauss(rng.normal(0.0, 1.0, (h, w)).astype(np.float32), GRAIN_COARSE_SIGMA)
    fine = _gauss(rng.normal(0.0, 1.0, (h, w)).astype(np.float32), GRAIN_FINE_SIGMA)
    out = out + (coarse * GRAIN_COARSE_AMP + fine * GRAIN_FINE_AMP)[..., None]

    # --- low-frequency illumination gradient (plane + radial), mean-normalised ----------------
    amp = float(rng.uniform(ILLUM_MIN, ILLUM_MAX))
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    u = xx / max(w - 1, 1)
    v = yy / max(h - 1, 1)
    radial = np.clip(1.0 - 2.0 * ((u - 0.5) ** 2 + (v - 0.5) ** 2), 0.0, 1.0)
    illum = 1.0 - amp * u - 0.6 * amp * (1.0 - radial) - 0.25 * amp * v
    out = out * (illum / float(illum.mean()))[..., None]

    # --- sensor noise (luma + mild chroma) -----------------------------------------------------
    nl = float(rng.uniform(NOISE_LUMA_MIN, NOISE_LUMA_MAX))
    out = out + rng.normal(0.0, nl, (h, w)).astype(np.float32)[..., None]
    out[..., 0] += rng.normal(0.0, NOISE_CHROMA, (h, w)).astype(np.float32)
    out[..., 2] += rng.normal(0.0, NOISE_CHROMA, (h, w)).astype(np.float32)

    faint = np.clip(np.rint(out), 0, 255).astype(np.uint8)
    params = {
        "source": source_name,
        "page": page_index,
        "seed": int(seed),
        "stroke_ratio": round(ratio, 6),
        "paper_level": round(paper, 3),
        "ink_level": round(ink, 3),
        "ink_delta": round(delta, 3),
        "rel": round(rel, 6),
        "page_area_frac": round(float(mask.mean()), 6),
        "illum_amp": round(amp, 6),
        "noise_luma_sigma": round(nl, 6),
        "edge_soften_sigma": EDGE_SOFTEN_SIGMA,
        "graphite_desat": GRAPHITE_DESAT,
        "size": [int(w), int(h)],
    }
    return faint, composite, params


def _masks(gt_rgb):
    g = _luma(gt_rgb.astype(np.float32))
    mask = page_mask(g)
    stats = page_stats(g, mask)
    if stats is None:
        return None
    paper, ink, rel = stats
    delta = paper - ink
    # "core stroke" = darkest 20 % of the page's ink band; "paper" = clearly above the ink band.
    core = mask & (g <= ink + 0.20 * delta)
    paper_px = mask & (g >= paper - 0.10 * delta)
    if int(core.sum()) < 200 or int(paper_px.sum()) < 200:
        return None
    return g, core, paper_px, delta


def measure_ratio_composite(composite_rgb, gt_rgb):
    """
    Ratio achieved by the STROKE-CONTRAST MODEL itself, measured before grain / illumination /
    sensor noise are added. This is the quantity the 25-45 % contract refers to, and it is measured
    from delivered pixels, not from the parameter we asked for.
    """
    m = _masks(gt_rgb)
    if m is None:
        return -1.0
    _, core, paper_px, delta = m
    c = _luma(composite_rgb.astype(np.float32))
    core_level = float(np.percentile(c[core], 20.0))
    paper_level = float(np.median(c[paper_px]))
    return (paper_level - core_level) / delta


def measure_ratio_final(faint_rgb, gt_rgb):
    """
    Informational only: LOCAL contrast ratio of the delivered JPEG. It differs from the model ratio
    because the fixture deliberately contains a low-frequency illumination field whose amplitude is
    the same order as the faint stroke delta. No gate depends on this number.
    """
    m = _masks(gt_rgb)
    if m is None:
        return -1.0
    _, core, paper_px, delta = m
    f = _luma(faint_rgb.astype(np.float32))
    pm = paper_px.astype(np.float32)
    num = _blur(f * pm, 20.0)
    den = _blur(pm, 20.0)
    paper_field = (num / np.maximum(den, 1e-3))[core]
    support = den[core] > 0.15
    if int(support.sum()) < 200:
        return -1.0
    stroke_level = float(np.percentile(f[core], 20.0))
    return (float(np.median(paper_field[support])) - stroke_level) / delta


def find_sources(src_dir):
    if not os.path.isdir(src_dir):
        raise SystemExit("source folder not found: %s" % src_dir)
    names = [
        n for n in sorted(os.listdir(src_dir))
        if n.lower().endswith((".jpg", ".jpeg", ".png"))
    ]
    return [os.path.join(src_dir, n) for n in names]


def main(argv):
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    assets_pencil = os.path.join(repo, "app", "src", "androidTest", "assets", "pencil")
    ap = argparse.ArgumentParser()
    ap.add_argument("--pages", type=int, default=DEFAULT_PAGES)
    ap.add_argument("--long-edge", type=int, default=DEFAULT_LONG_EDGE)
    ap.add_argument("--seed", type=int, default=BASE_SEED)
    ap.add_argument("--src", default=assets_pencil,
                    help="H23: defaults to the bundled REAL pencil pages, not the printed corpus")
    ap.add_argument("--out", default=os.path.join("E:\\", "DocScanAI-cache", "pencil", "very_faint"),
                    help="H23: cache directory, deliberately outside the APK")
    ap.add_argument("--prefix", default="pencil_faint",
                    help="output stem; a name that cannot be confused with pencil_real_XX")
    ap.add_argument("--real-manifest", default=os.path.join(assets_pencil, "pencil_manifest.json"),
                    help="provenance of the real pages this tier is derived from")
    ap.add_argument("--allow-assets", action="store_true",
                    help="permit writing the augmented tier into app/src/androidTest/assets (never do this)")
    ap.add_argument("--strict-band", action="store_true",
                    help="H22 contract only: fail when a page leaves the 22-48 %% stroke-ratio band. The "
                         "H23 'very faint' tier is deliberately allowed to sit BELOW that band.")
    args = ap.parse_args(argv[1:])

    if os.path.abspath(args.out).startswith(os.path.abspath(assets_pencil)) and not args.allow_assets:
        raise SystemExit("refusing to write an augmented tier into the bundled assets: %s" % args.out)

    sources = find_sources(args.src)
    real = {}
    for path in sources:
        name = os.path.basename(path)
        if not name.startswith("pencil_real_"):
            raise SystemExit(
                "H23: --src must contain only pencil_real_*.jpg pages (found %s). The augmented tier "
                "is defined as a transform OF the real pages; it is not a fixture source of its own." % name
            )
    if os.path.isfile(args.real_manifest):
        with open(args.real_manifest, encoding="utf-8") as fh:
            for item in json.load(fh).get("items", []):
                real[item.get("asset")] = item
    if len(sources) < args.pages:
        raise SystemExit("need %d source pages, found %d in %s" % (args.pages, len(sources), args.src))
    sources = sources[: args.pages]
    os.makedirs(args.out, exist_ok=True)

    manifest = {
        "generator": "tooling/h22_pencil_gen.py",
        "tier": "very faint",
        "synthetic": True,
        "real_data": False,
        "augmentation_only": True,
        "labelling_rule": (
            "Every page here is an augmented copy of a real public-domain page listed in "
            "'derived_from'. Numbers from this tier MUST be reported as 'augmented from real page X', "
            "never as a measurement on real pencil data."
        ),
        "generated_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "base_seed": args.seed,
        "page_seed_stride": PAGE_SEED_STRIDE,
        "pages": args.pages,
        "long_edge": args.long_edge,
        "jpeg_quality_input": JPEG_QUALITY_INPUT,
        "jpeg_quality_gt": JPEG_QUALITY_GT,
        "jpeg_subsampling": JPEG_SUBSAMPLING,
        "parameters": {
            "stroke_ratio_range": [STROKE_RATIO_MIN, STROKE_RATIO_MAX],
            "edge_soften_sigma": EDGE_SOFTEN_SIGMA,
            "graphite_desat": GRAPHITE_DESAT,
            "page_mask_blur": PAGE_MASK_BLUR,
            "grain_coarse": {"sigma": GRAIN_COARSE_SIGMA, "amp": GRAIN_COARSE_AMP},
            "grain_fine": {"sigma": GRAIN_FINE_SIGMA, "amp": GRAIN_FINE_AMP},
            "illum_amp_range": [ILLUM_MIN, ILLUM_MAX],
            "noise_luma_range": [NOISE_LUMA_MIN, NOISE_LUMA_MAX],
            "noise_chroma": NOISE_CHROMA,
            "composite": "multiplicative inside the Otsu page mask: out_c = src_c * paper*(1-r*coverage)/lum",
        },
        "items": [],
    }

    ratios = []
    for i, path in enumerate(sources):
        name = os.path.basename(path)
        seed = args.seed + PAGE_SEED_STRIDE * i
        with Image.open(path) as im:
            im = im.convert("RGB")
            w, h = im.size
            scale = args.long_edge / float(max(w, h))
            nw, nh = max(1, int(round(w * scale))), max(1, int(round(h * scale)))
            gt = im.resize((nw, nh), Image.LANCZOS)

        gt_arr = np.asarray(gt, dtype=np.uint8)
        faint, composite, params = degrade(gt_arr, seed, i, name)
        achieved = measure_ratio_composite(composite, gt_arr)
        params["achieved_ratio"] = round(float(achieved), 6)
        ratios.append(achieved)

        base = "%s_%02d" % (args.prefix, i)
        in_path = os.path.join(args.out, base + ".jpg")
        gt_path = os.path.join(args.out, base + "_gt.jpg")

        # Encode through an in-memory JPEG so the artefacts are baked into the fixture exactly as a
        # camera would leave them (and so the file on disk is the decoded source of truth).
        buf = io.BytesIO()
        Image.fromarray(faint, mode="RGB").save(
            buf, format="JPEG", quality=JPEG_QUALITY_INPUT, subsampling=JPEG_SUBSAMPLING, optimize=False
        )
        buf.seek(0)
        jpeg_bytes = buf.getvalue()
        with open(in_path, "wb") as fh:
            fh.write(jpeg_bytes)
        with Image.open(io.BytesIO(jpeg_bytes)) as decoded:
            faint_final = np.asarray(decoded.convert("RGB"), dtype=np.uint8)
        params["achieved_ratio_final_local"] = round(float(measure_ratio_final(faint_final, gt_arr)), 6)

        Image.fromarray(gt_arr, mode="RGB").save(gt_path, format="JPEG", quality=JPEG_QUALITY_GT, subsampling=0)

        params["input_file"] = base + ".jpg"
        params["gt_file"] = base + "_gt.jpg"
        params["input_bytes"] = len(jpeg_bytes)
        params["tier"] = "very faint"
        params["synthetic"] = True
        params["derived_from"] = {
            "asset": name,
            "sha256": real.get(name, {}).get("asset_sha256"),
            "source_page": real.get(name, {}).get("source_page"),
            "licence": real.get(name, {}).get("licence"),
            "licence_url": real.get(name, {}).get("licence_url"),
        }
        manifest["items"].append(params)
        log(
            "  [%2d/%2d] %-52s %dx%d target=%.3f composite=%.3f final_local=%.3f %6d B"
            % (i + 1, len(sources), name[:52], nw, nh, params["stroke_ratio"],
               params["achieved_ratio"], params["achieved_ratio_final_local"], len(jpeg_bytes))
        )

    valid = [r for r in ratios if r > 0]
    manifest["achieved_ratio_mean"] = round(float(np.mean(valid)) if valid else -1.0, 6)
    manifest["achieved_ratio_min"] = round(float(np.min(valid)) if valid else -1.0, 6)
    manifest["achieved_ratio_max"] = round(float(np.max(valid)) if valid else -1.0, 6)
    manifest["total_input_bytes"] = int(sum(it["input_bytes"] for it in manifest["items"]))

    with open(os.path.join(args.out, "manifest.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2, sort_keys=True)
        fh.write("\n")

    log(
        "PENCIL_GEN pages=%d mean_ratio=%.3f min=%.3f max=%.3f input_bytes=%d"
        % (len(manifest["items"]), manifest["achieved_ratio_mean"],
           manifest["achieved_ratio_min"], manifest["achieved_ratio_max"],
           manifest["total_input_bytes"])
    )
    # Contract check: the STROKE MODEL (pre-sensor) must land inside the requested 25-45 % band.
    # H23: the "very faint" tier is a stress tier derived from real pages, so leaving the band is
    # expected and harmless; only the original H22 fixture role is strict.
    bad = [it for it in manifest["items"] if not (0.22 <= it["achieved_ratio"] <= 0.48)]
    if bad and args.strict_band:
        log("PENCIL_GEN WARNING out-of-band pages: %s" % [it["input_file"] for it in bad])
        return 1
    if bad:
        log("PENCIL_GEN NOTE out-of-band pages (allowed for the H23 very-faint tier): %s"
            % [it["input_file"] for it in bad])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
