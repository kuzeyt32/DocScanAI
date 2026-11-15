package com.docscanai.ui.screens.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.docscanai.core.util.Diag
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Memory-bounded decoder for the full-screen page viewer (SUBTASK F).
 *
 * The viewer must show a captured page in detail WITHOUT ever holding a full-resolution ARGB_8888
 * bitmap in memory: this app runs on 2 GB-class emulator configs and the neural capture path already
 * peaks at ~420 MB native. The processed page / raw capture can be up to ~12 MP (4000 px long edge),
 * which decoded at full size would be ~64 MB for a single page — far too much while a viewer is open.
 *
 * Strategy (SUBTASK F "memory is a real constraint"):
 *  1. bounds-only decode ([BitmapFactory.Options.inJustDecodeBounds]) to learn the source size
 *     without allocating pixels;
 *  2. choose a power-of-two [BitmapFactory.Options.inSampleSize] targeting the on-screen long edge;
 *  3. [downscaleToFit] the (worst-case ~2× target) decoded bitmap down to the target box;
 *  4. bake any page rotation so the returned bitmap is already upright (the viewer then only needs a
 *     simple fit + zoom transform);
 *  5. log the exact decode sizes under the stable `DocScan` tag via [Diag] so the harness can see
 *     what was actually allocated.
 *
 * Ownership: the returned bitmap is OWNED by the caller, which must recycle it when done (the viewer
 * does this on page change / leaving composition). Intermediate bitmaps created here are recycled.
 * Every failure (missing file, corrupt file, OOM) returns `null` instead of throwing so the viewer
 * can render a clean error state.
 *
 * SUBTASK H11 adds a second, opt-in tier: [decodeForDetail] produces a high-resolution bitmap for
 * zoomed viewing (up to [MAX_DETAIL_LONG_EDGE] px) under an explicit pixel budget derived from
 * `Runtime.maxMemory()`. [decode] keeps its exact contract (hard 2048 px preview cap) so the first
 * frame is never delayed by detail work.
 */
internal object PageImageDecoder {

    /** Hard cap on the decoded long edge. Keeps peak bitmap memory small even for huge captures. */
    private const val MAX_DISPLAY_LONG_EDGE = 2048

    /** Never sample below a tiny floor (a 0/negative target would break sampling maths). */
    private const val MIN_TARGET_LONG_EDGE = 64

    /** SUBTASK H11: absolute ceiling for the zoom-detail tier (~4096 px long edge). */
    const val MAX_DETAIL_LONG_EDGE = 4096

    /** Fraction of `Runtime.maxMemory()` a detail bitmap may occupy (bytes, before /4). */
    private const val DETAIL_HEAP_FRACTION = 0.20

    /** Hard ceiling on detail bitmap pixels: 12 MP == 48 MB at ARGB_8888. */
    private const val MAX_DETAIL_PIXELS = 12_000_000

    /** ARGB_8888 is 4 bytes per pixel; used to turn a byte budget into a pixel budget. */
    private const val BYTES_PER_PIXEL = 4

    internal const val BYTES_PER_MB = 1024.0 * 1024.0

    /** Pixel + byte budget chosen for one page's detail decode (logged once per page by the viewer). */
    data class DetailBudget(val maxPixels: Int, val budgetMb: Int)

    /**
     * Derives the detail-tier budget from the process heap: at most 20 % of
     * `Runtime.maxMemory()`, further capped at 12 MP. This keeps a 4096 px decode possible on a
     * 2 GB-class device while never risking OOM of the rest of the app.
     */
    fun detailBudget(): DetailBudget {
        val maxMemory = Runtime.getRuntime().maxMemory()
        val budgetBytes = (maxMemory * DETAIL_HEAP_FRACTION).toLong()
        val byHeap = min(budgetBytes / BYTES_PER_PIXEL, Int.MAX_VALUE.toLong()).toInt()
        val maxPixels = min(byHeap, MAX_DETAIL_PIXELS)
            .coerceAtLeast(MIN_TARGET_LONG_EDGE * MIN_TARGET_LONG_EDGE)
        val budgetMb = (maxPixels.toLong() * BYTES_PER_PIXEL / (1024L * 1024L)).toInt()
        return DetailBudget(maxPixels = maxPixels, budgetMb = budgetMb)
    }

    /**
     * Decodes [file] downsampled so its long edge is ~[viewLongEdgePx] (clamped to a safe maximum),
     * applying [rotationDegrees] so the result is upright. Returns `null` (never throws) when the
     * file is missing, corrupt, or cannot be decoded.
     */
    fun decode(file: File?, rotationDegrees: Int, viewLongEdgePx: Int): Bitmap? {
        if (file == null || !file.exists() || !file.canRead()) {
            Diag.log("Viewer.decode", "missing/unreadable file=${file?.absolutePath}")
            return null
        }
        val target = viewLongEdgePx.coerceIn(MIN_TARGET_LONG_EDGE, MAX_DISPLAY_LONG_EDGE)

        // 1) Bounds only — no pixel allocation.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Diag.log("Viewer.decode", "undecodable (corrupt?) file=${file.name}")
            return null
        }

        // 2) Sample to the view size. inSampleSize is a power of two (BitmapFactory requirement).
        val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, target)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts)
        if (decoded == null) {
            Diag.log("Viewer.decode", "decodeFile returned null file=${file.name}")
            return null
        }

        // 3) Sample-size rounding can leave the bitmap up to ~2× the target: cap it precisely.
        val bounded = downscaleToFit(decoded, target)

        // 4) Bake rotation so the displayed bitmap is upright (keeps the view transform trivial).
        val result = rotateIfNeeded(bounded, rotationDegrees)

        Diag.log(
            "Viewer.decode",
            "file=${file.name} src=${bounds.outWidth}x${bounds.outHeight} sample=$sample " +
                "out=${result.width}x${result.height} bytes~=${result.byteCount} rot=$rotationDegrees"
        )
        return result
    }

    /**
     * SUBTASK H11 — zoom-detail tier.
     *
     * Decodes [file] at the highest resolution the source and the [maxPixels] budget allow, so a
     * zoomed viewer shows real source pixels instead of a 2.1× upsampled preview. The target long
     * edge is:
     *
     *   `min(sourceLongEdge, targetLongEdgePx, MAX_DETAIL_LONG_EDGE, sqrt(maxPixels * aspect))`
     *
     * A power-of-two `inSampleSize` does the bulk of the reduction (cheap, native), and a final
     * high-quality bilinear scale is applied only when the sampled result is still larger than the
     * target. Rotation is baked in exactly like [decode]. Never throws: returns `null` on
     * missing/corrupt files and on OOM so the viewer simply keeps showing the preview tier.
     */
    fun decodeForDetail(
        file: File?,
        rotationDegrees: Int,
        targetLongEdgePx: Int,
        maxPixels: Int
    ): Bitmap? {
        if (file == null || !file.exists() || !file.canRead()) {
            Diag.log("Viewer.detail", "missing/unreadable file=${file?.absolutePath}")
            return null
        }

        // 1) Bounds only — no pixel allocation.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Diag.log("Viewer.detail", "undecodable (corrupt?) file=${file.name}")
            return null
        }

        // 2) Target = min(source, requested, hard cap, pixel budget). Rotation never changes the
        //    long edge nor the aspect ratio, so the budget maths is rotation-independent.
        val srcLong = max(bounds.outWidth, bounds.outHeight)
        val srcShort = min(bounds.outWidth, bounds.outHeight)
        val aspect = srcLong.toDouble() / srcShort.toDouble().coerceAtLeast(1.0)
        val pixelBudget = maxPixels.coerceAtLeast(MIN_TARGET_LONG_EDGE * MIN_TARGET_LONG_EDGE)
        val maxLongEdgeByPixels = sqrt(pixelBudget.toDouble() * aspect).toInt()
        val target = min(
            min(srcLong, targetLongEdgePx.coerceAtMost(MAX_DETAIL_LONG_EDGE)),
            maxLongEdgeByPixels
        ).coerceAtLeast(MIN_TARGET_LONG_EDGE)

        // 3) Power-of-two sampling, then a single high-quality scale only if still oversized.
        val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, target)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (t: Throwable) {
            // OutOfMemoryError is an Error, not an Exception: catch Throwable deliberately.
            Diag.warn("Viewer.detail", "decode failed (${t.javaClass.simpleName}); keeping preview")
            null
        }
        if (decoded == null) {
            Diag.log("Viewer.detail", "decodeFile returned null file=${file.name}")
            return null
        }

        val bounded = downscaleToFit(decoded, target)
        val result = rotateIfNeeded(bounded, rotationDegrees)

        Diag.log(
            "Viewer.detail",
            "file=${file.name} src=${bounds.outWidth}x${bounds.outHeight} target=$target " +
                "maxPixels=$pixelBudget sample=$sample out=${result.width}x${result.height} " +
                "bytes~=${result.byteCount} rot=$rotationDegrees"
        )
        return result
    }

    /**
     * Largest power-of-two sample where the downsampled long edge is still `>= target`. Combined with
     * [downscaleToFit] the final bitmap long edge is `<= target`.
     */
    private fun computeInSampleSize(width: Int, height: Int, target: Int): Int {
        val longEdge = max(width, height)
        var sample = 1
        while (longEdge / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** Scales [source] so its long edge is `<= target`; recycles [source] when a copy is created. */
    private fun downscaleToFit(source: Bitmap, target: Int): Bitmap {
        val longEdge = max(source.width, source.height)
        if (longEdge <= target) return source
        val ratio = target.toFloat() / longEdge.toFloat()
        val w = (source.width * ratio).toInt().coerceAtLeast(1)
        val h = (source.height * ratio).toInt().coerceAtLeast(1)
        val scaled = try {
            Bitmap.createScaledBitmap(source, w, h, true)
        } catch (t: Throwable) {
            Diag.logThrowable("Viewer.decode.scale", t)
            return source
        }
        if (scaled !== source) source.recycle()
        return scaled
    }

    /** Rotates [source] by [degrees] (normalised to `[0,360)`); recycles [source] when replaced. */
    private fun rotateIfNeeded(source: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return source
        return try {
            val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
            val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            if (rotated !== source) source.recycle()
            rotated
        } catch (t: Throwable) {
            // Rotation failure is non-fatal: show the unrotated bitmap rather than nothing.
            Diag.logThrowable("Viewer.decode.rotate", t)
            source
        }
    }
}
