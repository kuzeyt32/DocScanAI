package com.docscanai.ml

import android.graphics.Bitmap
import com.docscanai.core.util.Diag
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * ============================================================================================
 * SUBTASK H30 — M-LSD (NAVER, Apache-2.0) as an optional on-device line-evidence provider.
 * ============================================================================================
 *
 * **Model:** PINTO model zoo `119_M-LSD`, `saved_model_320x320_large/model_float32.onnx`
 * (6,170,288 B, SHA-256 `26c4d103…19e2a`). Contract (verified with `tooling/OnnxInspect.java` and
 * the H30 offline run):
 *  - input  `input_image_with_alpha:0` `[1, 320, 320, 4]` float32, **NHWC**, 0-255, alpha = 1;
 *  - outputs: junctions `[1,200,2]` int32 (row, col in the 160x160 maps), scores `[1,200]`
 *    float32, displacement `[1,160,160,4]` float32.
 *
 * The 320 variant is the one that ships: at 512 the offline run used 6.17 MB of the same weights
 * but scored *worse* after the merge rule (1/12 merged slivers vs 0/12) and costs 2.5x the model
 * time, so there is nothing to buy by paying for it.
 *
 * **Optional by construction.** `ServiceLocator.mlsdModel` returns `null` whenever the artifact
 * has not been downloaded, and every caller treats `null` as "no line evidence" — the app then
 * behaves exactly as 0.3.8. Nothing here runs unless a model is present *and* the detector asks
 * for it.
 *
 * No OpenCV, no new Gradle dependency: preprocessing is `Bitmap.createScaledBitmap` + a float
 * buffer, and the post-processing is [MlsdGeometry].
 */
class MlsdModel(
    private val model: OnnxModel,
    /** Square edge the graph was exported for (320 for the shipped artifact). */
    val inputEdge: Int = DEFAULT_EDGE,
    private val params: MlsdGeometry.Params = MlsdGeometry.Params()
) {

    /** One analysed frame: line segments and squares, all in **frame pixels**. */
    class Result(
        val segments: List<MlsdGeometry.Seg>,
        val squares: List<MlsdGeometry.Square>,
        val modelMs: Long,
        val postMs: Long,
        val truncated: Boolean,
        /**
         * SUBTASK H31: `Bitmap.createScaledBitmap` + the NHWC float pack. Split out because on the
         * still path it is a 3024x4032 -> 320 downscale, which H30 folded into `modelMs` and then
         * had to explain; the trigger can skip the whole call but not this part.
         */
        val prepMs: Long = 0
    ) {
        /** Best square by NAVER score + line support, or `null`. */
        val bestSquare: MlsdGeometry.Square? get() = squares.firstOrNull()
    }

    /** Releases the underlying ORT session. Safe to call more than once. */
    fun close() {
        model.close()
    }

    /** True when the loaded graph accepts the expected `[1, edge, edge, 4]` input. */
    val inputEdgeMatchesGraph: Boolean
        get() {
            val shape = model.inputShape ?: return true // unknown: let the first run decide
            return shape.size != 4 || shape[1] <= 0 || shape[1].toInt() == inputEdge
        }

    /**
     * Runs inference + post-processing. Returns `null` on any failure (closed session, bad tensor,
     * empty output) — never throws, because this sits on the live detection path.
     */
    fun analyze(bitmap: Bitmap): Result? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val edge = inputEdge
        val t0 = System.nanoTime()
        val input = preprocess(bitmap, edge) ?: return null
        val prepMs = (System.nanoTime() - t0) / 1_000_000
        val t1Infer = System.nanoTime()
        val outputs = model.runAllOutputs(input, longArrayOf(1L, edge.toLong(), edge.toLong(), 4L))
            ?: return null
        val modelMs = (System.nanoTime() - t1Infer) / 1_000_000

        val junctions = outputs.values.filterIsInstance<IntArray>().maxByOrNull { it.size }
            ?: return null
        val floats = outputs.values.filterIsInstance<FloatArray>()
        // The displacement map is the 4-channel one; the score array is the other.
        val vmap = floats.firstOrNull { it.size == (edge / 2) * (edge / 2) * 4 }
            ?: floats.maxByOrNull { it.size } ?: return null
        val scores = floats.firstOrNull { it !== vmap && it.size >= junctions.size / 2 }
            ?: return null
        val mapEdge = edge / 2

        val t1 = System.nanoTime()
        val raw = MlsdGeometry.decodeJunctions(
            junctions, scores, vmap, mapEdge, mapEdge, params.score
        )
        val mergedMap = MlsdGeometry.mergeSegments(raw, junctions.size / 2)
        val (squaresMap, truncated) =
            MlsdGeometry.findSquares(mergedMap, edge, params)
        if (truncated) {
            Diag.warn(TAG, "square enumeration hit the candidate cap; treating as no evidence")
            return Result(
                emptyList(), emptyList(), modelMs, (System.nanoTime() - t1) / 1_000_000, true, prepMs
            )
        }

        // Map -> frame, and re-rank squares by NAVER score + our own line support.
        val segments = mergedMap.map { MlsdGeometry.mapToFrame(it, bitmap.width, bitmap.height, edge) }
        val ranked = squaresMap.map { sq ->
            val frameCorners = sq.corners.map {
                MlsdGeometry.mapToFrame(it, bitmap.width, bitmap.height, edge)
            }
            val support = MlsdGeometry.quadLineSupport(frameCorners, segments)
            MlsdGeometry.Square(frameCorners, sq.score, support)
        }.sortedWith(
            compareByDescending<MlsdGeometry.Square> { it.score + 0.5 * it.support }
                .thenByDescending { it.support }
        )
        val postMs = (System.nanoTime() - t1) / 1_000_000
        Diag.log(
            TAG,
            "mlsd edge=$edge junctions=${junctions.size / 2} segments=${segments.size} " +
                "squares=${ranked.size} prepMs=$prepMs modelMs=$modelMs postMs=$postMs"
        )
        return Result(segments, ranked, modelMs, postMs, false, prepMs)
    }

    /**
     * `Bitmap` -> `[1, edge, edge, 4]` NHWC float, 0-255, alpha = 1.
     *
     * The corner model wants NCHW RGB/255 with no alpha (see `preprocessForCornerModel`); the
     * contracts are not interchangeable (H29 §7), so this is deliberately a separate function.
     */
    private fun preprocess(bitmap: Bitmap, edge: Int): FloatBuffer? {
        if (edge <= 0) return null
        val scaled = if (bitmap.width == edge && bitmap.height == edge) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, edge, edge, true)
        }
        val pixels = IntArray(edge * edge)
        try {
            scaled.getPixels(pixels, 0, edge, 0, 0, edge, edge)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        val plane = edge * edge
        val buffer = FloatBuffer.allocate(plane * 4)
        var i = 0
        for (p in pixels) {
            buffer.put((p shr 16 and 0xFF).toFloat())
            buffer.put((p shr 8 and 0xFF).toFloat())
            buffer.put((p and 0xFF).toFloat())
            buffer.put(1f)
            i += 4
        }
        buffer.rewind()
        return buffer
    }

    companion object {
        private const val TAG = "Mlsd"

        /** Edge of the shipped artifact (`saved_model_320x320_large`). */
        const val DEFAULT_EDGE = 320

        /** Edge of the larger candidate, kept so an A/B is possible without a rebuild. */
        const val EDGE_512 = 512

        /**
         * Picks the graph edge from the model's declared input shape, so swapping in the 512
         * artifact (or a future export) does not need a code change. Falls back to [DEFAULT_EDGE].
         */
        fun edgeFor(model: OnnxModel): Int {
            val shape = model.inputShape
            if (shape != null && shape.size == 4 && shape[1] in longArrayOf(320L, 512L)) {
                return shape[1].toInt()
            }
            return DEFAULT_EDGE
        }

        /** Smallest edge the post-processing can work with (the maps must not collapse). */
        const val MIN_EDGE = 160

        fun clampEdge(edge: Int): Int = max(MIN_EDGE, min(edge, EDGE_512))
    }
}
