package com.docscanai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscanai.capture.OpenCvQuadPageDetector
import com.docscanai.capture.PagePlausibility
import com.docscanai.core.di.ServiceLocator
import com.docscanai.core.util.TraceDump
import com.docscanai.ml.MlsdTrigger
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ============================================================================================
 * SUBTASK H39 — DETECTOR TRACE DUMP: make the AI's own maps inspectable.
 * ============================================================================================
 *
 * The owner's verdict on the rejected curved-page attempt was that "the ink map of the AI was
 * not good enough" — but there was no image to look at. This lane replays bundled stills through
 * the REAL production path with [TraceDump] enabled and writes, per photo:
 *
 *   corner_input.png        exact 256x256 (or latched edge) tensor input to the corner model
 *   heat_c0..c3.png         the four corner heat-map channels (TL, TR, BR, BL)
 *   heat_max.png            max-over-channels composite
 *   heat_montage.png        2x2 labelled montage of the four channels
 *   classical_gray.png      the classical fallback's working grayscale
 *   classical_edges.png     the union edge map (Canny + Otsu Sobel [+ shadow band])
 *   classical_candidates.png every scored quad candidate (source + score)
 *   page_rectified.png      the rectified page the gate scores
 *   page_interior.png       the interior ROI (7 % margin) used for paper/ink statistics
 *   page_ink.png            the adaptive-threshold INK map (text evidence)
 *   page_edges.png          Canny edges on the interior
 *   page_glyphs.png         ink map + the glyph components that feed text/line coherence
 *   final_quad.png          the returned quad + provenance
 *   trace.json              every scalar (peaks, candidate scores, gate breakdown, quad)
 *
 * The files land in the app's external files dir
 * (`Android/data/com.docscanai/files/diag/<tag>/`) and `tooling/h39_dump.cmd` pulls them into
 * `tooling/out/h39/<tag>/`; `tooling/h39_sheet.py` then builds contact sheets + `index.html`.
 *
 * Production is untouched: tracing is disabled unless `TraceDump.root` is set, which only this
 * test does.
 */
@RunWith(AndroidJUnit4::class)
class DetectorTraceTest {

    companion object {
        private const val TAG = "DocScan"
        private const val ASSET_DIR = "owner110"

        /**
         * Default sample: the 5 H38 struggle photos (curvy/dyed/whole-book), the 2 known
         * no-detections, the 2 known merged/wrong-page cases, and 3 ordinary successes. Override
         * with `-e traceImages "20261025_130818,20261024_190249"` (file stems, no extension) or
         * `-e traceAll 1` for the whole 116-photo set.
         */
        private val DEFAULT_IMAGES = listOf(
            "20261025_130818", "20261025_130821", "20261025_131201",
            "20261025_131246", "20261025_131249",
            "20261024_190249", "20261024_185748",
            "20261024_190232", "20261024_190138",
            "20261018_210910", "20261020_195348", "20261024_185245"
        )

        @JvmStatic
        @BeforeClass
        fun setUp() {
            NeuralRepro.setUp(enableXnnpack = false, tag = "DetectorTraceTest/CPU-EP")
        }
    }

    @Test
    fun detectorTraceDump() {
        NeuralRepro.requireModels()
        val args = InstrumentationRegistry.getArguments()
        val tag = args.getString("traceTag") ?: args.getString("h35Tag") ?: "default"
        // H47: a fresh holdout lives in its own asset dir; owner110 must stay untouched so the
        // holdout is not quietly tuned against.
        val assetDir = args.getString("assetDir") ?: ASSET_DIR
        val traceAll = args.getString("traceAll") == "1"
        // SUBTASK H40: `traceJsonOnly=1` keeps `trace.json` (peaks, quad, gate) but skips the PNG
        // maps, so the whole 116-photo set can be traced and pulled quickly for offline analysis.
        val jsonOnly = args.getString("traceJsonOnly") == "1"
        val requested = args.getString("traceImages")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val names = when {
            traceAll -> assetNames(assetDir)
            requested.isNotEmpty() -> requested
            else -> DEFAULT_IMAGES.filter { stem ->
                assetNames(assetDir).any { it == "$stem.jpg" }
            }
        }
        assertTrue("no trace images resolved (assets under $assetDir?)", names.isNotEmpty())

        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val outRoot = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "diag/$tag"
        )
        outRoot.deleteRecursively()
        outRoot.mkdirs()
        TraceDump.resetForTests()
        TraceDump.root = outRoot
        TraceDump.imagesEnabled = !jsonOnly
        TraceDump.maxScenes = if (traceAll) 500 else 64

        val detector = ServiceLocator.pageDetector as? OpenCvQuadPageDetector
        assertTrue("ServiceLocator.pageDetector is not OpenCvQuadPageDetector", detector != null)
        detector!!.mlsdMode = OpenCvQuadPageDetector.MlsdMode.REPAIR

        val manifest = JSONObject()
        manifest.put("tag", tag)
        manifest.put("mode", "OpenCvQuadPageDetector.detect + PagePlausibility.evaluate(still)")
        manifest.put("mlsd_mode", detector.mlsdMode.name)
        manifest.put("presence", ServiceLocator.modelPresenceSummary())
        val rows = JSONArray()
        var ok = 0
        var failed = 0

        try {
            names.forEach { name ->
                val file = if (name.endsWith(".jpg", true)) name else "$name.jpg"
                val bmp = decode("$assetDir/$file")
                if (bmp == null) {
                    Log.e(TAG, "H39_TRACE name=$file DECODE_FAILED")
                    failed++
                    return@forEach
                }
                try {
                    detector.mlsdHint = MlsdTrigger.Hint.STILL
                    detector.mlsdSchedule.reset()
                    val scene = TraceDump.beginScene(file.substringBeforeLast('.'))
                    if (scene == null) {
                        Log.w(TAG, "H39_TRACE name=$file scene not opened (cap reached?)")
                        return@forEach
                    }
                    try {
                        val t0 = System.nanoTime()
                        val quad = runCatching { runBlocking { detector.detect(bmp) } }.getOrNull()
                        val detectMs = (System.nanoTime() - t0) / 1e6
                        val provenance = runCatching { detector.lastProvenance }.getOrNull()
                        val gate = quad?.let {
                            PagePlausibility.evaluate(
                                bmp, it, file, provenance ?: com.docscanai.capture.QuadProvenance.UNKNOWN,
                                requireLineProof = false
                            )
                        }
                        scene.put("detect_ms", detectMs)
                        scene.put("image_w", bmp.width)
                        scene.put("image_h", bmp.height)
                        scene.put("gate_passed", gate?.passed ?: false)
                        scene.put("gate_score", gate?.score ?: 0.0)
                        scene.put("paper_score", gate?.paperScore ?: 0.0)
                        scene.put("text_score", gate?.textScore ?: 0.0)
                        scene.put("edge_score", gate?.edgeScore ?: 0.0)
                        scene.put("glyphs", gate?.glyphCount ?: 0)
                        scene.put("line_coherence", gate?.lineCoherence ?: 0.0)
                        scene.put("provenance", (provenance ?: com.docscanai.capture.QuadProvenance.UNKNOWN).name)

                        val row = JSONObject()
                        row.put("image", file)
                        row.put("detected", quad != null)
                        row.put("provenance", (provenance ?: com.docscanai.capture.QuadProvenance.UNKNOWN).name)
                        row.put("gate_passed", gate?.passed ?: false)
                        row.put("gate_score", gate?.score ?: 0.0)
                        row.put("paper", gate?.paperScore ?: 0.0)
                        row.put("text", gate?.textScore ?: 0.0)
                        row.put("glyphs", gate?.glyphCount ?: 0)
                        row.put("detect_ms", detectMs)
                        rows.put(row)
                        ok++
                        Log.i(
                            TAG,
                            "H39_TRACE name=$file detected=${quad != null} " +
                                "prov=${provenance?.name} gate=${gate?.passed} " +
                                "paper=${fmt(gate?.paperScore ?: 0.0)} " +
                                "text=${fmt(gate?.textScore ?: 0.0)} " +
                                "glyphs=${gate?.glyphCount ?: 0} ms=${fmt(detectMs)}"
                        )
                    } finally {
                        TraceDump.endScene()
                    }
                } finally {
                    bmp.recycle()
                }
            }
        } finally {
            TraceDump.root = null
            TraceDump.imagesEnabled = true
            TraceDump.maxScenes = Int.MAX_VALUE
        }

        manifest.put("images_ok", ok)
        manifest.put("images_failed", failed)
        manifest.put("json_only", jsonOnly)
        manifest.put("rows", rows)
        File(outRoot, "manifest.json").writeText(manifest.toString(2))
        Log.i(TAG, "H39_TRACE_DONE tag=$tag images=$ok failed=$failed out=$outRoot")
        assertTrue("trace lane wrote no images", ok > 0)
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private fun assetNames(dir: String = ASSET_DIR): List<String> =
        InstrumentationRegistry.getInstrumentation().context.assets
            .list(dir)
            ?.filter { it.endsWith(".jpg", true) }
            ?.sorted()
            ?: emptyList()

    private fun decode(path: String): Bitmap? = try {
        InstrumentationRegistry.getInstrumentation().context.assets.open(path).use { input ->
            BitmapFactory.decodeStream(input)
        }
    } catch (t: Throwable) {
        Log.e(TAG, "H39_TRACE decode failed: $path", t)
        null
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.3f", v)
}
