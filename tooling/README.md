# tooling/ - what is here, and why the numbers have gaps

## Why the numbers skip

Two things share the `hNN` prefix, which is why `h50` can be followed by `h54` with nothing in
between:

1. **Scripts** - `tooling/hNN_name.py`, one per investigation.
2. **Lane tags** - the *output folder name* passed to `tooling/h39_dump.cmd <Tag>`. A tag has no
   script of its own; it is just a label for a run of the detector over a photo set. Tags that look
   like missing scripts: `h53spread`, `h56owner`, `h59norepair`, `h59owner`, `h64fast`, `h64owner`,
   `h52angle`, `h44trace`, `h47holdout`. Traces live in `tooling/out/h39/<tag>/`.

3. **Deleted probes.** `h51_enclosure.py` and `h52_trim_probe.py` existed briefly and were removed
   once they had answered their question; their findings are in `docs/runs/`. The numbering was not
   reused, hence the gap.

Rule of thumb: if a number is missing from `tooling/h*.py`, it was either a lane tag or a deleted
probe. Nothing is referenced by the docs that is not present.

## Current pipeline (use these)

### Build the reference (page masks)

| script | job |
|---|---|
| `h48_pointgrid.py` | SAM point-prompt pass -> page mask per photo, saved to `tooling/out/h48/masks/*.npy`. Selection is on printed matter, not frame centre; a frame-cut sheet is not merged in. |
| `h50_mask_sheet.py` | contact sheet of those masks, to check them by eye before trusting any score |

### Score the detector

| script | job |
|---|---|
| `h39_dump.cmd <tag> all` | replays a photo set through the real detector, writes traces |
| `h50_quad_vs_mask.py` | quad vs page mask -> recall / precision / IoU / verdict |
| `h47_score.py` | the older quad-vs-quad score against an oracle manifest (owner110) |
| `h54_compare.py` | A/B two scores photo by photo, so regressions cannot hide in a mean |
| `h55_quad_vs_mask_sheet.py` | visual sheet: mask outline + detector quad, per photo |
| `h66_ceiling.py` | best IoU any quad could achieve per photo (the headroom) |
| `h67_oneleaf.py` / `h67b_oneleaf_owner.py` | is the quad one leaf of a two-leaf page? |
| `h62_referee.py` | is the "miss" actually the mask including the page behind? |
| `h63_timing.py` | detection time and the M-LSD share of it |
| `h64_verify_fast.py` | proves an optimisation changed nothing (quads/provenance/gate) |

### A/B a specific rule

| script | job |
|---|---|
| `h57_ab_repair.py` | pre-repair vs shipped quad, holdout, mask referee |
| `h57b_ab_owner.py` | same on owner110, oracle referee |
| `h58_trimclass.py` | the failed "is the trimmed strip printed or blank?" discriminator |
| `h68_extend_rule.py` | extension v1 (luma only) - NEGATIVE, kept for the record |
| `h68b_extend_ink.py` | extension v2 (printed content + frame guard) - prototype, not shipped |
| `h68c_owner.py` / `h68d_tally.py` | v2 on owner110, and the final tally |

### Data intake

`h47_intake.py` (holdout assets + oracle), `h47_montage.py` (contact sheets),
`h49_audit.py` (failure-mode taxonomy; superseded for edge accuracy).

## Where the numbers behind the releases are

* `docs/runs/0.3.17-detector-fixes.md` - spread rule + blunt-corner gate
* `docs/runs/0.3.17-repair-disabled.md` - why the M-LSD trim was switched off
* `docs/runs/0.3.18-what-remains.md` - referee error vs detector error
* `docs/runs/quad-ceiling-and-extension.md` - quad ceiling, one-leaf locks, extension prototype

## Conventions

* Photos are **never** committed. `app/src/androidTest/assets/holdout*/*.jpg` is gitignored; only
  the JSON manifests are tracked, and those carry filenames and dimensions, not image content.
* Every claim in `docs/runs/` is produced by a script here. If a number has no script, it was not
  measured.
* A change ships only if **both** photo sets improve: `holdout42` (never tuned on) and `owner110`
  (the regression guard).
