package com.docscanai.capture

import com.docscanai.data.Quad
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * SUBTASK H13 — temporal stability requirement for AUTO-capture.
 *
 * A page must be *held steady* before the camera fires:
 *  - the last [requiredFrames] consecutive evaluations must all have passed the page-ness gate
 *    (a failing frame resets the counter), and
 *  - every consecutive pair of quads in that window must agree by IoU ≥ [minIou].
 *
 * Pure Kotlin (no Android/OpenCV) on purpose: the logic is unit-tested directly on the JVM
 * ([com.docscanai.capture.AutoCaptureStabilityTest]) *and* instrumented on-device.
 *
 * NOTE: this class only governs AUTOMATIC capture. The manual shutter never consults it.
 */
class AutoCaptureStability(
    val requiredFrames: Int = DEFAULT_REQUIRED_FRAMES,
    val minIou: Double = DEFAULT_MIN_IOU
) {

    private val window = ArrayDeque<Quad>()

    /** Consecutive accepted frames currently in the window (0 .. [requiredFrames]). */
    val stableCount: Int
        @Synchronized get() = window.size

    /** The most recent accepted quad, or null. */
    val lastQuad: Quad?
        @Synchronized get() = window.peekLast()

    /** Clears the window (called on capture, on loss of the document, and on teardown). */
    @Synchronized
    fun reset() {
        window.clear()
    }

    /**
     * Feeds one frame.
     *
     * @param quad the gate-passing quad for this frame, or `null` when there was no detection /
     *   the page-ness gate rejected it. A null resets the counter immediately.
     * @return the decision for this frame; [Decision.fire] is true only when the window is full.
     */
    @Synchronized
    fun update(quad: Quad?): Decision {
        if (quad == null) {
            window.clear()
            return Decision(0, requiredFrames, 0.0, false)
        }

        val last = window.peekLast()
        // Measured agreement with the previous accepted quad (1.0 for the first frame).
        val iou = if (last == null) 1.0 else iouOf(last, quad)
        if (last != null && iou < minIou) {
            // The quad jumped: the user is not holding the same document steady. Restart the
            // hold from THIS frame (it is itself a legitimate gate-passing observation). The
            // reported iou stays the measured value so the reject is auditable in logcat.
            window.clear()
        }
        window.addLast(quad)
        while (window.size > requiredFrames) window.removeFirst()
        return Decision(window.size, requiredFrames, iou, window.size >= requiredFrames)
    }

    /** Per-frame stability verdict, logged by [CaptureStateMachine]. */
    data class Decision(
        val stableCount: Int,
        val requiredFrames: Int,
        val iou: Double,
        val fire: Boolean
    )

    companion object {
        const val DEFAULT_REQUIRED_FRAMES = 3
        const val DEFAULT_MIN_IOU = 0.85

        /** Intersection-over-union of two convex, ordered quads (0..1). Never throws. */
        fun iouOf(a: Quad, b: Quad): Double {
            return try {
                val polyA = a.points.map { doubleArrayOf(it.x.toDouble(), it.y.toDouble()) }
                val polyB = b.points.map { doubleArrayOf(it.x.toDouble(), it.y.toDouble()) }
                val areaA = areaOf(polyA)
                val areaB = areaOf(polyB)
                if (areaA <= 1e-9 || areaB <= 1e-9) {
                    0.0
                } else {
                    val intersection = areaOf(clipToConvex(polyA, polyB))
                    val union = areaA + areaB - intersection
                    if (union <= 1e-9) 0.0 else (intersection / union).coerceIn(0.0, 1.0)
                }
            } catch (t: Throwable) {
                0.0
            }
        }

        private fun signedArea(poly: List<DoubleArray>): Double {
            if (poly.size < 3) return 0.0
            var sum = 0.0
            for (i in poly.indices) {
                val p = poly[i]
                val q = poly[(i + 1) % poly.size]
                sum += p[0] * q[1] - q[0] * p[1]
            }
            return sum / 2.0
        }

        private fun areaOf(poly: List<DoubleArray>): Double = abs(signedArea(poly))

        private fun cross(a: DoubleArray, b: DoubleArray, p: DoubleArray): Double =
            (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])

        /** Sutherland–Hodgman clip of a convex subject polygon by a convex clip polygon. */
        private fun clipToConvex(
            subject: List<DoubleArray>,
            clip: List<DoubleArray>
        ): List<DoubleArray> {
            if (subject.isEmpty() || clip.size < 3) return emptyList()
            var output: List<DoubleArray> = subject
            val ccw = signedArea(clip) >= 0.0

            fun inside(p: DoubleArray, a: DoubleArray, b: DoubleArray): Boolean {
                val c = cross(a, b, p)
                return if (ccw) c >= -1e-9 else c <= 1e-9
            }

            fun intersection(p: DoubleArray, q: DoubleArray, a: DoubleArray, b: DoubleArray): DoubleArray {
                val x1 = p[0]; val y1 = p[1]
                val x2 = q[0]; val y2 = q[1]
                val x3 = a[0]; val y3 = a[1]
                val x4 = b[0]; val y4 = b[1]
                val den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
                if (abs(den) < 1e-12) return p.copyOf()
                val t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / den
                return doubleArrayOf(x1 + t * (x2 - x1), y1 + t * (y2 - y1))
            }

            for (i in clip.indices) {
                if (output.isEmpty()) break
                val a = clip[i]
                val b = clip[(i + 1) % clip.size]
                val input = output
                val next = ArrayList<DoubleArray>(input.size + 1)
                for (j in input.indices) {
                    val cur = input[j]
                    val prev = input[(j + input.size - 1) % input.size]
                    val curIn = inside(cur, a, b)
                    val prevIn = inside(prev, a, b)
                    when {
                        curIn && prevIn -> next.add(cur)
                        curIn && !prevIn -> {
                            next.add(intersection(prev, cur, a, b))
                            next.add(cur)
                        }
                        !curIn && prevIn -> next.add(intersection(prev, cur, a, b))
                    }
                }
                output = next
            }
            return output
        }
    }
}
