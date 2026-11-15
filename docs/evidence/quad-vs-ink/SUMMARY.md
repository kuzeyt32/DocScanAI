# H40 — quad vs ink, owner110 (offline)

* photos measured: **116** (detected 96, correct 62, merged 27, wrong-page 17)
* mean text gain when the SAME photo is re-rectified with the oracle quad, over the bad quads: **-0.183**

## Does an INK-side feature separate bad quads from good ones?

AUC = chance that a bad quad scores higher than a good one (0.5 = no separation).

| feature | median bad | median good | AUC |
|---|---|---|---|
| border_ink | 0.093 | 0.089 | 0.462 |
| ink_ratio | 0.576 | 0.556 | 0.529 |
| band_luma | 139.998 | 149.155 | 0.298 |
| mode_band | 0.546 | 0.580 | 0.431 |
| text | 0.848 | 0.874 | 0.454 |
| paper | 143.000 | 154.500 | 0.306 |

Reading: `border_ink` / `ink_ratio` describe how much ink/texture sits in the outer 12 % band of the rectified page. A page that is correctly cropped has a plain paper margin there; a quad that swallowed the table does not.

