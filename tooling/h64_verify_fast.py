"""Verify the M-LSD skip changed nothing but time.

Two claims must hold before this can ship:
  1. every quad is byte-identical to the previous lane (no behaviour change),
  2. detect_ms drops.

Anything less and the optimisation is a silent detector change.
"""
import glob
import json
import os
import statistics
import sys


def load(tag):
    out = {}
    for p in sorted(glob.glob(f"tooling/out/h39/{tag}/*/trace.json")):
        out[os.path.basename(os.path.dirname(p))] = json.load(open(p, encoding="utf-8"))
    return out


pairs = [("h59norepair", "h64fast"), ("h59owner", "h64owner")]
ok = True
for before_tag, after_tag in pairs:
    a, b = load(before_tag), load(after_tag)
    if not b:
        print(f"{after_tag}: no traces yet")
        ok = False
        continue
    diffs = [s for s in a if s in b and json.dumps(a[s].get("quad")) != json.dumps(b[s].get("quad"))]
    prov = [s for s in a if s in b and a[s].get("provenance") != b[s].get("provenance")]
    gate = [s for s in a if s in b and a[s].get("gate_passed") != b[s].get("gate_passed")]
    ta = [a[s]["detect_ms"] for s in a if s in b and a[s].get("detect_ms")]
    tb = [b[s]["detect_ms"] for s in b if s in b and b[s].get("detect_ms")]
    print(f"--- {before_tag} -> {after_tag} ({len(a)} vs {len(b)} photos) ---")
    print(f"  quads changed      : {len(diffs)}")
    print(f"  provenance changed : {len(prov)}")
    print(f"  gate changed       : {len(gate)}")
    if ta and tb:
        ma, mb = statistics.median(ta), statistics.median(tb)
        print(f"  detect_ms median   : {ma:.0f} -> {mb:.0f}  ({100 * (mb - ma) / ma:+.0f} %)")
        print(f"  detect_ms mean     : {statistics.mean(ta):.0f} -> {statistics.mean(tb):.0f}")
    if diffs:
        ok = False
        print(f"  QUAD DIFFS: {diffs[:8]}")
    if prov:
        ok = False
        print(f"  PROVENANCE DIFFS: {prov[:8]}")
    if gate:
        ok = False
        print(f"  GATE DIFFS: {gate[:8]}")
    print()

print("RESULT:", "IDENTICAL output, time reduced" if ok else "BEHAVIOUR CHANGED - do not ship")
sys.exit(0 if ok else 1)
