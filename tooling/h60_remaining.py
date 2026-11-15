import json

r = json.load(open(r"tooling/out/h50/norepair.json", encoding="utf-8"))
for v in ("UNDER", "OVER", "PART"):
    g = [x for x in r if x["verdict"] == v]
    if not g:
        continue
    print(f"--- {v} ({len(g)}) ---")
    for x in sorted(g, key=lambda x: x["iou"]):
        print(f"  {x['image'][:-4]:22} {x['prov']:18} "
              f"rec={x['recall']:.2f} prec={x['precision']:.2f} iou={x['iou']:.2f}")
