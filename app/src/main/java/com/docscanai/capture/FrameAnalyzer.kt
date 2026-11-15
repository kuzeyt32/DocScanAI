package com.docscanai.capture

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.docscanai.core.util.BitmapUtils
import com.docscanai.core.util.Diag
import com.docscanai.data.Point
import com.docscanai.data.Quad
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * `ImageAnalysis.Analyzer` that turns each preview frame into a smoothed live document [Quad]
 * for the overlay and the auto-capture decision (TECHNICAL_SPEC.md §4.1, ARCHITECTURE.md §3).
 *
 * Per frame it:
 *  1. converts the [ImageProxy] to an upright bitmap and downscales it to [workingLongEdge];
 *  2. (recycles intermediates; closes the proxy immediately) then runs [PageDetector.detect] on a
 *     single background dispatch, dropping frames while busy (`KEEP_ONLY_LATEST`-friendly);
 *  3. **SUBTASK H13 page-ness gate**: the detected quad is scored by [PagePlausibility] on the
 *     ORIGINAL frame. A quad that fails is treated as NO DETECTION (no overlay, no auto-capture,
 *     no neural dewarp). [gatePass] is false on every failing frame — even while the smoothed
 *     overlay is still tolerated — so the auto-capture stability counter always resets;
 *  4. applies temporal smoothing: an average over the last [smoothingWindow] quads plus a
 *     per-corner jitter check that drives [stableFrames].
 *
 * Coordinates are emitted NORMALIZED to `[0,1]` in the rotated analysis frame; [frameAspectRatio]
 * lets the overlay letterbox/crop map them onto the preview surface. Allocation-light: exactly one
 * working bitmap per accepted frame, always recycled.
 */
class FrameAnalyzer(
    private val pageDetector: PageDetector,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val workingLongEdge: Int = 480,
    private val smoothingWindow: Int = 5,
    private val maxJitter: Float = 0.045f,
    private val lostFrameTolerance: Int = 5
) : ImageAnalysis.Analyzer {

    private val _quad = MutableStateFlow<Quad?>(null)

    /** Smoothed document quad in normalized `[0,1]` coordinates, or null when not detected. */
    val quad: StateFlow<Quad?> = _quad.asStateFlow()

    private val _stableFrames = MutableStateFlow(0)

    /** Consecutive frames the (jitter-checked) quad has been stable. Resets on loss/jitter. */
    val stableFrames: StateFlow<Int> = _stableFrames.asStateFlow()

    private val _gatePass = MutableStateFlow(false)

    /**
     * H13: true only for a frame whose detected quad passed [PagePlausibility]. Unlike [quad]
     * (which tolerates [lostFrameTolerance] misses for overlay smoothing) this is **false on every
     * failing frame**, so the auto-capture state machine resets its stability window immediately.
     */
    val gatePass: StateFlow<Boolean> = _gatePass.asStateFlow()

    private val _gateScore = MutableStateFlow(0.0)

    /** Latest [PagePlausibility] score (0.0 when there was no detection). Logging/UI only. */
    val gateScore: StateFlow<Double> = _gateScore.asStateFlow()

    private val _autoCaptureEligible = MutableStateFlow(false)

    /**
     * SUBTASK H21: true only when the gate ACCEPTED the frame **and** the quad is trustworthy
     * enough to capture without the user asking (a model detection, or a fallback that proved
     * text-line structure). The overlay may still show a gate-passing quad for which this is
     * false; auto-capture must not fire on it.
     */
    val autoCaptureEligible: StateFlow<Boolean> = _autoCaptureEligible.asStateFlow()

    private val _frameSignature = MutableStateFlow<ByteArray?>(null)

    /**
     * SUBTASK H21: cheap 16×16 luminance fingerprint of the analysis frame. The state machine
     * uses it (together with quad IoU) to suppress a repeated capture of an unchanged scene.
     */
    val frameSignature: StateFlow<ByteArray?> = _frameSignature.asStateFlow()

    private val _frameMotion = MutableStateFlow(0.0)

    /**
     * SUBTASK H21 (field feedback): normalised 0..1 mean-absolute-difference between this frame
     * and the previous one. Above [MOTION_SKIP_MAD] the phone is considered to be moving:
     * detection is skipped and auto-capture is blocked, so the camera only "sees" a document once
     * the user actually holds still.
     */
    val frameMotion: StateFlow<Double> = _frameMotion.asStateFlow()

    private val _frameTimestampMs = MutableStateFlow(0L)

    /**
     * SUBTASK H21 (field feedback): `SystemClock.elapsedRealtime()` of the analysed frame. Lets
     * [CaptureStateMachine] enforce a real minimum hold TIME instead of counting frames — three
     * frames at 30 fps is only ~100 ms, which is why auto-capture felt far too fast on a phone.
     */
    val frameTimestampMs: StateFlow<Long> = _frameTimestampMs.asStateFlow()

    private val _frameAspectRatio = MutableStateFlow(0f)

    /** Aspect ratio (w/h) of the analyzed frame; used to map the overlay onto the preview. */
    val frameAspectRatio: StateFlow<Float> = _frameAspectRatio.asStateFlow()

    /**
     * SUBTASK H31 — the detector's M-LSD trigger needs three things this class already knows:
     * whether the phone is moving (H21 motion guard), how many frames the quad has been stable, and
     * the previous frame's paper/text evidence. It is fed through the detector's [mlsdHint] so the
     * optional model stays behind the same seam it had in H30.
     */
    private val mlsdAware: com.docscanai.capture.OpenCvQuadPageDetector? =
        pageDetector as? com.docscanai.capture.OpenCvQuadPageDetector

    /** Last gate evaluation, kept only to supply `paper`/`text` to the next frame's trigger. */
    private var lastGate: com.docscanai.capture.PagePlausibility.Result? = null

    private val busy = AtomicBoolean(false)
    // Guards the (non-thread-safe) ArrayDeque + lostFrames: frames are appended from the analysis
    // dispatcher while [release] may run on the main thread during teardown.
    private val stateLock = Any()
    private val recent = ArrayDeque<Quad>()
    private var lostFrames = 0

    /** Fingerprint of the previous frame, for the motion pre-gate. */
    private var lastSignature: ByteArray? = null

    /** Rate-limits the "detection skipped (phone moving)" diagnostics to once a second. */
    private var lastMotionLogAtMs = 0L

    override fun analyze(image: ImageProxy) {
        val frame: Bitmap? = try {
            val full = BitmapUtils.imageProxyToBitmap(image)
            if (full == null) null else BitmapUtils.downscaleToLongEdge(full, workingLongEdge)
        } catch (t: Throwable) {
            null
        }
        // The proxy must be closed promptly; we already own an independent bitmap copy.
        image.close()

        val working = frame ?: return
        _frameAspectRatio.value = working.width.toFloat() / working.height.toFloat()
        _frameTimestampMs.value = SystemClock.elapsedRealtime()

        if (!busy.compareAndSet(false, true)) {
            working.recycle() // drop frame while a detection is in flight
            return
        }

        // Read the stability counter BEFORE this frame can change it: the trigger wants "how many
        // frames has the quad been still", which is the previous frame's value.
        val settledBefore = _stableFrames.value
        val gateBefore = lastGate

        scope.launch(dispatcher) {
            // SUBTASK H21 (field feedback): "page detection should not start until the phone
            // seems steady enough". Measure the frame-to-frame content change FIRST — 256 byte
            // reads — and skip the detector entirely while the scene is moving. This follows the
            // user's hand and removes the whole detection cost on frames nothing could use.
            val signature = signatureOf(working)
            val motion = motionMad(lastSignature, signature)
            lastSignature = signature
            _frameSignature.value = signature
            _frameMotion.value = motion ?: 0.0
            // SUBTASK H31: motion is a hard "do not spend neural time here" signal, for the
            // detector's optional model as well as for the corner model below.
            mlsdAware?.mlsdHint = com.docscanai.ml.MlsdTrigger.Hint(
                moving = motion != null && motion > MOTION_SKIP_MAD,
                settledFrames = settledBefore,
                still = false,
                hasEvidence = gateBefore != null,
                paperScore = gateBefore?.paperScore ?: 0.0,
                textScore = gateBefore?.textScore ?: 0.0
            )
            if (motion != null && motion > MOTION_SKIP_MAD) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastMotionLogAtMs >= MOTION_LOG_INTERVAL_MS) {
                    lastMotionLogAtMs = now
                    Diag.log(
                        "FrameAnalyzer",
                        String.format(
                            java.util.Locale.US,
                            "motion mad=%.3f > %.3f -> detection skipped (phone moving)",
                            motion, MOTION_SKIP_MAD
                        )
                    )
                }
                _gatePass.value = false
                _autoCaptureEligible.value = false
                _gateScore.value = 0.0
                try {
                    updateSmoothing(null)
                } finally {
                    working.recycle()
                    busy.set(false)
                }
                return@launch
            }

            val raw = try {
                pageDetector.detect(working)
            } catch (t: Throwable) {
                null
            }
            // SUBTASK H21: the gate must know WHERE the quad came from — a classical-fallback
            // construct is a guess and has to prove text-line structure before it can pass.
            val provenance = pageDetector.lastProvenance
            // H13: content page-ness gate on the ORIGINAL analysis frame. Fail-closed: an
            // evaluation error returns passed=false, which is treated exactly like no detection.
            val gate = raw?.let { PagePlausibility.evaluate(working, it, "live", provenance) }
            lastGate = gate
            val detected = if (gate == null || gate.passed) raw else null
            _gatePass.value = detected != null
            _autoCaptureEligible.value = gate?.autoCaptureEligible == true
            _gateScore.value = gate?.score ?: 0.0
            try {
                val normalized = detected?.let { normalize(it, working.width, working.height) }
                updateSmoothing(normalized)
            } finally {
                working.recycle()
                busy.set(false)
            }
        }
    }

    /** Clears transient state (call on teardown). Does not release the shared [PageDetector]. */
    fun release() {
        synchronized(stateLock) {
            recent.clear()
            lostFrames = 0
            lastSignature = null
        }
        // SUBTASK H31: the next live session must not inherit stale paper/text evidence.
        lastGate = null
        _quad.value = null
        _stableFrames.value = 0
        _gatePass.value = false
        _gateScore.value = 0.0
        _autoCaptureEligible.value = false
        _frameSignature.value = null
        _frameMotion.value = 0.0
        _frameTimestampMs.value = 0L
    }

    /**
     * H21: normalised mean-absolute-difference between two 16×16 fingerprints (0..1). `null`
     * when the comparison is impossible, which is never interpreted as "still".
     */
    private fun motionMad(a: ByteArray?, b: ByteArray?): Double? {
        if (a == null || b == null || a.size != b.size || a.isEmpty()) return null
        var sum = 0L
        for (i in a.indices) {
            sum += kotlin.math.abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
        }
        return sum.toDouble() / a.size / 255.0
    }

    /**
     * SUBTASK H21: 16×16 luminance fingerprint, read from the SAME working frame the gate already
     * uses (no extra decode). ~0.1 ms. Returns null on failure (duplicate suppression then
     * degrades to IoU-only rather than throwing).
     */
    private fun signatureOf(bitmap: Bitmap): ByteArray? = try {
        val small = Bitmap.createScaledBitmap(bitmap, SIG_EDGE, SIG_EDGE, true)
        try {
            val pixels = IntArray(SIG_EDGE * SIG_EDGE)
            small.getPixels(pixels, 0, SIG_EDGE, 0, 0, SIG_EDGE, SIG_EDGE)
            ByteArray(pixels.size) { i ->
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                ((r * 77 + g * 150 + b * 29) shr 8).toByte()
            }
        } finally {
            if (small !== bitmap) small.recycle()
        }
    } catch (t: Throwable) {
        null
    }

    private fun updateSmoothing(quad: Quad?) {
        synchronized(stateLock) {
            if (quad == null) {
                lostFrames++
                if (lostFrames >= lostFrameTolerance) {
                    recent.clear()
                    _quad.value = null
                    _stableFrames.value = 0
                }
                return
            }
            lostFrames = 0
            recent.addLast(quad)
            while (recent.size > smoothingWindow) recent.removeFirst()

            val smoothed = averageQuads(recent)
            _quad.value = smoothed

            val jitter = maxCornerDelta(smoothed, quad)
            _stableFrames.value = if (jitter <= maxJitter) {
                (_stableFrames.value + 1).coerceAtMost(120)
            } else {
                0
            }
        }
    }

    private fun normalize(quad: Quad, width: Int, height: Int): Quad {
        val fw = width.toFloat().coerceAtLeast(1f)
        val fh = height.toFloat().coerceAtLeast(1f)
        return Quad(
            topLeft = Point(quad.topLeft.x / fw, quad.topLeft.y / fh),
            topRight = Point(quad.topRight.x / fw, quad.topRight.y / fh),
            bottomRight = Point(quad.bottomRight.x / fw, quad.bottomRight.y / fh),
            bottomLeft = Point(quad.bottomLeft.x / fw, quad.bottomLeft.y / fh)
        )
    }

    private fun averageQuads(quads: Collection<Quad>): Quad {
        val n = quads.size.toFloat().coerceAtLeast(1f)
        fun avg(selector: (Quad) -> Point): Point = Point(
            quads.sumOf { selector(it).x.toDouble() }.toFloat() / n,
            quads.sumOf { selector(it).y.toDouble() }.toFloat() / n
        )
        return Quad(
            topLeft = avg { it.topLeft },
            topRight = avg { it.topRight },
            bottomRight = avg { it.bottomRight },
            bottomLeft = avg { it.bottomLeft }
        )
    }

    private fun maxCornerDelta(a: Quad, b: Quad): Float {
        var maxDelta = 0f
        a.points.zip(b.points).forEach { (p, q) ->
            val d = hypot((p.x - q.x).toDouble(), (p.y - q.y).toDouble()).toFloat()
            if (d > maxDelta) maxDelta = d
        }
        return maxDelta
    }

    private companion object {
        /** SUBTASK H21: edge length of the duplicate-suppression luminance fingerprint. */
        const val SIG_EDGE = 16

        /**
         * H21 field feedback: motion above which the frame is treated as "phone is moving" and
         * detection is skipped. 0.06 ≈ 15 grey levels of average change — well above sensor noise
         * and autofocus breathing, well below a real pan or a page being turned.
         */
        const val MOTION_SKIP_MAD = 0.06

        /** Rate limit for the "detection skipped" log line. */
        const val MOTION_LOG_INTERVAL_MS = 1000L
    }
}
