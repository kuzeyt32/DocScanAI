# Rebuild plan - setup + project prompts

These two prompts were written on the Windows machine and would otherwise exist only in a chat
session that does not transfer to Linux. Committed here so they survive the OS switch.

**Canonical copies** (kept identical to the USB stick):

* `docs/plans/prompts/01-setup-cachyos.txt`
* `docs/plans/prompts/02-rebuild-project.txt`

**Order:** run the SETUP prompt first (CachyOS + OpenCode + T3 Code + ROCm + USB restore), then
the PROJECT prompt when the photos are restored and the repo is cloned.

The setup prompt also reads the handoff from a 32 GB USB stick
(`DocScanAI-handoff/`) that carries the private photos, the page masks, the detector traces and a
full git bundle — see `docs/plans/usb-handoff-README.md` for that stick's index.

> **Gap to correct:** earlier work evaluated only on `holdout42` and `owner110`.
> `bookspread_real` (16 real open-book spread photos — exactly the failure mode) and the
> `pencil` / `live_room` sets were never scored. All six sets are on the stick and the project
> prompt requires them in P0.

Context that makes these prompts make sense is in:

* `docs/runs/0.3.17-detector-fixes.md`
* `docs/runs/0.3.17-repair-disabled.md`
* `docs/runs/0.3.18-what-remains.md`
* `docs/runs/quad-ceiling-and-extension.md`
* `tooling/README.md`

---

## PROMPT 1 - machine setup (paste into the fresh CachyOS agent)

Canonical file: `docs/plans/prompts/01-setup-cachyos.txt` (also `PROMPTS/` on the USB).
It additionally resolves the USB handoff, restores all six photo sets and the measurements,
and verifies the GPU before the project prompt is used.

```
You are setting up a fresh CachyOS workstation for an Android + PyTorch project.
Work step by step. After EVERY step, show the actual command output that proves it
worked. If a step fails, report the exact error and continue with the next independent
step - never claim success you did not observe.

CRITICAL: CachyOS is Arch-based. It uses pacman/paru, NOT apt. Do not run apt, apt-get,
dpkg, or any Debian/Ubuntu command. If you catch yourself typing apt, stop.

Idempotent: before installing anything, check whether it already exists. Do not
reinstall or overwrite user config. Do not reboot. Do not delete anything.

=== STEP 0 - detect the system ===
Run and report:
  cat /etc/os-release
  uname -r
  lspci -nn | grep -Ei 'vga|3d|display'
  whoami; id
  df -h | head -20
  lsblk -f
Report: distro, kernel, GPU model and PCI id, your user, free space.
Note which of these already exist: git curl wget node npm python3 pip docker adb java paru yay

=== STEP 1 - system update ===
  sudo pacman -Syu --noconfirm
Report the before/after package counts. If a kernel update happens, say so (do NOT reboot
yourself, just report it).

=== STEP 2 - base packages ===
  sudo pacman -S --needed --noconfirm base-devel git curl wget unzip zip jq \
      htop tree rsync python python-pip nodejs npm jdk21-openjdk \
      android-tools ntfs-3g fuse2 cmake clang pkgconf vulkan-tools
Report which were newly installed vs already present.
Then verify: git --version, node --version, npm --version, java -version,
adb --version, python3 --version

=== STEP 3 - AUR helper (CachyOS ships paru; AUR may be disabled) ===
  command -v paru; command -v yay
If neither exists:
  sudo pacman -S --needed --noconfirm paru
  (if that fails, build it from the AUR manually and report)
Then TEST that AUR access actually works:
  paru -Si t3code-bin
If AUR is disabled, find and report the setting, and ask the user before changing it.
Do not silently edit pacman.conf.

=== STEP 4 - OpenCode ===
Prefer the official Arch package:
  sudo pacman -S --needed --noconfirm opencode
If that package does not exist in the repos:
  curl -fsSL https://opencode.ai/v2/install | bash
Fallbacks, in order: paru -S opencode-beta  |  npm install -g @opencode/cli
Verify: opencode --version   (must print a version)

=== STEP 5 - T3 Code ===
  paru -S --needed --noconfirm t3code-bin
Verify the binary/desktop entry exists:
  command -v t3code || ls /usr/bin | grep -i t3
  ls /usr/share/applications | grep -i t3
Report how the user launches it (command name and/or menu entry name).

=== STEP 6 - GPU access for compute ===
  ls -l /dev/kfd /dev/dri/renderD* 2>&1
  groups
  sudo usermod -aG video "$USER"
  sudo usermod -aG render "$USER"
Report that the group change needs a re-login to take effect (do not reboot).

=== STEP 7 - ROCm (two routes - try the simple one, fall back to Docker) ===
ROUTE A - native packages (simpler if it works):
  sudo pacman -S --needed --noconfirm rocm-hip-sdk rocm-opencl-runtime
  Verify: /opt/rocm/bin/rocminfo | head -40   and   /opt/rocm/bin/hipconfig --version
  Check the ISA list contains gfx1201 (RX 9070 XT / RDNA4).
  If rocminfo fails or gfx1201 is missing, say so and move to Route B.

ROUTE B - Docker with AMD's official PyTorch image (most reliable):
  sudo pacman -S --needed --noconfirm docker docker-compose
  sudo systemctl enable --now docker
  sudo usermod -aG docker "$USER"
  sudo docker pull rocm/pytorch:rocm7.14_ubuntu24.04_py3.11_pytorch_release_2.10.0
  Test:
  sudo docker run --rm --device=/dev/kfd --device=/dev/dri \
      -e PYTORCH_TUNABLEOP_ENABLED=1 \
      rocm/pytorch:rocm7.14_ubuntu24.04_py3.11_pytorch_release_2.10.0 \
      python -c "import torch; print(torch.__version__); print('cuda available:', torch.cuda.is_available()); print(torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'NO GPU')"
Report which route succeeded. "cuda available: True" is the only acceptable proof.

=== STEP 8 - Python ML environment (host side) ===
Create a venv, do not pollute system python:
  python3 -m venv ~/mlenv
  source ~/mlenv/bin/activate
  pip install --upgrade pip
  pip install numpy opencv-python-headless pillow onnxruntime
Verify:
  python -c "import onnxruntime as o; print(o.__version__, o.get_available_providers())"
  python -c "import cv2; print(cv2.__version__)"
Report the ONNX Runtime providers list - the project deploys to CPU/GPU on Android, so
know which providers exist here.

=== STEP 9 - Windows drives (the project and photos live there) ===
  lsblk -f   (identify the NTFS partitions)
Mount them read-write under /mnt, e.g.:
  sudo mkdir -p /mnt/d /mnt/e
  sudo mount -t ntfs3 /dev/<PARTITION> /mnt/d
  sudo mount -t ntfs3 /dev/<PARTITION> /mnt/e
  touch /mnt/d/.writetest && rm /mnt/d/.writetest && echo "writable"
IMPORTANT - if the mount comes up read-only, the cause is almost always Windows
"Fast Startup" or hibernation leaving the filesystem dirty. Do NOT force it with
remove_hiberfile unless the user explicitly approves (it can corrupt Windows' state).
Instead report: "NTFS mounted read-only - Windows Fast Startup/hibernation is likely on.
Disable Fast Startup in Windows, shut down fully, then re-mount."

=== STEP 10 - final report ===
Write ~/SETUP_REPORT.md containing a table:
  component | version | how verified | PASS/FAIL
covering: OS, kernel, GPU + /dev/kfd, ROCm route, torch GPU test, onnxruntime, opencode,
t3code, node, java, adb, python venv, mounted drives.
End with a short "what still needs the user" list (re-login for groups, Windows Fast
Startup, anything that failed).

Do not install Android Studio, do not download models, do not clone any repository.
Stop after the report and wait.
```

---

## PROMPT 2 - project rebuild (paste when photos are mounted)

```
You are taking over a working Android document-scanner project (DocScanAI) at the point
where we have decided the detection pipeline must be rebuilt. Do NOT start by coding.
Start by reading, then verify the environment, then follow the phases.

## Context

Repo: DocScanAI (Kotlin/Android, OpenCV, ONNX Runtime, models loaded at runtime).
Detection today: DocAligner LCNet100 corner model + classical OpenCV fallbacks + a stack of
hand-written gates. It is a SINGLE-DOCUMENT corner detector.

What we have measured (do not re-derive):
- It cannot handle two-page spreads reliably. Its own paper (arXiv 2306.05749, Appendix F)
  states: orientation must be upright, "Incomplete" (partly out-of-frame) documents
  unsupported, large rotations excluded. These map exactly onto the observed failures.
- 6 of 42 holdout photos are "one-leaf locks" (quad on one leaf of a two-page target);
  9 of 94 on the original set. So it is a general weakness, ~10-14% of captures.
- The best possible quad for those photos scores median IoU 0.98, and 40 of 42 are
  reachable at IoU>=0.85 while the detector reaches 23. So the loss is quad CHOICE, not
  quad SHAPE. Do not build polygonal-crop machinery to fix coverage; it is not the issue.
- Approaches already tried and REJECTED with measurements (do not retry):
  * luma-based edge extension: fired 29, helped 9, hurt 19 (white page on white table)
  * M-LSD merge-repair splitting: damaged 36 of 38 photos it touched; disabled
  * "is there paper outside the quad" panel test: does not separate panel from page
- The SAM-based measuring mask over-covers (includes the page fanned out behind the front
  one) on ~5 of 16 short cases. It is a pre-annotation tool, NOT ground truth.

## Decision already made

Replace the hypothesis: instead of "a page is 4 corners", predict PAGE INSTANCES (masks),
then fit a quad or mesh. Architecture:

  PREVIEW  tiny net (256 grayscale) -> coarse instances + Kalman filter + IMU gate
  CAPTURE  full-res instance segmenter (the real model) -> decision layer:
             drop instances cut by the frame (a partly-seen leaf is not the target)
             rank by area/centre/ink
             two adjacent similar instances -> SPREAD (union)
             one dominant -> SINGLE PAGE
           -> contour -> quad fit -> subpixel edge refinement (already exists, keep it)
  WARP     homography for flat pages, mesh for curved

Rationale, from industry primary sources: Scanner Pro runs a small keypoint net on every
frame and a heavier segmentation model at capture; Genius Scan uses MobileNetV2 at 96x96
then refines the quad classically (51% -> 75% -> 85%); Adobe uses a 64x64 cascade at
557 fps and labels "two pages" explicitly. Genius Scan at 85% still lists "better manage
the detection of book pages" as FUTURE work - this is genuinely the hard case.

Model to train: small 3-class instance segmenter (page / gutter / hand),
MobileNetV3 backbone, ~5M params, ONNX export. Not a foundation model.

## Hard rules

1. NEVER tune on the evaluation split. Split user photos ~150 train / ~150 eval; the eval
   half is sealed and opened exactly once, at the end.
2. A change ships only if it improves on BOTH an unseen set and the regression set.
3. Photos are private: never commit them or upload them. Manifests only.
4. Every claimed number must come from a script in the repo. If a number has no script,
   it was not measured.
5. Report regressions explicitly, photo by photo. No means hiding failures.

## Phases

P0 instrument (before any model work)
  - Rotation harness: replay the existing 42 photos at 0/90/180/270 through the unchanged
    detector. Quantify the rotation dependence the user reported.
  - Replace the mask referee with INSTANCE-level ground truth (each leaf its own instance).
  - Gate: rotation effect quantified; phantom failures removed from the baseline.

P1 synthetic factory
  - Composite clean page images into: single page, two-page spread with gutter and
    cylinder curvature, panels lying on a page, frame-cut pages, random 90-degree
    rotations, backgrounds (wood/white table/cloth), shading maps, occasional hand/glare.
  - Exact instance masks by construction. Target 30K images.
  - Synthetic supplies LABELS (topology, rotation, truncation), never APPEARANCE.
    Appearance and final fine-tuning come from the user's real photos.
  - Use REAL background plates and REAL shading maps captured by the user, plus the
    measured noise / blur / JPEG quality of the user's camera.
  - Track a realism critic (real-vs-synthetic classifier accuracy) and a synthetic-only
    -> real transfer test. If transfer collapses, drop synthetic.
  - Gate: visual audit - synthetic must be hard to tell from the user's real photos.

P2 train the segmenter
  - Real-only baseline FIRST (user's ~150 photos). Then add synthetic only for starved
    classes (rotated spreads, frame-cut pages, two-leaf topology).
  - Heavy augmentation including random 90-degree rotation (Adobe does this because phone
    orientation metadata is unreliable).
  - Gate: on held-out real photos, instance IoU >= 0.90 median; 1-vs-2 leaf decision >= 95%.

P3 pipeline v2
  - Decision layer (pure logic, unit-testable offline), Kalman + IMU, two-stage preview,
    mesh warp for curved pages.
  - Optional only if needed: pseudo-sequences (random small homography + brightness jitter
    per frame) with N frames as input channels. Do NOT collect real video for this.
  - Gate: >= 15 percentage points absolute gain; zero regressions on any existing set.

P4 honest evaluation
  - Freeze code, run once on the sealed set, publish the number even if lower.

## Environment to verify first

Linux (CachyOS, Arch-based), AMD RX 9070 XT (gfx1201, 16GB), Ryzen 7 7800X3D.
- Prefer AMD's Docker image over native packages: rocm/pytorch with PyTorch 2.10+
- Verify: torch.cuda.is_available() == True on ROCm, and a real training step runs
- Verify torch.onnx.export of the intended architecture, and that the exported model loads
  in onnxruntime with the CPU provider (this is the real deployment target)
- Report actual measured throughput (images/sec) before quoting any training-time estimate

## Your first response

Do not write model code yet. Reply with:
1. what you verified about the environment, with command output
2. a concrete P0 plan with the exact scripts you will write
3. any place where the instructions above are wrong or under-specified

Then wait for confirmation before starting P0.
```
