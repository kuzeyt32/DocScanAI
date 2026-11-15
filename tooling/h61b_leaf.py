"""Which of the short photos are ONE-LEAF locks, and does extending actually pay?

h61 tested for a gutter signature and found none. But a saddle-stitched workbook spread has NO
visible dark gutter - the pages meet at a flat fold. So the right question is simpler and
pixel-free: is the quad roughly half of a TWO-LEAF target, and does the target really have two
leaves (rather than one page plus the stack behind it)?

`h48_pointgrid` already answers the second half: `spread` is True only when two sheets merge and
the second one is fully in frame. Cross that against quad/target area.
"""
import json

rows = json.load(open(r"tooling/out/h50/norepair.json", encoding="utf-8"))
pg = {r["image"][:-4]: r for r in json.load(open(r"tooling/out/h48/pointgrid.json",
                                                 encoding="utf-8"))}

print(f"{'photo':22} {'verdict':6} {'spread':6} {'2nd':>4} {'tgt':>5} {'quad':>5} {'q/t':>5} "
      f"{'rec':>5} {'prec':>5}")
leaf_locks = []
for r in sorted(rows, key=lambda r: r["iou"]):
    if r["verdict"] == "LOCK":
        continue
    stem = r["image"][:-4]
    p = pg.get(stem, {})
    ratio = r["quad_frac"] / max(1e-9, r["target_frac"])
    is_leaf = p.get("spread") and 0.40 <= ratio <= 0.62 and r["precision"] >= 0.95
    if is_leaf:
        leaf_locks.append(stem)
    print(f"{stem:22} {r['verdict']:6} {str(p.get('spread')):6} "
          f"{p.get('second_leaf') or 0:4.2f} {r['target_frac']:5.2f} {r['quad_frac']:5.2f} "
          f"{ratio:5.2f} {r['recall']:5.2f} {r['precision']:5.2f}"
          f"{'   <- one leaf' if is_leaf else ''}")

print(f"\n{len(leaf_locks)} photos are a ONE-LEAF lock on a genuine two-leaf target:")
for s in leaf_locks:
    print(f"   {s}")
print("\nThese are the only ones an 'extend across the fold' rule could fix, and the rule needs")
print("to know which way to extend: the direction with page on the far side and a flat fold")
print("(no dark gutter) in between.")
