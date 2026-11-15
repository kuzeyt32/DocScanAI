package com.docscanai.core.util

import android.graphics.Bitmap
import android.os.Debug
import android.util.Log
import java.util.Locale

/**
 * Central diagnostics helper for the SUbtask 9a crash investigation.
 *
 * Everything logged through here uses the single, stable log tag **[TAG]** = `"DocScan"` so the
 * whole capture → detect → warp → dewarp → enhance → store path can be filtered with a single
 * `adb logcat -s DocScan` (or `adb logcat | findstr DocScan`). This is deliberately separate from
 * the per-class tags used elsewhere so the "processing" window right before the reported crash is
 * trivially greppable.
 *
 * The helper is intentionally allocation-light and never throws — logging must not introduce a new
 * failure mode into the pipeline.
 */
object Diag {

    /** Stable log tag for the entire diagnostic stream (crash reports reuse it too). */
    const val TAG = "DocScan"

    private const val BYTES_PER_MB = 1024.0 * 1024.0

    /** Verbose trace line for a pipeline stage (info-level detail, `Log.d`). */
    fun log(stage: String, msg: String) {
        runCatching { Log.d(TAG, "[$stage] $msg") }
    }

    /** Warning line for a recoverable/degraded path (`Log.w`). */
    fun warn(stage: String, msg: String, t: Throwable? = null) {
        runCatching { Log.w(TAG, "[$stage] $msg", t) }
    }

    /** Error line for a failed stage (`Log.e`). */
    fun error(stage: String, msg: String, t: Throwable? = null) {
        runCatching { Log.e(TAG, "[$stage] $msg", t) }
    }

    /**
     * Logs a [Throwable] with its full stack trace under [TAG]. Used by every catch block in the
     * hardened pipeline so a swallowed failure is still fully visible in logcat.
     */
    fun logThrowable(stage: String, t: Throwable) {
        runCatching { Log.e(TAG, "[$stage] throwable: ${t.javaClass.name}: ${t.message}", t) }
    }

    /** Convenience entry marker so stage timings bracket cleanly. */
    fun stageEnter(stage: String, detail: String = "") {
        val suffix = if (detail.isBlank()) "" else " $detail"
        log(stage, "enter$suffix | ${memString()}")
    }

    /** Convenience exit marker carrying the elapsed milliseconds for a stage. */
    fun stageExit(stage: String, elapsedMs: Long, detail: String = "") {
        val suffix = if (detail.isBlank()) "" else " $detail"
        log(stage, "exit${suffix} | ${elapsedMs}ms | ${memString()}")
    }

    /** Human-readable description of a [Bitmap] (dimensions + config + recycled state). */
    fun describe(bitmap: Bitmap?): String {
        if (bitmap == null) return "bitmap=null"
        return try {
            "bitmap=${bitmap.width}x${bitmap.height}/${bitmap.config} recycled=${bitmap.isRecycled} " +
                "bytes~${mb(bitmap.byteCount.toLong())}"
        } catch (t: Throwable) {
            "bitmap=<unreadable: ${t.message}>"
        }
    }

    /**
     * Current JVM + native memory snapshot. The native heap value is what reveals OpenCV/ORT
     * blowups (they allocate off the Java heap), so it is logged before and after every stage.
     */
    fun memString(): String = try {
        val rt = Runtime.getRuntime()
        val total = rt.totalMemory()
        val free = rt.freeMemory()
        val used = total - free
        val native = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L)
        String.format(
            Locale.US,
            "mem: javaUsed=%s/%s native=%s",
            mb(used), mb(total), mb(native)
        )
    } catch (t: Throwable) {
        "mem: <unavailable: ${t.message}>"
    }

    private fun mb(bytes: Long): String =
        String.format(Locale.US, "%.1fMB", bytes / BYTES_PER_MB)
}
