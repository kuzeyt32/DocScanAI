import glob
import json
import os
import re
from collections import Counter

for tag in ("h59norepair", "h59owner"):
    c = Counter()
    for p in sorted(glob.glob(f"tooling/out/h39/{tag}/*/trace.json")):
        t = json.load(open(p, encoding="utf-8"))
        log = t.get("mlsd_log") or ""
        m = re.search(r"reason=([a-z_]+)", log)
        c[m.group(1) if m else "(none)"] += 1
    print(f"{tag}: {dict(c)}")
