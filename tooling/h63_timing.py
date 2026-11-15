"""Where does per-photo detection time go? (battery/speed work, without changing behaviour)

Not a benchmark harness - just the trace's own `detect_ms` grouped by provenance, plus the M-LSD
`modelMs`/`triggerUs` counters it already records. Answers whether there is an easy win or
whether the time is all in the ONNX corner model (which cannot be made cheaper without changing
output).
"""
import glob
import json
import os
import re
import statistics

for tag, label in (("h59norepair", "holdout42"), ("h59owner", "owner110")):
    rows = []
    mlsd_model = []
    for p in sorted(glob.glob(f"tooling/out/h39/{tag}/*/trace.json")):
        t = json.load(open(p, encoding="utf-8"))
        ms = t.get("detect_ms")
        if ms:
            rows.append((ms, t.get("provenance")))
        log = t.get("mlsd_log") or ""
        m = re.search(r"modelMs=(\d+)", log)
        if m:
            mlsd_model.append(int(m.group(1)))
    if not rows:
        continue
    allms = [r[0] for r in rows]
    corner = [r[0] for r in rows if r[1] == "CORNER_MODEL"]
    classic = [r[0] for r in rows if r[1] == "CLASSICAL_FALLBACK"]
    print(f"{label} (n={len(rows)})")
    print(f"  detect_ms  median {statistics.median(allms):6.0f}  "
          f"mean {statistics.mean(allms):6.0f}  max {max(allms):6.0f}")
    if corner:
        print(f"    CORNER_MODEL     n={len(corner):3}  median {statistics.median(corner):6.0f}  "
              f"mean {statistics.mean(corner):6.0f}")
    if classic:
        print(f"    CLASSICAL_FALLBACK n={len(classic):3}  median {statistics.median(classic):6.0f}  "
              f"mean {statistics.mean(classic):6.0f}")
    if mlsd_model:
        print(f"  M-LSD modelMs    median {statistics.median(mlsd_model):5.0f}  "
              f"(share of a corner detect: "
              f"{100 * statistics.median(mlsd_model) / max(1, statistics.median(corner or allms)):.0f} %)")
    print()
