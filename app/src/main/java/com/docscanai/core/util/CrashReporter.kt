package com.docscanai.core.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global uncaught-exception reporting for on-device diagnosis of the post-capture crash
 * (SUBTASK 9a).
 *
 * Installed once from [`DocScanApplication.onCreate`]. On an uncaught throwable it:
 *  1. logs the **full** throwable under the stable tag [`Diag.TAG`] (`Log.e("DocScan", "FATAL
 *     UNCAUGHT", t)`), and
 *  2. writes a report (timestamp + device/build info + stack trace + memory snapshot) to
 *     `filesDir/crash/last_crash.txt`, rotating the previous report into
 *     `filesDir/crash/crash_history.txt` so earlier crashes are preserved, and
 *  3. **chains to the previous default handler** so the process still crashes normally (we never
 *     swallow the crash — we only make it observable).
 *
 * This works on the user's real Galaxy S25 FE (no adb/usb needed to *record* the crash); the file
 * can be pulled later or read with `adb shell run-as com.docscanai cat files/crash/last_crash.txt`.
 */
object CrashReporter {

    /** Folder (under `filesDir`) holding crash reports. */
    const val CRASH_DIR = "crash"

    /** The most recent crash report. */
    const val LAST_CRASH_FILE = "last_crash.txt"

    /** Rolling history of previous crash reports (append-only). */
    const val CRASH_HISTORY_FILE = "crash_history.txt"

    /**
     * Installs the handler, chaining to whatever handler was previously installed. Idempotent-ish:
     * calling it twice simply chains through, but [DocScanApplication] only calls it once.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 1) Always surface it in logcat under the single diagnostic tag.
            runCatching { Log.e(Diag.TAG, "FATAL UNCAUGHT", throwable) }

            // 2) Persist a report; must never itself crash the crash handler.
            runCatching { writeReport(appContext, thread, throwable) }

            // 3) Preserve default behaviour (process dies, ANR/dialog shown as usual).
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Absolute path of the last-crash file (for UI/help text). */
    fun lastCrashPath(context: Context): String =
        File(File(context.filesDir, CRASH_DIR), LAST_CRASH_FILE).absolutePath

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, CRASH_DIR).apply { mkdirs() }
        val lastFile = File(dir, LAST_CRASH_FILE)
        val historyFile = File(dir, CRASH_HISTORY_FILE)

        // Preserve any previous report by appending it to the history file first.
        if (lastFile.exists()) {
            runCatching {
                historyFile.appendText(
                    "\n===== previous report (superseded) =====\n" + lastFile.readText() + "\n"
                )
            }
        }

        val report = buildReport(context, thread, throwable)
        runCatching { lastFile.writeText(report) }
    }

    private fun buildReport(context: Context, thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        val versionName = runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
        }.getOrDefault("?")

        val sb = StringBuilder()
        sb.appendLine("===== DocScan AI crash report =====")
        sb.appendLine("timestamp   : $timestamp")
        sb.appendLine("thread      : ${thread.name} (id=${thread.id})")
        sb.appendLine("app         : ${context.packageName} v$versionName")
        sb.appendLine("device      : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("android     : API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        sb.appendLine("abis        : ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine("memory      : ${Diag.memString()}")
        sb.appendLine("-----------------------------------")
        sb.appendLine(sw.toString())
        return sb.toString()
    }
}
