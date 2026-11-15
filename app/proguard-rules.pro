# DocScan AI — R8 / ProGuard rules (release builds only).
#
# The release build type enables isMinifyEnabled = true and isShrinkResources = true
# (app/build.gradle.kts), so these keep rules protect the native (JNI) and reflective
# pieces of the app from shrinking/obfuscation.

# ---------------------------------------------------------------------------
# ONNX Runtime (ai.onnxruntime) — JNI entry points resolved by class + method
# name (OrtEnvironment, OrtSession, OnnxTensor, ...). Keep everything so the
# native bindings and class names survive R8.
# ---------------------------------------------------------------------------
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ---------------------------------------------------------------------------
# OpenCV (org.opencv) — JNI loaders and bindings are resolved by name.
# ---------------------------------------------------------------------------
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# ---------------------------------------------------------------------------
# WorkManager — workers are (re)instantiated *by class name* via reflection in
# androidx.work.WorkerFactory. androidx.work:work-runtime:2.10.0 already ships
# consumer rules keeping ListenableWorker subclasses' names + constructors; we
# restate them here explicitly (and pin our worker) as a safety net so a future
# dependency change cannot silently break the model-download worker.
# ---------------------------------------------------------------------------
-keepnames class * extends androidx.work.ListenableWorker
-keepclassmembers public class * extends androidx.work.ListenableWorker {
    public <init>(...);
}
-keep class com.docscanai.models.ModelDownloadWorker { *; }

# ---------------------------------------------------------------------------
# Model registry / metadata — ModelSpec + ModelRegistry members are read by the
# download worker and the processing pipeline. Also guards the `meta.json`
# field names written/parsed on the model-store path.
# ---------------------------------------------------------------------------
-keepclassmembers class com.docscanai.models.** { *; }

# ---------------------------------------------------------------------------
# Session persistence note: SessionStore serialises the scan document with the
# *platform* org.json API (JSONObject/JSONArray) through explicit getters/puts,
# NOT reflection, and org.json is part of the Android framework (excluded from
# R8 processing). No keep rule is therefore required for it — recorded here for
# auditability.
# ---------------------------------------------------------------------------
