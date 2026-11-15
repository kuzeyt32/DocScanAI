# Building & Installing DocScan AI

This guide walks you through building the DocScan AI Android app and putting it on a phone, using
**either** Android Studio **or** the command line. Pick the path that suits you — both produce the
same app.

- **Path A — Android Studio** (recommended if you have the IDE): the friendliest option.
- **Path B — Command line (Windows `cmd.exe`)**: for a terminal-only workflow.

Everything here targets a single device (the Samsung Galaxy S25 FE, **arm64-v8a**, Android 14/15),
but the same steps work on any arm64 Android 8.0 (API 26)+ device.

> Read [`README.md`](README.md) first for what the app does, its features, models, and permissions.

---

## Table of contents

- [What you need first](#what-you-need-first)
- [The JDK-21 pin (read this before building)](#the-jdk-21-pin-read-this-before-building)
- [Path A — Build & run in Android Studio](#path-a--build--run-in-android-studio)
- [Path B — Build from the command line (Windows cmd.exe)](#path-b--build-from-the-command-line-windows-cmdexe)
- [Installing on the phone with adb](#installing-on-the-phone-with-adb)
- [First launch](#first-launch)
- [Provisioning the AI models (in-app or by hand)](#provisioning-the-ai-models-in-app-or-by-hand)
- [How to build a signed release / share the APK](#how-to-build-a-signed-release--share-the-apk)
- [Troubleshooting the build](#troubleshooting-the-build)

---

## What you need first

1. **A JDK 21.** The project pins `C:\Program Files\Java\jdk-21` (see below). If you don't have it,
   install a JDK 21 (e.g. from [Adoptium](https://adoptium.net/)) to that location, or edit the path.
2. **The Android SDK** with **Platform 35** and the matching **Build-Tools**. On this machine the SDK
   lives at:

   ```
   C:\Users\Kuzey\AppData\Local\Android\Sdk
   ```

   That path is recorded in [`local.properties`](local.properties) (`sdk.dir=...`). Android Studio
   creates/updates this automatically; on the command line you need it to exist.
3. **Android Studio Ladybug 2024.2.1 or newer** — only for Path A.
4. **A phone** (or an arm64 emulator) with **Developer Options** and **USB debugging** enabled — for
   installing and running.

> **Note on Android Studio:** this particular machine did **not** have Android Studio installed; the
> project was built and verified entirely from the command line (Path B). Path A is documented here
> because it is the most common workflow and works with the same project.

---

## The JDK-21 pin (read this before building)

Gradle **8.10.2** supports Java **8–22**. This machine's default `JAVA_HOME` is **JDK 25**, which
Gradle 8.10.2 **does not support**, so the project pins JDK 21 explicitly in
[`gradle.properties`](gradle.properties):

```properties
org.gradle.java.home=C:/Program Files/Java/jdk-21
```

The app's **bytecode target is still JDK 17** (`jvmTarget = JVM_17` in
[`app/build.gradle.kts`](app/build.gradle.kts:79)) — JDK 21 is only the *toolchain that runs Gradle*.

**What to do:**

- If you **do** have a JDK 21 at `C:\Program Files\Java\jdk-21` → nothing to do.
- If your JDK 21 is elsewhere → edit the path in [`gradle.properties`](gradle.properties).
- If your **default** JDK is already 17 or 21 → you can delete the `org.gradle.java.home` line.
- If you build with Android Studio, Studio normally uses its **bundled JBR (JDK 17/21)**, so the pin
  is usually harmless; if Studio complains, set the **Gradle JDK** to a JDK 17/21 (see
  [Troubleshooting](#troubleshooting-the-build)).

---

## Path A — Build & run in Android Studio

1. **Open the project.**
   - Launch Android Studio.
   - **File → Open…** and select the project folder:

     ```
     C:\Users\Kuzey\Desktop\DocScanAI
     ```

     (Select the folder that contains `settings.gradle.kts`, **not** the `app` subfolder.)
   - Click **OK** / **Trust Project** if prompted.

2. **Let Gradle sync.**
   - Studio detects the Gradle project and starts a **Gradle sync** (progress bar at the bottom).
   - The first sync downloads **Gradle 8.10.2**, the pinned AGP/Kotlin plugins, and the library
     dependencies — this can take several minutes.
   - If Studio asks about the **SDK location** or a missing **Platform 35**, click the suggested
     **Install** links and let the SDK Manager fetch them.
   - If the Gradle **wrapper JAR** were ever missing, Android Studio regenerates it during sync (or
     you can run `gradle wrapper` once); this project ships it, so this is normally a non-issue.

3. **Select the run configuration.**
   - In the toolbar's run-configuration dropdown, choose **`app`** (it should already be selected).

4. **Prepare the phone.**
   - On the device: **Settings → About phone → Software information**, tap **Build number** 7 times to
     enable **Developer options**.
   - In **Developer options**, turn on **USB debugging**.
   - Connect the phone by USB and tap **Allow** on the *"Allow USB debugging?"* prompt.

5. **Run.**
   - Make sure the phone appears in the device dropdown at the top.
   - Click the green **Run ▶** button (or **Shift+F10**).
   - Android Studio builds the debug APK, installs it, and launches DocScan AI on the phone.
   - When the app opens, continue with [First launch](#first-launch).

**Handy Studio actions**

- **Build → Build Bundle(s) / APK(s) → Build APK(s)** builds the debug APK without running.
- **Build → Generate Signed Bundle / APK…** walks you through creating a signed release (see
  [How to build a signed release / share the APK](#how-to-build-a-signed-release--share-the-apk)).

---

## Path B — Build from the command line (Windows cmd.exe)

All commands below are for **Windows `cmd.exe`** and run from the project root. The `&&` chains each
step to run only if the previous one succeeded; the `set "JAVA_HOME=…"` form is quoted so the space
in `Program Files` is handled correctly.

### 1. Open a terminal in the project folder and point at JDK 21

```bat
cd C:\Users\Kuzey\Desktop\DocScanAI && set "JAVA_HOME=C:\Program Files\Java\jdk-21"
```

> `set "VAR=value"` sets the variable **only for this terminal session**. If you close the window,
> set it again. You can also set it permanently in *System Properties → Environment Variables*.

### 2. (Optional) Check the toolchain

```bat
gradlew.bat --version
```

Expected: **Gradle 8.10.2** and a **JVM 21** line. If it reports JDK 25, your `JAVA_HOME` wasn't set
— go back to step 1 (and/or fix the `org.gradle.java.home` pin).

### 3. Build the debug APK

```bat
gradlew.bat assembleDebug
```

The APK is written to:

```
app\build\outputs\apk\debug\app-debug.apk        (≈ 58.9 MiB)
```

### 4. Run the unit tests (optional but recommended)

```bat
gradlew.bat testDebugUnitTest
```

Results/HTML report: `app\build\reports\tests\testDebugUnitTest\`.

### 5. Build the release APK (optional)

```bat
gradlew.bat assembleRelease
```

The release variant is **signed automatically** when a [`keystore.properties`](keystore.properties)
file exists at the repo root (SUBTASK H10). It points at `keystore/docscan-release.jks` and supplies
the store/key passwords, so `assembleRelease` writes a **signed** APK:

```
app\build\outputs\apk\release\app-release.apk            (≈ 119.7 MiB / 125,545,230 B)
```

Without `keystore.properties` the same task still produces `app-release-unsigned.apk` (buildable,
but not installable). The release build enables **R8 minification** and **resource shrinking**. See
[How to build a signed release / share the APK](#how-to-build-a-signed-release--share-the-apk) for
the keystore setup and how to verify the certificate.

> **Back up the keystore.** `keystore/docscan-release.jks` + `keystore.properties` are the **only**
> copy of the release signing key. Losing them means every future release installs as a *different*
> app and cannot update this one. Both are git-ignored — keep an offline backup.

### One-shot example (build then install)

```bat
cd C:\Users\Kuzey\Desktop\DocScanAI && set "JAVA_HOME=C:\Program Files\Java\jdk-21" && gradlew.bat assembleDebug && adb install -r app\build\outputs\apk\debug\app-debug.apk
```

> `adb` must be on your `PATH` for the one-liner above. If it isn't, use the full path (next
> section).

### Optional: install straight from Gradle

If you set `sdk.dir` in [`local.properties`](local.properties) (it is), Gradle can install the debug
build to a connected device without calling `adb` yourself:

```bat
gradlew.bat installDebug
```

---

## Installing on the phone with adb

`adb` (Android Debug Bridge) ships with the Android SDK in the **`platform-tools`** folder. On this
machine that is:

```
C:\Users\Kuzey\AppData\Local\Android\Sdk\platform-tools\adb.exe
```

### Get `adb` on your PATH (or just use the full path)

**Option 1 — add it to `PATH` for this session:**

```bat
set "PATH=%PATH%;C:\Users\Kuzey\AppData\Local\Android\Sdk\platform-tools"
```

**Option 2 — call it by full path** (no PATH change needed):

```bat
"C:\Users\Kuzey\AppData\Local\Android\Sdk\platform-tools\adb.exe" version
```

If `platform-tools` is missing, install it once from the SDK Manager (Android Studio →
*SDK Manager → SDK Tools → Android SDK Platform-Tools*), or open Android Studio once and let it set
up the SDK.

### Verify the device is connected

```bat
adb devices
```

You should see your phone's serial number with the status `device`. If it shows `unauthorized`, look
at the phone and tap **Allow** on the USB-debugging prompt.

### Install (or update) the debug APK

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

- `-r` **replaces** an already-installed copy, keeping its data.
- For a first-time clean install you can drop `-r`.
- To install a **signed** release APK instead, point the same command at your signed file (see the
  next section).

The launcher icon **DocScan AI** appears in your app drawer. Open it and continue with
[First launch](#first-launch).

---

## First launch

On the **first** launch the app shows a **Set up AI models** screen:

1. It explains that the **on-device** models give the best automatic page detection and
   flattening, that they download **once over HTTPS**, and that the app then works **fully offline**.
2. **Download AI models** fetches (the two primary models total ~36 MiB):
   - **Page detector** (`m1c_detect`) — DocAligner LCNet100 (finds the four page corners), and
   - **Page flattening** (`m2_dewarp_grid`) — UVDoc grid (corrects page curvature).
   The download runs in the background (WorkManager), is **resumable**, and is **byte-count +
   SHA-256 verified** per file;
   you'll see per-model and overall progress, with a **Retry** button if something fails.
3. **Skip — use built-in processing** lets you proceed immediately with the **classical fallback**
   pipeline (perspective correction, curvature correction, and full enhancement). *No feature is
   locked* — you can download the models later from the same screen.
4. If you're **offline**, the screen says so and waits for a connection; you can still skip and scan.

Once the pinned models are present (or you skip), the setup screen completes and the app routes
to the **camera**. Because DocScan AI is **fully on-device**, everything after this point works with
no network connection at all. The Quality/fallback/optional models are shown as **not available
yet** (their artifacts are intentionally not published) — this is expected and does not affect
scanning.

**Returning users** skip the setup screen entirely (the app remembers you completed first-launch)
and go straight to the camera.

---

## How to build a signed release / share the APK

### For personal use: just share the debug APK

The **debug APK is installable directly** — for personal use, copy
`app\build\outputs\apk\debug\app-debug.apk` to the phone (or send it via USB/cloud) and open it,
allowing *"Install unknown apps"* for your file manager/browser. This is the simplest way to get the
app onto a device without Android Studio.

> Debug builds are fine for personal testing but are signed with a throwaway debug key and are not
> meant for distribution.

### For a proper release: create a signed APK

The release build is **unsigned by default**, so you must sign it. The easiest route is Android
Studio:

1. **Build → Generate Signed Bundle / APK…**
2. Choose **APK** (or **Android App Bundle** if you're publishing to Google Play).
3. Under **Key store path**, click **Create new…** to make a keystore (`.jks`) — keep this file and
   its passwords safe; you'll need the same key for future updates.
4. Fill in the key alias and passwords, then click **Next**.
5. Choose the **release** build variant and finish. Studio writes a signed APK (typically
   `app\release\app-release.apk`).

The command line already works out of the box: [`app/build.gradle.kts`](app/build.gradle.kts) has a
`signingConfigs { release { … } }` block that reads [`keystore.properties`](keystore.properties)
(see the [release build](#5-build-the-release-apk-optional) step). With that file present,
`gradlew.bat assembleRelease` writes a **signed** `app-release.apk` directly. If you prefer to sign
manually, sign the produced `app-release-unsigned.apk` with the SDK's `apksigner`:

```bat
"C:\Users\Kuzey\AppData\Local\Android\Sdk\build-tools\35.0.0\apksigner.bat" sign --ks my-release-key.jks --out app-release-signed.apk app\build\outputs\apk\release\app-release-unsigned.apk
```

To verify a signed APK's certificate (the release cert SHA-256 is
`7a6977076f2c16e1c521b9301339ec9196aaac6f6a7641d7ec3fc965854253dc`):

```bat
"C:\Users\Kuzey\AppData\Local\Android\Sdk\build-tools\35.0.0\apksigner.bat" verify --print-certs -v app\build\outputs\apk\release\app-release.apk
```

---

## Provisioning the AI models (in-app or by hand)

The neural paths need the ONNX models to be present under `filesDir/models/<id>/`. There are two
ways to get them there. Everything is verified by **byte count + SHA-256** before use (see
[`MODELS.md`](MODELS.md) and [`MODEL_SOURCES.md`](MODEL_SOURCES.md) for the pinned URLs).

**A — In-app (what a user does).** On first launch, tap **Download AI models** on the setup screen.
This fetches the pinned models over HTTPS through a resumable WorkManager job and verifies each
file. This covers `m1c_detect`, `m2_dewarp_grid` (two files), `m2_dewarp`, and `m1_boundary`.

**B — Tooling (what the test lanes do).** [`tooling/_provision_models.cmd`](tooling/_provision_models.cmd)
pushes the artifacts straight into app-private storage with `adb` + `run-as` (binary-safe, no bytes
cross Windows stdin). It reads the `.onnx` files from a local source directory and creates the per-model
folders:

```bat
cd C:\Users\Kuzey\Desktop\DocScanAI
tooling\_provision_models.cmd
```

It pushes `m1_boundary`, `m2_dewarp`, `m1c_detect`, and the `m2_dewarp_grid` graph + data sidecar.
It **also** pushes `m2_mlsd.onnx` **only if that file is present** in the source directory.

> **`m2_mlsd` (optional) has no in-app download.** Upstream ships the M-LSD graphs only inside a
> single `resources.tar.gz`, so there is no single-file URL to pin and verify. The only way to place
> it is a manual `adb push` / `tooling\_provision_models.cmd`. When it is absent the detector behaves
> exactly like 0.3.8 — the merge-repair feature is optional and degrades gracefully. **Open item:**
> a hosted single-file mirror is needed before this becomes user-downloadable.

**On a release build** (not debuggable), `run-as` is unavailable, so provisioning uses `adb root` +
`adb push` + `chown`/`restorecon` instead. The definitive multi-file proof is the in-app download
(H10 verified `m2_dewarp_grid` downloading as 242,890 B + 31,588,352 B with no `.part`/`.tmp` leftovers).

Then install it as usual:

```bat
adb install -r app-release-signed.apk
```

> Always install updates with the **same signing key**; Android rejects an update signed with a
> different key.

---

## Troubleshooting the build

### `Unsupported class file major version` / Gradle won't start

Your `JAVA_HOME` (or `org.gradle.java.home`) points at a JDK Gradle 8.10.2 doesn't support (e.g.
JDK 25). Set `JAVA_HOME` to JDK 21 for the session and/or fix the pin in
[`gradle.properties`](gradle.properties):

```bat
set "JAVA_HOME=C:\Program Files\Java\jdk-21" && gradlew.bat assembleDebug
```

### Android Studio reports the wrong Gradle JDK

Go to **File → Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** and
select a **JDK 17** or **JDK 21** (Studio's bundled JBR works). Then re-sync.

### `SDK location not found` / missing `local.properties`

Create or fix [`local.properties`](local.properties) in the project root:

```properties
sdk.dir=C:/Users/Kuzey/AppData/Local/Android/Sdk
```

Forward slashes are fine on Windows. Android Studio writes this file automatically when you open the
project; on the command line you may need to create it.

### Missing Platform 35 / Build-Tools

Open **SDK Manager** (Android Studio) and install **Android SDK Platform 35** plus the matching
**Android SDK Build-Tools**; or, on the command line, let Gradle report which package is missing and
install it via `sdkmanager`.

### `adb: command not found`

Either add `platform-tools` to `PATH` for the session, or call `adb` by its full path:

```bat
set "PATH=%PATH%;C:\Users\Kuzey\AppData\Local\Android\Sdk\platform-tools"
```

### Install fails with `INSTALL_FAILED_…`

- **`…NO_MATCHING_ABIS`** — the APK is `arm64-v8a` only; install it on an arm64 device (the Galaxy
  S25 FE qualifies).
- **`…UPDATE_INCOMPATIBLE` / signature mismatch** — an older copy signed with a different key is
  installed. Uninstall the existing app first:

  ```bat
  adb uninstall com.docscanai
  ```

- **`…INSUFFICIENT_STORAGE`** — free up space on the device.

### `gradlew.bat` is not recognized / wrapper missing

Run the command from the **project root** (`C:\Users\Kuzey\Desktop\DocScanAI`), or use `.\gradlew.bat`.
If the wrapper JAR is ever missing, Android Studio regenerates it on sync; from the command line run
`gradle wrapper` once with a compatible Gradle installed.

### The build is slow the first time

The first build downloads Gradle 8.10.2, the plugins, and all dependencies, then compiles the app.
Subsequent builds are much faster. `org.gradle.parallel=true` and `org.gradle.caching=true` are
already enabled in [`gradle.properties`](gradle.properties).
