package com.docscanai.pdf

import android.net.Uri
import com.docscanai.data.PageSize
import com.docscanai.data.ScannedPage
import java.io.OutputStream

/**
 * PDF export configuration (TECHNICAL_SPEC.md §9).
 *
 * @param pageSize target paper size. [PageSize.AUTO] is surfaced in the UI as **"Fit"** and makes
 *   every PDF page adopt the (rotated) image's own aspect ratio.
 * @param quality 0–100 hint that drives the maximum raster resolution used per page (higher = more
 *   detail, larger file). See [AndroidPdfExporter.maxLongEdgeForQuality].
 * @param fileName explicit file name; when null the exporter generates `Scan_yyyyMMdd_HHmmss.pdf`.
 * @param title document title metadata. NOTE: the platform `android.graphics.pdf.PdfDocument` API
 *   does not expose document-info setters, so this is currently recorded but not written.
 * @param marginPt uniform margin (in PDF points, 1/72") applied on A4/Letter pages. Ignored for
 *   [PageSize.AUTO] (a Fit page is exactly the image, so there is nothing to inset).
 */
data class PdfExportOptions(
    val pageSize: PageSize = PageSize.AUTO,
    val quality: Int = 90,
    val fileName: String? = null,
    val title: String? = null,
    val marginPt: Float = 0f
)

/**
 * Result of [PdfExporter.exportAndPublish]. Either a [Success] carrying the published location, or a
 * typed [Failure] (never an exception thrown to the UI).
 */
sealed interface PdfExportResult {

    /**
     * @param uri `content://` Uri of the published PDF (MediaStore or FileProvider).
     * @param displayPath human-readable location, e.g. `Documents/DocScanAI/Scan_20251013_120000.pdf`.
     * @param byteSize size of the written PDF in bytes.
     * @param pageCount number of pages written.
     * @param isAppPrivate true when the last-resort app-specific directory was used (the file is not
     *   visible in the system Documents UI and is only reachable via its `content://` Uri).
     */
    data class Success(
        val uri: Uri,
        val displayPath: String,
        val byteSize: Long,
        val pageCount: Int,
        val isAppPrivate: Boolean
    ) : PdfExportResult

    data class Failure(
        val message: String,
        val cause: Throwable? = null
    ) : PdfExportResult
}

/**
 * Builds a single multi-page PDF from ordered page images and publishes it to public storage via
 * MediaStore.
 *
 * Contract (TECHNICAL_SPEC.md §8–§9, ARCHITECTURE.md §3):
 *  - Uses the platform `android.graphics.pdf.PdfDocument` (no third-party dependency).
 *  - Pages are drawn one at a time; bitmaps are decoded with `inSampleSize` and recycled after each
 *    page to bound memory even for multi-hundred-page documents.
 *  - Publish order: `Documents/DocScanAI` → `Downloads/DocScanAI` → `DCIM/DocScanAI` →
 *    app-specific external dir; API 26–28 uses the legacy `File` + `MediaScanner` path.
 *
 * The concrete implementation is [AndroidPdfExporter]; both methods are safe to call from any thread
 * and dispatch their blocking work to `Dispatchers.IO`.
 */
interface PdfExporter {

    /** Renders the page images (in order) into a PDF written to [output]. */
    suspend fun exportToStream(
        output: OutputStream,
        pages: List<ScannedPage>,
        options: PdfExportOptions
    )

    /** Renders and publishes the PDF to public storage, returning a typed [PdfExportResult]. */
    suspend fun exportAndPublish(
        pages: List<ScannedPage>,
        options: PdfExportOptions
    ): PdfExportResult
}
