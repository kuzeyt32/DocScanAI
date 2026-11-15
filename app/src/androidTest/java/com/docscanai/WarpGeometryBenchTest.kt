package com.docscanai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscanai.capture.OpenCvQuadPageDetector
import com.docscanai.core.di.ServiceLocator
import com.docscanai.data.EnhancementMode
import com.docscanai.data.PageEnhanceSettings
import com.docscanai.imaging.PerspectiveDewarpProcessor
import com.docscanai.imaging.WarpPolicy
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.max
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SUBTASK H16 — benchmark A/B harness for the warp/geometry subtask.
 *
 * Runs the REAL pipeline on the 20 DocUNet benchmark photos for several variants **from one
 * build**, so STEP 1 (residual fit), STEP 2 (sub-pixel corner refinement) and STEP 3 (output
 * geometry sanity) can be compared on identical inputs:
 *
 *  - `raw_refine_off`     — 0.3.3 residual path, no corner refinement (control)
 *  - `bicubic_refine_off` — Catmull-Rom residual reconstruction
 *  - `poly2_refine_off`   — quadratic least-squares residual surface
 *  - `poly3_refine_off`   — cubic least-squares residual surface
 *  - `poly3_refine_on`    — cubic residual + sub-pixel edge refinement
 *  - `poly2_refine_on`    — quadratic residual + sub-pixel edge refinement
 *  - `poly3_refine_on_legacy` — same, but pre-H16 output sizing (no source-density clamp)
 *
 * Artifacts (pulled by the H16 lane):
 * `Android/data/com.docscanai/files/geom_out/<variant>/<bench_name>/{page.jpg,docscan.txt}`
 * with shared `_shared/<bench_name>/{input.jpg,gt_scan.jpg}`.
 *
 * Diagnostic only: no production behavior changes outside the explicit debug overrides, which are
 * reset in `finally`. Models must be provisioned first (same contract as the corpus lane).
 */
@RunWith(AndroidJUnit4::class)
class WarpGeometryBenchTest {

    companion object {
        private const val TAG = "DocScan"
        private const val BENCH_DIR = "corpus/benchmark/inputs"
        private const val GT_DIR = "corpus/benchmark/gt_scan"
        private const val WORK_LONG_EDGE = 2000

        @JvmStatic
        @BeforeClass
        fun setUp() {
            NeuralRepro.setUp(enableXnnpack = false, tag = "WarpGeometryBenchTest/CPU-EP")
        }
    }

    private data class Variant(
        val name: String,
        val fit: String,
        val refine: Boolean,
        val legacyGeometry: Boolean
    )

    private val variants = listOf(
        Variant("raw_refine_off", WarpPolicy.FIT_RAW, false, false),
        Variant("raw_refine_on", WarpPolicy.FIT_RAW, true, false),
        Variant("raw_refine_on_legacy", WarpPolicy.FIT_RAW, true, true),
        Variant("bicubic_refine_off", WarpPolicy.FIT_BICUBIC, false, false),
        Variant("poly2_refine_off", WarpPolicy.FIT_POLY2, false, false),
        Variant("poly3_refine_off", WarpPolicy.FIT_POLY3, false, false),
        Variant("poly2_refine_on", WarpPolicy.FIT_POLY2, true, false),
        Variant("poly3_refine_on", WarpPolicy.FIT_POLY3, true, false),
        Variant("poly3_refine_on_legacy", WarpPolicy.FIT_POLY3, true, true)
    )

    @Test
    fun benchmark_variants() {
        val targetCtx = InstrumentationRegistry.getInstrumentation().targetContext
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        NeuralRepro.requireModels()
        val presence = ServiceLocator.modelPresenceSummary()
        assertTrue("grid model missing: $presence", presence.contains("dewarpGridModelFile=true"))
        assertTrue("AI path not enabled", ServiceLocator.neuralProcessingEnabled)

        val names = testCtx.assets.list(BENCH_DIR)?.toList().orEmpty()
            .filter { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) || it.endsWith(".png", true) }
            .sorted()
        assertTrue("no benchmark assets", names.isNotEmpty())

        val root = File(targetCtx.getExternalFilesDir(null), "geom_out").apply { mkdirs() }
        val shared = File(root, "_shared").apply { mkdirs() }
        val settings = PageEnhanceSettings(mode = EnhancementMode.ENHANCED)
        val pipeline = ServiceLocator.pipeline
        val detector = ServiceLocator.pageDetector as? OpenCvQuadPageDetector
        assertTrue("page detector is not the OpenCV detector", detector != null)

        var totalDetected = 0
        var totalImages = 0
        try {
            variants.forEach { variant ->
                WarpPolicy.debugFitOverride = variant.fit
                detector?.setSubpixelRefineOverride(variant.refine)
                PerspectiveDewarpProcessor.debugLegacyGeometryOverride = variant.legacyGeometry
                val outRoot = File(root, variant.name).apply { mkdirs() }
                val sessionId = "h16-${variant.name}-${System.currentTimeMillis()}"
                var detected = 0
                try {
                    names.forEachIndexed { idx, f ->
                        val bmp = runCatching {
                            testCtx.assets.open("$BENCH_DIR/$f").use { BitmapFactory.decodeStream(it) }
                        }.getOrNull() ?: return@forEachIndexed
                        val src = capLongEdge(bmp, WORK_LONG_EDGE)
                        val name = "bench_" + f.substringBeforeLast('.')
                        val dir = File(outRoot, name).apply { mkdirs() }
                        // Shared input + GT, saved once.
                        val sharedDir = File(shared, name).apply { mkdirs() }
                        val sharedIn = File(sharedDir, "input.jpg")
                        if (!sharedIn.exists()) saveJpeg(src, sharedIn, 90)
                        val gtFile = File(sharedDir, "gt_scan.jpg")
                        if (!gtFile.exists()) {
                            runCatching {
                                testCtx.assets.open("$GT_DIR/$f").use { BitmapFactory.decodeStream(it) }
                            }.getOrNull()?.let { gt ->
                                saveJpeg(gt, gtFile, 90)
                                gt.recycle()
                            }
                        }
                        try {
                            shell("logcat -c")
                            val page = runBlocking {
                                pipeline.process(
                                    sessionId = sessionId,
                                    source = src,
                                    quad = null,
                                    enhancement = settings
                                )
                            }
                            val dump = shell("logcat -d -v brief -s DocScan")
                            val docLines = dump.lines()
                                .filter { it.contains("DocScan") && it.contains("[") }
                            File(dir, "docscan.txt").writeText(docLines.joinToString("\n"))
                            if (docLines.any { it.contains("[Detect]") && it.contains("found=true") }) {
                                detected++
                            }
                            val pageFile = File(page.imageFile)
                            if (pageFile.exists()) copyFile(pageFile, File(dir, "page.jpg"))
                            val warp = docLines.firstOrNull { it.contains("[Warp] residual fit=") }
                                ?.substringAfter("residual fit=")?.trim() ?: "-"
                            Log.i(
                                TAG,
                                "WARPGEOM_AB variant=${variant.name} [${idx + 1}/${names.size}] " +
                                    "name=$name detected=" + docLines.any {
                                        it.contains("[Detect]") && it.contains("found=true")
                                    } + " fit=$warp"
                            )
                        } catch (t: Throwable) {
                            Log.e(TAG, "WARPGEOM_AB variant=${variant.name} name=$name ERROR: ${t.message}", t)
                        } finally {
                            src.recycle()
                        }
                    }
                } finally {
                    runCatching { pipeline.deleteSession(sessionId) }
                }
                totalDetected += detected
                totalImages += names.size
                Log.i(TAG, "WARPGEOM_AB_SUMMARY variant=${variant.name} detected=$detected/${names.size}")
            }
        } finally {
            WarpPolicy.debugFitOverride = null
            detector?.setSubpixelRefineOverride(null)
            PerspectiveDewarpProcessor.debugLegacyGeometryOverride = null
        }
        assertTrue(
            "detection collapsed across variants: $totalDetected/$totalImages",
            totalDetected >= variants.size * 15
        )
    }

    // ---------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------

    private fun shell(cmd: String): String {
        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
            FileInputStream(pfd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (t: Throwable) {
            Log.w(TAG, "shell failed: $cmd :: ${t.message}")
            ""
        } finally {
            runCatching { pfd?.close() }
        }
    }

    private fun capLongEdge(src: Bitmap, longEdge: Int): Bitmap {
        val maxDim = max(src.width, src.height)
        if (maxDim <= longEdge || maxDim == 0) return src
        val s = longEdge.toFloat() / maxDim.toFloat()
        val w = (src.width * s).toInt().coerceAtLeast(1)
        val h = (src.height * s).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    private fun saveJpeg(bmp: Bitmap, f: File, q: Int) {
        f.parentFile?.mkdirs()
        runCatching { FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) } }
    }

    private fun copyFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        runCatching { src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } } }
    }
}
