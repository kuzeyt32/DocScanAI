package com.docscanai.core.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.docscanai.data.Point
import com.docscanai.data.Quad
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Small, allocation-conscious bitmap helpers shared by the camera layer and the scan pipeline
 * (ARCHITECTURE.md §2 `core.util`, §6 threading).
 *
 * Ownership convention: helpers that produce a NEW bitmap never leak the input; callers own and
 * recycle what they receive. [downscaleToLongEdge]/[rotate] recycle their input when they allocate
 * a replacement.
 */
object BitmapUtils {

    /**
     * Converts a CameraX [ImageProxy] to an upright [Bitmap], handling both the
     * `RGBA_8888` frames from `ImageAnalysis` and the `JPEG` frames from `ImageCapture`
     * and applying [ImageProxy.getImageInfo] rotation. Does NOT close the proxy.
     *
     * NOTE: For `RGBA_8888` analysis frames the R/B channels may be interpreted swapped by
     * `ARGB_8888` bitmaps; this is irrelevant here because analysis frames only feed boundary
     * detection (geometry), never the final page image.
     */
    fun imageProxyToBitmap(proxy: ImageProxy): Bitmap? {
        val decoded = when (proxy.format) {
            ImageFormat.JPEG -> decodeJpeg(proxy)
            else -> decodeRgba(proxy)
        } ?: return null
        return rotate(decoded, proxy.imageInfo.rotationDegrees)
    }

    /** Rotates [source] by [degrees]; recycles the input when a new bitmap is allocated. */
    fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return source
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }

    /**
     * Scales [source] so its long edge equals [longEdge] (never upscales). Recycles the
     * input when a smaller copy is created.
     */
    fun downscaleToLongEdge(source: Bitmap, longEdge: Int): Bitmap {
        val maxDim = max(source.width, source.height)
        if (maxDim <= longEdge || maxDim == 0) return source
        val scale = longEdge.toFloat() / maxDim.toFloat()
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    /**
     * Creates a small thumbnail (long edge <= [maxLongEdge]) that is ALWAYS a distinct
     * bitmap, so the caller can safely recycle it independently of [source].
     */
    fun createThumbnail(source: Bitmap, maxLongEdge: Int = 320): Bitmap {
        val maxDim = max(source.width, source.height)
        if (maxDim <= maxLongEdge || maxDim == 0) {
            return source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
        }
        val scale = maxLongEdge.toFloat() / maxDim.toFloat()
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    /** Writes [bitmap] as JPEG to [target] (creating parent dirs). Returns success. */
    fun saveJpeg(bitmap: Bitmap, target: File, quality: Int = 90): Boolean {
        target.parentFile?.mkdirs()
        return try {
            FileOutputStream(target).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** A quad covering the whole image — the safe fallback when detection yields nothing. */
    fun fullFrameQuad(width: Int, height: Int): Quad = Quad(
        topLeft = Point(0f, 0f),
        topRight = Point(width.toFloat(), 0f),
        bottomRight = Point(width.toFloat(), height.toFloat()),
        bottomLeft = Point(0f, height.toFloat())
    )

    private fun decodeJpeg(proxy: ImageProxy): Bitmap? {
        val buffer = proxy.planes.firstOrNull()?.buffer ?: return null
        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun decodeRgba(proxy: ImageProxy): Bitmap? {
        val plane = proxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0) return null
        val rowPadding = rowStride - pixelStride * proxy.width
        val paddedWidth = proxy.width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(paddedWidth, proxy.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        if (paddedWidth == proxy.width) return bitmap
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, proxy.width, proxy.height)
        bitmap.recycle()
        return cropped
    }
}
