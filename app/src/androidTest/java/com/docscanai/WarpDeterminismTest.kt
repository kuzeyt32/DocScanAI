package com.docscanai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscanai.core.di.ServiceLocator
import com.docscanai.imaging.NeuralGridDewarp
import com.docscanai.ml.OnnxModel
import com.docscanai.ml.OrtSessionFactory
import com.docscanai.models.ModelRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point as CvPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * ============================================================================================
 * SUBTASK H27 — grid-dewarp determinism proof.
 * ============================================================================================
 *
 * H26 observed that the second-stage UVDoc grid dewarp did not look bit-stable: two
 * reprocesses of the same page produced 258,204 B and 288,331 B page JPEGs. This class isolates
 * the question with a FIXED bitmap, a FIXED quad and a FIXED delivered page size:
 *
 *  1. run the real [NeuralGridDewarp.dewarpGrid] N times in one process and hash the delivered
 *     RGBA pixels plus the quality-92 JPEG the pipeline would write;
 *  2. run the raw grid graph (same preprocessed tensor) N times and hash the float grid, so a
 *     model-numerics cause is separated from an OpenCV map/encode cause;
 *  3. repeat (1)+(2) under four ORT session configurations — default threads, deterministic
 *     compute, intra-op 1, intra-op 1 + deterministic — and record the latency of each.
 *
 * Every measurement is logged as `H27_DET ...` / `H27_GRID ...` for `tooling/out/h27/`.
 * The cross-session guarantee is written to `filesDir/h27_det/` and compared on the next process.
 */
@RunWith(AndroidJUnit4::class)
class WarpDeterminismTest {

    companion object {
        private const val TAG = "DocScan"
        private const val EVID = "H27_DET"
        private const val GRID_EVID = "H27_GRID"

        /** A real benchmark capture (not a synthetic drawing). */
        private const val ASSET = "corpus/benchmark/inputs/docunet_01.jpg"

        /** Runs per configuration. */
        private const val N = 5

        /**
         * The configuration the shipped app uses (H27 decision): the default intra-op thread count
         * with `setDeterministicCompute(true)` — [com.docscanai.core.di.ServiceLocator]
         * `dewarpGridSessionFactory`. Measured on this device: identical output bytes to the plain
         * default session at +1.2 % delivered latency, whereas intra-op = 1 costs 3.3x for the
         * same bytes, so pinning one thread was rejected.
         */
        private const val PRODUCTION_CFG = "det"

        private lateinit var original: Mat
        private lateinit var sourceToPage: Mat
        private var pageW = 0
        private var pageH = 0
        private lateinit var modelFile: File

        /** Fixed quad inside the asset: mild perspective, never a full frame. */
        private val QUAD_FRAC = doubleArrayOf(
            0.06, 0.08,
            0.94, 0.06,
            0.96, 0.94,
            0.05, 0.96
        )

        /** Does the graph accept this device's CPU? */
        private var modelPresent = false

        @JvmStatic
        @BeforeClass
        fun setUp() {
            NeuralRepro.setUp(enableXnnpack = false, tag = "WarpDeterminismTest")
            ServiceLocator.ensureOpenCv()
            // `corpus/...` lives in the INSTRUMENTATION APK's assets, not the app's (see
            // CorpusRealLifeTest:531). The app target context has only `testdocs/`.
            val testContext = InstrumentationRegistry.getInstrumentation().context

            val spec = ServiceLocator.modelRegistry.byId(ModelRegistry.ID_M2_DEWARP_GRID)
            assertNotNull("m2_dewarp_grid must be registered", spec)
            modelFile = ServiceLocator.modelDownloadManager.modelFile(spec!!)
            modelPresent = modelFile.isFile && modelFile.length() > 0L
            assertTrue(
                "WarpDeterminismTest requires the provisioned grid model at ${modelFile.absolutePath}",
                modelPresent
            )

            val decoded: Bitmap = testContext.assets.open(ASSET).use { BitmapFactory.decodeStream(it) }
                ?: throw AssertionError("asset $ASSET must decode")
            // Mirror the production working-image cap (ScanPipeline.MAX_PROCESS_LONG_EDGE = 2000)
            // so the delivered page stays inside NeuralGridDewarp.MAX_PAGE_PIXELS. The production
            // pipeline never asks the grid path for a 5.3 MP page.
            val srcLongPx = max(decoded.width, decoded.height)
            val bmp = if (srcLongPx > 2000) {
                val s = 2000f / srcLongPx.toFloat()
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * s).roundToInt().coerceAtLeast(1),
                    (decoded.height * s).roundToInt().coerceAtLeast(1),
                    true
                ).also { decoded.recycle() }
            } else {
                decoded
            }
            original = Mat()
            Utils.bitmapToMat(bmp, original)
            bmp.recycle()

            val w = original.cols()
            val h = original.rows()
            val q = QUAD_FRAC
            val tl = CvPoint(w * q[0], h * q[1])
            val tr = CvPoint(w * q[2], h * q[3])
            val br = CvPoint(w * q[4], h * q[5])
            val bl = CvPoint(w * q[6], h * q[7])

            // Production rule (PerspectiveDewarpProcessor.computePerspective): page size from the
            // mean opposite edge lengths, capped at the projected page long edge (no invented px).
            fun d(a: CvPoint, b: CvPoint) = hypot(a.x - b.x, a.y - b.y)
            val top = d(tl, tr)
            val bottom = d(bl, br)
            val left = d(tl, bl)
            val right = d(tr, br)
            var outW = ((top + bottom) / 2.0).roundToInt().coerceAtLeast(2)
            var outH = ((left + right) / 2.0).roundToInt().coerceAtLeast(2)
            val srcPageLong = max(max(top, bottom), max(left, right))
            val pageLongEdge = max(outW, outH).toDouble()
            if (pageLongEdge > srcPageLong) {
                val s = srcPageLong / pageLongEdge
                outW = (outW * s).roundToInt().coerceAtLeast(2)
                outH = (outH * s).roundToInt().coerceAtLeast(2)
            }
            pageW = outW
            pageH = outH

            val srcM = MatOfPoint2f(tl, tr, br, bl)
            val dstM = MatOfPoint2f(
                CvPoint(0.0, 0.0),
                CvPoint(outW - 1.0, 0.0),
                CvPoint(outW - 1.0, outH - 1.0),
                CvPoint(0.0, outH - 1.0)
            )
            try {
                sourceToPage = Imgproc.getPerspectiveTransform(srcM, dstM)
            } finally {
                srcM.release()
                dstM.release()
            }
            Log.i(
                TAG,
                "$EVID fixture asset=$ASSET src=${w}x$h page=${pageW}x$pageH " +
                    "model=${modelFile.name} modelBytes=${modelFile.length()}"
            )
        }

        /** Session configuration under test. */
        private data class Cfg(val name: String, val intra: Int, val deterministic: Boolean)

        private fun configs(): List<Cfg> {
            val def = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            return listOf(
                Cfg("baseline", def, false),
                Cfg("det", def, true),
                Cfg("intra1", 1, false),
                Cfg("intra1_det", 1, true)
            )
        }

        private fun loadGrid(cfg: Cfg): OnnxModel? {
            val factory = OrtSessionFactory(
                enableNnapi = false,
                intraOpThreads = cfg.intra,
                enableXnnpack = false,
                deterministicCompute = cfg.deterministic
            )
            return OnnxModel.load(factory, modelFile, allowXnnpack = false)
        }

        /**
         * Builds the exact graph input [NeuralGridDewarp.runGrid] builds: page rendered at the
         * graph's fixed H×W with `M' = S·H`, RGB [0,1] NCHW.
         */
        private fun graphInput(model: OnnxModel): Pair<java.nio.FloatBuffer, Pair<Int, Int>>? {
            val shape = model.inputShape ?: return null
            if (shape.size != 4 || shape[1] != 3L || shape[2] <= 0L || shape[3] <= 0L) return null
            val inH = shape[2].toInt()
            val inW = shape[3].toInt()
            val sx = inW.toDouble() / pageW.toDouble()
            val sy = inH.toDouble() / pageH.toDouble()
            val scaleM = Mat(3, 3, CvType.CV_64F)
            val modelM = Mat()
            val pageSmall = Mat()
            try {
                scaleM.put(
                    0, 0,
                    *doubleArrayOf(sx, 0.0, 0.0, 0.0, sy, 0.0, 0.0, 0.0, 1.0)
                )
                Core.gemm(scaleM, sourceToPage, 1.0, Mat(), 0.0, modelM)
                Imgproc.warpPerspective(
                    original, pageSmall, modelM,
                    Size(inW.toDouble(), inH.toDouble()),
                    Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE
                )
                val plane = inW * inH
                val bytes = ByteArray(plane * 4)
                pageSmall.get(0, 0, bytes)
                val buffer = java.nio.FloatBuffer.allocate(3 * plane)
                for (i in 0 until plane) {
                    val b = i * 4
                    buffer.put(i, (bytes[b].toInt() and 0xFF) / 255f)
                    buffer.put(plane + i, (bytes[b + 1].toInt() and 0xFF) / 255f)
                    buffer.put(2 * plane + i, (bytes[b + 2].toInt() and 0xFF) / 255f)
                }
                buffer.rewind()
                return buffer to (inH to inW)
            } finally {
                scaleM.release(); modelM.release(); pageSmall.release()
            }
        }

        private fun sha256(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(bytes).joinToString("") { "%02x".format(it) }
        }

        private fun sha256Floats(values: FloatArray): String {
            val bb = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { bb.putFloat(it) }
            return sha256(bb.array())
        }

        private fun matBytes(mat: Mat): ByteArray {
            val n = (mat.total() * mat.elemSize()).toInt()
            val out = ByteArray(n)
            mat.get(0, 0, out)
            return out
        }

        private fun psnr(a: ByteArray, b: ByteArray): Double {
            if (a.size != b.size) return 0.0
            var sum = 0.0
            for (i in a.indices) {
                val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                sum += d.toDouble() * d.toDouble()
            }
            val mse = sum / a.size.toDouble()
            return if (mse == 0.0) Double.POSITIVE_INFINITY else 10.0 * log10(255.0 * 255.0 / mse)
        }

        private fun jpegSize(mat: Mat): Pair<Int, String> {
            val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
            return try {
                Utils.matToBitmap(mat, bmp)
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                val bytes = out.toByteArray()
                bytes.size to sha256(bytes)
            } finally {
                bmp.recycle()
            }
        }

        private fun fmt(v: Double): String =
            if (v.isInfinite()) "inf" else String.format(java.util.Locale.US, "%.3f", v)
    }

    // ==========================================================================================
    // 1) Raw grid graph determinism per session configuration
    // ==========================================================================================

    @Test
    fun gridGraph_isStablePerConfiguration() {
        val perConfig = LinkedHashMap<String, String>()
        configs().forEach { cfg ->
            val model = loadGrid(cfg)
                ?: throw AssertionError("grid model must load for cfg=${cfg.name}")
            model.use { m ->
                val (buffer, dims) = graphInput(m)
                    ?: throw AssertionError("graph input shape unsupported: ${m.inputShape?.toList()}")
                val (inH, inW) = dims
                val shape = longArrayOf(1L, 3L, inH.toLong(), inW.toLong())
                val hashes = ArrayList<String>()
                var firstMs = 0L
                var totalMs = 0L
                repeat(N) { run ->
                    val t0 = System.nanoTime()
                    val out = m.runFloat(buffer, shape, m.outputNames.firstOrNull())
                    val ms = (System.nanoTime() - t0) / 1_000_000L
                    if (run == 0) firstMs = ms
                    totalMs += ms
                    assertNotNull("grid inference must return floats (cfg=${cfg.name})", out)
                    val sha = sha256Floats(out!!)
                    hashes.add(sha)
                    Log.i(
                        TAG,
                        "$GRID_EVID cfg=${cfg.name} intra=${cfg.intra} det=${cfg.deterministic} " +
                            "run=$run ms=$ms floats=${out.size} sha=$sha " +
                            "in=${inW}x${inH}"
                    )
                }
                val stable = hashes.distinct().size == 1
                perConfig[cfg.name] = hashes.first()
                Log.i(
                    TAG,
                    "$GRID_EVID cfg=${cfg.name} RESULT stable=$stable distinct=${hashes.distinct().size} " +
                        "firstMs=$firstMs avgMs=${totalMs / N} sha0=${hashes.first()}"
                )
                assertTrue(
                    "H27 DETERMINISM: the grid graph must return the same floats on every run " +
                        "(cfg=${cfg.name}, distinct=${hashes.distinct().size})",
                    stable
                )
            }
        }
        // The grid is a pure function of the input tensor: thread count and the deterministic
        // flag must not move a single bit, otherwise the map/JPEG guarantee below would depend on
        // the device's CPU count.
        Log.i(TAG, "$GRID_EVID crossConfig shas=${perConfig.entries.joinToString(",") { "${it.key}=${it.value}" }}")
        assertTrue(
            "H27 DETERMINISM: every session configuration must yield the same grid floats, " +
                "got ${perConfig.values.distinct().size} distinct result(s): $perConfig",
            perConfig.values.distinct().size == 1
        )
    }

    // ==========================================================================================
    // 2) Delivered page determinism (composed map + remap + JPEG)
    // ==========================================================================================

    @Test
    fun deliveredPage_isStablePerConfiguration() {
        val perConfigPageSha = LinkedHashMap<String, String>()
        val perConfigJpegSha = LinkedHashMap<String, String>()
        configs().forEach { cfg ->
            val model = loadGrid(cfg)
                ?: throw AssertionError("grid model must load for cfg=${cfg.name}")
            model.use { m ->
                val dewarp = NeuralGridDewarp(modelProvider = null, gridModelProvider = { m })
                var reference: ByteArray? = null
                val hashes = ArrayList<String>()
                val jpegSizes = ArrayList<Int>()
                val jpegHashes = ArrayList<String>()
                var firstMs = 0L
                var totalMs = 0L
                val psnrs = ArrayList<Double>()
                repeat(N) { run ->
                    val t0 = System.nanoTime()
                    val out = dewarp.dewarpGrid(original, sourceToPage, pageW, pageH)
                    val ms = (System.nanoTime() - t0) / 1_000_000L
                    if (run == 0) firstMs = ms
                    totalMs += ms
                    val page = out
                        ?: throw AssertionError("dewarpGrid must deliver a page (cfg=${cfg.name})")
                    try {
                        val bytes = matBytes(page)
                        val sha = sha256(bytes)
                        hashes.add(sha)
                        if (reference == null) {
                            reference = bytes
                        }
                        val p = reference!!.let { psnr(it, bytes) }
                        psnrs.add(p)
                        val (jsize, jsha) = jpegSize(page)
                        jpegSizes.add(jsize)
                        jpegHashes.add(jsha)
                        Log.i(
                            TAG,
                            "$EVID cfg=${cfg.name} intra=${cfg.intra} det=${cfg.deterministic} " +
                                "run=$run ms=$ms out=${page.cols()}x${page.rows()} bytes=${bytes.size} " +
                                "sha=$sha jpegBytes=$jsize jpegSha=$jsha psnr=${fmt(p)}"
                        )
                    } finally {
                        page.release()
                    }
                }
                val stable = hashes.distinct().size == 1
                val jpegStable = jpegHashes.distinct().size == 1
                val minPsnr = psnrs.minOrNull() ?: 0.0
                perConfigPageSha[cfg.name] = hashes.first()
                perConfigJpegSha[cfg.name] = jpegHashes.first()
                Log.i(
                    TAG,
                    "$EVID cfg=${cfg.name} RESULT stable=$stable jpegStable=$jpegStable " +
                        "distinct=${hashes.distinct().size} jpegDistinct=${jpegHashes.distinct().size} " +
                        "minPsnr=${fmt(minPsnr)} jpegSizes=${jpegSizes.joinToString(",")} " +
                        "firstMs=$firstMs avgMs=${totalMs / N} sha0=${hashes.first()}"
                )
                // The guarantee asserted for EVERY configuration, not just the shipped one: the
                // delivered page (composed map + remap) and the quality-92 JPEG must be
                // bit-identical run over run.
                assertTrue(
                    "H27 DETERMINISM: grid dewarp must be bit-identical within a session " +
                        "(cfg=${cfg.name}, distinct=${hashes.distinct().size}, " +
                        "minPsnr=${fmt(minPsnr)}, sizes=${jpegSizes.joinToString(",")})",
                    stable && jpegStable
                )
            }
        }
        assertTrue(
            "H27 DETERMINISM: the shipped configuration '$PRODUCTION_CFG' must be one of the " +
                "measured configurations ${configs().map { it.name }}",
            perConfigPageSha.containsKey(PRODUCTION_CFG)
        )
        Log.i(
            TAG,
            "$EVID crossConfig pageShas=${perConfigPageSha.entries.joinToString(",") { "${it.key}=${it.value}" }} " +
                "jpegShas=${perConfigJpegSha.entries.joinToString(",") { "${it.key}=${it.value}" }}"
        )
        assertTrue(
            "H27 DETERMINISM: the delivered page must not depend on the ORT thread configuration, " +
                "got ${perConfigPageSha.values.distinct().size} distinct page hash(es)",
            perConfigPageSha.values.distinct().size == 1
        )
        assertTrue(
            "H27 DETERMINISM: the delivered JPEG must not depend on the ORT thread configuration, " +
                "got ${perConfigJpegSha.values.distinct().size} distinct JPEG hash(es)",
            perConfigJpegSha.values.distinct().size == 1
        )
    }

    // ==========================================================================================
    // 3) Cross-session comparison (two separate `am instrument` processes)
    // ==========================================================================================

    @Test
    fun deliveredPage_isStableAcrossSessions() {
        val cfg = configs().first { it.name == PRODUCTION_CFG }
        val model = loadGrid(cfg)
            ?: throw AssertionError("grid model must load for the cross-session check")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "h27_det")
        dir.mkdirs()
        val stamp = "page${pageW}x$pageH-model${modelFile.length()}"
        val record = File(dir, "${cfg.name}_$stamp.txt")
        model.use { m ->
            val dewarp = NeuralGridDewarp(modelProvider = null, gridModelProvider = { m })
            val out = dewarp.dewarpGrid(original, sourceToPage, pageW, pageH)
                ?: throw AssertionError("dewarpGrid must deliver a page for the cross-session check")
            try {
                val sha = sha256(matBytes(out))
                val (jsize, jsha) = jpegSize(out)
                val line = "$sha $jsha $jsize"
                val previous = if (record.isFile) record.readText().trim() else null
                val matches = previous == line
                Log.i(
                    TAG,
                    "$EVID crossSession cfg=${cfg.name} previous=${previous ?: "none"} " +
                        "current=$line matches=$matches record=${record.absolutePath}"
                )
                record.writeText(line)
                if (previous != null) {
                    assertTrue(
                        "H27 DETERMINISM: identical input must produce identical output across " +
                            "sessions (previous=$previous current=$line)",
                        matches
                    )
                }
            } finally {
                out.release()
            }
        }
    }
}
