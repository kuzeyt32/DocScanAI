package com.docscanai.capture

import android.os.SystemClock
import com.docscanai.core.util.Diag
import com.docscanai.data.Quad
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The vFlat-style fast multi-page capture states (TECHNICAL_SPEC.md §6.3, ARCHITECTURE.md §5):
 *
 * ```
 * Idle -> Detecting -> Locked -> Capturing -> Processing -> Thumbnail1s -> Detecting
 *                 ^------------------- page lost / unstable ------------------|
 * ```
 *
 * - [onCameraBound]/[onCameraReleased] track the camera lifecycle.
 * - [onQuadUpdate] drives the `Detecting <-> Locked` transitions and auto-capture.
 * - [requestManualCapture] is the shutter path.
 * - [onStillCaptured]/[onProcessingComplete] advance `Capturing -> Processing -> Thumbnail1s`,
 *   after which the machine waits [thumbnailDurationMs] (~1 s) and returns to `Detecting`.
 *
 * ## SUBTASK H13 — auto-capture is now HARD-gated
 * [onQuadUpdate] only ever receives a quad that **passed [PagePlausibility]** (the analyzer emits
 * `null` on any gate failure). On top of that, auto-capture requires **temporal stability**:
 * [stableFramesRequired] consecutive passing frames whose quads agree by IoU ≥ [minQuadIou]
 * ([AutoCaptureStability]). A single failing frame resets the counter. Only then does the machine
 * fire, still throttled by [autoCaptureCooldownMs].
 *
 * ## SUBTASK H21 — field feedback: "it fired while the pages were still moving"
 * Testing the shipped build on a physical phone showed the H13 rule is necessary but not
 * sufficient: the quad can stay perfectly still while the *content* changes (a page being turned,
 * a book being closed, someone walking past). Auto-capture therefore additionally requires
 *
 *  1. **content stillness** — consecutive 16×16 fingerprints must differ by a normalised
 *     mean-absolute-difference ≤ [MOTION_MAD]. Quad agreement alone can never see a page turn
 *     inside a stationary quad. Only checked when the analyzer supplies a fingerprint (`null`
 *     means "unknown", which is never treated as movement *or* as proof of stillness); and
 *  2. a **minimum hold time** — [minHoldMs] of real elapsed time with the page held still, because
 *     three frames at 30 fps is ~100 ms: far too fast for a human to even see the lock appear.
 *     A human cannot hold a phone perfectly still for a second, so the bar is deliberately modest.
 *     The timer needs real frame timestamps; a caller without a clock (synthetic test lanes) gets
 *     the rule skipped and logged as `held=-1ms`, never a silent pass.
 *
 * The **manual shutter always works**: [requestManualCapture] bypasses the page-ness gate, the
 * stability window and the cooldown — explicit user intent always wins.
 *
 * Consumers collect [state], [indicator] (visible "Document detected" / "Hold steady" hint) and
 * [captureRequests]; the latter emits a request the camera layer fulfils by taking a still.
 */
enum class CaptureState { IDLE, DETECTING, LOCKED, CAPTURING, PROCESSING, THUMBNAIL_1S }

/** User-visible auto-capture hint. */
enum class CaptureIndicator {
    /** Nothing page-like in view. */
    NONE,

    /** A page-like quad passed the gate — keep the camera pointed at it. */
    DOCUMENT_DETECTED,

    /** The document has been steady long enough that a capture is imminent. */
    HOLD_STEADY
}

class CaptureStateMachine(
    private val scope: CoroutineScope,
    private val stableFramesRequired: Int = AutoCaptureStability.DEFAULT_REQUIRED_FRAMES,
    private val autoCaptureCooldownMs: Long = DEFAULT_POST_CAPTURE_COOLDOWN_MS,
    private val thumbnailDurationMs: Long = DEFAULT_THUMBNAIL_MS,
    minQuadIou: Double = AutoCaptureStability.DEFAULT_MIN_IOU,
    private val minHoldMs: Long = DEFAULT_MIN_HOLD_MS
) {

    /** H13: consecutive gate-passing frames with IoU agreement — the auto-capture prerequisite. */
    private val stability = AutoCaptureStability(stableFramesRequired, minQuadIou)

    private val _state = MutableStateFlow(CaptureState.IDLE)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _indicator = MutableStateFlow(CaptureIndicator.NONE)

    /** Visible hint state: `NONE` / `DOCUMENT_DETECTED` / `HOLD_STEADY`. */
    val indicator: StateFlow<CaptureIndicator> = _indicator.asStateFlow()

    private val _captureRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once per capture (auto or manual) that the camera layer should fulfil. */
    val captureRequests: SharedFlow<Unit> = _captureRequests.asSharedFlow()

    /** Auto-capture toggle (mirrors `SettingsRepository.autoCaptureEnabled`). Default ON. */
    @Volatile
    var autoCaptureEnabled: Boolean = true

    private var lastCaptureAtMs = 0L
    private var thumbnailJob: Job? = null

    // --- SUBTASK H21: duplicate-capture suppression -----------------------------------------
    /** Fingerprint of the analysis frame currently being evaluated (16×16 luminance). */
    private var currentSignature: ByteArray? = null

    /** Quad + fingerprint of the LAST accepted capture (auto or manual). */
    private var lastCaptureQuad: Quad? = null
    private var lastCaptureSignature: ByteArray? = null

    // --- SUBTASK H21 field feedback: content motion + minimum hold time ---------------------
    /** Fingerprint of the previous frame, for the content-motion guard. */
    private var lastSignature: ByteArray? = null

    /**
     * Wall-clock start of the current unbroken hold (`SystemClock.elapsedRealtime()` of the first
     * stable frame), or `0L` when the hold is empty **or** the caller supplied no timestamps.
     */
    private var holdStartMs = 0L

    /** Frame timestamp of the most recent evaluation, on the same clock as [holdStartMs]. */
    private var lastFrameTsMs = 0L

    /** Call when the camera use cases are bound -> leave `Idle`. */
    fun onCameraBound() {
        if (_state.value == CaptureState.IDLE) _state.value = CaptureState.DETECTING
    }

    /** Call when the camera is released -> back to `Idle` and cancel any pending timer. */
    fun onCameraReleased() {
        thumbnailJob?.cancel()
        thumbnailJob = null
        stability.reset()
        _indicator.value = CaptureIndicator.NONE
        _state.value = CaptureState.IDLE
    }

    /**
     * SUBTASK H38 — user-requested detector reset (the "stuck after a miss" recovery): drop every
     * piece of capture-side state that could hold a stale decision — the stability window, the
     * minimum-hold clock, the cooldown clock and the duplicate-capture fingerprint — and return
     * to `Detecting` so a capture can fire immediately again. Safe to call at any time from the UI
     * thread; the detector-side caches are cleared separately by `OpenCvQuadPageDetector.reset()`.
     */
    @Synchronized
    fun reset() {
        thumbnailJob?.cancel()
        thumbnailJob = null
        stability.reset()
        holdStartMs = 0L
        lastFrameTsMs = 0L
        lastSignature = null
        currentSignature = null
        lastCaptureQuad = null
        lastCaptureSignature = null
        lastCaptureAtMs = 0L
        _indicator.value = CaptureIndicator.NONE
        _state.value = CaptureState.DETECTING
    }

    /**
     * Feed the latest **gate-passing** detection result.
     *
     * @param quad the smoothed document quad, or `null` when there is no detection, **the
     *   page-ness gate rejected the frame**, or the scene is moving. A null frame resets the
     *   stability counter and the auto-capture hint.
     * @param gateScore the [PagePlausibility] score for this frame (logging only).
     * @param frameSignature 16×16 luminance fingerprint of the analysed frame, or `null` when the
     *   caller cannot provide one (motion guard then degrades to quad-only, as in H13).
     * @param frameTimestampMs `SystemClock.elapsedRealtime()` for this frame, or `null` when the
     *   caller has no clock (synthetic lanes) — the minimum-hold rule is then skipped.
     */
    fun onQuadUpdate(
        quad: Quad?,
        gateScore: Double = 0.0,
        frameSignature: ByteArray? = null,
        frameTimestampMs: Long? = null
    ) {
        currentSignature = frameSignature
        if (frameTimestampMs != null) lastFrameTsMs = frameTimestampMs
        // H21 (field feedback): the quad can be rock-steady while the page under it is being
        // turned or the book is being closed. If the CONTENT moved, this frame cannot confirm a
        // hold — feed null so the stability window restarts exactly as it does for a gate miss.
        val motion = motionMad(lastSignature, frameSignature)
        lastSignature = frameSignature
        val moving = motion != null && motion > MOTION_MAD
        if (moving) {
            Diag.log(
                "AutoCapture",
                String.format(
                    Locale.US,
                    "motion mad=%.3f > %.3f -> hold reset",
                    motion ?: 0.0, MOTION_MAD
                )
            )
            // Discard the history but keep THIS frame as the first observation of the new
            // position. A scene that keeps moving therefore never gets past one stable frame,
            // while a page that merely changed once continues from here.
            stability.reset()
        }
        val decision = stability.update(quad)
        // The dwell timer starts on the first frame of an unbroken hold. `0L` means "no clock",
        // which the auto-capture path reports instead of pretending the hold was long enough.
        holdStartMs = when {
            decision.stableCount <= 0 -> 0L
            decision.stableCount == 1 || holdStartMs == 0L -> frameTimestampMs ?: 0L
            else -> holdStartMs
        }
        updateIndicator(decision)

        when (_state.value) {
            CaptureState.DETECTING -> {
                if (quad != null && decision.stableCount > 0) {
                    _state.value = CaptureState.LOCKED
                    maybeAutoCapture(decision, gateScore)
                }
            }
            CaptureState.LOCKED -> {
                if (quad == null || decision.stableCount == 0) {
                    _state.value = CaptureState.DETECTING
                } else {
                    maybeAutoCapture(decision, gateScore)
                }
            }
            else -> Unit // capture is blocked until we return to Detecting
        }
    }

    /**
     * Manual shutter. Only honoured from `Detecting`/`Locked`.
     *
     * **The gate does NOT apply here.** The page-ness gate exists to stop UNATTENDED captures of
     * clutter; when the user presses the shutter, the capture always happens (the pipeline still
     * skips a neural dewarp of an implausible frame, but the page is saved).
     */
    fun requestManualCapture() {
        val current = _state.value
        if (current == CaptureState.DETECTING || current == CaptureState.LOCKED) {
            Diag.log("AutoCapture", "manual shutter -> capture (page-ness gate bypassed)")
            triggerCapture("manual")
        } else {
            Diag.log("AutoCapture", "manual shutter ignored in state=$current")
        }
    }

    /** Call when the still has been captured (bitmap obtained) -> `Processing`. */
    fun onStillCaptured() {
        stability.reset()
        _indicator.value = CaptureIndicator.NONE
        _state.value = CaptureState.PROCESSING
    }

    /** Call when a capture attempt failed -> recover to `Detecting`. */
    fun onCaptureFailed() {
        stability.reset()
        _indicator.value = CaptureIndicator.NONE
        _state.value = CaptureState.DETECTING
    }

    /** Call when the page has been processed & appended -> show the ~1 s thumbnail, then resume. */
    fun onProcessingComplete() {
        _state.value = CaptureState.THUMBNAIL_1S
        thumbnailJob?.cancel()
        thumbnailJob = scope.launch {
            delay(thumbnailDurationMs)
            if (_state.value == CaptureState.THUMBNAIL_1S) {
                _state.value = CaptureState.DETECTING
            }
        }
    }

    private fun updateIndicator(decision: AutoCaptureStability.Decision) {
        _indicator.value = when {
            decision.stableCount <= 0 -> CaptureIndicator.NONE
            decision.stableCount >= (stableFramesRequired - 1).coerceAtLeast(1) ->
                CaptureIndicator.HOLD_STEADY
            else -> CaptureIndicator.DOCUMENT_DETECTED
        }
    }

    private fun maybeAutoCapture(decision: AutoCaptureStability.Decision, gateScore: Double) {
        if (!autoCaptureEnabled) {
            Diag.log("AutoCapture", "auto capture disabled (stable=${decision.stableCount})")
            return
        }
        if (_state.value != CaptureState.LOCKED) return
        if (!decision.fire) {
            Diag.log(
                "AutoCapture",
                String.format(
                    Locale.US,
                    "holding n=%d/%d iou=%.2f score=%.2f",
                    decision.stableCount, decision.requiredFrames, decision.iou, gateScore
                )
            )
            return
        }
        val now = SystemClock.elapsedRealtime()
        // The hold length is measured on the FRAME clock — the same clock the analyzer stamps
        // frames with — never by mixing a frame timestamp with the wall clock at evaluation time.
        val heldMs = if (holdStartMs != 0L && lastFrameTsMs != 0L) {
            lastFrameTsMs - holdStartMs
        } else {
            -1L
        }
        if (lastCaptureAtMs != 0L && now - lastCaptureAtMs < autoCaptureCooldownMs) {
            Diag.log(
                "AutoCapture",
                "cooldown active (${now - lastCaptureAtMs}ms < ${autoCaptureCooldownMs}ms)"
            )
            return
        }
        // H21 (field feedback): "it captures too fast". A 3-frame window at 30 fps is ~100 ms, so
        // the user never gets to see that a capture is imminent or what is about to be captured.
        // Require the page to be held for [minHoldMs] of real time first.
        if (minHoldMs > 0L && heldMs >= 0L && heldMs < minHoldMs) {
            Diag.log(
                "AutoCapture",
                String.format(Locale.US, "settling held=%dms < %dms -> wait", heldMs, minHoldMs)
            )
            return
        }
        // SUBTASK H21: a repeated shot of an UNCHANGED scene is not a new page. 18 captures in
        // ~100 s of one static room proved this was missing. Suppress AUTO capture only — the
        // manual shutter never reaches this method.
        if (isDuplicateOfLastCapture(stability.lastQuad, currentSignature)) {
            Diag.log(
                "AutoCapture",
                String.format(
                    Locale.US,
                    "duplicate suppressed (quadIoU>=%.2f mad<=%.2f)",
                    DUP_IOU, DUP_MAD
                )
            )
            return
        }
        Diag.log(
            "AutoCapture",
            String.format(
                Locale.US,
                "stable n=%d/%d iou=%.2f score=%.2f held=%dms -> capture",
                decision.stableCount, decision.requiredFrames, decision.iou, gateScore, heldMs
            )
        )
        triggerCapture("auto")
    }

    private fun triggerCapture(reason: String) {
        lastCaptureAtMs = SystemClock.elapsedRealtime()
        // Remember WHAT was captured so the next auto-capture can tell "same page again" from
        // "the user moved to a new page" (SUBTASK H21).
        lastCaptureQuad = stability.lastQuad
        lastCaptureSignature = currentSignature
        // A fresh hold is required after every capture (auto or manual), and the dwell timer must
        // restart too — otherwise a second page would be captured with a stale hold time.
        stability.reset()
        holdStartMs = 0L
        lastFrameTsMs = 0L
        _indicator.value = CaptureIndicator.NONE
        _state.value = CaptureState.CAPTURING
        val emitted = _captureRequests.tryEmit(Unit)
        Diag.log("AutoCapture", "capture triggered ($reason) emitted=$emitted state=CAPTURING")
    }

    /**
     * SUBTASK H21: true when [quad]/[signature] describe the scene already captured — same
     * position (quad IoU ≥ [DUP_IOU]) AND near-identical pixels (downscaled mean-absolute
     * difference ≤ [DUP_MAD]). Either one failing (the user moved, the page changed) re-enables
     * auto-capture. Missing data (no signature) is never treated as a duplicate.
     */
    /**
     * H21: normalised mean-absolute-difference between two luminance fingerprints (0..1), or
     * `null` when a comparison cannot be made (missing/mismatched data is never "still").
     * 256 byte reads, no allocation — measured well under 0.05 ms per frame.
     */
    private fun motionMad(a: ByteArray?, b: ByteArray?): Double? {
        if (a == null || b == null || a.size != b.size || a.isEmpty()) return null
        var sum = 0L
        for (i in a.indices) {
            sum += kotlin.math.abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
        }
        return sum.toDouble() / a.size / 255.0
    }

    private fun isDuplicateOfLastCapture(quad: Quad?, signature: ByteArray?): Boolean {
        val lastQuad = lastCaptureQuad ?: return false
        val lastSignature = lastCaptureSignature ?: return false
        if (quad == null || signature == null) return false
        if (signature.size != lastSignature.size) return false
        val iou = AutoCaptureStability.iouOf(lastQuad, quad)
        if (iou < DUP_IOU) return false
        var sum = 0L
        for (i in signature.indices) {
            sum += kotlin.math.abs(
                (signature[i].toInt() and 0xFF) - (lastSignature[i].toInt() and 0xFF)
            )
        }
        val mad = sum.toDouble() / signature.size / 255.0
        return mad <= DUP_MAD
    }

    private companion object {
        /** SUBTASK H21: quad IoU above which a re-capture is considered the same position. */
        const val DUP_IOU = 0.95

        /** SUBTASK H21: normalised MAD below which the frame content is considered unchanged. */
        const val DUP_MAD = 0.02

        /**
         * H21 field feedback: normalised MAD above which the scene counts as MOVING, so the hold
         * restarts. 0.035 ≈ 9 grey levels of average change over the 16×16 grid — comfortably
         * above sensor noise / autofocus breathing, far below a page turn.
         */
        const val MOTION_MAD = 0.035

        /**
         * H21 field feedback: how long a page must be held before auto-capture. Chosen from the
         * reported behaviour ("a human cannot hold exact frames for a second"): long enough that
         * the user sees the lock, short enough to feel instant.
         */
        const val DEFAULT_MIN_HOLD_MS = 350L

        /** H21 field feedback: keep the captured page on screen long enough to see it. */
        const val DEFAULT_THUMBNAIL_MS = 1400L

        /**
         * H21 field feedback: "it sometimes shoots the same thing twice … a debounce would be
         * pleasing". Enforced AFTER every capture (auto and manual) so the next AUTO shot cannot
         * follow immediately even when hand tremor defeats the fingerprint comparison. Long enough
         * to read the page that was just captured, short enough not to slow a multi-page session.
         */
        const val DEFAULT_POST_CAPTURE_COOLDOWN_MS = 2500L
    }
}
