#!/usr/bin/env python3
"""
h16_geometry.py - SUBTASK H16 per-image GEOMETRY measurement harness.

Reads the artifacts already saved by the corpus lane (no device access):

    <run>/corpus_out/ai_on/images/<name>/input.jpg     working input still
    <run>/corpus_out/ai_on/images/<name>/page.jpg      delivered (warped+enhanced) page
    <run>/corpus_out/ai_on/images/<name>/gt_scan.jpg   DocUNet flatbed GT (benchmark only)
    <run>/corpus_out/ai_on/images/<name>/docscan.txt   per-image DocScan logcat

and reports, for every benchmark page that WAS detected:

  * OUTPUT page-border straightness: robust line fit to each visible output border
    (RMS px and % of the page diagonal). If a border is flush with the delivered
    image edge (the normal, correct case) it is counted as CLIPPED and excluded
    from the RMS.
  * TEXT-LINE straightness: for each detected text row band, per-column ink
    centroid -> robust line fit -> RMS deviation. Aggregated (ink-weighted) RMS px,
    % of diagonal, the std of per-line slopes (parallelism) and the legacy
    row-projection CV.
  * STROKE sharpness: Laplacian variance on the full page and on five fixed
    25%-size crops.
  * residual mean/max/p95, chosen strength and delivered dimensions (from the
    `[Warp]` audit lines).
  * PSNR / SSIM against the bundled GT flatbed scan (reported, but the GT is a
    DIFFERENT physical capture).

Usage:
  py -3 tooling/h16_geometry.py <run-dir> [--out <csv>] [--variant <label>]
  py -3 tooling/h16_geometry.py tooling/out/h15_warp/corpus_final
"""
import csv
import json
import math
import os
import re
import statistics
import sys

import numpy as np
from PIL import Image

RESIDUAL_RE = re.compile(
    r"\[Warp\] residual mean=([0-9.]+) max=([0-9.]+) p95=([0-9.]+) curv=([0-9.]+)")
STRENGTH_RE = re.compile(
    r"\[Warp\] strength=([0-9.]+)(?: \(flat\))? mean=([0-9.]+) max=([0-9.]+)")
FOUND_RE = re.compile(r"\[Detect\] exit found=(true|false)")


# ---------------------------------------------------------------------------- image helpers
def load_gray(path):
    with Image.open(path) as im:
        im = im.convert("RGB")
        a = np.asarray(im, dtype=np.float32)
    return 0.299 * a[:, :, 0] + 0.587 * a[:, :, 1] + 0.114 * a[:, :, 2]


def resize_gray(g, w, h):
    im = Image.fromarray(np.clip(g, 0, 255).astype(np.uint8), mode="L")
    im = im.resize((int(w), int(h)), Image.BILINEAR)
    return np.asarray(im, dtype=np.float32)


def box_blur(a, radius):
    radius = int(max(1, radius))
    pad = np.pad(a, radius, mode="edge")
    cs = np.cumsum(np.cumsum(pad, axis=0), axis=1)
    cs = np.pad(cs, ((1, 0), (1, 0)), mode="constant")
    k = 2 * radius + 1
    out = (cs[k:, k:] - cs[:-k, k:] - cs[k:, :-k] + cs[:-k, :-k]) / float(k * k)
    return out[:a.shape[0], :a.shape[1]]


def laplacian_var(g):
    if g.shape[0] < 3 or g.shape[1] < 3:
        return -1.0
    lap = np.zeros_like(g)
    c = g[1:-1, 1:-1]
    lap[1:-1, 1:-1] = (-4.0 * c + g[:-2, 1:-1] + g[2:, 1:-1] +
                       g[1:-1, :-2] + g[1:-1, 2:])
    return float(np.var(lap[1:-1, 1:-1]))


CROPS = ((0.20, 0.20, 0.45, 0.45), (0.55, 0.20, 0.80, 0.45),
         (0.375, 0.375, 0.625, 0.625), (0.20, 0.55, 0.45, 0.80),
         (0.55, 0.55, 0.80, 0.80))


def sharpness(g):
    full = laplacian_var(g)
    vals = []
    h, w = g.shape
    for (x0, y0, x1, y1) in CROPS:
        vals.append(laplacian_var(g[int(y0 * h):int(y1 * h), int(x0 * w):int(x1 * w)]))
    return full, float(np.mean(vals))


# ---------------------------------------------------------------------------- robust line fit
def robust_line(pts, x_of_y=False):
    """Fit x = a*y + b (x_of_y) or y = a*x + b. Returns (a,b,rms,inliers,count)."""
    if len(pts) < 6:
        return None
    p = np.asarray(pts, dtype=np.float64)
    u = p[:, 1] if x_of_y else p[:, 0]
    v = p[:, 0] if x_of_y else p[:, 1]
    keep = np.ones(len(p), dtype=bool)
    a = b = 0.0
    for _ in range(3):
        if keep.sum() < 6:
            return None
        uu, vv = u[keep], v[keep]
        den = float(np.sum((uu - uu.mean()) ** 2))
        if den < 1e-9:
            return None
        a = float(np.sum((uu - uu.mean()) * (vv - vv.mean())) / den)
        b = float(vv.mean() - a * uu.mean())
        resid = np.abs(v - (a * u + b))
        r = resid[keep]
        mad = float(np.median(np.abs(r - np.median(r))))
        limit = max(1.2, 2.5 * 1.4826 * mad)
        new_keep = resid <= limit
        if np.array_equal(new_keep, keep):
            break
        keep = new_keep
    r = np.abs(v - (a * u + b))
    rms = float(math.sqrt(float(np.mean(r[keep] ** 2)))) if keep.sum() else -1.0
    return a, b, rms, int(keep.sum()), int(len(p))


def border_straightness(g):
    """Robust fit to the four visible page borders. Clipped sides are excluded."""
    h, w = g.shape
    diag = math.hypot(w, h)
    sides = {}
    # (name, axis, scan_limit, x_of_y, gradient axis)
    specs = (("left", "x", int(0.12 * w), True, "h"),
             ("right", "x", int(0.12 * w), True, "h"),
             ("top", "y", int(0.12 * h), False, "v"),
             ("bottom", "y", int(0.12 * h), False, "v"))
    for name, axis, limit, x_of_y, grad in specs:
        pts = []
        values = []
        if axis == "x":
            lo, hi = (0, limit) if name == "left" else (w - limit, w)
            ys = range(int(0.05 * h), int(0.95 * h), max(1, h // 240))
            for y in ys:
                row = box_blur(g[max(0, y - 1):min(h, y + 2), :], 1).mean(axis=0)
                prof = np.abs(row[2:] - row[:-2])
                seg = prof[lo:hi - 2]
                if seg.size == 0:
                    continue
                k = int(np.argmax(seg)) + lo + 1
                if prof[k - 1] < 8.0:
                    continue
                pts.append((float(k), float(y)))
                values.append(float(prof[k - 1]))
        else:
            lo, hi = (0, limit) if name == "top" else (h - limit, h)
            xs = range(int(0.05 * w), int(0.95 * w), max(1, w // 240))
            for x in xs:
                col = box_blur(g[:, max(0, x - 1):min(w, x + 2)], 1).mean(axis=1)
                prof = np.abs(col[2:] - col[:-2])
                seg = prof[lo:hi - 2]
                if seg.size == 0:
                    continue
                k = int(np.argmax(seg)) + lo + 1
                if prof[k - 1] < 8.0:
                    continue
                pts.append((float(x), float(k)))
                values.append(float(prof[k - 1]))
        clipped = 0
        if pts:
            # A point on the very first/last row or column is the delivered image edge,
            # not a visible page border.
            edge = 0 if name in ("left", "top") else (w - 1 if axis == "x" else h - 1)
            clipped = sum(1 for p in pts if p[0 if axis == "x" else 1] == edge)
            if clipped > 0.6 * len(pts):
                pts = []
        fit = robust_line(pts, x_of_y=x_of_y) if len(pts) >= 12 else None
        if fit is None or fit[2] < 0:
            sides[name] = {"visible": False, "rms": -1.0, "n": len(pts)}
        else:
            a, b, rms, nin, ntot = fit
            ok = nin >= max(10, int(0.5 * ntot)) and rms <= 0.02 * diag
            sides[name] = {"visible": bool(ok), "rms": rms if ok else -1.0,
                           "n": nin, "total": ntot, "slope": a}
    vis = [s["rms"] for s in sides.values() if s["visible"]]
    rms = float(np.mean(vis)) if vis else -1.0
    return rms, (rms / diag * 100.0 if rms >= 0 else -1.0), sum(
        1 for s in sides.values() if s["visible"]), sides


# ---------------------------------------------------------------------------- text lines
def _peaks(v, min_dist, thresh):
    idx = []
    n = len(v)
    for i in range(1, n - 1):
        if v[i] < thresh:
            continue
        lo = max(0, i - min_dist)
        hi = min(n, i + min_dist + 1)
        if v[i] >= float(np.max(v[lo:hi])):
            idx.append(i)
    # de-duplicate plateau maxima keeping the strongest
    out = []
    for i in idx:
        if not out or i - out[-1] >= min_dist:
            out.append(i)
        elif v[i] > v[out[-1]]:
            out[-1] = i
    return out


def _line_spacing(rp):
    n = len(rp)
    x = rp - rp.mean()
    den = float(np.sum(x * x)) + 1e-9
    max_lag = max(8, n // 8)
    best_lag, best_corr = 0, 0.0
    for lag in range(5, max_lag):
        c = float(np.sum(x[:-lag] * x[lag:])) / den
        if c > best_corr:
            best_corr, best_lag = c, lag
    return best_lag if best_corr > 0.12 else max(8, n // 30)


def text_line_metrics(g):
    h, w = g.shape
    diag = math.hypot(w, h)
    ink = 255.0 - g
    # Remove the slowly varying illumination/background so text dominates.
    ink = np.clip(ink - box_blur(ink, max(3, h // 40)), 0.0, None)
    rp = ink.mean(axis=1)
    rp_s = box_blur(rp.reshape(-1, 1), 1).ravel()
    cv = float(np.std(rp_s) / (np.mean(rp_s) + 1e-6) * 100.0)
    spacing = max(6, _line_spacing(rp_s))
    peaks = _peaks(rp_s, max(3, spacing // 2), float(np.mean(rp_s) + 0.35 * np.std(rp_s)))
    lines = []
    weights = []
    angles = []
    for pk in peaks:
        y0 = max(0, int(pk - 0.42 * spacing))
        y1 = min(h, int(pk + 0.42 * spacing))
        if y1 - y0 < 4:
            continue
        band = ink[y0:y1, :]
        mass = band.sum(axis=0)
        valid = mass > max(1.0, 0.10 * float(np.max(mass)))
        if float(valid.mean()) < 0.35:
            continue
        idx = np.nonzero(valid)[0]
        xs = idx.astype(np.float64)
        weights_col = mass[idx]
        ys = (band[:, idx] * np.arange(y0, y1, dtype=np.float64)[:, None]).sum(axis=0)
        ys = ys / np.maximum(weights_col, 1e-6)
        fit = robust_line(list(zip(xs, ys)), x_of_y=False)
        if fit is None:
            continue
        a, b, rms, nin, ntot = fit
        if nin < max(20, int(0.4 * ntot)) or rms > 0.30 * spacing:
            continue
        lines.append(rms)
        weights.append(float(weights_col.sum()))
        angles.append(math.degrees(math.atan(a)))
    if not lines:
        return {"text_rms_px": -1.0, "text_rms_pct_diag": -1.0,
                "text_angle_std_deg": -1.0, "text_lines": 0, "row_peak_cv": cv}
    lines_a = np.asarray(lines)
    weights_a = np.asarray(weights)
    rms_w = float(np.average(lines_a, weights=weights_a))
    return {"text_rms_px": rms_w,
            "text_rms_pct_diag": rms_w / diag * 100.0,
            "text_angle_std_deg": float(np.std(angles)) if len(angles) > 1 else 0.0,
            "text_lines": len(lines),
            "row_peak_cv": cv}


# ---------------------------------------------------------------------------- GT metrics
def psnr_ssim(a, b, size=384):
    ga = resize_gray(a, size, size)
    gb = resize_gray(b, size, size)
    mse = float(np.mean((ga - gb) ** 2))
    psnr = 99.0 if mse <= 1e-9 else 10.0 * math.log10(255.0 * 255.0 / mse)
    ma, mb = float(ga.mean()), float(gb.mean())
    va = max(0.0, float(np.mean(ga * ga)) - ma * ma)
    vb = max(0.0, float(np.mean(gb * gb)) - mb * mb)
    cov = float(np.mean(ga * gb)) - ma * mb
    c1 = (0.01 * 255.0) ** 2
    c2 = (0.03 * 255.0) ** 2
    den = (ma * ma + mb * mb + c1) * (va + vb + c2)
    ssim = 0.0 if den == 0 else ((2.0 * ma * mb + c1) * (2.0 * cov + c2)) / den
    return psnr, ssim


# ---------------------------------------------------------------------------- main
def parse_log(text):
    found = False
    m = FOUND_RE.search(text)
    if m:
        found = m.group(1) == "true"
    if "skipped (no detection)" in text:
        found = False
    res = RESIDUAL_RE.findall(text)
    st = STRENGTH_RE.findall(text)
    strength = None
    if st:
        strength = float(st[-1][0])
    elif "[Warp] strength=0.00 (flat)" in text:
        strength = 0.0
    warp = ("grid" if "stage2 grid dewarp applied" in text else
            "image" if "stage2 image dewarp applied" in text else
            "classical" if "stage3 classical curvature applied" in text else
            "rectified_skip" if "stage2 skipped (already rectified" in text else
            "skipped" if "skipped (no detection)" in text else "other")
    return {
        "found": found, "warp": warp, "strength": strength,
        "residual_mean": float(res[-1][0]) if res else -1.0,
        "residual_max": float(res[-1][1]) if res else -1.0,
        "residual_p95": float(res[-1][2]) if res else -1.0,
        "curv": float(res[-1][3]) if res else -1.0,
    }


def measure(name, dirpath, ref_gray=None):
    path_page = os.path.join(dirpath, "page.jpg")
    path_in = os.path.join(dirpath, "input.jpg")
    path_gt = os.path.join(dirpath, "gt_scan.jpg")
    path_log = os.path.join(dirpath, "docscan.txt")
    if not os.path.isfile(path_in) or not os.path.isfile(path_gt):
        parent = os.path.dirname(os.path.normpath(dirpath))
        shared_dirs = [
            os.path.join(parent, "_shared", name),
            os.path.join(os.path.dirname(parent), "_shared", name),
        ]
        for shared in shared_dirs:
            if not os.path.isfile(path_in):
                alt = os.path.join(shared, "input.jpg")
                if os.path.isfile(alt):
                    path_in = alt
            if not os.path.isfile(path_gt):
                alt = os.path.join(shared, "gt_scan.jpg")
                if os.path.isfile(alt):
                    path_gt = alt
    row = {"name": name}
    text = ""
    if os.path.isfile(path_log):
        text = open(path_log, "r", encoding="utf-8", errors="replace").read()
    row.update(parse_log(text))
    if not os.path.isfile(path_page):
        row["detected"] = 0
        return row
    g_out = load_gray(path_page)
    row["out_w"], row["out_h"] = int(g_out.shape[1]), int(g_out.shape[0])
    if os.path.isfile(path_in):
        g_in = load_gray(path_in)
        row["in_w"], row["in_h"] = int(g_in.shape[1]), int(g_in.shape[0])
        ti = text_line_metrics(g_in)
        _, row["input_sharp_crops"] = sharpness(g_in)
        row["input_text_rms_px"] = ti["text_rms_px"]
    # Everything below is measured at <= 1400 px long edge for speed/comparability.
    scale = min(1.0, 1400.0 / max(g_out.shape))
    if scale < 1.0:
        g_metric = resize_gray(g_out, g_out.shape[1] * scale, g_out.shape[0] * scale)
    else:
        g_metric = g_out
    brms, bpct, bn, _ = border_straightness(g_metric)
    row["border_rms_px"] = brms
    row["border_rms_pct_diag"] = bpct
    row["border_sides"] = bn
    row.update(text_line_metrics(g_metric))
    row["sharp_full"], row["sharp_crops"] = sharpness(g_metric)
    if os.path.isfile(path_gt):
        g_gt = load_gray(path_gt)
        row["psnr"], row["ssim"] = psnr_ssim(g_out, g_gt)
    else:
        row["psnr"] = row["ssim"] = -1.0
    row["detected"] = 1 if row.get("found") else 0
    return row


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    run = argv[1]
    out_csv = None
    variant = os.path.basename(os.path.normpath(run))
    i = 2
    while i < len(argv):
        if argv[i] == "--out" and i + 1 < len(argv):
            out_csv = argv[i + 1]
            i += 2
        elif argv[i] == "--variant" and i + 1 < len(argv):
            variant = argv[i + 1]
            i += 2
        else:
            i += 1
    img_root = os.path.join(run, "corpus_out", "ai_on", "images")
    if not os.path.isdir(img_root):
        # Also accept a directory that directly contains <name>/page.jpg (variant runs).
        if all(os.path.isdir(os.path.join(run, n)) for n in os.listdir(run)[:1]):
            img_root = run
        else:
            print("no corpus images under", img_root, file=sys.stderr)
            return 2
    names = sorted(n for n in os.listdir(img_root)
                   if n.startswith("bench_") and os.path.isdir(os.path.join(img_root, n)))
    if not names:
        print("no benchmark images under", img_root, file=sys.stderr)
        return 2
    rows = [measure(n, os.path.join(img_root, n)) for n in names]
    cols = ["name", "detected", "warp", "out_w", "out_h", "in_w", "in_h",
            "residual_mean", "residual_max", "residual_p95", "curv", "strength",
            "border_rms_px", "border_rms_pct_diag", "border_sides",
            "text_rms_px", "text_rms_pct_diag", "text_angle_std_deg", "text_lines",
            "row_peak_cv", "sharp_full", "sharp_crops", "psnr", "ssim",
            "input_text_rms_px", "input_sharp_crops", "variant"]
    for r in rows:
        r["variant"] = variant
    if out_csv is None:
        out_csv = os.path.join(run, "geometry_table.csv")
    os.makedirs(os.path.dirname(os.path.abspath(out_csv)), exist_ok=True)
    with open(out_csv, "w", newline="", encoding="utf-8") as fh:
        wr = csv.DictWriter(fh, fieldnames=cols, extrasaction="ignore")
        wr.writeheader()
        for r in rows:
            wr.writerow(r)

    det = [r for r in rows if r.get("detected")]
    print("=== H16 geometry: %s ===" % variant)
    print("%-18s %6s %6s %8s %9s %8s %8s %8s" % (
        "image", "out", "bRms", "txtRms", "txtAngStd", "sharp", "psnr", "ssim"))
    for r in det:
        print("%-18s %4dx%-4d %6s %8s %9s %8.1f %8.2f %8.4f" % (
            r["name"].replace("bench_docunet_", "d"),
            r.get("out_w", 0), r.get("out_h", 0),
            _f(r.get("border_rms_px")), _f(r.get("text_rms_px"), 3),
            _f(r.get("text_angle_std_deg"), 3), r.get("sharp_crops", -1.0),
            r.get("psnr", -1.0), r.get("ssim", -1.0)))
    print("\ndetected=%d/%d  mean text_rms_px=%.4f  mean text_angle_std=%.3f  "
          "mean sharp_crops=%.1f  mean PSNR=%.3f  mean SSIM=%.4f" % (
              len(det), len(rows),
              _mean([r.get("text_rms_px") for r in det]),
              _mean([r.get("text_angle_std_deg") for r in det]),
              _mean([r.get("sharp_crops") for r in det]),
              _mean([r.get("psnr") for r in det]),
              _mean([r.get("ssim") for r in det])))
    print("wrote", out_csv)
    return 0


def _f(v, nd=2):
    return "-" if v is None or v < 0 else ("%." + str(nd) + "f") % v


def _mean(vals):
    vals = [v for v in vals if isinstance(v, (int, float)) and v >= 0]
    return statistics.fmean(vals) if vals else -1.0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
