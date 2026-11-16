"""Final tally of the extension prototype, both sets, at the scored threshold."""
import json

hold = json.load(open(r"tooling/out/h68/extend_ink.json", encoding="utf-8"))
own = json.load(open(r"tooling/out/h68/extend_owner.json", encoding="utf-8"))

for label, rows, lock in (("holdout42", hold, 0.85), ("owner110", own, 0.5)):
    fired = [r for r in rows if r.get("accepted")]
    helped = [r for r in fired if r["after"] > r["before"] + 0.02]
    hurt = [r for r in fired if r["after"] < r["before"] - 0.02]
    lb = sum(1 for r in rows if r["before"] >= lock)
    la = sum(1 for r in rows if r["after"] >= lock)
    working_before = [r for r in rows if r["before"] >= lock]
    working_hurt = [r for r in hurt if r["before"] >= lock]
    print(f"{label}: {len(rows)} photos, threshold {lock}")
    print(f"  passing  {lb} -> {la}  ({la - lb:+d})")
    print(f"  mean IoU {sum(r['before'] for r in rows) / len(rows):.3f} -> "
          f"{sum(r['after'] for r in rows) / len(rows):.3f}")
    print(f"  fired {len(fired)}, helped {len(helped)}, hurt {len(hurt)}")
    print(f"  photos that WERE passing and got worse: {len(working_hurt)}"
          + (f" {[r['stem'] for r in working_hurt]}" if working_hurt else ""))
    print()
