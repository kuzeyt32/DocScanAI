#!/usr/bin/env python3
"""SUBTASK H23 - host-side summariser for the on-device measurement lane.

Reads tooling/out/h23/device/h23_results.json (written by
app/src/androidTest/java/com/docscanai/HandwritingEnhanceTest.kt), prints a per-page table,
computes the per-variant means and applies the mechanical keep/revert rule from the brief:

    contrast gain  >= +30 %   (relative, stroke-to-background)
    blank noise    <= +10 %   (relative)
    non-stroke PSNR >= 30 dB
    sharpness on strokes must not drop  (ratio >= 1.00)
    connected components must not increase (continuity: ratio <= 1.00)
    latency median and max at 2000 px must stay <= 120 ms

Usage:  py -3 tooling\\h23_summary.py [path/to/h23_results.json]
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
H23_DIR = os.path.join(ROOT, "tooling", "out", "h23")


def newest_results():
    """Newest h23_results.json under tooling/out/h23 (adb nests pulls into subdirectories when the
    destination already exists, so do not hard-code one path)."""
    newest, newest_mtime = None, -1.0
    for dirpath, _dirnames, filenames in os.walk(H23_DIR):
        for name in filenames:
            if name != "h23_results.json":
                continue
            path = os.path.join(dirpath, name)
            mtime = os.path.getmtime(path)
            if mtime > newest_mtime:
                newest, newest_mtime = path, mtime
    return newest


DEFAULT = newest_results()

GATES = {
    "contrast_min_pct": 30.0,
    "noise_max_pct": 10.0,
    "psnr_min_db": 30.0,
    "sharpness_min_ratio": 1.00,
    "components_max_ratio": 1.00,
    "latency_max_ms": 120.0,
}


def fmt(value, spec="%.1f"):
    if value is None:
        return "   n/a"
    return spec % value


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)

    pages = data.get("pages", [])
    print("dataset : %s" % data.get("dataset"))
    print("pages   : %d real pencil pages, %d normal corpus pages"
          % (len(pages), len(data.get("normal_pages", []))))
    print()

    head = ("asset", "F.con", "F.noise", "F.psnr", "F.sharp", "F.comp", "C.con", "C.noise", "C.psnr", "C.sharp")
    print("%-22s %6s %7s %6s %7s %6s %7s %8s %6s %7s" % head)
    for p in pages:
        f = p.get("full", {})
        c = p.get("clahe", {})
        print("%-22s %+6.1f %+7.1f %6.1f %7.2f %6d %+7.1f %+8.1f %6.1f %7.2f" % (
            p.get("asset", "?"),
            f.get("contrast_gain_pct", 0.0), f.get("noise_gain_pct", 0.0),
            f.get("psnr_nonstroke_db", 0.0), f.get("sharpness_ratio", 0.0), f.get("components_out", 0),
            c.get("contrast_gain_pct", 0.0), c.get("noise_gain_pct", 0.0),
            c.get("psnr_nonstroke_db", 0.0), c.get("sharpness_ratio", 0.0),
        ))
    print()

    verdict = {}
    for variant in ("full", "clahe"):
        keys = ("contrast_gain_pct", "noise_gain_pct", "psnr_nonstroke_db", "sharpness_ratio")
        avail = [p[variant] for p in pages if variant in p]
        if not avail:
            continue
        means = {k: sum(v[k] for v in avail) / len(avail) for k in keys}
        worst = {
            "contrast_gain_pct": min(v["contrast_gain_pct"] for v in avail),
            "noise_gain_pct": max(v["noise_gain_pct"] for v in avail),
            "psnr_nonstroke_db": min(v["psnr_nonstroke_db"] for v in avail),
            "sharpness_ratio": min(v["sharpness_ratio"] for v in avail),
        }
        comp_ratio = None
        if all("components_in" in v and v["components_in"] > 0 for v in avail):
            ratios = [v["components_out"] / float(v["components_in"]) for v in avail]
            comp_ratio = sum(ratios) / len(ratios)
        latency = data.get("latency_%s_ms" % variant, {}) or {}
        lat_med = latency.get("median")
        lat_max = latency.get("max")

        checks = {
            "contrast": (means["contrast_gain_pct"], means["contrast_gain_pct"] >= GATES["contrast_min_pct"]),
            "noise": (means["noise_gain_pct"], means["noise_gain_pct"] <= GATES["noise_max_pct"]),
            "psnr": (means["psnr_nonstroke_db"], means["psnr_nonstroke_db"] >= GATES["psnr_min_db"]),
            "sharpness": (means["sharpness_ratio"], means["sharpness_ratio"] >= GATES["sharpness_min_ratio"]),
            "components": (comp_ratio, comp_ratio is not None and comp_ratio <= GATES["components_max_ratio"]),
            "latency_med": (lat_med, lat_med is not None and lat_med <= GATES["latency_max_ms"]),
            "latency_max": (lat_max, lat_max is not None and lat_max <= GATES["latency_max_ms"]),
        }
        passed = all(ok for _, ok in checks.values())
        verdict[variant] = {"means": means, "worst": worst, "components_ratio": comp_ratio,
                            "checks": checks, "passed": passed,
                            "latency_median": lat_med, "latency_max": lat_max}

        print("VARIANT %s" % variant.upper())
        print("  mean contrast gain  %+7.2f %%   (worst page %+7.2f %%)  gate >= %+.1f %%   %s"
              % (means["contrast_gain_pct"], worst["contrast_gain_pct"], GATES["contrast_min_pct"],
                 "PASS" if checks["contrast"][1] else "FAIL"))
        print("  mean blank noise    %+7.2f %%   (worst page %+7.2f %%)  gate <= %+.1f %%   %s"
              % (means["noise_gain_pct"], worst["noise_gain_pct"], GATES["noise_max_pct"],
                 "PASS" if checks["noise"][1] else "FAIL"))
        print("  mean non-stroke PSNR %7.2f dB   (worst page %7.2f dB)   gate >= %.1f dB  %s"
              % (means["psnr_nonstroke_db"], worst["psnr_nonstroke_db"], GATES["psnr_min_db"],
                 "PASS" if checks["psnr"][1] else "FAIL"))
        print("  mean sharpness ratio %7.2f      (worst page %7.2f)      gate >= %.2f     %s"
              % (means["sharpness_ratio"], worst["sharpness_ratio"], GATES["sharpness_min_ratio"],
                 "PASS" if checks["sharpness"][1] else "FAIL"))
        print("  components out/in    %7.2f                                 gate <= %.2f     %s"
              % (comp_ratio, GATES["components_max_ratio"], "PASS" if checks["components"][1] else "FAIL"))
        print("  latency median/max   %5s / %-5s ms                          gate <= %.0f ms  %s"
              % (lat_med, lat_max, GATES["latency_max_ms"],
                 "PASS" if checks["latency_med"][1] and checks["latency_max"][1] else "FAIL"))
        print("  => %s" % ("KEEP" if passed else "REVERT"))
        print()

    # normal-corpus damage check
    normals = data.get("normal_pages", [])
    if normals:
        det = [n for n in normals if n.get("detected") is not None]
        changed = [n for n in normals if n.get("psnr_vs_input_db") is not None and n["psnr_vs_input_db"] < 30.0]
        print("NORMAL CORPUS: %d pages, %d with PSNR vs input < 30 dB (expected: handwritten modes are"
              " supposed to change normal pages; this lane only reports, the h7/corpus lanes gate)" %
              (len(normals), len(changed)))

    keep = [v for v in verdict if verdict[v]["passed"]]
    print()
    if keep:
        print("MECHANICAL VERDICT: keep %s" % ", ".join(sorted(keep)).upper())
    else:
        print("MECHANICAL VERDICT: revert-flag (no variant passes every gate)")

    out = os.path.join(os.path.dirname(os.path.abspath(path)), "h23_summary.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump({"gates": GATES, "verdict": verdict,
                   "mechanical": ("keep:" + ",".join(sorted(keep))) if keep else "revert"}, fh, indent=1)
    print("wrote %s" % out)


if __name__ == "__main__":
    main()
