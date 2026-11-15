package com.docscanai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PointF
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscanai.capture.OpenCvQuadPageDetector
import com.docscanai.capture.PagePlausibility
import com.docscanai.capture.QuadProvenance
import com.docscanai.core.di.ServiceLocator
import com.docscanai.data.Quad
import com.docscanai.ml.MlsdTrigger
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ============================================================================================
 * SUBTASK H35 — owner-110 replay through the REAL in-app detector path.
 * ============================================================================================
 *
 * H34 measured a HOST mirror of the NN corner path only and reported 10/111 detected
 * ("peak" gate misses on 101). That mirror lacked `detectClassical()`, the `PagePlausibility`
 * gate and the M-LSD repair, so the true in-app recall was UNKNOWN. This lane answers it with
 * ONE instrumented run over the bundled owner photos (`assets/owner110/`, downscaled copies of
 * `corpus_owner/` — originals never modified) through the exact production chain:
 *
 *   [`OpenCvQuadPageDetector.detect()`]   NN corner model -> plausibility gate -> classical
 *                                         fallback -> [`MlsdTrigger`]/[`MlsdMergeRepair`] with
 *                                         the SHIPPED policy ([MlsdMode.REPAIR], STILL hint);
 *   [`PagePlausibility.evaluate()`]       the same still-path gate `ScanPipeline` applies
 *                                         (`requireLineProof = false`), recorded as gate pass;
 *
 * and scored against the H34 oracle pseudo-GT (`assets/owner110/oracle_manifest.json`) in the
 * bundled asset pixel frame. Per photo the lane records:
 *
 *   provenance (CORNER_MODEL / CLASSICAL_FALLBACK / UNKNOWN=none), gate pass, the raw and the
 *   effective (gate-passed) quad, IoU + corner error vs the oracle quad (LOWER BOUNDS — the
 *   oracle's corners are ~10 % of the diagonal off on real photos, H34 calibration), the
 *   frame-touch signatures used for wrong-page/merged (computed host-side in `h35_report.py`
 *   with the EXACT H34 definitions so the two runs are comparable), no-detection, latency,
 *   and the per-image `corner detect` log lines (the real peak evidence).
 *
 * Coordinate frame: assets are RAW JPEG pixels (no EXIF transpose) = the oracle's frame.
 * Diagnostic-only: no production code is touched by this test. Results land in
 * `Android/data/com.docscanai/files/h35_owner110/owner110_realpath.json` (pulled by the lane).
 */
@RunWith(AndroidJUnit4::class)
class Owner110RealPathTest {

    companion object {
        private const val TAG = "DocScan"

        private const val ASSET_DIR = "owner110"
        private const val MANIFEST = "$ASSET_DIR/owner110_manifest.json"
        private const val ORACLE = "$ASSET_DIR/oracle_manifest.json"

        /** The manifest is the contract; a silent shrink makes the measurement worthless. */
        private const val MIN_ITEMS = 100

        /** Production M-LSD mode under test (the shipped H31 decision). */
        private val PRODUCTION_MODE = OpenCvQuadPageDetector.MlsdMode.REPAIR

        private val measured = java.util.concurrent.ConcurrentHashMap<String, List<Row>>()

        /** The INSTRUMENTATION context owns the androidTest assets (owner110/). */
        private lateinit var testContext: Context

        /** The TARGET context owns the app's files dir (where the evidence JSON lands). */
        private lateinit var context: Context

        @JvmStatic
        @BeforeClass
        fun setUp() {
            NeuralRepro.setUp(enableXnnpack = false, tag = "Owner110RealPathTest/CPU-EP")
            testContext = InstrumentationRegistry.getInstrumentation().context
            context = InstrumentationRegistry.getInstrumentation().targetContext
        }
    }

    // ==========================================================================================
    // Manifest model (owner110_manifest.json + oracle_manifest.json, written by tooling/h35_bundle.py)
    // ==========================================================================================

    private class Shot(
        val image: String,
        val width: Int,
        val height: Int,
        val diagonal: Double,
        val oracle: List<PointF>?
    )

    private class Row(
        val shot: Shot,
        val detected: Boolean,
        val gatePassed: Boolean,
        val provenance: QuadProvenance,
        val quad: List<PointF>?,
        val iou: Double,
        val cornerPct: Double,
        val coversOracle: Double,
        val areaRatio: Double,
        val ourFrameTouch: Boolean,
        val oracleFrameTouch: Boolean,
        val lumaMean: Double,
        val lumaStd: Double,
        val millis: Double,
        val mlsdInvoked: Boolean,
        val cornerLog: String,
        val gateLog: String,
        /** STEP 2a: 4 per-channel heat-map peaks [TL,TR,BR,BL] from the measurement-only probe. */
        val peaks: FloatArray?,
        /**
         * SUBTASK H36 — the `[MLSD]` decision line (trigger/reason) for THIS photo. H35 could not
         * tell why the 34 merged quads were not repaired: `mlsd_invoked` says the model ran, but
         * not what `MlsdMergeRepair.apply` decided. Recorded verbatim from
         * [`OpenCvQuadPageDetector.lastMlsdLog`] (and the logcat fallback).
         */
        val mlsdLog: String,
        /**
         * SUBTASK H36 — the `classical cues=… top=[…]` diagnostic line: the per-candidate
         * `(area, pageLike)` scores the classical ranking actually saw. This is the evidence the
         * table-vs-paper ranking change is tuned on.
         */
        val cueLog: String
    )

    // ==========================================================================================
    // The one lane: 111 photos -> REAL pipeline -> JSON evidence + summary
    // ==========================================================================================

    @Test
    fun owner110RealPathReplay() {
        NeuralRepro.requireModels()
        val tag = InstrumentationRegistry.getArguments().getString("h35Tag") ?: "baseline"
        val shots = loadShots()
        assertTrue(
            "owner110 set shrank: ${shots.size} item(s), need >= $MIN_ITEMS",
            shots.size >= MIN_ITEMS
        )
        Log.i(TAG, "H35_OWNER110_START tag=$tag items=${shots.size} " +
            "mlsdModelPresent=${ServiceLocator.isMlsdModelPresent()} " +
            "presence=${ServiceLocator.modelPresenceSummary()}")

        val detector = ServiceLocator.pageDetector as? OpenCvQuadPageDetector
        assertTrue("ServiceLocator.pageDetector is not OpenCvQuadPageDetector", detector != null)
        detector!!.mlsdMode = PRODUCTION_MODE

        // H7 Tier-A fact-finding (STEP 2c): does the pinned graph accept a 512 input? Probe ONCE
        // on a small safe bitmap and record the answer — if 512 is rejected, "higher-resolution
        // input" is not an available recall lever without a new model pin.
        val probe512 = probeCornerScale()

        val out = ArrayList<Row>()
        var nNn = 0
        var nClassical = 0
        var nGateReject = 0
        var nNone = 0
        var nMlsd = 0
        loadShots().forEach { s ->
            // Every image is a NEW still: cold schedule + the capture semantics hint (the app's
            // still path gives exactly one M-LSD attempt per capture).
            detector.mlsdHint = MlsdTrigger.Hint.STILL
            detector.mlsdSchedule.reset()
            val bmp = decode("$ASSET_DIR/${s.image}") ?: run {
                Log.e(TAG, "H35_OWNER110 name=${s.image} DECODE FAILED")
                return@forEach
            }
            try {
                val (lumaMean, lumaStd) = lumaStats(bmp)
                // STEP 2a — the peak histogram. The production gate logs only the FIRST channel
                // that falls under CORNER_PEAK_MIN, so the full 4-peak vector is measured here
                // with a faithful mirror of the documented preprocess (squash-resize to the
                // latched input edge, RGB /255 NCHW) and the SAME output peak statistic. This is
                // measurement-only; production code is untouched. Cross-check: when the production
                // log reports `channel c peak=V`, peaksProbe[c] must equal V.
                val peaks = cornerPeaks(bmp, ServiceLocator.detectionModel)
                shell("logcat -c")
                val before = detector.mlsdSchedule.invocations
                val t0 = System.nanoTime()
                val quad = runCatching { runBlocking { detector.detect(bmp) } }.getOrNull()
                val ms = (System.nanoTime() - t0) / 1e6
                val invoked = detector.mlsdSchedule.invocations > before
                val prov = runCatching { detector.lastProvenance }.getOrDefault(QuadProvenance.UNKNOWN)
                val logDump = shell("logcat -d -v brief -s DocScan")
                val cornerLog = logDump.lines()
                    .filter { it.contains("corner detect") }
                    .joinToString(" | ")
                // SUBTASK H36: the per-photo M-LSD decision and the classical candidate ranking.
                val mlsdLog: String = detector.lastMlsdLog
                    ?: logDump.lines().firstOrNull { it.contains("[MLSD]") }?.substringAfter("[MLSD] ") ?: "-"
                val cueLog: String = logDump.lines()
                    .filter { it.contains("classical cues=") }
                    .joinToString(" | ") { it.substringAfter("classical cues=") }
                    .ifEmpty { "-" }
                val gateLogLine: String

                if (quad == null) {
                    nNone++
                    gateLogLine = "-"
                    Log.i(TAG, "H35_OWNER110 name=${s.image} detected=false prov=$prov " +
                        "ms=${fmt(ms)} luma=${fmt(lumaMean)}/${fmt(lumaStd)} " +
                        "peaks=${peaks?.joinToString(",") { fmt(it.toDouble()) } ?: "-"} " +
                        "cornerLog=[$cornerLog]")
                    out.add(Row(s, false, false, prov, null, Double.NaN, Double.NaN,
                        0.0, 0.0, false, s.oracle?.let { touchesFrame(it, s.width, s.height) } ?: false,
                        lumaMean, lumaStd, ms, invoked, cornerLog, "-", peaks, mlsdLog, cueLog))
                    return@forEach
                }

                // The still-path gate, exactly as ScanPipeline applies it after the shutter.
                val gate = PagePlausibility.evaluate(
                    bmp, quad, s.image, prov, requireLineProof = false
                )
                gateLogLine = gate.logLine(s.image)
                val gatePassed = gate.passed
                if (!gatePassed) nGateReject++

                val pts = quad.points.map { PointF(it.x, it.y) }
                val oracle = s.oracle
                val iou = oracle?.let { iouOf(pts, it, s.width, s.height) } ?: Double.NaN
                val cornerPct = oracle?.let {
                    val (meanPx, _) = alignAndMeasure(pts, it)
                    100.0 * meanPx / s.diagonal.coerceAtLeast(1.0)
                } ?: Double.NaN
                val covers = oracle?.let { coverage(it, pts, s.width, s.height) } ?: 0.0
                val areaRatio = oracle?.let {
                    (polygonArea(pts) / polygonArea(it).coerceAtLeast(1.0))
                } ?: 0.0
                val ourTouch = touchesFrame(pts, s.width, s.height)
                val oracleTouch = oracle?.let { touchesFrame(it, s.width, s.height) } ?: false

                when (prov) {
                    QuadProvenance.CORNER_MODEL -> nNn++
                    QuadProvenance.CLASSICAL_FALLBACK -> nClassical++
                    else -> {}
                }
                if (invoked) nMlsd++

                Log.i(
                    TAG,
                    "H35_OWNER110 name=${s.image} detected=true prov=$prov gatePass=$gatePassed " +
                        "iou=${fmt(iou)} cornerPct=${fmt(cornerPct)} covers=${fmt(covers)} " +
                        "areaRatio=${fmt(areaRatio)} frameTouch=$ourTouch oracleTouch=$oracleTouch " +
                        "ms=${fmt(ms)} mlsdInvoked=$invoked " +
                        "peaks=${peaks?.joinToString(",") { fmt(it.toDouble()) } ?: "-"} " +
                        "quad=${quadString(pts)} cornerLog=[$cornerLog]"
                )
                out.add(Row(s, true, gatePassed, prov, pts, iou, cornerPct, covers,
                    areaRatio, ourTouch, oracleTouch, lumaMean, lumaStd, ms, invoked,
                    cornerLog, gateLogLine, peaks, mlsdLog, cueLog))
            } finally {
                bmp.recycle()
            }
        }

        val det = out.count { it.detected }
        val gateOk = out.count { it.detected && it.gatePassed }
        Log.i(
            TAG,
            "H35_OWNER110_SUMMARY tag=$tag n=${out.size} detectedRaw=$det detectedGatePassed=$gateOk " +
                "provNN=$nNn provClassical=$nClassical gateRejected=$nGateReject none=$nNone " +
                "mlsdInvoked=$nMlsd probe512=$probe512"
        )

        writeEvidence(tag, out, probe512)

        // The lane's own contract: the set is complete and the models were actually present.
        assertTrue("owner110 replay processed ${out.size}/${shots.size} photos", out.size == shots.size)
    }

    // ==========================================================================================
    // STEP 2a — peak probe (measurement-only mirror of the documented preprocess)
    // ==========================================================================================

    /**
     * Runs the SAME documented preprocessing the detector uses (`preprocessForCornerModel`:
     * squash-resize to the pinned graph edge, RGB /255 NCHW, no mean/std) and returns the four
     * per-channel heat-map peaks in TL,TR,BR,BL order. Faithful to the production statistic
     * (`max` over the heat channel) so a `peak=` value the production gate logs can be matched
     * here. Returns `null` when the model is absent or inference fails. Nothing in production is
     * modified — this exists purely to build the peak distribution the subtask asks for.
     */
    private fun cornerPeaks(bmp: Bitmap, model: com.docscanai.ml.OnnxModel?): FloatArray? {
        if (model == null) return null
        val edge = 256 // the pinned m1c graph declares a static [1,3,256,256] input
        return try {
            val resized = Bitmap.createScaledBitmap(bmp, edge, edge, true)
            val pixels = IntArray(edge * edge)
            resized.getPixels(pixels, 0, edge, 0, 0, edge, edge)
            if (resized !== bmp) resized.recycle()
            val plane = edge * edge
            val buf = java.nio.FloatBuffer.allocate(3 * plane)
            for (i in 0 until plane) {
                val p = pixels[i]
                buf.put(i, ((p shr 16) and 0xFF) / 255f)
                buf.put(plane + i, ((p shr 8) and 0xFF) / 255f)
                buf.put(2 * plane + i, (p and 0xFF) / 255f)
            }
            buf.rewind()
            val heat = model.runFloat(
                buf,
                longArrayOf(1L, 3L, edge.toLong(), edge.toLong()),
                model.outputNames.firstOrNull()
            ) ?: return null
            if (heat.isEmpty() || heat.size % 4 != 0) return null
            val hPlane = heat.size / 4
            val peaks = FloatArray(4)
            for (c in 0 until 4) {
                var mx = 0f
                for (i in 0 until hPlane) {
                    val v = heat[c * hPlane + i]
                    if (v > mx) mx = v
                }
                peaks[c] = mx
            }
            peaks
        } catch (t: Throwable) {
            Log.w(TAG, "cornerPeaks failed: ${t.message}")
            null
        }
    }

    // ==========================================================================================
    // Corner-scale probe (STEP 2c fact): does the pinned m1c graph accept a 512 input?
    // ==========================================================================================

    private fun probeCornerScale(): String {
        val model = ServiceLocator.detectionModel ?: return "no_model"
        val tiny = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        return try {
            val buf = java.nio.FloatBuffer.allocate(3 * 512 * 512)
            for (i in 0 until 512 * 512) {
                buf.put(i, 0.5f); buf.put(512 * 512 + i, 0.5f); buf.put(2 * 512 * 512 + i, 0.5f)
            }
            buf.rewind()
            val run = runCatching {
                model.runFloat(buf, longArrayOf(1L, 3L, 512L, 512L), model.outputNames.firstOrNull())
            }.getOrNull()
            if (run != null && run.isNotEmpty()) "accepted" else "rejected"
        } catch (t: Throwable) {
            "rejected(${t.javaClass.simpleName})"
        } finally {
            tiny.recycle()
        }
    }

    // ==========================================================================================
    // Evidence output
    // ==========================================================================================

    private fun writeEvidence(tag: String, rows: List<Row>, probe512: String) {
        // INTERNAL files dir: `run-as com.docscanai` (the lane's pull path) can read it on every
        // API level, unlike the external files dir which scoped storage may lock down.
        val dir = File(context.filesDir, "h35_owner110").also { it.mkdirs() }
        val root = JSONObject()
        root.put("tag", tag)
        root.put("produced_by", "Owner110RealPathTest")
        root.put("path", "OpenCvQuadPageDetector.detect + PagePlausibility.evaluate(still)")
        root.put("mlsd_mode", PRODUCTION_MODE.name)
        root.put("probe512", probe512)
        root.put("mlsd_model_present", ServiceLocator.isMlsdModelPresent())
        root.put("presence", ServiceLocator.modelPresenceSummary())
        val arr = JSONArray()
        rows.forEach { r ->
            val o = JSONObject()
            o.put("image", r.shot.image)
            o.put("width", r.shot.width)
            o.put("height", r.shot.height)
            o.put("diag", r.shot.diagonal)
            o.put("detected", r.detected)
            o.put("gate_pass", r.gatePassed)
            o.put("prov", r.provenance.name)
            o.put("quad", r.quad?.let { quadJson(it) } ?: JSONObject.NULL)
            o.put("iou", if (r.iou.isNaN()) JSONObject.NULL else r.iou)
            o.put("corner_pct", if (r.cornerPct.isNaN()) JSONObject.NULL else r.cornerPct)
            o.put("covers_oracle", r.coversOracle)
            o.put("area_ratio", r.areaRatio)
            o.put("our_frame_touch", r.ourFrameTouch)
            o.put("oracle_frame_touch", r.oracleFrameTouch)
            o.put("luma_mean", r.lumaMean)
            o.put("luma_std", r.lumaStd)
            o.put("ms", r.millis)
            o.put("mlsd_invoked", r.mlsdInvoked)
            o.put("peaks", r.peaks?.let { p ->
                JSONArray().also { a -> p.forEach { v -> a.put(v.toDouble()) } }
            } ?: JSONObject.NULL)
            o.put("corner_log", r.cornerLog)
            o.put("gate_log", r.gateLog)
            // SUBTASK H36 — the trigger/repair decision and the classical candidate ranking.
            o.put("mlsd_log", r.mlsdLog)
            o.put("cue_log", r.cueLog)
            arr.put(o)
        }
        root.put("rows", arr)
        val file = File(dir, "owner110_realpath.json")
        file.writeText(root.toString())
        Log.i(TAG, "H35_OWNER110_EVIDENCE file=${file.absolutePath} bytes=${file.length()}")
    }

    // ==========================================================================================
    // Geometry helpers (platform-exact, same as RealBookSpreadDetectionTest)
    // ==========================================================================================

    private fun decode(path: String): Bitmap? = try {
        testContext.assets.open(path).use { BitmapFactory.decodeStream(it) }
    } catch (t: Throwable) {
        Log.e(TAG, "failed to decode assets/$path", t)
        null
    }

    private fun lumaStats(bmp: Bitmap): Pair<Double, Double> {
        val w = bmp.width
        val h = bmp.height
        val step = maxOf(1, (w * h) / 200_000) // bounded work; statistical, not exact
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var n = 0
        var sum = 0.0
        var sum2 = 0.0
        var i = 0
        while (i < px.size) {
            val p = px[i]
            val y = (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p))
            sum += y
            sum2 += y * y
            n++
            i += step
        }
        if (n == 0) return 0.0 to 0.0
        val mean = sum / n
        val std = Math.sqrt((sum2 / n - mean * mean).coerceAtLeast(0.0))
        return mean to std
    }

    private fun alignAndMeasure(det: List<PointF>, gt: List<PointF>): Pair<Double, Double> {
        var bestMean = Double.MAX_VALUE
        var bestMax = Double.MAX_VALUE
        for (rev in 0..1) {
            val seq = if (rev == 0) det else det.reversed()
            for (shift in seq.indices) {
                var sum = 0.0
                var mx = 0.0
                for (i in gt.indices) {
                    val a = seq[(i + shift) % seq.size]
                    val b = gt[i]
                    val d = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
                    sum += d
                    if (d > mx) mx = d
                }
                val mean = sum / gt.size
                if (mean < bestMean) {
                    bestMean = mean
                    bestMax = mx
                }
            }
        }
        return bestMean to bestMax
    }

    private fun pathOf(pts: List<PointF>): android.graphics.Path {
        val p = android.graphics.Path()
        p.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) p.lineTo(pts[i].x, pts[i].y)
        p.close()
        return p
    }

    private fun regionOf(pts: List<PointF>, w: Int, h: Int): android.graphics.Region {
        val r = android.graphics.Region()
        r.setPath(pathOf(pts), android.graphics.Region(0, 0, w, h))
        return r
    }

    private fun regionArea(r: android.graphics.Region): Double {
        var a = 0.0
        val it = android.graphics.RegionIterator(r)
        val rect = android.graphics.Rect()
        while (it.next(rect)) a += rect.width().toDouble() * rect.height().toDouble()
        return a
    }

    private fun coverage(inner: List<PointF>, outer: List<PointF>, w: Int, h: Int): Double {
        val ri = regionOf(inner, w, h)
        val base = regionArea(ri)
        if (base <= 0.0) return 0.0
        val ro = regionOf(outer, w, h)
        val inter = android.graphics.Region(ri)
        if (!inter.op(ro, android.graphics.Region.Op.INTERSECT)) return 0.0
        return (regionArea(inter) / base).coerceIn(0.0, 1.0)
    }

    private fun iouOf(a: List<PointF>, b: List<PointF>, w: Int, h: Int): Double {
        val ra = regionOf(a, w, h)
        val rb = regionOf(b, w, h)
        val inter = android.graphics.Region(ra)
        if (!inter.op(rb, android.graphics.Region.Op.INTERSECT)) return 0.0
        val ai = regionArea(inter)
        val union = regionArea(ra) + regionArea(rb) - ai
        return if (union <= 0.0) 0.0 else ai / union
    }

    private fun polygonArea(pts: List<PointF>): Double {
        var a = 0.0
        for (i in pts.indices) {
            val p = pts[i]
            val q = pts[(i + 1) % pts.size]
            a += p.x.toDouble() * q.y - q.x.toDouble() * p.y
        }
        return abs(a) / 2.0
    }

    /** True when any quad corner sits within 2 % of the frame border (H34's frame signature). */
    private fun touchesFrame(pts: List<PointF>, w: Int, h: Int): Boolean {
        val tolX = w * 0.02
        val tolY = h * 0.02
        return pts.any {
            it.x <= tolX || it.x >= w - tolX || it.y <= tolY || it.y >= h - tolY
        }
    }

    // ==========================================================================================
    // Manifest parsing
    // ==========================================================================================

    private fun loadShots(): List<Shot> {
        val text = testContext.assets.open(MANIFEST).use { it.bufferedReader().readText() }
        val oText = testContext.assets.open(ORACLE).use { it.bufferedReader().readText() }
        val oracleByName = HashMap<String, List<PointF>>()
        val oItems = JSONObject(oText).getJSONArray("items")
        (0 until oItems.length()).forEach { i ->
            val o = oItems.getJSONObject(i)
            val c = o.getJSONObject("corners_px")
            oracleByName[o.getString("image")] = listOf("tl", "tr", "br", "bl").map { k ->
                val p = c.getJSONArray(k)
                PointF(p.getDouble(0).toFloat(), p.getDouble(1).toFloat())
            }
        }
        val items = JSONObject(text).getJSONArray("items")
        return (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            Shot(
                image = o.getString("image"),
                width = o.getInt("width"),
                height = o.getInt("height"),
                diagonal = o.getDouble("diagonal"),
                oracle = oracleByName[o.getString("image")]
            )
        }
    }

    private fun quadJson(pts: List<PointF>): JSONObject {
        val arr = JSONArray()
        pts.forEach { p ->
            val q = JSONArray()
            q.put(p.x.toDouble())
            q.put(p.y.toDouble())
            arr.put(q)
        }
        val o = JSONObject()
        o.put("pts", arr)
        return o
    }

    private fun quadString(pts: List<PointF>): String =
        pts.joinToString(";") { "${it.x.toInt()},${it.y.toInt()}" }

    private fun shell(cmd: String): String = try {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pfd = ui.executeShellCommand(cmd)
        val inStream = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val text = inStream.bufferedReader().use { it.readText() }
        text
    } catch (t: Throwable) {
        Log.w(TAG, "shell('$cmd') failed: ${t.message}")
        ""
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.4f", v)
}
