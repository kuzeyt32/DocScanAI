package com.docscanai.pdf

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.docscanai.data.PageSize
import com.docscanai.data.ScannedPage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Production [PdfExporter] built on the platform `android.graphics.pdf.PdfDocument`
 * (TECHNICAL_SPEC.md §8–§9).
 *
 * ### Rendering
 *  - One PDF page per [ScannedPage]; the processed image (`imageFile`) is used, honouring
 *    [ScannedPage.rotationDegrees].
 *  - Page sizes: [PageSize.A4] (595×842 pt), [PageSize.LETTER] (612×792 pt) and [PageSize.AUTO]
 *    (`"Fit"` — the page adopts the (rotated) image's own pixel size in points, drawn 1:1).
 *  - A4/Letter draw the bitmap uniformly scaled to fit a centred content rect inset by the
 *    configurable [PdfExportOptions.marginPt] (aspect preserved, never distorted).
 *  - Each bitmap is decoded with `inSampleSize` and precisely downscaled to a quality-derived
 *    maximum long edge (≈150–300 dpi for A4) and recycled immediately after `finishPage`, so memory
 *    stays flat regardless of page count.
 *
 * ### Publishing (TECHNICAL_SPEC.md §8.1)
 *  - **API 29+:** insert a pending MediaStore row (`DISPLAY_NAME`, `MIME_TYPE=application/pdf`,
 *    `RELATIVE_PATH`, `IS_PENDING=1`), stream the PDF into it, then clear `IS_PENDING`. The primary
 *    target is the public **`Documents/DocScanAI/`** folder — the same location mainstream scanner
 *    apps use. On any failure the pending row is deleted and the next target is tried:
 *    `Documents` → `Downloads` → `DCIM` → app-specific external dir.
 *  - **API 26–28:** `RELATIVE_PATH`/`IS_PENDING` do not exist, so the PDF is written with a plain
 *    `File` under `getExternalStoragePublicDirectory(DIRECTORY_DOCUMENTS)/DocScanAI/` and indexed
 *    via [MediaScannerConnection].
 *  - **Last resort (any API):** `getExternalFilesDir(null)/DocScanAI/`, exposed through the existing
 *    [FileProvider] so Open/Share still work (the file is not part of the public Documents UI).
 */
class AndroidPdfExporter(
    private val context: Context
) : PdfExporter {

    private val appContext: Context get() = context.applicationContext

    override suspend fun exportToStream(
        output: OutputStream,
        pages: List<ScannedPage>,
        options: PdfExportOptions
    ): Unit = withContext(Dispatchers.IO) {
        renderToStream(pages, options, output)
    }

    override suspend fun exportAndPublish(
        pages: List<ScannedPage>,
        options: PdfExportOptions
    ): PdfExportResult {
        if (pages.isEmpty()) {
            return PdfExportResult.Failure("There are no pages to export.")
        }
        val fileName = resolveFileName(options)
        return withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    publishViaMediaStore(pages, options, fileName)
                } else {
                    publishLegacy(pages, options, fileName)
                }
            }.getOrElse { t ->
                Log.e(TAG, "PDF export failed unexpectedly", t)
                PdfExportResult.Failure(
                    "Export failed: ${t.message ?: t.javaClass.simpleName}",
                    t
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------------

    /**
     * Streams a multi-page PDF for [pages] into [output]. Blocking; callers dispatch to IO.
     * The [PdfDocument] is always closed in a `finally` block, and every decoded bitmap is recycled
     * after its page is finished.
     */
    private fun renderToStream(pages: List<ScannedPage>, options: PdfExportOptions, output: OutputStream) {
        require(pages.isNotEmpty()) { "Cannot write a PDF with no pages" }
        val maxLongEdge = maxLongEdgeForQuality(options.quality)
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, page ->
                val pageNumber = index + 1
                val bitmap = decodePageBitmap(page.imageFile, maxLongEdge, page.rotationDegrees)
                if (bitmap == null) {
                    // Unreadable/missing image: emit a blank A4 page so the page count is preserved
                    // instead of aborting the whole export.
                    Log.w(TAG, "Skipping unreadable page ${page.id} (${page.imageFile})")
                    drawBlankPage(document, pageNumber)
                } else {
                    try {
                        drawBitmapPage(document, bitmap, options, pageNumber)
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            }
            document.writeTo(output)
        } finally {
            document.close()
        }
    }

    /** Vectors a single image page, scaled to fit the target paper with a centred margin. */
    private fun drawBitmapPage(
        document: PdfDocument,
        bitmap: Bitmap,
        options: PdfExportOptions,
        pageNumber: Int
    ) {
        val (pageWidth, pageHeight) = resolvePageSize(options.pageSize, bitmap)
        val margin = if (options.pageSize == PageSize.AUTO) 0f else options.marginPt.coerceAtLeast(0f)

        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        val page = document.startPage(pageInfo)
        val canvas: Canvas = page.canvas
        canvas.drawColor(Color.WHITE)

        val contentWidth = (pageWidth - 2f * margin).coerceAtLeast(1f)
        val contentHeight = (pageHeight - 2f * margin).coerceAtLeast(1f)
        // Uniform scale => aspect ratio preserved (no distortion). Centre within the content rect.
        val scale = min(contentWidth / bitmap.width, contentHeight / bitmap.height)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = margin + (contentWidth - drawWidth) / 2f
        val top = margin + (contentHeight - drawHeight) / 2f

        val dest = RectF(left, top, left + drawWidth, top + drawHeight)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(bitmap, null, dest, paint)
        document.finishPage(page)
    }

    private fun drawBlankPage(document: PdfDocument, pageNumber: Int) {
        val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH_PT, A4_HEIGHT_PT, pageNumber).create()
        val page = document.startPage(pageInfo)
        page.canvas.drawColor(Color.WHITE)
        document.finishPage(page)
    }

    /**
     * Resolves the PDF page size in points (1 pt = 1/72").
     * - A4 → 595×842 pt
     * - Letter → 612×792 pt
     * - AUTO ("Fit") → the (already rotated) bitmap's pixel dimensions in points (drawn 1:1).
     */
    private fun resolvePageSize(size: PageSize, bitmap: Bitmap): Pair<Int, Int> = when (size) {
        PageSize.A4 -> A4_WIDTH_PT to A4_HEIGHT_PT
        PageSize.LETTER -> LETTER_WIDTH_PT to LETTER_HEIGHT_PT
        PageSize.AUTO -> bitmap.width.coerceAtLeast(1) to bitmap.height.coerceAtLeast(1)
    }

    /**
     * Decodes [path] scaled so its long edge is ≤ [maxLongEdge] and rotated by [rotationDegrees].
     * Returns null when the file is missing or cannot be decoded.
     */
    private fun decodePageBitmap(path: String, maxLongEdge: Int, rotationDegrees: Int): Bitmap? {
        val file = File(path)
        if (!file.exists() || file.length() == 0L) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxLongEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = BitmapFactory.decodeFile(path, decodeOptions) ?: return null

        // inSampleSize only halves; refine to the exact cap so file size stays predictable.
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge > maxLongEdge && longEdge > 0) {
            val ratio = maxLongEdge.toFloat() / longEdge.toFloat()
            val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
            val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        return applyRotation(bitmap, rotationDegrees)
    }

    /** Applies a clockwise rotation, recycling the source when a new bitmap is produced. */
    private fun applyRotation(source: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return source
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxLongEdge: Int): Int {
        var sample = 1
        var longEdge = max(width, height)
        while (maxLongEdge > 0 && longEdge / 2 >= maxLongEdge) {
            sample *= 2
            longEdge /= 2
        }
        return sample
    }

    /**
     * Maps the 0–100 [quality] hint onto a maximum long edge. 100 → 2480 px (~300 dpi on A4),
     * 10 → 1240 px (~150 dpi). This is the dominant lever for PDF size with [PdfDocument], which
     * stores the drawn raster itself.
     */
    private fun maxLongEdgeForQuality(quality: Int): Int {
        val clamped = quality.coerceIn(10, 100)
        val fraction = (clamped - 10).toFloat() / 90f
        return (MIN_LONG_EDGE + ((MAX_LONG_EDGE - MIN_LONG_EDGE) * fraction)).toInt()
    }

    // ---------------------------------------------------------------------------------------------
    // Publishing — API 29+ (MediaStore)
    // ---------------------------------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishViaMediaStore(
        pages: List<ScannedPage>,
        options: PdfExportOptions,
        fileName: String
    ): PdfExportResult {
        val resolver = appContext.contentResolver
        for (directory in MEDIASTORE_DIRECTORIES) {
            val relativePath = "$directory/$SUBFOLDER"
            val uri = insertPendingRow(resolver, directory, relativePath, fileName) ?: continue
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    renderToStream(pages, options, out)
                } ?: throw IOException("Could not open output stream for $uri")

                clearPending(resolver, uri)
                val size = queryFileSize(resolver, uri)
                Log.i(TAG, "Published PDF to $relativePath/$fileName ($size bytes)")
                return PdfExportResult.Success(
                    uri = uri,
                    displayPath = "$relativePath/$fileName",
                    byteSize = size,
                    pageCount = pages.size,
                    isAppPrivate = false
                )
            } catch (t: Throwable) {
                // Clean up the half-written pending row and try the next target (fallback chain).
                Log.w(TAG, "MediaStore publish to $relativePath failed; trying next target", t)
                runCatching { resolver.delete(uri, null, null) }
            }
        }
        // Everything under MediaStore failed — fall back to the always-writable app directory.
        return publishToAppDir(pages, options, fileName)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertPendingRow(
        resolver: ContentResolver,
        directory: String,
        relativePath: String,
        fileName: String
    ): Uri? {
        // Downloads must use the dedicated collection; Documents/DCIM go through the general Files
        // collection (TECHNICAL_SPEC.md §8.1).
        val collection = if (directory == Environment.DIRECTORY_DOWNLOADS) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE_PDF)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return runCatching { resolver.insert(collection, values) }
            .onFailure { Log.w(TAG, "Failed to insert pending row for $relativePath", it) }
            .getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun clearPending(resolver: ContentResolver, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        runCatching { resolver.update(uri, values, null, null) }
    }

    // ---------------------------------------------------------------------------------------------
    // Publishing — API 26–28 (legacy File + MediaScanner)
    // ---------------------------------------------------------------------------------------------

    private fun publishLegacy(
        pages: List<ScannedPage>,
        options: PdfExportOptions,
        fileName: String
    ): PdfExportResult {
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val targetDir = File(publicDir, SUBFOLDER)
        val created = runCatching { targetDir.exists() || targetDir.mkdirs() }.getOrDefault(false)
        if (created) {
            val file = File(targetDir, fileName)
            val written = runCatching {
                FileOutputStream(file).use { out -> renderToStream(pages, options, out) }
                true
            }.getOrElse { t ->
                Log.w(TAG, "Legacy Documents write failed", t)
                false
            }
            if (written) {
                runCatching {
                    MediaScannerConnection.scanFile(
                        appContext,
                        arrayOf(file.absolutePath),
                        arrayOf(MIME_TYPE_PDF),
                        null
                    )
                }
                return PdfExportResult.Success(
                    uri = fileProviderUri(file) ?: Uri.fromFile(file),
                    displayPath = "Documents/$SUBFOLDER/$fileName",
                    byteSize = file.length(),
                    pageCount = pages.size,
                    isAppPrivate = false
                )
            }
        }
        // No permission / unwritable legacy storage → app-specific dir.
        return publishToAppDir(pages, options, fileName)
    }

    // ---------------------------------------------------------------------------------------------
    // Publishing — last-resort app-specific external directory (any API)
    // ---------------------------------------------------------------------------------------------

    private fun publishToAppDir(
        pages: List<ScannedPage>,
        options: PdfExportOptions,
        fileName: String
    ): PdfExportResult {
        return try {
            val base = appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: appContext.filesDir
            val dir = File(base, SUBFOLDER)
            if (!dir.exists() && !dir.mkdirs()) {
                throw IOException("Could not create ${dir.absolutePath}")
            }
            val file = File(dir, fileName)
            FileOutputStream(file).use { out -> renderToStream(pages, options, out) }
            val uri = fileProviderUri(file)
                ?: throw IOException("Could not obtain a content Uri for ${file.absolutePath}")
            Log.i(TAG, "Published PDF to app-specific dir ${file.absolutePath}")
            PdfExportResult.Success(
                uri = uri,
                displayPath = file.absolutePath,
                byteSize = file.length(),
                pageCount = pages.size,
                isAppPrivate = true
            )
        } catch (t: Throwable) {
            Log.e(TAG, "App-specific publish failed", t)
            PdfExportResult.Failure("Could not save the PDF: ${t.message ?: t.javaClass.simpleName}", t)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun fileProviderUri(file: File): Uri? = runCatching {
        FileProvider.getUriForFile(appContext, fileProviderAuthority(), file)
    }.getOrNull()

    private fun fileProviderAuthority(): String = "${appContext.packageName}.fileprovider"

    private fun queryFileSize(resolver: ContentResolver, uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /** Builds a safe `*.pdf` file name, defaulting to `Scan_yyyyMMdd_HHmmss.pdf`. */
    private fun resolveFileName(options: PdfExportOptions): String {
        val stamp = SimpleDateFormat(FILE_NAME_PATTERN, Locale.US).format(Date())
        val provided = options.fileName?.trim().orEmpty()
        val base = provided.ifEmpty { "Scan_$stamp" }
        val sanitized = base
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .trimEnd('.')
        val withExt = if (sanitized.lowercase(Locale.US).endsWith(".pdf")) {
            sanitized
        } else {
            "$sanitized.pdf"
        }
        return withExt.ifBlank { "Scan_$stamp.pdf" }
    }

    companion object {
        private const val TAG = "AndroidPdfExporter"

        /** Sub-folder created under the target public collection. */
        const val SUBFOLDER = "DocScanAI"
        const val MIME_TYPE_PDF = "application/pdf"

        private const val FILE_NAME_PATTERN = "yyyyMMdd_HHmmss"

        // PDF point dimensions (1 pt = 1/72").
        private const val A4_WIDTH_PT = 595
        private const val A4_HEIGHT_PT = 842
        private const val LETTER_WIDTH_PT = 612
        private const val LETTER_HEIGHT_PT = 792

        // Raster cap range (long edge, px). ~300 dpi on A4 at the top end.
        private const val MAX_LONG_EDGE = 2480
        private const val MIN_LONG_EDGE = 1240

        /** Fallback chain for the API 29+ MediaStore path (TECHNICAL_SPEC.md §8.1). */
        private val MEDIASTORE_DIRECTORIES = listOf(
            Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DCIM
        )
    }
}
