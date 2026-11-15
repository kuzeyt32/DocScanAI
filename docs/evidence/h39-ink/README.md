# H40 — the ink evidence, and what it says

What is in this folder
----------------------
`index.html` shows, for 12 owner photos from the H39 trace dump, the detector's own maps:
the tensor handed to the corner model, the four AI corner heat maps, the final quad, and the
gate's rectified page + INK map + glyph boxes.

**The INK map is not produced by the AI.** It is `cv2.adaptiveThreshold(MEAN_C, block 25, C 10)`
on the rectified page (`PagePlausibility.analyseRectifiedInterior`). The AI only produces the
four corner heat maps; the INK map is the *evidence* the gate uses to decide whether the quad
was a page. So the question "is the AI ink map the problem?" is really "is the gate's ink
evidence bad, or is the quad it was given bad?".

The experiment
--------------
For every photo the same image was rectified twice: once with the quad the detector returned,
once with the oracle (ground-truth) quad. Same photo, same INK extractor, same 300 px/7 % ROI.

Measured result (12 photos, `probe.json`)
-----------------------------------------
* **No-detection photos** (`20261024_185245`, `20261024_185748`): no quad at all, but the
  oracle-quad INK map is clean (64 and 116 glyphs, text 0.86 / 0.93). The ink evidence would
  have been fine — detection never produced a quad.
* **Merged / wrong-page photos** (`20261025_130821`, `20261025_131201`, `20261025_131249`,
  `20261025_130818`): the INK map of the *wrong* quad scores text 0.71–0.95, i.e. as high as —
  sometimes higher than — the *correct* quad (0.75–0.93). The ink map does not get "worse" when
  the quad is wrong; it just describes the wrong region. The gate therefore cannot tell.
* **Good detections**: ink and quad agree; nothing to fix.

Whole-set result (116 owner photos, `../quad-vs-ink/`)
------------------------------------------------------
* Mean text-score change when the bad quads are replaced by the oracle quad: **-0.183** (the
  wrong region scores *higher*). The text score saturates at 0.85–0.95 on any textured region.
* No INK-side feature separates bad from good quads: `border_ink` AUC 0.462, `ink_ratio` 0.529,
  paper-mode fraction 0.431 (`quad-vs-ink/SUMMARY.md`).
* All 34 bad quads were `CLASSICAL_FALLBACK`; all 31 `CORNER_MODEL` quads were correct.

Conclusion: the ink map is not the root cause. On curved/dyed pages the failure is upstream
(no quad, or a quad merged with the table/background), and the INK score is too easily satisfied
by texture that is not text, so the merged quad is accepted instead of rejected.

How to read a card
------------------
* `heat_*` near-black on a real page -> the corner model did not see corners (input/domain
  problem).
* bright heat blob away from the page corner -> the model is looking at the wrong thing.
* INK map good but the quad is visibly not the page -> postprocess/rank/gate problem; the ink
  evidence cannot save it.
* INK map empty/noisy while the page clearly has text -> genuinely an INK-map problem (not
  observed in this sample).

Regenerate
----------
    tooling\h39_dump.cmd <Tag> [stems|all]                      # phone lane, writes the trace
    python tooling\h40_ink_probe.py  tooling\out\h39\<Tag>    # quad-vs-oracle ink probe
    python tooling\h40_ink_report.py tooling\out\h39\<Tag> tooling\out\h40_ink_probe
