"""How much of the remaining UNDER class is the REFEREE, not the detector?

Looking at the overlay sheets: on `192342`, `192354_0` and `192356` the green mask extends well
above the front page - it includes the next page fanned out behind. The red quad sits exactly on
the front page. Those are correct detections scored as failures.

This measures it without asking the mask to be perfect: for each UNDER photo, how much of the
UNCOVERED mask lies ABOVE the quad's top edge (i.e. in the stack-behind direction) versus
elsewhere. If the miss is concentrated above, the quad is right and the mask over-covers.
"""
import json

import cv2
import numpy as np

from pathlib import Path

ROOT = Path("app/src/androidTest/assets/holdout42")
MASKS = Path("tooling/out/h48/masks")
TRACES = Path("tooling/out/h39/h59norepair")

rows = json.load(open(r"tooling/out/h50/norepair.json", encoding="utf-8"))
rows = [r for r in rows if r["verdict"] in ("UNDER", "PART")]

print(f"{'photo':22} {'rec':>5} {'missed px':>10} {'above':>6} {'below':>6} {'left':>6} "
      f"{'right':>6}   verdict")
suspect = []
for r in sorted(rows, key=lambda r: r["recall"]):
    stem = r["image"][:-4]
    tp = TRACES / stem / "trace.json"
    mp = MASKS / f"{stem}.npy"
    if not tp.exists() or not mp.exists():
        continue
    t = json.loads(tp.read_text(encoding="utf-8"))
    if not t.get("quad"):
        continue
    mask = np.load(mp).astype(bool)
    h, w = mask.shape
    W, H = float(t["image_w"]), float(t["image_h"])
    q = np.array([[d["x"] / W * w, d["y"] / H * h] for d in t["quad"]], np.float64)
    qm = np.zeros((h, w), np.uint8)
    cv2.fillPoly(qm, [np.round(q).astype(np.int32)], 1)
    missed = mask & (qm == 0)
    tot = int(missed.sum())
    if tot < 500:
        continue
    qys, qxs = np.nonzero(qm.astype(bool))
    above = int((missed & (np.arange(h)[:, None] < qys.min())).sum())
    below = int((missed & (np.arange(h)[:, None] > qys.max())).sum())
    left = int((missed & (np.arange(w)[None, :] < qxs.min())).sum())
    right = int((missed & (np.arange(w)[None, :] > qxs.max())).sum())
    frac_above = above / tot
    tag = ""
    if frac_above >= 0.55:
        tag = "REFEREE (miss is above the page)"
        suspect.append(stem)
    print(f"{stem:22} {r['recall']:5.2f} {tot:10d} {frac_above:6.2f} {below / tot:6.2f} "
          f"{left / tot:6.2f} {right / tot:6.2f}   {tag}")

print(f"\n{len(suspect)} of {len(rows)} short photos are dominated by a miss ABOVE the front page -")
print("that is the next page fanned out behind being included in the target mask.")
for s in suspect:
    print(f"   {s}")
