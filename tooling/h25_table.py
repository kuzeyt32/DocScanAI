"""H25 table builder: reads the pulled h25_results.json and prints the per-page
table, the means and the mechanical gate verdict. Numbers only, no tuning."""
import json
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
DEFAULT = os.path.join(ROOT, "out", "h25")


def newest_results(base):
    best = None
    for name in os.listdir(base):
        p = os.path.join(base, name, "h25_results.json")
        if os.path.isfile(p):
            if best is None or os.path.getmtime(p) > os.path.getmtime(best):
                best = p
    return best


def mean(xs):
    xs = [x for x in xs if x is not None]
    return (sum(xs) / len(xs)) if xs else float("nan")


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else newest_results(DEFAULT)
    if not path or not os.path.isfile(path):
        print("no h25_results.json found")
        return 1
    with open(path, "r", encoding="utf-8") as fh:
        d = json.load(fh)
    print("source:", os.path.relpath(path, os.path.dirname(ROOT)))
    print("dataset:", d.get("dataset"), "variant:", d.get("variant"))
    print()
    hdr = ("asset|paper|depth_lvl|sugg|contrast%|noise%|psnr_dB|sharp x|comps in|comps out|comps%|out paper|out depth")
    print(hdr)
    for p in d.get("pages", []):
        r = p.get("ridge") or {}
        print("|".join([
            str(p.get("asset")),
            "%.1f" % p.get("input_paper", 0.0),
            "%.2f" % p.get("input_depth_levels", 0.0),
            "Y" if p.get("suggested") else "n",
            "%+.2f" % r.get("contrast_gain_pct", 0.0),
            "%+.2f" % r.get("noise_gain_pct", 0.0),
            "%.2f" % r.get("psnr_nonstroke_db", 0.0),
            "%.2f" % r.get("sharpness_ratio", 0.0),
            str(r.get("components_in", 0)),
            str(r.get("components_out", 0)),
            "%+.2f" % r.get("components_delta_pct", 0.0),
            "%.1f" % r.get("paper_level", 0.0),
            "%.2f" % r.get("stroke_depth_levels", 0.0),
        ]))
    pages = d.get("pages", [])
    print()
    print("MEANS (12 real pencil pages)")
    for key, fmt in [("contrast_gain_pct", "%+.2f"), ("noise_gain_pct", "%+.2f"),
                     ("psnr_nonstroke_db", "%.2f"), ("sharpness_ratio", "%.2f"),
                     ("components_delta_pct", "%+.2f")]:
        vals = [(p.get("ridge") or {}).get(key) for p in pages]
        print("  %-20s min=%s max=%s mean=%s" % (key, fmt % min(vals), fmt % max(vals), fmt % mean(vals)))
    print("  suggested fired     %d/%d" % (sum(1 for p in pages if p.get("suggested")), len(pages)))
    normals = d.get("normal_pages", [])
    if normals:
        print()
        print("MEANS (%d normal-paper corpus pages)" % len(normals))
        for key, fmt in [("contrast_gain_pct", "%+.2f"), ("psnr_nonstroke_db", "%.2f"),
                         ("sharpness_ratio", "%.2f"), ("components_delta_pct", "%+.2f")]:
            vals = [n.get(key) for n in normals]
            print("  %-20s min=%s max=%s mean=%s" % (key, fmt % min(vals), fmt % max(vals), fmt % mean(vals)))
        print("  worst normal PSNR: %s (%.2f dB)" % tuple(
            min(((n.get("asset"), n.get("psnr_nonstroke_db")) for n in normals), key=lambda t: t[1])))
    lat = d.get("latency_ms", {})
    print()
    print("LATENCY @2000px: median=%sms p95=%sms max=%sms n=%s" % (
        lat.get("median"), lat.get("p95"), lat.get("max"), len(lat.get("samples") or [])))
    print()
    print("GATES")
    gates = d.get("gates", {})
    for k in sorted(gates):
        print("  %-26s %s" % (k, gates[k]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
