package com.docscanai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscanai.core.di.ServiceLocator
import com.docscanai.data.EnhancementMode
import com.docscanai.data.PageEnhanceSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ============================================================================================
 * SUBTASK H27 — ITEM B: "B&W is too sharp, you can decrease it a lil bit" (owner, verbatim)
 * ============================================================================================
 *
 * What this measures on real corpus pages, for [EnhancementMode.ORIGINAL] (the photographic
 * reference), [EnhancementMode.ENHANCED] (the "magic colour" chain, which *does* run an unsharp
 * mask) and [EnhancementMode.BW]:
 *
 *  - `lapVarInk` / `lapAbsInk` — stroke sharpness: the variance / mean magnitude of the 3x3
 *    Laplacian of luminance, sampled on INK pixels only (ink mask taken from the B&W output, so
 *    all three modes are scored on the same strokes). Higher = harder, more aliased stroke edges.
 *  - `overshoot` — ringing: the mean positive excursion (grey levels) of the 2-px background ring
 *    around ink above the paper reference level (mean luminance of pixels more than 8 px from any
 *    ink). This is the classic white halo an unsharp mask leaves next to dark strokes.
 *  - `clipped` — the fraction of pixels at 0 or 255, i.e. how much of the page has been crushed.
 *  - `inkShare` — the fraction of ink pixels, a sanity check that the page did not lose content.
 *
 * The same run also writes the B&W and ENHANCED pages to `filesDir/h27_bw/<tag>/` as PNG, and —
 * when an earlier `before` folder exists — composes `contact_sheet.png` with a 1:1 detail crop
 * per page so the change can be judged by eye as well as by number.
 *
 * Tag comes from the instrumentation argument `-e tag BEFORE|AFTER` (see tooling/h27_bw.cmd).
 */
@RunWith(AndroidJUnit4::class)
class BwSharpnessTest {

    companion object {
        private const val TAG = "DocScan"
        private const val EVID = "H27_BW"

        /** Three real benchmark captures (DocUNet photos), different page types. */
        private val PAGES = listOf(
            "corpus/benchmark/inputs/docunet_01.jpg",
            "corpus/benchmark/inputs/docunet_18.jpg",
            "corpus/benchmark/inputs/docunet_35.jpg"
        )

        /** Working long edge: big enough for stroke statistics, small enough for a fast lane. */
        private const val LONG_EDGE = 900

        /** Ink threshold used to build the stroke mask from the B&W output. */
        private const val INK_LUM = 128

        @JvmStatic
        @BeforeClass
        fun setUp() {
            NeuralRepro.setUp(enableXnnpack = false, tag = "BwSharpnessTest")
            ServiceLocator.ensureOpenCv()
        }
    }

    /** Aggregate of one mode on one page. */
    private data class BwMetrics(
        val lapVarInk: Double,
        val lapAbsInk: Double,
        val overshoot: Double,
        val clipped: Double,
        val inkShare: Double
    )

    @Test
    fun bw_isSofterThanBefore_butSharperThanOriginal() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val tag = InstrumentationRegistry.getArguments().getString("tag")?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "run"
        val outRoot = File(appContext.filesDir, "h27_bw")
        val outDir = File(outRoot, tag.lowercase())
        outDir.mkdirs()

        val enhancer = ServiceLocator.enhancer
        val summary = StringBuilder()
        val perPage = ArrayList<Pair<String, Map<EnhancementMode, BwMetrics>>>()

        PAGES.forEachIndexed { index, asset ->
            val decoded = testContext.assets.open(asset).use { BitmapFactory.decodeStream(it) }
                ?: throw AssertionError("asset $asset must decode")
            val longEdge = max(decoded.width, decoded.height)
            val source = if (longEdge > LONG_EDGE) {
                val s = LONG_EDGE.toFloat() / longEdge.toFloat()
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * s).toInt().coerceAtLeast(8),
                    (decoded.height * s).toInt().coerceAtLeast(8),
                    true
                ).also { decoded.recycle() }
            } else {
                decoded
            }
            val pageName = asset.substringAfterLast('/').substringBeforeLast('.')
            val sizeText = "${source.width}x${source.height}"
            val results = LinkedHashMap<EnhancementMode, BwMetrics>()
            try {
                // The stroke mask has to come from ONE common image for all three modes.
                val bw = runBlocking {
                    enhancer.enhance(source, PageEnhanceSettings(mode = EnhancementMode.BW))
                }
                val ink = inkMask(bw)
                savePng(bw, File(outDir, "${pageName}_bw.png"))
                results[EnhancementMode.BW] = metrics(bw, ink)
                bw.recycle()

                for (mode in listOf(EnhancementMode.ORIGINAL, EnhancementMode.ENHANCED)) {
                    val out = runBlocking {
                        enhancer.enhance(source, PageEnhanceSettings(mode = mode))
                    }
                    results[mode] = metrics(out, ink)
                    if (mode == EnhancementMode.ENHANCED) {
                        savePng(out, File(outDir, "${pageName}_enhanced.png"))
                    }
                    out.recycle()
                }
            } finally {
                source.recycle()
            }

            results.forEach { (mode, m) ->
                Log.i(
                    TAG,
                    "$EVID tag=$tag page=$index asset=$asset mode=${mode.id} " +
                        "size=${sizeText} lapVarInk=${fmt(m.lapVarInk)} " +
                        "lapAbsInk=${fmt(m.lapAbsInk)} overshoot=${fmt(m.overshoot)} " +
                        "clipped=${fmt(m.clipped * 100.0)}% inkShare=${fmt(m.inkShare * 100.0)}%"
                )
            }
            val bwM = results.getValue(EnhancementMode.BW)
            val enhM = results.getValue(EnhancementMode.ENHANCED)
            val origM = results.getValue(EnhancementMode.ORIGINAL)
            Log.i(
                TAG,
                "$EVID tag=$tag page=$index RATIO bwOverOriginal=" +
                    "${fmt(ratio(bwM.lapAbsInk, origM.lapAbsInk))} " +
                    "enhOverOriginal=${fmt(ratio(enhM.lapAbsInk, origM.lapAbsInk))} " +
                    "bwOverEnh=${fmt(ratio(bwM.lapAbsInk, enhM.lapAbsInk))}"
            )
            summary.append(
                "page=$pageName bw(lap=${fmt(bwM.lapAbsInk)},ovs=${fmt(bwM.overshoot)}," +
                    "clip=${fmt(bwM.clipped * 100.0)}%) " +
                    "enh(lap=${fmt(enhM.lapAbsInk)},ovs=${fmt(enhM.overshoot)}," +
                    "clip=${fmt(enhM.clipped * 100.0)}%) " +
                    "orig(lap=${fmt(origM.lapAbsInk)},ovs=${fmt(origM.overshoot)}," +
                    "clip=${fmt(origM.clipped * 100.0)}%)\n"
            )
            perPage.add(pageName to results)
        }

        Log.i(TAG, "$EVID tag=$tag SUMMARY\n$summary")

        // Persist the numbers so the OTHER run can compare without a second instrumentation pass.
        val record = File(outDir, "metrics.txt")
        record.writeText(summary.toString())
        val before = File(outRoot, "before/metrics.txt")

        // B&W must stay measurably sharper than the photographic ORIGINAL: that is what the owner
        // asked to keep, only "a lil bit" less. Asserted on every page, in every run.
        perPage.forEach { (page, results) ->
            val bw = results.getValue(EnhancementMode.BW)
            val orig = results.getValue(EnhancementMode.ORIGINAL)
            assertTrue(
                "H27 B&W must remain sharper than ORIGINAL on $page: " +
                    "bw lapAbs=${fmt(bw.lapAbsInk)} vs orig=${fmt(orig.lapAbsInk)}",
                bw.lapAbsInk > orig.lapAbsInk
            )
        }

        if (before.isFile && tag.lowercase() != "before") {
            val beforeRoot = File(outRoot, "before")
            beforeRoot.mkdirs()
            afterComparison(beforeRoot, outDir, perPage, appContext.filesDir)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Metrics
    // ------------------------------------------------------------------------------------------

    /** Luminance of the ARGB pixel, 0..255. */
    private fun lum(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** Ink mask of a B&W page: 1 where the pixel is dark. */
    private fun inkMask(bmp: Bitmap): BooleanArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        return BooleanArray(w * h) { lum(px[it]) < INK_LUM }
    }

    private fun metrics(bmp: Bitmap, ink: BooleanArray): BwMetrics {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val grey = IntArray(w * h) { lum(px[it]) }

        // --- stroke sharpness: Laplacian on ink pixels -------------------------------------
        var lapSum = 0.0
        var lapSq = 0.0
        var lapN = 0L
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                if (!ink[i]) continue
                val lap = 4 * grey[i] - grey[i - 1] - grey[i + 1] - grey[i - w] - grey[i + w]
                lapSum += abs(lap).toDouble()
                lapSq += (lap.toDouble() * lap)
                lapN++
            }
        }
        val lapAbs = if (lapN == 0L) 0.0 else lapSum / lapN
        val lapMean = lapAbs
        val lapVar = if (lapN == 0L) 0.0 else max(0.0, lapSq / lapN - lapMean * lapMean)

        // --- paper reference: pixels far from any ink --------------------------------------
        val near = BooleanArray(w * h)
        val far = BooleanArray(w * h)
        val rNear = 2
        val rFar = 8
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!ink[i]) continue
                for (dy in -rFar..rFar) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -rFar..rFar) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        far[yy * w + xx] = true
                        if (abs(dx) <= rNear && abs(dy) <= rNear) near[yy * w + xx] = true
                    }
                }
            }
        }
        var bgSum = 0.0
        var bgN = 0L
        for (i in 0 until w * h) {
            if (!far[i]) {
                bgSum += grey[i]
                bgN++
            }
        }
        val paper = if (bgN == 0L) 255.0 else bgSum / bgN

        // --- ringing: positive halo on the ring immediately around ink ----------------------
        var overSum = 0.0
        var ringN = 0L
        for (i in 0 until w * h) {
            if (!near[i] || ink[i]) continue
            val v = grey[i] - paper
            if (v > 0) overSum += v
            ringN++
        }
        val overshoot = if (ringN == 0L) 0.0 else overSum / ringN

        // --- clipping -----------------------------------------------------------------------
        var clippedCount = 0L
        for (i in 0 until w * h) {
            if (grey[i] <= 1 || grey[i] >= 254) clippedCount++
        }
        var inkCount = 0L
        for (i in 0 until w * h) if (ink[i]) inkCount++

        val n = (w * h).toDouble()
        return BwMetrics(
            lapVarInk = lapVar,
            lapAbsInk = lapAbs,
            overshoot = overshoot,
            clipped = clippedCount / n,
            inkShare = inkCount / n
        )
    }

    private fun ratio(a: Double, b: Double): Double = if (b == 0.0) 0.0 else a / b

    // ------------------------------------------------------------------------------------------
    // Contact sheet
    // ------------------------------------------------------------------------------------------

    /**
     * One row per page: the SAME 3x detail crop of the B&W output before and after the change,
     * side by side, with the stroke-Laplacian / clipping numbers printed underneath. The full pages
     * are 90 % white paper after binarization, so a detail crop is the honest way to judge
     * "too sharp".
     */
    private fun afterComparison(
        beforeDir: File,
        afterDir: File,
        perPage: List<Pair<String, Map<EnhancementMode, BwMetrics>>>,
        filesDir: File
    ) {
        val cellW = 520
        val cellH = 260
        val labelH = 26
        val sheetW = cellW * 2
        val sheetH = (cellH + labelH) * perPage.size
        val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val label = Paint().apply {
            color = Color.BLACK
            textSize = 20f
            isAntiAlias = true
        }

        perPage.forEachIndexed { row, (page, results) ->
            val beforeBw = BitmapFactory.decodeFile(File(beforeDir, "${page}_bw.png").path)
            val afterBw = BitmapFactory.decodeFile(File(afterDir, "${page}_bw.png").path)
            val y = row * (cellH + labelH)
            if (beforeBw != null && afterBw != null) {
                val bwM = results.getValue(EnhancementMode.BW)
                canvas.drawText(
                    "$page   BEFORE (3x)   |   AFTER (3x)     " +
                        "after: lap ${fmt(bwM.lapAbsInk)} clip ${fmt(bwM.clipped * 100.0)}%",
                    8f, 18f + y, label
                )
                val detail = detailRect(beforeBw)
                drawZoom(canvas, beforeBw, detail, paint, 3f, 0, y + labelH, cellW, cellH)
                drawZoom(canvas, afterBw, detail, paint, 3f, cellW, y + labelH, cellW, cellH)
                beforeBw.recycle()
                afterBw.recycle()
            } else {
                canvas.drawText("$page: missing before/after PNG", 8f, 18f + y, label)
            }
        }
        val target = File(filesDir, "h27_bw/contact_sheet.png")
        FileOutputStream(target).use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        sheet.recycle()
        Log.i(TAG, "$EVID contact_sheet=${target.absolutePath} bytes=${target.length()}")
    }

    /** The 1:1 detail area: a text-heavy region near the page centre. */
    private fun detailRect(src: Bitmap): Rect {
        val w = min(src.width, (src.width * 0.45).toInt().coerceAtLeast(64))
        val h = min(src.height, (src.height * 0.22).toInt().coerceAtLeast(64))
        val x = ((src.width - w) / 2).coerceAtLeast(0)
        val y = ((src.height * 0.42).toInt()).coerceIn(0, (src.height - h).coerceAtLeast(0))
        return Rect(x, y, x + w, y + h)
    }

    private fun drawFit(canvas: Canvas, bmp: Bitmap, paint: Paint, left: Int, top: Int, w: Int, h: Int) {
        val scale = min(w.toFloat() / bmp.width.toFloat(), h.toFloat() / bmp.height.toFloat())
        val dw = (bmp.width * scale).toInt().coerceAtLeast(1)
        val dh = (bmp.height * scale).toInt().coerceAtLeast(1)
        canvas.drawBitmap(
            bmp,
            null,
            Rect(left + (w - dw) / 2, top + (h - dh) / 2, left + (w + dw) / 2, top + (h + dh) / 2),
            paint
        )
    }

    private fun drawZoom(
        canvas: Canvas,
        bmp: Bitmap,
        src: Rect,
        paint: Paint,
        zoom: Float,
        left: Int,
        top: Int,
        w: Int,
        h: Int
    ) {
        val crop = Bitmap.createBitmap(bmp, src.left, src.top, src.width(), src.height())
        val scale = min(w.toFloat() / (crop.width * zoom), h.toFloat() / (crop.height * zoom))
        val dw = (crop.width * zoom * scale).toInt().coerceAtLeast(1)
        val dh = (crop.height * zoom * scale).toInt().coerceAtLeast(1)
        canvas.drawBitmap(
            crop,
            null,
            Rect(left + (w - dw) / 2, top + (h - dh) / 2, left + (w + dw) / 2, top + (h + dh) / 2),
            paint
        )
        crop.recycle()
    }

    private fun savePng(bmp: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun fmt(v: Double): String =
        if (v.isInfinite()) "inf" else String.format(java.util.Locale.US, "%.3f", v)
}
