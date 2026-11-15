package com.docscanai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscanai.data.ScannedPage
import com.docscanai.ui.screens.viewer.DETAIL_ZOOM_THRESHOLD
import com.docscanai.ui.screens.viewer.PageImageDecoder
import com.docscanai.ui.screens.viewer.PageViewerContent
import com.docscanai.ui.screens.viewer.ViewerDetailProbe
import com.docscanai.ui.screens.viewer.ViewerTags
import com.docscanai.ui.screens.viewer.viewerClampOffset
import com.docscanai.ui.theme.DocScanTheme
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ============================================================================================
 * SUBTASK H10 / H11 — viewer verification at real phone resolution (1080 x 2400 class viewport).
 * ============================================================================================
 *
 * Test 1 (H10, unchanged): proves a >= 3000 px long-edge page decodes, [PageImageDecoder] bounds
 * the PREVIEW long edge to <= 2048 px, the aspect ratio survives within 1 %, the UI renders and
 * zooms without crashing, panning stays clamped by [viewerClampOffset], and peak native heap stays
 * under 150 MB.
 *
 * Test 2 (H11): proves the zoom-detail tier exists and works — preview stays 2048 px, the detail
 * decode returns REAL source pixels (> 2500 px, <= 4096 px), keeps the aspect ratio, is measurably
 * sharper (Laplacian variance), stays under a 200 MB peak native-heap delta, does not leak on a
 * second call, and that the live UI actually swaps the detail bitmap in after a 3× pinch.
 *
 * Every assertion logs a line prefixed `HIGHRES_` so the report can quote measured values.
 */
@RunWith(AndroidJUnit4::class)
class HighResViewerTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var workDir: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        workDir = File(context.cacheDir, "highres_viewer_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        runCatching { workDir.deleteRecursively() }
    }

    @Test
    fun highResPage_decodesBounded_rendersAt4x_panClamped_heapBounded() {
        val pageFile = synthesizeDenseTextPage("page_4000x3000.png", SRC_W, SRC_H)
        Log.i(TAG, "HIGHRES_INPUT ${pageFile.name} bytes=${pageFile.length()} src=${SRC_W}x${SRC_H}")

        // --- (d) start sampling native heap before decode+render ----------------------------
        val heapBefore = Debug.getNativeHeapAllocatedSize()
        val peak = AtomicLong(heapBefore)
        val sampler = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val now = Debug.getNativeHeapAllocatedSize()
                peak.set(max(peak.get(), now))
                try {
                    Thread.sleep(15L)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        sampler.isDaemon = true
        sampler.start()

        val decoded: Bitmap?
        val decodeMs: Long
        try {
            // --- (b) decode through the production decoder for a 1080x2400-class viewport ----
            val t0 = SystemClock.elapsedRealtime()
            decoded = PageImageDecoder.decode(pageFile, rotationDegrees = 0, viewLongEdgePx = VIEWPORT_LONG_EDGE)
            decodeMs = SystemClock.elapsedRealtime() - t0
        } finally {
            // Sampling continues through the Compose render below; stopped in its own finally.
        }

        assertNotNull("PageImageDecoder.decode returned null for a valid 4000x3000 PNG", decoded)
        val bitmap = decoded!!

        try {
            val longEdge = max(bitmap.width, bitmap.height)
            val aspect = bitmap.width.toDouble() / bitmap.height.toDouble()
            val expectedAspect = SRC_W.toDouble() / SRC_H.toDouble()
            val aspectDeltaPct = abs(aspect - expectedAspect) / expectedAspect * 100.0

            assertTrue("decoded long edge $longEdge exceeds the 2048 px cap", longEdge <= 2048)
            assertTrue("aspect delta ${aspectDeltaPct}% >= 1%", aspectDeltaPct < 1.0)
            Log.i(
                TAG,
                "HIGHRES_DECODE src=${SRC_W}x${SRC_H} out=${bitmap.width}x${bitmap.height} " +
                    "longEdge=$longEdge decodeMs=$decodeMs aspectDeltaPct=" +
                    String.format(java.util.Locale.US, "%.4f", aspectDeltaPct) +
                    " bytes=${bitmap.byteCount}"
            )

            // --- (c) render the real viewer body -------------------------------------------
            val page = ScannedPage(
                id = "highres",
                imageFile = pageFile.absolutePath,
                rawFile = null,
                thumbnailFile = pageFile.absolutePath
            )
            rule.setContent {
                DocScanTheme {
                    PageViewerContent(pages = listOf(page), startIndex = 0, onClose = {})
                }
            }
            rule.waitUntil(20_000) {
                rule.onAllNodesWithTag(ViewerTags.IMAGE).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag(ViewerTags.IMAGE).assertExists()
            rule.waitForIdle()

            val fitShot = capturePixels()
            saveScreenshot("highres_viewer_fit.png")

            // Pinch from a 200 px finger distance to 800 px => ~4x (MIN 1x, MAX 8x).
            rule.onNodeWithTag(ViewerTags.IMAGE).performTouchInput {
                pinch(
                    start0 = Offset(centerX - 100f, centerY),
                    end0 = Offset(centerX - 400f, centerY),
                    start1 = Offset(centerX + 100f, centerY),
                    end1 = Offset(centerX + 400f, centerY)
                )
            }
            rule.waitForIdle()
            rule.onNodeWithTag(ViewerTags.IMAGE).assertExists()
            val zoomShot = capturePixels()
            saveScreenshot("highres_viewer_4x.png")

            val changedRatio = differingPixelRatio(fitShot, zoomShot)
            assertTrue(
                "pinch did not change the rendered frame (changedRatio=$changedRatio) - zoom not applied",
                changedRatio > 0.01
            )

            // Pan hard while zoomed: must not throw and must leave the image on screen.
            repeat(3) {
                rule.onNodeWithTag(ViewerTags.IMAGE).performTouchInput { swipeUp() }
            }
            rule.waitForIdle()
            rule.onNodeWithTag(ViewerTags.IMAGE).assertExists()

            // --- (c) pan clamp: assert the EXACT rule the view applies at 4x ---------------
            val node = rule.onNodeWithTag(ViewerTags.IMAGE).fetchSemanticsNode()
            val containerW = node.size.width.toFloat()
            val containerH = node.size.height.toFloat()
            val fit = min(containerW / bitmap.width.toFloat(), containerH / bitmap.height.toFloat())
            val displayW = bitmap.width * fit
            val displayH = bitmap.height * fit
            val maxX = max(0f, (displayW * 4f - containerW) / 2f)
            val maxY = max(0f, (displayH * 4f - containerH) / 2f)
            assertTrue("4x clamp bounds are degenerate (maxX=$maxX maxY=$maxY)", maxX > 0f && maxY > 0f)

            val clamped = viewerClampOffset(
                candidate = Offset(1_000_000f, -1_000_000f),
                atScale = 4f,
                displayW = displayW,
                displayH = displayH,
                containerW = containerW,
                containerH = containerH
            )
            assertEquals("pan X not clamped to +maxX", maxX, clamped.x, 0.5f)
            assertEquals("pan Y not clamped to -maxY", -maxY, clamped.y, 0.5f)
            assertTrue("clamped |x| exceeds bound", abs(clamped.x) <= maxX + 0.5f)
            assertTrue("clamped |y| exceeds bound", abs(clamped.y) <= maxY + 0.5f)
            Log.i(
                TAG,
                "HIGHRES_CLAMP container=${containerW.toInt()}x${containerH.toInt()} " +
                    "display=${displayW.toInt()}x${displayH.toInt()} at4x maxX=$maxX maxY=$maxY " +
                    "clamped=${clamped.x},${clamped.y} changedRatio=" +
                    String.format(java.util.Locale.US, "%.4f", changedRatio)
            )
        } finally {
            sampler.interrupt()
            runCatching { sampler.join(1_000) }
            if (!bitmap.isRecycled) bitmap.recycle()
        }

        // --- (d) peak native heap delta ----------------------------------------------------
        val deltaBytes = (peak.get() - heapBefore).coerceAtLeast(0L)
        val deltaMb = deltaBytes / (1024.0 * 1024.0)
        Log.i(
            TAG,
            "HIGHRES_HEAP before=" + (heapBefore / (1024 * 1024)) + "MB peak=" +
                (peak.get() / (1024 * 1024)) + "MB delta=" +
                String.format(java.util.Locale.US, "%.1f", deltaMb) + "MB"
        )
        assertTrue("peak native heap delta ${deltaMb}MB >= 150MB", deltaMb < 150.0)
    }

    /**
     * SUBTASK H11 — detail (zoom) tier.
     *
     * Preview must stay at the hard 2048 px cap; the detail decode must deliver source pixels under
     * the same heap budget the viewer uses ([PageImageDecoder.detailBudget]); the composed viewer
     * must swap it in when the user pinches past [DETAIL_ZOOM_THRESHOLD].
     */
    @Test
    fun detailTier_zoomDecodesRealSourcePixels_sharperAndBounded() {
        val pageFile = synthesizeDenseTextPage("detail_4000x3000.png", SRC_W, SRC_H)
        Log.i(
            TAG,
            "HIGHRES_DETAIL_INPUT ${pageFile.name} bytes=${pageFile.length()} src=${SRC_W}x${SRC_H}"
        )

        val budget = PageImageDecoder.detailBudget()
        Log.i(
            TAG,
            "HIGHRES_DETAIL_BUDGET maxPixels=${budget.maxPixels} budgetMB=${budget.budgetMb} " +
                "maxMemoryMB=${Runtime.getRuntime().maxMemory() / (1024 * 1024)} " +
                "threshold=$DETAIL_ZOOM_THRESHOLD " +
                "maxDetailLongEdge=${PageImageDecoder.MAX_DETAIL_LONG_EDGE}"
        )
        assertTrue("detail maxPixels budget is degenerate: ${budget.maxPixels}", budget.maxPixels > 0)

        val heapBefore = Debug.getNativeHeapAllocatedSize()
        val peak = AtomicLong(heapBefore)
        val sampler = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val now = Debug.getNativeHeapAllocatedSize()
                peak.set(max(peak.get(), now))
                try {
                    Thread.sleep(15L)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        sampler.isDaemon = true
        sampler.start()

        var preview: Bitmap? = null
        var detail: Bitmap? = null
        try {
            // --- PREVIEW tier: unchanged 2048 px contract ------------------------------------
            val p0 = SystemClock.elapsedRealtime()
            preview = PageImageDecoder.decode(
                pageFile,
                rotationDegrees = 0,
                viewLongEdgePx = VIEWPORT_LONG_EDGE
            )
            val previewMs = SystemClock.elapsedRealtime() - p0
            assertNotNull("preview decode returned null", preview)
            val previewBmp = preview!!
            val previewLongEdge = max(previewBmp.width, previewBmp.height)
            assertEquals("preview tier must stay at the 2048 px cap", 2048, previewLongEdge)
            Log.i(
                TAG,
                "HIGHRES_PREVIEW longEdge=$previewLongEdge " +
                    "out=${previewBmp.width}x${previewBmp.height} decodeMs=$previewMs " +
                    "bytes=${previewBmp.byteCount}"
            )

            // --- DETAIL tier: production threshold + production budget -----------------------
            val d0 = SystemClock.elapsedRealtime()
            detail = PageImageDecoder.decodeForDetail(
                file = pageFile,
                rotationDegrees = 0,
                targetLongEdgePx = PageImageDecoder.MAX_DETAIL_LONG_EDGE,
                maxPixels = budget.maxPixels
            )
            val detailMs = SystemClock.elapsedRealtime() - d0
            assertNotNull("decodeForDetail returned null for a valid source", detail)
            val detailBmp = detail!!
            val detailLongEdge = max(detailBmp.width, detailBmp.height)
            val detailAspect = detailBmp.width.toDouble() / detailBmp.height.toDouble()
            val expectedAspect = SRC_W.toDouble() / SRC_H.toDouble()
            val aspectDeltaPct = abs(detailAspect - expectedAspect) / expectedAspect * 100.0

            assertTrue(
                "detail long edge $detailLongEdge <= 2500 — preview is still being upsampled",
                detailLongEdge > 2500
            )
            assertTrue(
                "detail long edge $detailLongEdge exceeds MAX_DETAIL_LONG_EDGE " +
                    "${PageImageDecoder.MAX_DETAIL_LONG_EDGE}",
                detailLongEdge <= PageImageDecoder.MAX_DETAIL_LONG_EDGE
            )
            assertTrue("detail aspect delta ${aspectDeltaPct}% >= 1%", aspectDeltaPct < 1.0)
            Log.i(
                TAG,
                "HIGHRES_DETAIL longEdge=$detailLongEdge " +
                    "out=${detailBmp.width}x${detailBmp.height} decodeMs=$detailMs " +
                    "aspectDeltaPct=" +
                    String.format(java.util.Locale.US, "%.4f", aspectDeltaPct) +
                    " bytes=${detailBmp.byteCount}"
            )

            // --- SHARPNESS: same crop, preview upsampled to the detail crop size -------------
            val cropW = (detailBmp.width * CROP_W).toInt()
            val cropH = (detailBmp.height * CROP_H).toInt()
            val previewVariance = cropLaplacianVariance(previewBmp, cropW, cropH)
            val detailVariance = cropLaplacianVariance(detailBmp, cropW, cropH)
            Log.i(
                TAG,
                "HIGHRES_LAPLACIAN crop=${cropW}x${cropH} previewVariance=" +
                    String.format(java.util.Locale.US, "%.2f", previewVariance) +
                    " detailVariance=" +
                    String.format(java.util.Locale.US, "%.2f", detailVariance) +
                    " ratio=" +
                    String.format(
                        java.util.Locale.US,
                        "%.2f",
                        if (previewVariance > 0.0) detailVariance / previewVariance else 0.0
                    )
            )
            assertTrue(
                "detail crop is not sharper than the preview (preview=$previewVariance " +
                    "detail=$detailVariance)",
                detailVariance > previewVariance
            )

            // --- NO LEAK on a second call ---------------------------------------------------
            val repeated = PageImageDecoder.decodeForDetail(
                file = pageFile,
                rotationDegrees = 0,
                targetLongEdgePx = PageImageDecoder.MAX_DETAIL_LONG_EDGE,
                maxPixels = budget.maxPixels
            )
            assertNotNull("second decodeForDetail returned null", repeated)
            assertEquals(
                "repeat detail decode changed the bitmap size",
                detailBmp.byteCount,
                repeated!!.byteCount
            )
            repeated.recycle()
            assertTrue("explicit recycle of the second detail bitmap did not take", repeated.isRecycled)
            Log.i(
                TAG,
                "HIGHRES_DETAIL_REPEAT out=${detailBmp.width}x${detailBmp.height} " +
                    "bytes=${detailBmp.byteCount} recycled=true"
            )

            // --- UI: a 3x pinch must swap in the detail bitmap -------------------------------
            val page = ScannedPage(
                id = "highres_detail",
                imageFile = pageFile.absolutePath,
                rawFile = null,
                thumbnailFile = pageFile.absolutePath
            )
            ViewerDetailProbe.reset()
            rule.setContent {
                DocScanTheme {
                    PageViewerContent(pages = listOf(page), startIndex = 0, onClose = {})
                }
            }
            rule.waitUntil(20_000) {
                rule.onAllNodesWithTag(ViewerTags.IMAGE).fetchSemanticsNodes().isNotEmpty()
            }
            rule.waitForIdle()
            // 200 px finger distance -> 600 px == 3x (over DETAIL_ZOOM_THRESHOLD).
            rule.onNodeWithTag(ViewerTags.IMAGE).performTouchInput {
                pinch(
                    start0 = Offset(centerX - 100f, centerY),
                    end0 = Offset(centerX - 300f, centerY),
                    start1 = Offset(centerX + 100f, centerY),
                    end1 = Offset(centerX + 300f, centerY)
                )
            }
            val swapped = runCatching {
                rule.waitUntil(60_000) { ViewerDetailProbe.lastDetailLongEdge > 0 }
            }.isSuccess
            rule.waitForIdle()
            val uiDetailLongEdge = ViewerDetailProbe.lastDetailLongEdge
            Log.i(
                TAG,
                "HIGHRES_DETAIL_UI swapped=$swapped " +
                    "previewLongEdge=${ViewerDetailProbe.lastPreviewLongEdge} " +
                    "detailLongEdge=$uiDetailLongEdge"
            )
            assertTrue("UI did not swap in the detail bitmap after a 3x pinch", swapped)
            assertTrue(
                "UI detail long edge $uiDetailLongEdge did not grow past the preview",
                uiDetailLongEdge > 2048
            )
            assertEquals(
                "UI detail long edge differs from the direct detail decode",
                detailLongEdge,
                uiDetailLongEdge
            )
        } finally {
            sampler.interrupt()
            runCatching { sampler.join(1_000) }
            preview?.let { if (!it.isRecycled) it.recycle() }
            detail?.let { if (!it.isRecycled) it.recycle() }
        }

        val deltaMb = (peak.get() - heapBefore).coerceAtLeast(0L) / (1024.0 * 1024.0)
        Log.i(
            TAG,
            "HIGHRES_DETAIL_HEAP before=" + (heapBefore / (1024 * 1024)) + "MB peak=" +
                (peak.get() / (1024 * 1024)) + "MB delta=" +
                String.format(java.util.Locale.US, "%.1f", deltaMb) + "MB"
        )
        assertTrue(
            "peak native heap delta ${deltaMb}MB >= 200MB across preview + detail + render",
            deltaMb < 200.0
        )
    }

    // --- helpers ------------------------------------------------------------------------------

    /** 4000x3000 white page with dense 14 px monospace text — a worst case for 4x viewing. */
    private fun synthesizeDenseTextPage(name: String, w: Int, h: Int): File {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 14f
            typeface = Typeface.MONOSPACE
        }
        val unit = "abcdefghij KLMNOP 0123456789 the quick brown fox jumps over the lazy dog "
        val line = (0 until 14).joinToString(" ") { unit }
        var y = 24f
        while (y < h - 12f) {
            canvas.drawText(line, 16f, y, paint)
            y += 20f
        }
        val file = File(workDir, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun capturePixels(): IntArray =
        rule.onRoot().captureToImage().asAndroidBitmap().let { shot ->
            try {
                val pixels = IntArray(shot.width * shot.height)
                shot.getPixels(pixels, 0, shot.width, 0, 0, shot.width, shot.height)
                pixels
            } finally {
                shot.recycle()
            }
        }

    private fun differingPixelRatio(a: IntArray, b: IntArray): Double {
        if (a.size != b.size || a.isEmpty()) return 1.0
        var diff = 0
        var total = 0
        var i = 0
        while (i < a.size) {
            if (a[i] != b[i]) diff++
            total++
            i += 97 // sparse sample keeps the comparison cheap
        }
        return diff.toDouble() / total.toDouble()
    }

    private fun saveScreenshot(name: String) {
        runCatching {
            val shot = rule.onRoot().captureToImage().asAndroidBitmap()
            try {
                val outDir = context.getExternalFilesDir(null) ?: workDir
                val out = File(outDir, name)
                FileOutputStream(out).use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Log.i(TAG, "HIGHRES_SHOT $name -> ${out.absolutePath} (${out.length()} bytes)")
            } finally {
                shot.recycle()
            }
        }.onFailure { Log.w(TAG, "screenshot $name failed", it) }
    }

    /**
     * Laplacian variance (a standard focus/sharpness metric) of the SAME normalised crop of
     * [source], resampled bilinearly to [targetW]x[targetH]. For the preview tier that resampling
     * is an UPSCALE — exactly what the screen does at 4x zoom — so the comparison measures what the
     * user actually sees: a blurred preview vs. real source pixels.
     */
    private fun cropLaplacianVariance(source: Bitmap, targetW: Int, targetH: Int): Double {
        val x = (source.width * CROP_X).toInt().coerceIn(0, source.width - 1)
        val y = (source.height * CROP_Y).toInt().coerceIn(0, source.height - 1)
        val w = (source.width * CROP_W).toInt().coerceIn(1, source.width - x)
        val h = (source.height * CROP_H).toInt().coerceIn(1, source.height - y)
        val crop = Bitmap.createBitmap(source, x, y, w, h)
        val scaled = if (crop.width == targetW && crop.height == targetH) {
            crop
        } else {
            Bitmap.createScaledBitmap(crop, targetW, targetH, true)
        }
        if (scaled !== crop) crop.recycle()
        try {
            val pixels = IntArray(targetW * targetH)
            scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
            var sum = 0.0
            var sumSq = 0.0
            var n = 0
            for (yy in 1 until targetH - 1) {
                val row = yy * targetW
                for (xx in 1 until targetW - 1) {
                    val i = row + xx
                    val lap = (
                        luminance(pixels[i - 1]) + luminance(pixels[i + 1]) +
                            luminance(pixels[i - targetW]) + luminance(pixels[i + targetW]) -
                            4 * luminance(pixels[i])
                        ).toDouble()
                    sum += lap
                    sumSq += lap * lap
                    n++
                }
            }
            if (n == 0) return 0.0
            val mean = sum / n
            return (sumSq / n) - mean * mean
        } finally {
            if (!scaled.isRecycled) scaled.recycle()
        }
    }

    /** Integer luminance (0..255) of an ARGB pixel. */
    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }

    companion object {
        private const val TAG = "DocScan"
        private const val SRC_W = 4000
        private const val SRC_H = 3000

        /** 1080 x 2400-class phone: the long edge the viewer will size its decode to. */
        private const val VIEWPORT_LONG_EDGE = 2400

        /** Normalised crop used for the sharpness comparison (same region in both tiers). */
        private const val CROP_X = 0.05
        private const val CROP_Y = 0.05
        private const val CROP_W = 0.20
        private const val CROP_H = 0.15
    }
}
