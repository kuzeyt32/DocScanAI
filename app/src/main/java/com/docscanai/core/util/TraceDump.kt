package com.docscanai.core.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point as CvPoint
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import com.docscanai.data.Point
import com.docscanai.data.Quad

/**
 * ============================================================================================
 * TRACE DUMP — write the detector's INTERMEDIATE maps (what the AI actually sees/produces)
 * to disk, so a human can look at them before trusting any pipeline decision.
 * ============================================================================================
 *
 * Why this exists (owner report, H39): the curved/bumpy-page fix was attempted, then rejected
 * because the *ink map* the analysis produced was visibly poor — but there was no artifact to
 * look at. Logcat only carries scalars. This class makes the evidence visual:
 *
 *  - `corner_input.png`   the exact resized image handed to the ONNX corner model;
 *  - `heat_c0..c3.png`    the four corner heat-map channels (TL, TR, BR, BL), per-channel
 *                         normalised, plus `heat_max.png` and a labelled `heat_montage.png`;
 *  - `classical_gray.png` / `classical_edges.png` / `classical_candidates.png`
 *                         the classical fallback's working image, the union edge map it builds,
 *                         and every scored quad candidate;
 *  - `page_rectified.png` / `page_interior.png` / `page_ink.png` / `page_edges.png`
 *                         the rectified page, the interior ROI, the adaptive-threshold INK map
 *                         (the map that decides "does this hold text?"), its Canny edges, and
 *                         `page_glyphs.png` with the glyph components that score it;
 *  - `final_quad.png`     the quad finally returned, drawn on the input frame;
 *  - `trace.json`         every scalar (peaks, candidate scores, gate breakdown, provenance).
 *
 * SAFETY / PRODUCTION CONTRACT
 *  - Disabled by default. Nothing is allocated, no file is touched, and no extra image pass is
 *    run unless [root] is set **and** a scene is open ([beginScene]).
 *  - Only diagnostics/tests call [beginScene]. The app's normal capture path never sets [root].
 *  - Every method is best-effort and swallows failures: tracing must never break scanning.
 *
 * Coordinate frames: every dumped image is the frame it was produced in. Heat maps are
 * `plane`-square (128x128 at the pinned 256 input); [heatDump] records that size in the JSON so
 * a viewer can map a heat pixel back to the source frame (`x * frameW / heatSide`).
 */
object TraceDump {

    /** Destination root; `null` disables tracing entirely (the production default). */
    @Volatile
    var root: File? = null

    /** Optional cap on scenes written in one run (tests use it to bound an emulator lane). */
    @Volatile
    var maxScenes: Int = Int.MAX_VALUE

    /**
     * SUBTASK H40: when false, only `trace.json` is written — no PNG maps. The frame-wide
     * all-photos lane uses this to collect corner heat peaks for the whole owner set without
     * pulling ~1 MB of PNG per photo. The scalar trace is byte-for-byte the same.
     */
    @Volatile
    var imagesEnabled: Boolean = true

    private val scenes = ThreadLocal<Session?>()

    private var sceneCount = 0

    val enabled: Boolean get() = root != null

    /** One trace folder: a directory plus its accumulating `trace.json`. */
    class Session internal constructor(val dir: File) {

        internal val json = JSONObject()
        private val quiet = HashSet<String>()

        fun put(key: String, value: Any?) {
            runCatching { json.put(key, value ?: JSONObject.NULL) }
        }

        fun put(key: String, value: Double) {
            runCatching { json.put(key, if (value.isFinite()) value else "NaN") }
        }

        fun section(name: String): JSONObject {
            val existing = runCatching { json.optJSONObject(name) }.getOrNull()
            if (existing != null) return existing
            val fresh = JSONObject()
            runCatching { json.put(name, fresh) }
            return fresh
        }

        /** Writes `name`.png. [bitmap] is not recycled (caller owns it). */
        fun png(name: String, bitmap: Bitmap?) {
            if (!imagesEnabled) return
            if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0) return
            runCatching {
                FileOutputStream(File(dir, "$name.png")).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
        }

        /** Writes an OpenCV Mat (8U, 1/3/4 channels) as `name.png`. */
        fun mat(name: String, mat: Mat?) {
            if (!imagesEnabled) return
            if (mat == null || mat.empty()) return
            var rgba: Mat? = null
            var bmp: Bitmap? = null
            try {
                val c = mat.channels()
                val src = when {
                    c == 1 -> mat
                    c == 4 -> mat
                    c == 3 -> {
                        rgba = Mat()
                        Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_BGR2RGBA)
                        rgba!!
                    }
                    else -> return
                }
                bmp = Bitmap.createBitmap(src.cols(), src.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(src, bmp)
                png(name, bmp)
            } catch (t: Throwable) {
                Diag.warn("Trace", "mat dump '$name' failed: ${t.message}")
            } finally {
                rgba?.release()
                bmp?.recycle()
            }
        }

        /**
         * Dumps the four ONNX corner-heat channels (and their max) for [heat] (flat `[4, plane]`
         * row-major). Also records per-channel peak value and pixel in `trace.json`.
         */
        fun heatDump(name: String, heat: FloatArray, plane: Int, frameW: Int, frameH: Int): Int {
            if (plane <= 0 || heat.size < 4 * plane) return 0
            val side = kotlin.math.sqrt(plane.toDouble()).toInt()
            if (side < 2 || side * side != plane) return 0
            val peaks = JSONArray()
            var globalMax = 1e-6f
            for (c in 0 until 4) {
                for (i in 0 until plane) {
                    val v = heat[c * plane + i]
                    if (v > globalMax) globalMax = v
                }
            }
            val channelBitmaps = ArrayList<Bitmap>(4)
            try {
                for (c in 0 until 4) {
                    var mx = 0f
                    var mi = 0
                    for (i in 0 until plane) {
                        val v = heat[c * plane + i]
                        if (v > mx) { mx = v; mi = i }
                    }
                    val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                    val px = IntArray(plane)
                    for (i in 0 until plane) {
                        val v = (heat[c * plane + i] / mx.coerceAtLeast(1e-6f))
                            .coerceIn(0f, 1f)
                        // Grayscale ramp: 0 -> black, 1 -> white. Kept linear so a viewer can read
                        // relative peak height, not a gamma-crushed picture.
                        val g = (v * 255f).toInt()
                        px[i] = Color.rgb(g, g, g)
                    }
                    bmp.setPixels(px, 0, side, 0, 0, side, side)
                    png("${name}_c$c", bmp)
                    channelBitmaps.add(bmp)
                    val peak = JSONObject()
                    peak.put("channel", c)
                    peak.put("peak", mx)
                    peak.put("px", mi % side)
                    peak.put("py", mi / side)
                    // Heat pixel -> source frame pixel (the same mapping the production
                    // postprocess uses: (px + 0.5) * frame / side).
                    peak.put("frame_x", (mi % side + 0.5) * frameW / side)
                    peak.put("frame_y", (mi / side + 0.5) * frameH / side)
                    peaks.put(peak)
                }

                // Max-over-channels composite: where ANY corner channel responds.
                val maxBmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                val mxPx = IntArray(plane)
                for (i in 0 until plane) {
                    var v = 0f
                    for (c in 0 until 4) v = maxOf(v, heat[c * plane + i])
                    val g = ((v / globalMax).coerceIn(0f, 1f) * 255f).toInt()
                    mxPx[i] = Color.rgb(g, g, g)
                }
                maxBmp.setPixels(mxPx, 0, side, 0, 0, side, side)
                png("${name}_max", maxBmp)

                // 2x2 labelled montage, one tile per corner channel (TL, TR, BR, BL), so the
                // shape of each predicted corner blobs is readable at a glance.
                val tile = side
                val pad = 18
                val montage = Bitmap.createBitmap(tile * 2 + pad * 3, tile * 2 + pad * 3,
                    Bitmap.Config.ARGB_8888)
                val canvas = Canvas(montage)
                canvas.drawColor(Color.rgb(24, 24, 28))
                val labelPaint = Paint().apply {
                    color = Color.WHITE
                    textSize = 14f
                    isAntiAlias = true
                }
                val labels = arrayOf("TL c0", "TR c1", "BR c2", "BL c3")
                for (c in 0 until 4) {
                    val col = c % 2
                    val row = c / 2
                    val left = pad + col * (tile + pad)
                    val top = pad + row * (tile + pad)
                    canvas.drawBitmap(channelBitmaps[c], null, Rect(left, top, left + tile, top + tile), null)
                    canvas.drawText(labels[c], left.toFloat(), (top - 4).toFloat(), labelPaint)
                }
                png("${name}_montage", montage)
                montage.recycle()

                val meta = section("corner_heat")
                meta.put("plane", plane)
                meta.put("side", side)
                meta.put("frame_w", frameW)
                meta.put("frame_h", frameH)
                meta.put("channels", peaks)
                meta.put("global_max", globalMax.toDouble())
                return side
            } catch (t: Throwable) {
                Diag.warn("Trace", "heat dump failed: ${t.message}")
                return 0
            } finally {
                for (b in channelBitmaps) if (!b.isRecycled) b.recycle()
            }
        }

        /**
         * Draws [quad] on a copy of [source] (scaled down for size) and saves `name.png`.
         * [extra] is an optional label drawn at the top-left.
         */
        fun quadOverlay(name: String, source: Bitmap, quad: Quad?, extra: String = "") {
            if (!imagesEnabled) return
            if (source.isRecycled) return
            var bmp: Bitmap? = null
            try {
                val maxEdge = 900
                val long = maxOf(source.width, source.height)
                bmp = if (long > maxEdge) {
                    val s = maxEdge.toDouble() / long
                    Bitmap.createScaledBitmap(
                        source,
                        (source.width * s).toInt().coerceAtLeast(1),
                        (source.height * s).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    source.copy(Bitmap.Config.ARGB_8888, true)
                }
                val canvas = Canvas(bmp)
                if (quad != null) {
                    val sx = bmp.width.toDouble() / source.width
                    val sy = bmp.height.toDouble() / source.height
                    val pts = quad.points.map { CvPoint(it.x * sx, it.y * sy) }
                    val line = Paint().apply {
                        color = Color.rgb(0, 220, 90)
                        style = Paint.Style.STROKE
                        strokeWidth = 4f
                        isAntiAlias = true
                    }
                    for (i in pts.indices) {
                        val a = pts[i]
                        val b = pts[(i + 1) % pts.size]
                        canvas.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), line)
                    }
                    val dot = Paint().apply {
                        color = Color.rgb(255, 60, 60)
                        style = Paint.Style.FILL
                    }
                    for (p in pts) canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 6f, dot)
                }
                if (extra.isNotEmpty()) {
                    val text = Paint().apply {
                        color = Color.WHITE
                        textSize = 20f
                        isAntiAlias = true
                        setShadowLayer(3f, 1f, 1f, Color.BLACK)
                    }
                    canvas.drawText(extra, 12f, 28f, text)
                }
                png(name, bmp)
            } catch (t: Throwable) {
                Diag.warn("Trace", "overlay dump '$name' failed: ${t.message}")
            } finally {
                if (bmp != null && bmp !== source) bmp.recycle()
            }
        }

        /** Overlay for the classical candidates: draws each quad with its source + score. */
        fun candidateOverlay(name: String, source: Bitmap, candidates: List<TraceQuad>) {
            if (source.isRecycled) return
            var bmp: Bitmap? = null
            try {
                val maxEdge = 900
                val long = maxOf(source.width, source.height)
                bmp = if (long > maxEdge) {
                    val s = maxEdge.toDouble() / long
                    Bitmap.createScaledBitmap(
                        source,
                        (source.width * s).toInt().coerceAtLeast(1),
                        (source.height * s).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    source.copy(Bitmap.Config.ARGB_8888, true)
                }
                val canvas = Canvas(bmp)
                val sx = bmp.width.toDouble() / source.width
                val sy = bmp.height.toDouble() / source.height
                val palette = intArrayOf(
                    Color.rgb(255, 210, 60), Color.rgb(255, 90, 90), Color.rgb(90, 200, 255),
                    Color.rgb(180, 120, 255), Color.rgb(120, 255, 160), Color.rgb(255, 150, 60)
                )
                candidates.take(12).forEachIndexed { i, c ->
                    val pts = c.quad.points.map { CvPoint(it.x * sx, it.y * sy) }
                    val paint = Paint().apply {
                        color = palette[i % palette.size]
                        style = Paint.Style.STROKE
                        strokeWidth = if (i == 0) 5f else 2f
                        isAntiAlias = true
                    }
                    for (k in pts.indices) {
                        val a = pts[k]
                        val b = pts[(k + 1) % pts.size]
                        canvas.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), paint)
                    }
                    if (pts.isNotEmpty()) {
                        val label = Paint().apply {
                            color = paint.color
                            textSize = 18f
                            isAntiAlias = true
                            setShadowLayer(3f, 1f, 1f, Color.BLACK)
                        }
                        canvas.drawText(
                            "${c.source} ${"%.2f".format(java.util.Locale.US, c.score)}",
                            pts[0].x.toFloat().coerceIn(0f, bmp.width - 120f),
                            pts[0].y.toFloat().coerceIn(16f, bmp.height - 6f),
                            label
                        )
                    }
                }
                png(name, bmp)
            } catch (t: Throwable) {
                Diag.warn("Trace", "candidate dump '$name' failed: ${t.message}")
            } finally {
                if (bmp != null && bmp !== source) bmp.recycle()
            }
        }

        internal fun finish() {
            runCatching {
                json.put("label", dir.name)
                File(dir, "trace.json").writeText(json.toString(2))
            }
        }
    }

    /** A candidate quad + its scores, for [Session.candidateOverlay]. */
    data class TraceQuad(val quad: Quad, val source: String, val score: Double)

    /**
     * Opens a trace scene (a folder under [root]). Returns `null` when tracing is disabled or the
     * per-run cap is reached — callers must treat that as "no tracing".
     */
    fun beginScene(label: String): Session? {
        val base = root ?: return null
        synchronized(this) {
            if (sceneCount >= maxScenes) return null
            sceneCount++
        }
        return try {
            val safe = label.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80).ifBlank { "scene" }
            var dir = File(base, safe)
            var n = 1
            while (dir.exists()) dir = File(base, "${safe}_${n++}")
            dir.mkdirs()
            val session = Session(dir)
            session.put("label", safe)
            session.put("timestamp", System.currentTimeMillis())
            session.put("device", android.os.Build.MODEL)
            session.put("sdk", android.os.Build.VERSION.SDK_INT)
            scenes.set(session)
            session
        } catch (t: Throwable) {
            Diag.warn("Trace", "beginScene failed: ${t.message}")
            null
        }
    }

    /** The scene currently open on this thread, if any. */
    fun current(): Session? = scenes.get()

    /** Closes the scene: flushes `trace.json` and clears the thread binding. */
    fun endScene() {
        val session = scenes.get() ?: return
        runCatching { session.finish() }
        scenes.remove()
    }

    /** Convenience for diagnostics: run [block] with a scene open. Never throws from tracing. */
    inline fun <T> scene(label: String, block: (Session?) -> T): T {
        val session = beginScene(label)
        return try {
            block(session)
        } finally {
            endScene()
        }
    }

    /** Resets the process-wide counters (tests call it between runs). */
    fun resetForTests() {
        synchronized(this) { sceneCount = 0 }
        scenes.remove()
    }

    /**
     * Saves a Mat as a normalised grayscale image (min/max from [min]/[max] or the Mat's own
     * range), for float maps such as MSLD line evidence. Kept here so every stage dumps the same
     * way.
     */
    fun normalizeTo8u(src: Mat, lo: Double = Double.NaN, hi: Double = Double.NaN): Mat {
        val out = Mat()
        if (src.empty()) return out
        var mn = lo
        var mx = hi
        if (mn.isNaN() || mx.isNaN()) {
            val mm = Core.minMaxLoc(src)
            mn = mm.minVal
            mx = mm.maxVal
        }
        val span = (mx - mn).coerceAtLeast(1e-6)
        Core.subtract(src, Scalar(mn), out)
        Core.multiply(out, Scalar(255.0 / span), out)
        out.convertTo(out, CvType.CV_8U)
        return out
    }
}
