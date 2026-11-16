# DocScanAI handoff — USB contents

Everything needed to continue this project on the CachyOS machine, offline.
Created from the Windows machine on 2026-11-16.

**Start here:** read this file, then `PROMPTS/01-setup-cachyos.txt`.
Paste prompt 1 into the agent first. Paste `PROMPTS/02-rebuild-project.txt` after it completes.

---

## Why this exists

The chat session and its reasoning do **not** transfer to another OS — OpenCode's session
database is bound to a Windows path. So everything that mattered was committed to git and
copied here.

This stick holds three things that are **not** on GitHub:

1. **The photos** — all six sets are gitignored on purpose. They are private.
2. **The measurements** — page masks and detector traces, so nothing needs recomputing.
3. **The prompts** — they existed only in a chat that will not survive the switch.

---

## `PROMPTS/`

| file | when to use |
|---|---|
| `01-setup-cachyos.txt` | first, on the fresh CachyOS machine. Installs OpenCode, T3 Code, ROCm, restores this handoff, verifies GPU. |
| `02-rebuild-project.txt` | after setup passes. The rebuild brief: context, decisions already made, hard rules, phases P0–P4. |

Prompt 1 installs OpenCode **before** T3 Code, because T3 Code is a UI that hosts a provider.

---

## `photos/` — 220 images, 59 MB

| set | count | what it is | use |
|---|---|---|---|
| `holdout42` | 42 | real phone photos, never used for tuning | **evaluation** (has masks) |
| `owner110` | 116 | real phone photos, the original corpus | **regression** |
| `bookspread_real` | 16 | real open-book spread photos | **evaluation — the failure case** |
| `pencil` | 12 | real handwriting photos | evaluation |
| `live_room` | 16 | real room scenes | evaluation |
| `bookspread` | 18 | **synthetic** book spreads | reference only — never a test set |

**All of these are private.** Do not commit them, do not upload them. They are gitignored in
the repo:
```
/app/src/androidTest/assets/owner110/*.jpg
/app/src/androidTest/assets/holdout*/*.jpg
/app/src/androidTest/assets/bookspread/*.jpg
/app/src/androidTest/assets/bookspread_real/*.jpg
/app/src/androidTest/assets/live_room/*.jpg
/app/src/androidTest/assets/pencil/*.jpg
```

JSON manifests sit beside the photos (dimensions, oracle corners). Those *are* tracked in git.

### Correction worth noting

The last round of work only evaluated on `holdout42` and `owner110`. **`bookspread_real` — 16
real spread photos, i.e. exactly the failure mode — was never scored.** That is a real gap and
prompt 2 requires it to be part of P0.

---

## `measurements/` — 60 MB

| path | contents | why keep it |
|---|---|---|
| `h48-masks/` | 42 `.npy` page masks (SAM, repaired) | reference target for scoring; ~20 min to rebuild |
| `scores/` | quad-vs-mask results, one-leaf analysis, ceiling analysis | the numbers quoted in `docs/runs/` |
| `traces/h64fast/` | 42 detector traces, holdout42 | current detector output |
| `traces/h64owner/` | 116 detector traces, owner110 | the regression baseline |

A trace is the detector's own record for one photo: quad, provenance, gate result, timing.
With these you can re-score any change **without a device or an emulator**.

---

## `docs/` — the reasoning

Copy of `docs/` from the repo, plus `tooling-README.md` (which explains the `hNN` numbering
gaps and lists every script).

Key reading, in order:

1. `runs/0.3.17-detector-fixes.md` — spread rule + blunt-corner gate
2. `runs/0.3.17-repair-disabled.md` — why the M-LSD trim was switched off
3. `runs/0.3.18-what-remains.md` — referee error vs detector error
4. `runs/quad-ceiling-and-extension.md` — **the important one**: the quad ceiling is 0.98, so
   four corners are not the limit; quad *choice* is
5. `plans/rebuild-plan.md` — the plan and both prompts (repo copy)

Note: `docs/evidence/h39-ink/img/` contains crops of private photos and is gitignored. It is
here on the stick, and **only** here.

---

## `repo/DocScanAI.bundle` — 109 MB

A complete git bundle: full history, every branch, every commit. Clone offline with:

```bash
git clone /path/to/DocScanAI.bundle DocScanAI
cd DocScanAI
git remote add origin https://github.com/kuzeyt32/DocScanAI.git   # optional
```

Verified with `git bundle verify` → *"records a complete history"*.

Latest commits at time of writing:
- `d2dbcb8` docs: commit the rebuild plan and both setup prompts
- `4759f25` 0.3.18: skip the M-LSD inference nothing consumes (60 % faster, identical output)
- `13de907` 0.3.17: disable the M-LSD merge-repair

Released APKs are **not** here — they are on GitHub Releases (`v0.3.17`, `v0.3.18`).

---

## Current state, in one paragraph

Released `v0.3.18`. On the 42 fresh photos, page lock went 7/42 (17 %) → 23/42 (55 %) and mean
IoU 0.637 → 0.756; on the 116-photo regression set, 44/94 (47 %) → 54/94 (57 %). Detection is
60 % faster. What is still wrong: the detector picks one leaf of a two-page spread
(~10–14 % of captures), and it sometimes locks a panel printed inside a page. The best possible
quad scores 0.98, and 40 of 42 photos are reachable — so the remaining loss is choosing the
wrong quad, which is why the next step replaces "four corners" with "page instances".

---

## Before you unplug

- [ ] 6 photo sets present with correct counts (42 / 116 / 16 / 18 / 12 / 16)
- [ ] 42 `.npy` masks in `measurements/h48-masks/`
- [ ] `repo/DocScanAI.bundle` present and ~109 MB
- [ ] both prompts in `PROMPTS/`

The stick mounts read-only on Linux if Windows left it dirty — eject safely from Windows first.
