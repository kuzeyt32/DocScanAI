package com.docscanai.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.util.Locale

/**
 * Shared intents for viewing/sharing exported PDFs and small formatting helpers used by the Export
 * and Library screens.
 *
 * Both [openPdf] and [sharePdf] take a `content://` [Uri] (MediaStore on API 29+, FileProvider for
 * the app-private fallback) and grant temporary read permission, so the receiving app can read the
 * file without any storage permission. Failures (e.g. no viewer installed) are surfaced as a Toast
 * instead of crashing.
 */
object PdfIntents {

    const val MIME_TYPE_PDF = "application/pdf"

    /** Launches the system PDF viewer for [uri]. */
    fun openPdf(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_TYPE_PDF)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                Toast.makeText(context, "No PDF viewer app found", Toast.LENGTH_SHORT).show()
            }
    }

    /** Shares [uri] via the system chooser. */
    fun sharePdf(context: Context, uri: Uri, title: String = "Share PDF") {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE_PDF
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
            .onFailure {
                Toast.makeText(context, "No app available to share", Toast.LENGTH_SHORT).show()
            }
    }

    /** Human-readable file size (e.g. `1.4 MB`). */
    fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }
}
