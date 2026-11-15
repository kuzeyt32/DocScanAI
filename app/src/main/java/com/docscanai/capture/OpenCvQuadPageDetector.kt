package com.docscanai.capture

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.docscanai.core.util.Diag
import com.docscanai.core.util.TraceDump
import com.docscanai.data.Point
import com.docscanai.data.Quad
import com.docscanai.ml.MlsdGeometry
import com.docscanai.ml.MlsdMergeRepair
import com.docscanai.ml.MlsdModel
import com.docscanai.ml.MlsdTrigger
import com.docscanai.ml.OnnxModel
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point as CvPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Document boundary / 4-corner detector (TECHNICAL_SPEC.md §4.1, MODELS.md §2).
 *
 * ## Primary AI path + classical fallback
 *  1. **PRIMARY — `m1c_detect` DocAligner corner ONNX** ([detectWithCornerModel], `// M1c
 *     DocAligner`, SUBTASK H4). Attempted first when a corner model was provided *and* loaded; the
 *     four heat-map peaks are ordered TL,TR,BR,BL and pass [isPlausiblePageQuad] (convex, area band,
 *     aspect, fill, **not frame-hugging**). Any miss/failure falls through to path 2.
 *  2. **FALLBACK — robust classical OpenCV** ([detectClassical], always available, no model
 *     required). Gray → Gaussian blur → multi-cue candidates (edges ∪ Otsu/adaptive thresholds ∪
 *     Hough lines) → `findContours(RETR_EXTERNAL)` → `approxPolyDP` → convex-quad validation →
 *     scoring → corner ordering (TL, TR, BR, BL).
 *
 * The legacy `m1_boundary` DexiNed path ([detectWithModel], marked `// M1 DexiNed`) is **retained
 * for reference/rollback but no longer consulted** — see the H4 rationale in MODELS.md §2.0.
 *
 * ## Coordinate convention (IMPORTANT)
 * [detect] returns the quad in **pixel coordinates of the bitmap passed in** (same width/height
 * the caller supplied). It does **NOT** normalise. This matches
 * [`FrameAnalyzer`](FrameAnalyzer.kt), which normalises the returned quad by dividing by the
 * working-frame width/height before temporal smoothing. `refineQuad` also returns pixel
 * coordinates — of the supplied full-resolution bitmap. Keeping both in raw pixel space lets the
 * exact same quad be used for the on-screen overlay (after FrameAnalyzer normalises it) and for
 * the full-res perspective warp (subtask 4), with no hidden scaling.
 *
 * Null is returned when no boundary clears the minimum confidence, so the live overlay does not
 * latch onto noise.
 *
 * @param modelProvider lazily supplies the optional DexiNed [`OnnxModel`], or `null` when the
 *   artifact is not downloaded **or the AI toggle is OFF**. Re-evaluated until a real model is
 *   resolved (see [resolveModel] / SUBTASK F latch fix). Wrapped in try/catch.
 * @param mlsdProvider SUBTASK H30: lazily supplies the optional M-LSD line-evidence model, or
 *   `null` when it has not been downloaded / the AI toggle is OFF. `null` means the detector
 *   behaves exactly as it did in 0.3.8 — this is an optional download with a graceful fallback,
 *   not a new hard dependency.
 */
class OpenCvQuadPageDetector(
    private val modelProvider: (() -> OnnxModel?)? = null,
    private val cornerModelProvider: (() -> OnnxModel?)? = null,
    private val mlsdProvider: (() -> MlsdModel?)? = null
) : PageDetector {

    /**
     * SUBTASK H30 — how M-LSD evidence may be used. Public and mutable so the instrumentation
     * test can run the same binary with the feature ON and OFF (the A/B the subtask requires)
     * without rebuilding or re-provisioning anything.
     *
     *  - [OFF]        no M-LSD work at all (the 0.3.8 behaviour, zero added latency);
     *  - [REPAIR]     split the corner quad on an interior fold (use a);
     *  - [CANDIDATE]  prefer a well-supported line-derived quad (use b);
     *  - [BOTH]       repair first, then let a consistent candidate replace it — **on a still
     *                 only**: on a live preview the candidate would swap the quad on invocation
     *                 frames alone, which is the flicker the cadence exists to prevent, so the
     *                 live path always takes the repair branch.
     */
    enum class MlsdMode { OFF, REPAIR, CANDIDATE, BOTH }

    /**
     * Production default is **[REPAIR]**, and *scheduled*: as of H31 the model is only consulted
     * when [MlsdTrigger] sees a merge signature on the corner quad, once the scene has settled, and
     * at most once per `cadence` frames — with the fold cached as geometry in between.
     *
     * Measured on the 18 book-spread fixtures with that schedule (tooling/out/h31/): merged slivers
     * 7/12 -> 2/12, mean corner 3.9562 % -> 3.4331 %, mean IoU 0.8279 -> 0.8560, detection 18/18;
     * over a 200-frame scan 8 frames (4.0 %) invoked the model, for a mean +1.85 ms and a p95 of
     * +25.2 ms per frame — inside the `+15 ms` / `+40 ms` acceptance budgets, and a clean
     * single-page sequence invokes it zero times. [CANDIDATE] stays measured but unshipped: it can
     * only ever *grow* a validated corner quad and H30's own matrix has it behind repair alone.
     */
    @Volatile var mlsdMode: MlsdMode = MlsdMode.REPAIR

    /** Last `[MLSD]` summary, for diagnostics/tests that cannot read logcat. */
    @Volatile var lastMlsdLog: String? = null
        private set

    // ==========================================================================================
    // SUBTASK H31 — the schedule: when to pay for M-LSD, and what to reuse when we do not.
    // ==========================================================================================

    /**
     * Cadence + geometric cache. Owns the only mutable state the trigger has: how many frames since
     * the last invocation, and the cached fold. Everything else is a pure function of the quad.
     */
    val mlsdSchedule: MlsdTrigger.Schedule = MlsdTrigger.Schedule()

    /**
     * Thresholds for the merge signature and the cadence. `@Volatile var` so the instrumentation
     * test can A/B a policy (and so production could retune it) without a rebuild; every write
     * also lands in the schedule, which is what actually reads it.
     */
    @Volatile
    var mlsdPolicy: MlsdTrigger.Policy = MlsdTrigger.Policy()
        set(value) {
            field = value
            mlsdSchedule.policy = value
        }

    /**
     * Per-frame context: motion, stability and the previous frame's gate evidence. The live
     * analyser ([FrameAnalyzer]) and the capture path ([ScanPipeline]) set it before calling
     * [detect]; the defaults are "moving, not settled, no evidence", i.e. M-LSD never runs unless
     * a caller says the scene is worth looking at.
     */
    @Volatile
    var mlsdHint: MlsdTrigger.Hint = MlsdTrigger.Hint.LIVE_UNKNOWN

    /**
     * SUBTASK H38 — user-requested detector reset (the "stuck after a miss" recovery): drop the
     * M-LSD cadence cache + counters, the last provenance and the last `[MLSD]` log, so the next
     * [detect] call starts completely cold. Wired to the CameraScreen reset control. Models are
     * NOT touched (they stay latched); this is per-scene state only.
     */
    fun reset() {
        mlsdSchedule.reset()
        lastQuadProvenance = QuadProvenance.UNKNOWN
        lastMlsdLog = null
    }

    @Volatile private var boundaryModel: OnnxModel? = null

    /** SUBTASK H30: latched M-LSD model (same latch semantics as the corner model). */
    @Volatile private var mlsdModel: MlsdModel? = null

    @Volatile private var mlsdResolved = false

    /**
     * SUBTASK H21 — provenance of the quad returned by the most recent [detect] call.
     * Read by `FrameAnalyzer` immediately after `detect` (single-flight per frame).
     */
    @Volatile private var lastQuadProvenance: QuadProvenance = QuadProvenance.UNKNOWN

    override val lastProvenance: QuadProvenance get() = lastQuadProvenance

    /**
     * SUBTASK H4 primary detector: the DocAligner LCNet100 corner-heatmap model (`m1c_detect`).
     * Latched like [boundaryModel] but through its own provider/slot.
     */
    @Volatile private var cornerModel: OnnxModel? = null

    /** True once a **non-null** corner model has been latched (null keeps the latch open). */
    @Volatile private var cornerResolved = false

    /**
     * SUBTASK H40: the per-channel peak floor the last postprocess actually applied
     * ([CORNER_PEAK_MIN], or [CORNER_PEAK_MIN_RELAXED] when three channels cleared
     * [CORNER_PEAK_STRONG]). Diagnostics only.
     */
    @Volatile private var lastCornerPeakFloor: Float = 0.30f

    /**
     * H7 Tier A: latched corner-model input edge (0 = not probed yet). Starts unknown; the first
     * detection probes 512 then 256 and latches whichever the graph actually accepts.
     */
    @Volatile private var cornerInputEdge: Int = 0

    /**
     * True once a **non-null** model has been latched. A `null` provider result intentionally does
     * NOT latch (SUBTASK F): see [resolveModel].
     */
    @Volatile private var modelResolved = false

    /** Set by [release]; once released the detector never loads a model again. */
    @Volatile private var released = false

    private val modelLock = Any()

    /**
     * Detects the document boundary in [bitmap] (pixel coordinates of that bitmap), or `null`.
     * Runs the optional ONNX path first when a model is available, then the classical fallback.
     * Never throws.
     */
    override suspend fun detect(bitmap: Bitmap): Quad? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            lastQuadProvenance = QuadProvenance.UNKNOWN
            return null
        }
        // SUBTASK H39: diagnostics-only. `null` in production (TraceDump.root is unset), so no
        // extra work, allocation or file I/O happens on the normal capture path.
        val trace = TraceDump.current()
        // SUBTASK H40: the per-scene MLSD log must start empty for this photo. It used to be
        // reset only by `reset()`, so a trace scene could carry the PREVIOUS photo's `[MLSD]`
        // line, which made the evidence look like the model had run when it had not.
        lastMlsdLog = null

        // 1) PRIMARY — // M1c DocAligner corner detector (SUBTASK H4). Any failure, or a quad that
        //    fails the plausibility gate, transparently falls through to the classical path.
        val corner = resolveCornerModel()
        if (corner != null) {
            val nnQuad = try {
                detectWithCornerModel(bitmap, corner, trace)
            } catch (t: Throwable) {
                Log.w(TAG, "corner detector failed; using classical detection", t)
                null
            }
            if (nnQuad != null) {
                // SUBTASK H21: four corners came from the model's heat-map peaks.
                lastQuadProvenance = QuadProvenance.CORNER_MODEL
                // SUBTASK H30: optional line-evidence repair. Returns `nnQuad` unchanged when
                // M-LSD is absent/OFF or finds no evidence, so this is a no-op in 0.3.8 terms.
                val repaired = applyMlsdEvidence(bitmap, nnQuad)
                traceResult(trace, bitmap, repaired, QuadProvenance.CORNER_MODEL)
                return repaired
            }
        }

        // 2) FALLBACK — classical multi-cue OpenCV. NOTE (SUBTASK H4): the legacy DexiNed
        //    `m1_boundary` path (`detectWithModel`, kept below for reference/rollback) is
        //    intentionally NOT consulted any more — measurements showed it cannot recover the
        //    "2 corners correct, 2 run to the frame border" mode and the corner model supersedes it
        //    (see MODELS.md §2.1).
        return try {
            val classical = detectClassical(bitmap, trace)
            // SUBTASK H21: tag a fallback quad for what it is — a structural guess, not a
            // detection. The live gate uses this to demand independent text-line evidence before
            // such a quad may pass / auto-capture.
            lastQuadProvenance = if (classical != null) {
                QuadProvenance.CLASSICAL_FALLBACK
            } else {
                QuadProvenance.UNKNOWN
            }
            // SUBTASK H32: the classical quad MUST reach the same merge-repair evidence as the
            // corner-model quad. Measured on the real book-spread set (tooling/out/h32/
            // RED_BASELINE.md): 6 of 8 merged and 2 of 3 wrong-page cases were classical quads
            // that were returned raw here and never saw the fold-split rule, because this branch
            // bypassed applyMlsdEvidence. The rule can only SHRINK a quad at measured evidence
            // (MlsdTrigger.acceptsRepair) and is a no-op when it finds none, so routing it through
            // is safe by construction — and it is what the owner's "detect the only page that is
            // fully seen" directive needs on exactly these images.
            val result = if (classical != null) applyMlsdEvidence(bitmap, classical) else null
            traceResult(trace, bitmap, result, lastQuadProvenance)
            result
        } catch (t: Throwable) {
            Log.w(TAG, "Classical detection failed", t)
            lastQuadProvenance = QuadProvenance.UNKNOWN
            null
        }
    }

    /**
     * SUBTASK H39: writes the returned quad + provenance to the open trace scene. No-op when
     * tracing is off.
     */
    private fun traceResult(
        trace: TraceDump.Session?,
        bitmap: Bitmap,
        quad: Quad?,
        provenance: QuadProvenance
    ) {
        if (trace == null) return
        runCatching {
            trace.put("detected", quad != null)
            trace.put("provenance", provenance.name)
            trace.put("mlsd_invocations", mlsdSchedule.invocations)
            trace.put("mlsd_log", lastMlsdLog ?: "-")
            val q = JSONArray()
            quad?.points?.forEach { p ->
                q.put(JSONObject().put("x", p.x.toDouble()).put("y", p.y.toDouble()))
            }
            trace.put("quad", q)
            trace.quadOverlay(
                "final_quad", bitmap, quad,
                if (quad == null) "$provenance  NO DETECTION" else provenance.name
            )
        }
    }


    /**
     * Re-runs detection on the **full-resolution** still, restricted to a region around
     * [seedQuad], for the capture path (subtask 4 calls this before warping, per
     * TECHNICAL_SPEC.md §4.2 and MODELS.md §2.1 "corner refinement").
     *
     * Strategy (all coordinates in the full-res bitmap's pixel space):
     *  1. downscale the still to a bounded working resolution (keeps latency/memory sane);
     *  2. map [seedQuad] into that space and expand its bounding box by [REFINE_MARGIN];
     *  3. detect inside the crop (the page fills more of the frame ⇒ a better `approxPolyDP`);
     *  4. **SUBTASK H26 — validate the refined quad against the seed** ([seedDecision]) and keep
     *     the seed when the refined quad is not the same page: `IoU >= 0.85`, `areaRatio` in
     *     `[0.9, 1.1]` and not strictly contained inside the seed with an inset larger than
     *     [REFINE_MARGIN]. Without this guard the crop's strongest 4-gon (an inner text block, a
     *     table frame or a ruled margin) silently replaced a correct seed and the warp extracted a
     *     sub-rectangle of the page — the owner's "half the paper" field bug (see
     *     `tooling/out/h25/CAPTURE_BUG_DIAGNOSIS.md`).
     *  5. map the accepted result back up; if the crop detection fails, whole-working detection is
     *     validated with the same guard; if that also fails, return [seedQuad] unchanged.
     *
     * Never throws; always returns a valid quad. Corner order stays TL, TR, BR, BL.
     */
    override fun refine(bitmap: Bitmap, seed: Quad): Quad = refineQuad(bitmap, seed)

    fun refineQuad(fullResBitmap: Bitmap, seedQuad: Quad): Quad {
        if (fullResBitmap.isRecycled || fullResBitmap.width <= 0 || fullResBitmap.height <= 0) {
            return seedQuad
        }
        val start = System.currentTimeMillis()
        Diag.stageEnter("Refine", Diag.describe(fullResBitmap))
        val fullW = fullResBitmap.width
        val fullH = fullResBitmap.height

        val maxEdge = max(fullW, fullH)
        val scale = if (maxEdge > REFINE_WORK_EDGE) REFINE_WORK_EDGE.toDouble() / maxEdge else 1.0
        val mw = (fullW * scale).toInt().coerceAtLeast(1)
        val mh = (fullH * scale).toInt().coerceAtLeast(1)

        var working: Bitmap? = null
        return try {
            val medium: Bitmap = if (scale < 1.0) {
                Bitmap.createScaledBitmap(fullResBitmap, mw, mh, true).also { working = it }
            } else {
                fullResBitmap
            }

            val seedMedium = scaleQuad(seedQuad, scale, scale)

            // 3) Region-restricted detection around the seed.
            val region = expandBounds(seedMedium, REFINE_MARGIN, mw, mh)
            val refinedMedium: Quad? = if (region != null) {
                var crop: Bitmap? = null
                try {
                    crop = Bitmap.createBitmap(medium, region.left, region.top, region.width, region.height)
                    detectClassical(crop)?.let {
                        offsetQuad(it, region.left.toFloat(), region.top.toFloat())
                    }
                } finally {
                    crop?.recycle()
                }
            } else {
                null
            }

            // 4) Fall back to whole-working detection, then to the seed itself.
            val mediumQuad = refinedMedium ?: detectClassical(medium) ?: seedMedium

            // 4b) SUBTASK H26 — SEED GUARD. The crop's winner is only accepted when it is the SAME
            //     page as the seed. Inside a small crop the page border may be outside (or clipped
            //     at) the crop, so an inner text block / table frame / ruled margin is often the
            //     strongest complete 4-gon; accepting it warps a sub-rectangle of the page.
            val decision = seedDecision(mediumQuad, seedMedium)
            Diag.log(
                TAG,
                "[Refine] seedIou=${fmt4(decision.iou)} area=${fmt4(decision.areaRatio)} " +
                    "inset=${fmt4(decision.inset)} accept=${decision.accept} " +
                    "reason=${decision.reason}"
            )
            if (!decision.accept) {
                // Keep the (already validated by the live gate / previous pass) seed verbatim.
                return seedQuad
            }

            // medium -> full-res
            scaleQuad(mediumQuad, 1.0 / scale, 1.0 / scale)
        } catch (t: Throwable) {
            Diag.logThrowable("Refine", t)
            seedQuad
        } finally {
            // Only recycle the copy we allocated (never the caller's full-res bitmap).
            if (working !== null && working !== fullResBitmap) working?.recycle()
            Diag.stageExit("Refine", System.currentTimeMillis() - start)
        }
    }

    override fun release() {
        synchronized(modelLock) {
            runCatching { boundaryModel?.close() }
            runCatching { cornerModel?.close() }
            runCatching { mlsdModel?.close() }
            boundaryModel = null
            cornerModel = null
            mlsdModel = null
            released = true // do not attempt to reload after an explicit release
        }
    }

    // ==========================================================================================
    // SUBTASK H30 — M-LSD line evidence: merge rejection/repair (a) + candidate generator (b)
    // ==========================================================================================

    /**
     * SUBTASK H31: creates the M-LSD session (and its ORT session) **away from any capture**.
     *
     * H30 measured the cold cost at +446 ms p95 in the first process that ran the model — that is
     * `OnnxModel.load` for a 6.17 MB graph plus OpenCV init, and it used to land on whichever
     * frame happened to need line evidence first. The camera screen calls this when it opens, so
     * the capture path only ever pays for inference. Returns the wall time the warm-up took.
     */
    fun warmMlsd(): Long {
        val t0 = System.currentTimeMillis()
        val model = resolveMlsdModel()
        val ms = System.currentTimeMillis() - t0
        Diag.log(
            "Detector",
            "M-LSD warm-up present=${model != null} tookMs=$ms " + MlsdTrigger.describe(mlsdPolicy)
        )
        return ms
    }

    /** Resolves the optional M-LSD model with the same latch semantics as the corner model. */
    private fun resolveMlsdModel(): MlsdModel? {
        if (mlsdResolved || released) return mlsdModel
        synchronized(modelLock) {
            if (mlsdResolved || released) return mlsdModel
            val resolved = try {
                mlsdProvider?.invoke()
            } catch (t: Throwable) {
                Log.w(TAG, "M-LSD model load failed", t)
                null
            }
            mlsdModel = resolved
            mlsdResolved = resolved != null
            if (resolved != null) {
                Diag.log("Detector", "M-LSD resolved (latch closed) edge=${resolved.inputEdge} " +
                    MlsdMergeRepair.describe())
            }
            return resolved
        }
    }

    /**
     * SUBTASK H31 — M-LSD evidence, but only when the frame can use it.
     *
     * The three paths, in the order they are considered:
     *
     *  1. **Do not run it.** [MlsdTrigger] evaluates a merge signature on the corner quad from
     *     free geometry, and [MlsdTrigger.Schedule] adds the motion/stability and cadence gates.
     *     A clean, settled, well-contained page ends here and pays a few microseconds.
     *  2. **Reuse the last answer.** If the signature holds but the cadence says this frame is not
     *     allowed to invoke, the fold the model found last time is re-applied to *this* quad
     *     ([MlsdMergeRepair.applyFractional]) — two lerps, no inference. This is why the overlay
     *     keeps the repaired quad continuously instead of flickering once every N frames.
     *  3. **Pay for a fresh answer** (signature fired and the cache is cold/stale/changed). Then,
     *     exactly as in H30: repair the quad at the fold (a); on a still capture additionally let a
     *     well-supported line-derived square replace it (b).
     *
     * The candidate use (b) is restricted to the **still** path on purpose: on a live preview it
     * would swap the quad on invocation frames only, which is precisely the flicker the cadence
     * exists to prevent. A capture is one decision, so there is nothing to flicker.
     *
     * Logs exactly one `[MLSD] …` line per detection, now carrying the trigger inputs, the
     * decision and the measured trigger cost. Never throws.
     */
    private fun applyMlsdEvidence(bitmap: Bitmap, quad: Quad): Quad {
        if (mlsdMode == MlsdMode.OFF) return quad

        val frameW = bitmap.width.toDouble()
        val frameH = bitmap.height.toDouble()
        val pts = toPts(quad)
        val hint = mlsdHint

        // --- 1) cheap gate: is this quad even a candidate for the merge failure? ---------------
        val tSig = System.nanoTime()
        val signature = MlsdTrigger.signature(pts, bitmap.width, bitmap.height, hint, mlsdPolicy)
        val decision = mlsdSchedule.decide(signature, pts, SystemClock.elapsedRealtime(), hint)
        val triggerUs = (System.nanoTime() - tSig) / 1_000

        if (!decision.invoke) {
            // --- 2) serve from the cache (or not at all) --------------------------------------
            val projected = if (decision.useCache) projectCached(pts, frameW, frameH) else null
            logMlsd(
                segments = 0, squares = 0, result = null,
                chosen = if (projected != null) "cached" else "corner",
                reason = decision.reason, signature = signature, triggerUs = triggerUs
            )
            return projected ?: quad
        }

        // --- 3) pay for it -------------------------------------------------------------------
        val model = resolveMlsdModel() ?: return quad
        val result = try {
            model.analyze(bitmap)
        } catch (t: Throwable) {
            Diag.warn(TAG, "M-LSD analysis failed: ${t.message}")
            null
        } ?: run {
            mlsdSchedule.noteInvocation(pts, null, SystemClock.elapsedRealtime())
            lastMlsdLog = "[MLSD] segments=0 squares=0 chosen=corner reason=analysis_failed"
            Diag.log(TAG, lastMlsdLog!!)
            return quad
        }

        var current = quad
        var currentPts = pts
        var reason = "corner"
        var cachedFold: MlsdMergeRepair.Fractional? = null

        if (mlsdMode == MlsdMode.REPAIR || mlsdMode == MlsdMode.BOTH) {
            // SUBTASK H32: the pixel partial-page term (border-is-paper) needs luma samples; the
            // bitmap is already in hand, so expose them as a lambda. The rule stays pure-Kotlin.
            val lumaSampler = { x: Int, y: Int ->
                val px = x.coerceIn(0, bitmap.width - 1)
                val py = y.coerceIn(0, bitmap.height - 1)
                val c = bitmap.getPixel(px, py)
                ((0.299 * ((c shr 16) and 0xFF)) + (0.587 * ((c shr 8) and 0xFF)) +
                    (0.114 * (c and 0xFF))) / 255.0
            }
            val outcome = MlsdMergeRepair.apply(pts, result.segments, bitmap.width, bitmap.height, lumaSampler)
            if (outcome.applied && outcome.quad != null) {
                val ordered = MlsdMergeRepair.orderCorners(outcome.quad)
                val candidate = toQuad(ordered)
                if (candidate != null && isPlausiblePageQuad(candidate, frameW, frameH)) {
                    // H31 "do no harm": a split along a TEXT edge makes the page narrower than a
                    // page, a split along a real gutter takes a spread back to page proportions.
                    // Only the latter may reach the warp — see MlsdTrigger.acceptsRepair for the
                    // two measured conditions (sliver size + direction of the aspect change).
                    if (!MlsdTrigger.acceptsRepair(pts, ordered, outcome.stripFrac)) {
                        reason = if (outcome.stripFrac < MlsdTrigger.REPAIR_MIN_STRIP) {
                            "split_rejected_strip"
                        } else {
                            "split_rejected_aspect"
                        }
                    } else {
                        current = candidate
                        currentPts = ordered
                        cachedFold = MlsdMergeRepair.fractionalOf(outcome)
                        reason = "split(${outcome.side},strip=${MlsdMergeRepair.pct(outcome.stripFrac)})"
                    }
                } else {
                    reason = "split_rejected_gate"
                }
            } else {
                reason = "corner(${outcome.reason})"
            }
        }

        if (hint.still && (mlsdMode == MlsdMode.CANDIDATE || mlsdMode == MlsdMode.BOTH)) {
            val best = result.squares.firstOrNull()
            if (best != null) {
                val candPts = MlsdMergeRepair.orderCorners(best.corners)
                val candArea = MlsdGeometry.polygonArea(candPts)
                val refArea = MlsdGeometry.polygonArea(currentPts).coerceAtLeast(1e-9)
                val ratio = candArea / refArea
                if (best.support >= CANDIDATE_MIN_SUPPORT &&
                    ratio in CANDIDATE_AREA_LO..CANDIDATE_AREA_HI
                ) {
                    val candidate = toQuad(candPts)
                    if (candidate != null && isPlausiblePageQuad(candidate, frameW, frameH)) {
                        if (!MlsdTrigger.improvesPaperAspect(currentPts, candPts)) {
                            reason = "mlsd_square_rejected_aspect"
                        } else {
                            current = candidate
                            currentPts = candPts
                            cachedFold = null // a replaced quad is not a fold: nothing to project
                            reason = "mlsd_square(support=${MlsdMergeRepair.pct(best.support)},ratio=" +
                                "${MlsdMergeRepair.pct(ratio)})"
                        }
                    }
                }
            }
        }

        // Cache against the CORNER quad: the next frame's IoU check must compare like with like.
        mlsdSchedule.noteInvocation(pts, cachedFold, SystemClock.elapsedRealtime())
        logMlsd(
            segments = result.segments.size, squares = result.squares.size, result = result,
            chosen = if (current !== quad) "mlsd" else "corner",
            reason = reason, signature = signature, triggerUs = triggerUs
        )
        return current
    }

    /**
     * Re-applies the cached fold to [pts]. The result still has to survive the detector's own
     * plausibility gate — a projection can never be a way to smuggle a bad quad past it.
     */
    private fun projectCached(
        pts: List<MlsdGeometry.Pt>,
        frameW: Double,
        frameH: Double
    ): Quad? {
        val repair = mlsdSchedule.cachedRepair() ?: return null
        val folded = MlsdMergeRepair.applyFractional(pts, repair) ?: return null
        val candidate = toQuad(MlsdMergeRepair.orderCorners(folded)) ?: return null
        return if (isPlausiblePageQuad(candidate, frameW, frameH)) candidate else null
    }

    private fun logMlsd(
        segments: Int,
        squares: Int,
        result: MlsdModel.Result?,
        chosen: String,
        reason: String,
        signature: MlsdTrigger.Signature,
        triggerUs: Long
    ) {
        val line = "[MLSD] segments=$segments squares=$squares chosen=$chosen reason=$reason " +
            "asp=${fmt3(signature.aspect)} area=${fmt3(signature.areaFrac)} " +
            "border=${fmt3(signature.borderMargin)} trigger=${signature.reasons} " +
            "triggerUs=$triggerUs modelMs=${result?.modelMs ?: 0} postMs=${result?.postMs ?: 0} " +
            "prepMs=${result?.prepMs ?: 0}"
        lastMlsdLog = line
        Diag.log(TAG, line)
    }

    private fun fmt3(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)

    private fun toPts(quad: Quad): List<MlsdGeometry.Pt> = listOf(
        MlsdGeometry.Pt(quad.topLeft.x.toDouble(), quad.topLeft.y.toDouble()),
        MlsdGeometry.Pt(quad.topRight.x.toDouble(), quad.topRight.y.toDouble()),
        MlsdGeometry.Pt(quad.bottomRight.x.toDouble(), quad.bottomRight.y.toDouble()),
        MlsdGeometry.Pt(quad.bottomLeft.x.toDouble(), quad.bottomLeft.y.toDouble())
    )

    private fun toQuad(pts: List<MlsdGeometry.Pt>): Quad? {
        if (pts.size != 4) return null
        return Quad(
            topLeft = Point(pts[0].x.toFloat(), pts[0].y.toFloat()),
            topRight = Point(pts[1].x.toFloat(), pts[1].y.toFloat()),
            bottomRight = Point(pts[2].x.toFloat(), pts[2].y.toFloat()),
            bottomLeft = Point(pts[3].x.toFloat(), pts[3].y.toFloat())
        )
    }

    // ==========================================================================================
    // Classical pipeline (DEFAULT)
    // ==========================================================================================

    /**
     * Classical document-boundary detection. Returns the best quad in the pixel coordinates of
     * the supplied [bitmap] (scaling back if a bounded working copy was created), or `null`.
     */
    private fun detectClassical(
        bitmap: Bitmap,
        trace: TraceDump.Session? = null
    ): Quad? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null

        // Normalise to ARGB_8888 and a bounded working size without ever mutating/recycling the
        // caller's bitmap.
        val maxEdge = max(bitmap.width, bitmap.height)
        val scale = if (maxEdge > MAX_WORK_EDGE) MAX_WORK_EDGE.toDouble() / maxEdge else 1.0
        var owned: Bitmap? = null
        val src: Bitmap = when {
            scale < 1.0 -> Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            ).also { owned = it }

            bitmap.config != Bitmap.Config.ARGB_8888 ->
                bitmap.copy(Bitmap.Config.ARGB_8888, false).also { owned = it }

            else -> bitmap
        }

        val rgba = Mat()
        val gray = Mat()
        val blur = Mat()
        try {
            Utils.bitmapToMat(src, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)

            val fw = src.width.toDouble()
            val fh = src.height.toDouble()
            // SUBTASK H36: one coarse luma grid per detection, shared by every candidate's
            // paper-interior / frame-continuation scoring (see [LumaGrid]).
            val grid = buildLumaGrid(blur, fw, fh)
            val candidates = ArrayList<Candidate>()

            // ---- SUBTASK H3b: multi-cue candidate generation ----------------------------------
            // Real photographs defeat a single edge cue: page-on-desk scenes carry texture,
            // shadows and a low-contrast page border, so `Canny ∪ Sobel` alone almost never
            // closes a 4-gon. We therefore gather quad candidates from several independent,
            // phone-cheap cues and score them with ONE documented metric ([buildCandidate]), so a
            // page only has to win on ONE cue. Each cue is guarded independently; a failure in
            // one never loses the others.
            runCatching { collectEdgeCue(blur, fw, fh, candidates, grid) }
                .onFailure { Diag.log(TAG, "cue 'edges' failed: ${it.message}") }
            runCatching { collectThresholdCues(blur, fw, fh, candidates, grid) }
                .onFailure { Diag.log(TAG, "cue 'threshold' failed: ${it.message}") }
            runCatching { collectHoughCue(blur, fw, fh, candidates, grid) }
                .onFailure { Diag.log(TAG, "cue 'hough' failed: ${it.message}") }

            // SUBTASK H36: acceptance still uses the base [MIN_SCORE] (detection count is
            // preserved by construction); the WINNER is chosen by [Candidate.rank], which adds
            // the paper-interior and frame-continuation signals as one signal among many —
            // never a veto, so a clean single candidate is still returned as before.
            val best = candidates.filter { it.score >= MIN_SCORE }.maxByOrNull { it.rank }

            // DIAGNOSIS (SUBTASK H3b): dump every cue's candidate count and the best few scores so
            // a real-photo miss is traceable from logcat (`Diag` -> the stable `DocScan` tag).
            // SUBTASK H36: up to 12 candidates, by base score, each carrying every sub-score
            // (area/page/paper/frame/rank) — one instrumented run then supports offline
            // re-weighting of the winner rank (tooling/out/h36/rank_sim.py) without re-running
            // the device per weight variant.
            val top = candidates.sortedByDescending { it.score }.take(12).joinToString(" | ") {
                "${it.source}:${fmt(it.score)}(area=${fmt(it.areaRatio)},page=${fmt(it.pageLike)}," +
                    "paper=${fmt(it.paperInterior)},frame=${fmt(it.frameContact)}," +
                    "rect=${fmt(it.rectangularity)},angle=${fmt(it.angleScore)}," +
                    "center=${fmt(it.centerScore)},rank=${fmt(it.rank)})"
            }
            Diag.log(
                TAG,
                "classical cues=${candidates.size} best=" +
                    (best?.let { "${it.source} score=${fmt(it.score)}" } ?: "NONE") +
                    " top=[$top]"
            )

            val quad = best?.quad ?: run {
                dumpClassicalTrace(trace, src, gray, blur, candidates, null)
                return null
            }
            dumpClassicalTrace(trace, src, gray, blur, candidates, best)

            // Scale the quad back from the (possibly downscaled) working copy to the input bitmap.
            return when {
                src === bitmap -> quad
                else -> scaleQuad(
                    quad,
                    bitmap.width.toDouble() / src.width.toDouble(),
                    bitmap.height.toDouble() / src.height.toDouble()
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Classical detection failed", t)
            return null
        } finally {
            rgba.release(); gray.release(); blur.release()
            owned?.recycle()
        }
    }

    /**
     * SUBTASK H39: diagnostics dump for the classical fallback. Writes the working grayscale, the
     * union edge map the cues actually see, every scored candidate (source + score drawn on the
     * frame), and a JSON breakdown. No-op unless a trace scene is open.
     *
     * All quads here are in [src]'s (working-copy) pixel space — the same frame as the images.
     */
    private fun dumpClassicalTrace(
        trace: TraceDump.Session?,
        src: Bitmap,
        gray: Mat,
        blur: Mat,
        candidates: List<Candidate>,
        best: Candidate?
    ) {
        if (trace == null) return
        runCatching {
            trace.mat("classical_gray", gray)
            val edges = Mat()
            try {
                buildEdges(blur, edges)
                trace.mat("classical_edges", edges)
            } finally {
                edges.release()
            }
            val sorted = candidates.sortedByDescending { it.score }
            trace.candidateOverlay(
                "classical_candidates", src,
                sorted.map { TraceDump.TraceQuad(it.quad, it.source, it.score.toDouble()) }
            )
            val section = trace.section("classical")
            section.put("candidate_count", candidates.size)
            section.put("best_source", best?.source ?: "NONE")
            section.put("best_score", best?.score?.toDouble() ?: 0.0)
            section.put("frame_w", src.width)
            section.put("frame_h", src.height)
            val top = JSONArray()
            sorted.take(12).forEach { c ->
                top.put(
                    JSONObject()
                        .put("source", c.source)
                        .put("score", c.score.toDouble())
                        .put("area_ratio", c.areaRatio)
                        .put("page_like", c.pageLike)
                        .put("paper_interior", c.paperInterior)
                        .put("frame_contact", c.frameContact)
                        .put("rectangularity", c.rectangularity)
                        .put("angle_score", c.angleScore)
                        .put("center_score", c.centerScore)
                        .put("rank", c.rank)
                        .put("quad", quadJson(c.quad))
                )
            }
            section.put("top", top)
        }.onFailure { Diag.warn("Trace", "classical trace dump failed: ${it.message}") }
    }

    private fun quadJson(quad: Quad): JSONArray {
        val arr = JSONArray()
        quad.points.forEach { p ->
            arr.put(JSONObject().put("x", p.x.toDouble()).put("y", p.y.toDouble()))
        }
        return arr
    }

    /**
     * Builds a binary edge image combining:
     *  - **Canny** with thresholds auto-derived from the frame mean (robust to exposure), and
     *  - a thresholded **Sobel gradient magnitude** (Otsu) that catches soft, low-contrast page
     * borders Canny alone can miss.
     *  - SUBTASK H36 fix f — a **locally normalized gradient band** (magnitude / local mean of
     * the magnitude) for the adjacent-page-shadow class: under a shadow the page edge's
     * absolute gradient falls below the frame-global Otsu level and the border simply drops
     * out of the edge image (H35: the shadowed pages end in no-detection). Normalizing by the
     * local mean makes the border a *relative* peak, which survives any lighting discontinuity
     * along the page edge; the shadow body itself normalizes to ~1 and stays out.
     */
    private fun buildEdges(blur: Mat, out: Mat) {
        val mean = Core.mean(blur).`val`[0]
        val lower = (0.66 * mean).coerceIn(10.0, 120.0)
        val upper = (1.33 * mean).coerceIn(lower + 40.0, 255.0)

        val canny = Mat()
        val gx = Mat()
        val gy = Mat()
        val absX = Mat()
        val absY = Mat()
        val mag = Mat()
        val grad = Mat()
        val mag32 = Mat()
        val local = Mat()
        val norm = Mat()
        val normBin = Mat()
        val norm8 = Mat()
        try {
            Imgproc.Canny(blur, canny, lower, upper, 3, true)

            Imgproc.Sobel(blur, gx, CvType.CV_16S, 1, 0, 3)
            Imgproc.Sobel(blur, gy, CvType.CV_16S, 0, 1, 3)
            Core.convertScaleAbs(gx, absX)
            Core.convertScaleAbs(gy, absY)
            Core.addWeighted(absX, 0.5, absY, 0.5, 0.0, mag)
            Imgproc.threshold(mag, grad, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)

            Core.bitwise_or(canny, grad, out)

            // fix f: locally normalized band (shadow-robust border gradients).
            if (SHADOW_BAND) {
                mag.convertTo(mag32, CvType.CV_32F)
                Imgproc.blur(mag32, local, Size(15.0, 15.0))
                Core.add(local, Scalar(4.0), local)
                Core.divide(mag32, local, norm, 1.0)
                Imgproc.threshold(norm, normBin, 1.5, 255.0, Imgproc.THRESH_BINARY)
                normBin.convertTo(norm8, CvType.CV_8U)
                Core.bitwise_or(out, norm8, out)
            }
        } finally {
            canny.release(); gx.release(); gy.release()
            absX.release(); absY.release(); mag.release(); grad.release()
            mag32.release(); local.release(); norm.release()
            normBin.release(); norm8.release()
        }
    }

    /** A scored quad candidate produced by one detection cue (SUBTASK H3b). */
    private data class Candidate(
        val quad: Quad,
        val score: Float,
        val source: String,
        val areaRatio: Double,
        val pageLike: Double,
        /**
         * SUBTASK H36 fix a — paper-likeness of the candidate's INTERIOR (bright + ink band +
         * flat bright side), see [paperInteriorOf]. The H35 evidence showed the old [pageLike]
         * term is *gamed by big quads* (a table+page union scores as high as the page itself),
         * because it only compares interior vs surround means.
         */
        val paperInterior: Double = 0.0,
        /**
         * SUBTASK H36 fix b — fraction of the candidate's edges that lie ON the frame border
         * (the "the table keeps going past the frame" continuation signature), see
         * [frameContactOf]. A continuous penalty, one signal among many — never a veto.
         */
        val frameContact: Double = 0.0,
        /** SUBTASK H36: rectangularity sub-score (kept for the offline rank simulator). */
        val rectangularity: Double = 0.0,
        /** SUBTASK H36: angle-regularity sub-score (kept for the offline rank simulator). */
        val angleScore: Double = 0.0,
        /** SUBTASK H36: centre sub-score (kept for the offline rank simulator). */
        val centerScore: Double = 0.0,
        /**
         * SUBTASK H36 — the WINNER-selection score. Acceptance is unchanged (`score >=
         * [MIN_SCORE]`), so the detection count is preserved by construction; this only decides
         * WHICH accepted candidate is returned.
         *
         * Deliberately **area-free**: the H35 cue evidence (`tooling/out/h36/step1_cue_analysis.md`)
         * shows the old `maxBy(score)` is won by the LARGEST quad (W_AREA=0.25 rewards coverage),
         * which is exactly the table/frame-union failure — bad winners score 0.78-0.90 with
         * `area=0.59..0.91` while the page-sized candidates in the same pool lose by 0.10-0.22 of
         * score. Rank keeps only page-evidence terms (fix a: paper interior; fix b: frame-contact
         * penalty) over the shape/quality terms that are not size-biased.
         */
        val rank: Double
    )

    /**
     * SUBTASK H36 — one coarse luma grid per [detectClassical] call, shared by every candidate's
     * interior/frame scoring. Bulk-reads the blurred gray Mat once (no per-sample JNI `Mat.get`),
     * so the added cost is a few microseconds per candidate.
     */
    private class LumaGrid(
        val gw: Int,
        val gh: Int,
        val w: Double,
        val h: Double,
        val data: FloatArray,
        val p95: Float
    ) {
        fun at(cx: Int, cy: Int): Float = data[cy * gw + cx]
    }

    /** Builds the 64x64 luma grid (and its frame p95) for [paperInteriorOf] / [frameContactOf]. */
    private fun buildLumaGrid(gray: Mat, w: Double, h: Double): LumaGrid {
        val gw = GRID_W
        val gh = GRID_H
        val data = FloatArray(gw * gh)
        val rows = gray.rows()
        val cols = gray.cols()
        if (rows <= 0 || cols <= 0) return LumaGrid(gw, gh, w, h, data, 255f)
        val bytes = ByteArray(rows * cols)
        try {
            gray.get(0, 0, bytes)
        } catch (t: Throwable) {
            return LumaGrid(gw, gh, w, h, data, 255f)
        }
        for (cy in 0 until gh) {
            val y0 = (cy.toLong() * rows / gh).toInt().coerceIn(0, rows - 1)
            val y1 = ((cy + 1).toLong() * rows / gh).toInt().coerceIn(y0 + 1, rows)
            for (cx in 0 until gw) {
                val x0 = (cx.toLong() * cols / gw).toInt().coerceIn(0, cols - 1)
                val x1 = ((cx + 1).toLong() * cols / gw).toInt().coerceIn(x0 + 1, cols)
                var sum = 0L
                var n = 0
                for (y in y0 until y1) {
                    val row = y * cols
                    for (x in x0 until x1) {
                        sum += (bytes[row + x].toInt() and 0xFF)
                        n++
                    }
                }
                data[cy * gw + cx] = if (n > 0) (sum.toFloat() / n) else 0f
            }
        }
        val sorted = data.clone().sortedArray()
        val p95 = sorted[((sorted.size - 1) * 95) / 100].coerceAtLeast(1f)
        return LumaGrid(gw, gh, w, h, data, p95)
    }

    /**
     * SUBTASK H36 fix a — "paper-likeness" of the candidate's INTERIOR, on the coarse grid.
     * SUBTASK H38 — **hue-agnostic**: the old version anchored on the FRAME's brightest levels
     * ("paper is the bright thing"), which is exactly the assumption that made DYED paper (e.g.
     * a green sheet) lose the rank to its own surround. The three terms now judge only the
     * interior's own distribution shape — a green page scores like a white one:
     *  1. **uniformity** — the paper half (above the median) is a tight cluster;
     *  2. **ink contrast** — the dark tail sits clearly below the paper mode (text/ruled lines);
     *     both "blank" and "table chaos" tails lose credit;
     *  3. **texture band** — the bright half has low std (a table gradient / laminate sheen
     *     spreads it).
     * Returns `[0,1]`; degenerate interiors return 0.5 (neutral for the rank).
     */
    private fun paperInteriorOf(quad: Quad, grid: LumaGrid): Double {
        val pts = quad.points
        if (pts.size != 4) return 0.5
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (p in pts) {
            val x = p.x.toDouble()
            val y = p.y.toDouble()
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (maxX - minX < 4.0 || maxY - minY < 4.0) return 0.5
        val samples = ArrayList<Float>(384)
        for (j in 0 until 24) {
            val fy = minY + (maxY - minY) * (j + 0.5) / 24.0
            for (i in 0 until 24) {
                val fx = minX + (maxX - minX) * (i + 0.5) / 24.0
                if (!pointInQuad(fx, fy, pts)) continue
                val cx = (fx / grid.w * grid.gw).toInt().coerceIn(0, grid.gw - 1)
                val cy = (fy / grid.h * grid.gh).toInt().coerceIn(0, grid.gh - 1)
                samples.add(grid.at(cx, cy))
            }
        }
        val n = samples.size
        if (n < 48) return 0.5
        samples.sort()
        // H38: percentiles of the INTERIOR only — no frame reference, no brightness assumption.
        val p10 = samples[n / 10]
        val p50 = samples[n / 2]
        val p90 = samples[(n * 9) / 10]
        val uniformity = clamp01(1.0 - ((p90 - p50) - 22.0) / 70.0)
        val inkContrast = clamp01(((p50 - p10) - 15.0) / 45.0)
        var cnt = 0
        var sum = 0.0
        var sum2 = 0.0
        for (v in samples) {
            if (v >= p50) {
                sum += v
                sum2 += v * v
                cnt++
            }
        }
        val brightStd = if (cnt > 1) {
            sqrt(max(0.0, sum2 / cnt - (sum / cnt) * (sum / cnt)))
        } else {
            99.0
        }
        val texture = clamp01(1.0 - (brightStd - 14.0) / 26.0)
        return (0.35 * uniformity + 0.35 * inkContrast + 0.30 * texture).coerceIn(0.0, 1.0)
    }

    /**
     * SUBTASK H36 fix b — fraction of the candidate's edges that LIE ON the frame border (a quad
     * edge "continues past the frame" when its whole run hugs the border: the table/frame the
     * edge belongs to keeps going outside the picture). Continuous in `[0,1]`, applied as a rank
     * penalty — deliberately not the H35 frame-cut VETO, which measured a -58 detection recall.
     */
    private fun frameContactOf(quad: Quad, grid: LumaGrid): Double {
        val pts = quad.points
        if (pts.size != 4) return 0.0
        val tol = 0.02 * min(grid.w, grid.h)
        var onEdges = 0
        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            var on = 0
            for (k in 0 until 9) {
                val t = k / 8.0
                val x = a.x + (b.x - a.x) * t
                val y = a.y + (b.y - a.y) * t
                val d = min(min(x, y), min(grid.w - 1 - x, grid.h - 1 - y))
                if (d <= tol) on++
            }
            if (on >= 7) onEdges++
        }
        return onEdges / 4.0
    }

    /**
     * SUBTASK H36 fix d — H32's partial-page / frame-cut pixel cue on the classical path:
     * for each QUAD border that the candidate's bbox reaches, sample the frame-edge patch
     * outside that border; when it is still PAPER (bright like the interior) the candidate is
     * a sub-page of something larger (a table cut by the frame, a page continued past the
     * frame). Returns the fraction of reached borders that are still paper, `[0, 1]`; 0.0 on
     * degenerate input (neutral for the rank).
     */
    private fun frameCutPenaltyOf(quad: Quad, grid: LumaGrid): Double {
        val pts = quad.points
        if (pts.size != 4 || grid.data.isEmpty()) return 0.0
        val tol = 0.02 * min(grid.w, grid.h)
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (p in pts) {
            val px = p.x.toDouble()
            val py = p.y.toDouble()
            if (px < minX) minX = px
            if (px > maxX) maxX = px
            if (py < minY) minY = py
            if (py > maxY) maxY = py
        }
        // The candidate's own interior brightness (the paper reference).
        var isum = 0.0
        var icount = 0
        val step = max(grid.w, grid.h) / 24.0
        var gy = minY
        while (gy <= maxY) {
            var gx = minX
            while (gx <= maxX) {
                if (pointInQuad(gx, gy, pts)) {
                    isum += grid.at((gx * grid.gw / grid.w).toInt(), (gy * grid.gh / grid.h).toInt())
                    icount++
                }
                gx += step
            }
            gy += step
        }
        if (icount < 8) return 0.0
        val paper = isum / icount
        var reached = 0
        var stillPaper = 0
        // A border "reached" -> the 5 px frame patch next to it (mid 50 % of the run).
        if (minX <= tol) { reached++; if (patchPaper(0.0, 3.0, minY + 0.25 * (maxY - minY), minY + 0.75 * (maxY - minY), grid) >= 0.70 * paper) stillPaper++ }
        if (maxX >= grid.w - 1 - tol) { reached++; if (patchPaper(grid.w - 4.0, grid.w - 1.0, minY + 0.25 * (maxY - minY), minY + 0.75 * (maxY - minY), grid) >= 0.70 * paper) stillPaper++ }
        if (minY <= tol) { reached++; if (patchPaper(minX + 0.25 * (maxX - minX), minX + 0.75 * (maxX - minX), 0.0, 3.0, grid) >= 0.70 * paper) stillPaper++ }
        if (maxY >= grid.h - 1 - tol) { reached++; if (patchPaper(minX + 0.25 * (maxX - minX), minX + 0.75 * (maxX - minX), grid.h - 4.0, grid.h - 1.0, grid) >= 0.70 * paper) stillPaper++ }
        return if (reached == 0) 0.0 else stillPaper.toDouble() / reached
    }

    /** Mean luma of the grid cells in the axis-aligned frame patch (framed coords). */
    private fun patchPaper(x0: Double, x1: Double, y0: Double, y1: Double, grid: LumaGrid): Double {
        var sum = 0.0; var n = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val cx = (x * grid.gw / grid.w).toInt().coerceIn(0, grid.gw - 1)
                val cy = (y * grid.gh / grid.h).toInt().coerceIn(0, grid.gh - 1)
                sum += grid.at(cx, cy)
                n++
                x += max(grid.w, grid.h) / 64.0
            }
            y += max(grid.w, grid.h) / 64.0
        }
        return if (n > 0) sum / n else 0.0
    }

    /** Convex point-in-quad (cross-product sign test) for interior sampling. */
    private fun pointInQuad(x: Double, y: Double, pts: List<Point>): Boolean {
        var sign = 0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            val cross = (b.x - a.x).toDouble() * (y - a.y) - (b.y - a.y).toDouble() * (x - a.x)
            if (abs(cross) < 1e-6) continue
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    private fun clamp01(v: Double): Double = if (v < 0.0) 0.0 else if (v > 1.0) 1.0 else v

    /**
     * **Cue 1 — edges.** Canny ∪ Otsu-Sobel gradient → morphological close → contours → quads.
     * Best for high-contrast page borders (dark desk, strong shadow line).
     */
    private fun collectEdgeCue(
        blur: Mat,
        w: Double,
        h: Double,
        out: MutableList<Candidate>,
        grid: LumaGrid? = null
    ) {
        val edges = Mat()
        val closed = Mat()
        try {
            buildEdges(blur, edges)
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
            try {
                Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, k)
            } finally {
                k.release()
            }
            collectContourCandidates(closed, blur, w, h, "edges", out, grid)
        } finally {
            edges.release(); closed.release()
        }
    }

    /**
     * **Cue 2 — intensity thresholds.** Otsu (both polarities) and an adaptive Gaussian threshold,
     * each morphologically closed. A page is usually the single largest *uniform bright* region, so
     * this cue recovers low-contrast pages (white paper on a white desk) that the edge cue misses.
     */
    private fun collectThresholdCues(
        blur: Mat,
        w: Double,
        h: Double,
        out: MutableList<Candidate>,
        grid: LumaGrid? = null
    ) {
        val otsu = Mat()
        val inv = Mat()
        try {
            Imgproc.threshold(blur, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            Core.bitwise_not(otsu, inv)
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
            try {
                val c1 = Mat()
                try {
                    Imgproc.morphologyEx(otsu, c1, Imgproc.MORPH_CLOSE, k)
                    collectContourCandidates(c1, blur, w, h, "otsu", out, grid)
                } finally {
                    c1.release()
                }
                val c2 = Mat()
                try {
                    Imgproc.morphologyEx(inv, c2, Imgproc.MORPH_CLOSE, k)
                    collectContourCandidates(c2, blur, w, h, "otsu_inv", out, grid)
                } finally {
                    c2.release()
                }
            } finally {
                k.release()
            }
        } finally {
            otsu.release(); inv.release()
        }

        val adapt = Mat()
        try {
            Imgproc.adaptiveThreshold(
                blur, adapt, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, ADAPT_BLOCK, ADAPT_C
            )
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            try {
                val c = Mat()
                try {
                    Imgproc.morphologyEx(adapt, c, Imgproc.MORPH_CLOSE, k)
                    collectContourCandidates(c, blur, w, h, "adaptive", out, grid)
                } finally {
                    c.release()
                }
            } finally {
                k.release()
            }
        } finally {
            adapt.release()
        }
    }

    /**
     * **Cue 3 — Hough lines.** Long straight borders are voted for with a probabilistic Hough
     * transform; the convex hull of the surviving line endpoints is approximated to a 4-gon. This
     * recovers pages whose border exists as *segments* but never closes into a single contour.
     */
    private fun collectHoughCue(
        blur: Mat,
        w: Double,
        h: Double,
        out: MutableList<Candidate>,
        grid: LumaGrid? = null
    ) {
        val edges = Mat()
        val lines = Mat()
        try {
            val mean = Core.mean(blur).`val`[0]
            val lower = (0.66 * mean).coerceIn(10.0, 120.0)
            val upper = (1.33 * mean).coerceIn(lower + 40.0, 255.0)
            Imgproc.Canny(blur, edges, lower, upper, 3, true)
            Imgproc.HoughLinesP(
                edges, lines, 1.0, Math.PI / 180.0,
                HOUGH_THRESHOLD,
                min(w, h) * HOUGH_MIN_LEN_FRAC,
                min(w, h) * HOUGH_MAX_GAP_FRAC
            )
            if (lines.rows() < 4) return

            val pts = ArrayList<CvPoint>(lines.rows() * 2)
            for (i in 0 until lines.rows()) {
                val l = lines.get(i, 0) ?: continue
                if (l.size < 4) continue
                pts.add(CvPoint(l[0], l[1]))
                pts.add(CvPoint(l[2], l[3]))
            }
            if (pts.size < 4) return

            val hullPts = MatOfPoint(*pts.toTypedArray())
            val hull = MatOfInt()
            try {
                Imgproc.convexHull(hullPts, hull, false)
                val idx = hull.toArray()
                if (idx.size < 4) return
                val hp = Array(idx.size) { pts[idx[it]] }
                val h2f = MatOfPoint2f(*hp)
                try {
                    val perim = Imgproc.arcLength(h2f, true)
                    if (perim <= 0.0) return
                    for (eps in EPSILON_FRACTIONS) {
                        val approx = MatOfPoint2f()
                        try {
                            Imgproc.approxPolyDP(h2f, approx, eps * perim, true)
                            if (approx.total() != 4L) continue
                            val ap = approx.toArray()
                            if (!Imgproc.isContourConvex(MatOfPoint(*ap))) continue
                            if (!isSaneAspect(ap)) continue
                            val quad = orderCorners(ap)
                            if (isFrameHugging(quad.points, w, h)) continue
                            out.add(buildCandidate(quad, "hough", blur, w, h, grid))
                            break
                        } finally {
                            approx.release()
                        }
                    }
                } finally {
                    h2f.release()
                }
            } finally {
                hullPts.release(); hull.release()
            }
        } finally {
            edges.release(); lines.release()
        }
    }

    /**
     * Shared contour → quad extraction: area gate → (several `approxPolyDP` epsilons) → exactly 4
     * vertices → convex → sane aspect → scored [Candidate]. Trying several epsilons is essential on
     * real photos, where a single epsilon rarely lands on exactly 4 vertices.
     */
    private fun collectContourCandidates(
        bin: Mat,
        refGray: Mat,
        w: Double,
        h: Double,
        source: String,
        out: MutableList<Candidate>,
        grid: LumaGrid? = null
    ) {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.findContours(
                bin, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
        } finally {
            hierarchy.release()
        }
        try {
            val frameArea = w * h
            for (contour in contours) {
                val area = abs(Imgproc.contourArea(contour))
                if (area < MIN_AREA_RATIO * frameArea) continue
                val c2f = MatOfPoint2f(*contour.toArray())
                try {
                    val perim = Imgproc.arcLength(c2f, true)
                    if (perim <= 0.0) continue
                    for (eps in EPSILON_FRACTIONS) {
                        val approx = MatOfPoint2f()
                        try {
                            Imgproc.approxPolyDP(c2f, approx, eps * perim, true)
                            if (approx.total() != 4L) continue
                            val pts = approx.toArray()
                            if (!Imgproc.isContourConvex(MatOfPoint(*pts))) continue
                            if (!isSaneAspect(pts)) continue
                            // Reject the whole-frame rectangle: it is a saturated threshold (or the
                            // image border), never a page boundary. Removing it lets the genuine
                            // interior page contour win, and stops not-a-document scenes (e.g. the
                            // texture-only negative) from being "detected" as a full-frame page.
                            val quad = orderCorners(pts)
                            if (isFrameHugging(quad.points, w, h)) continue
                            out.add(buildCandidate(quad, source, refGray, w, h, grid))
                            break
                        } finally {
                            approx.release()
                        }
                    }
                } finally {
                    c2f.release()
                }
                // SUBTASK H36 fix e — CURVATURE-TOLERANT BORDER FOLLOWING (the curved-page class).
                // A bowed/bumpy page border defeats strict `approxPolyDP` (no 4-gon at all, or a
                // 4-gon on an inner text block). The page's TRUE corners are the contour's extremes
                // along its own diagonals (x+y / x-y): pure geometry, no straight-edge assumption,
                // offered to the SAME rank as every other candidate so it wins only when it earns it.
                if (CURVE_CUE) {
                    val arr = contour.toArray()
                    if (arr.size >= 24) {
                        var tl = arr[0]; var tr = arr[0]; var br = arr[0]; var bl = arr[0]
                        var sMin = Double.MAX_VALUE; var sMax = -Double.MAX_VALUE
                        var dMin = Double.MAX_VALUE; var dMax = -Double.MAX_VALUE
                        for (p in arr) {
                            val s = p.x + p.y
                            val d = p.x - p.y
                            if (s < sMin) { sMin = s; tl = p }
                            if (s > sMax) { sMax = s; br = p }
                            if (d > dMax) { dMax = d; tr = p }
                            if (d < dMin) { dMin = d; bl = p }
                        }
                        val curvePts = arrayOf(tl, tr, br, bl)
                        val curveQuad = orderCorners(curvePts)
                        if (isSaneAspect(curvePts) &&
                            !isFrameHugging(curveQuad.points, w, h)
                        ) {
                            out.add(buildCandidate(curveQuad, "curve", refGray, w, h, grid))
                        }
                    }
                }
            }
        } finally {
            contours.forEach { runCatching { it.release() } }
        }
    }

    /**
     * Scores a quad with the documented, tuned metric (SUBTASK H3b). Five `[0,1]` sub-scores whose
     * weights ([W_AREA]+[W_RECT]+[W_ANGLE]+[W_CENTER]+[W_PAGE] = 1.0) make the result a
     * probability-like confidence:
     *  - **area**   — larger page coverage is better;
     *  - **rect**   — rectangularity `quadArea / minAreaRectArea`;
     *  - **angle**  — mean `1-|cos θ|` over the 4 corners (1 ⇒ right angles);
     *  - **center** — closeness to the frame centre;
     *  - **page**   — interior brightness/uniformity vs its surround (see [pageLikeness]); this is
     *                 the term that keeps a *page-like* region ahead of random textured regions and
     *                 is what suppresses the not-a-document negatives.
     */
    private fun buildCandidate(
        quad: Quad,
        source: String,
        refGray: Mat,
        w: Double,
        h: Double,
        grid: LumaGrid? = null
    ): Candidate {
        val pts = quad.points
        val frameArea = (w * h).coerceAtLeast(1.0)
        val area = polygonArea(pts)
        val areaRatio = (area / frameArea).coerceIn(0.0, 1.0)
        val rectangularity = minAreaRectArea(pts).let { rect ->
            if (rect > 0.0) (area / rect).coerceIn(0.0, 1.0) else 0.0
        }
        val angleScore = angleRegularity(pts)
        val cx = pts.sumOf { it.x.toDouble() } / pts.size
        val cy = pts.sumOf { it.y.toDouble() } / pts.size
        val maxDist = hypot(w / 2.0, h / 2.0).coerceAtLeast(1.0)
        val centerScore = (1.0 - hypot(cx - w / 2.0, cy - h / 2.0) / maxDist).coerceIn(0.0, 1.0)
        val pageLike = pageLikeness(quad, refGray)
        // SUBTASK H36 fix a/b: the interior / frame-contact signals (neutral when no grid —
        // e.g. the DexiNed path, which does not pass one).
        val paper = if (grid != null) paperInteriorOf(quad, grid) else 0.5
        val frame = if (grid != null) frameContactOf(quad, grid) else 0.0
        // SUBTASK H36 fix d — the H32 partial-page / frame-cut protection on the CLASSICAL
        // path: a quad whose frame border is STILL PAPER is cut out of a larger page (or is
        // a table continuing past the frame), which is the wrong-page class. Rank penalty
        // only — the H35 veto version measured -58 detections and stays rejected.
        val cut = if (grid != null) frameCutPenaltyOf(quad, grid) else 0.0

        val score = (
            W_AREA * areaRatio +
                W_RECT * rectangularity +
                W_ANGLE * angleScore +
                W_CENTER * centerScore +
                W_PAGE * pageLike
            ).toFloat()
        // SUBTASK H36 — area-free winner rank (see [Candidate.rank]); acceptance stays on [score].
        val rank = (
            W_RANK_PAPER * paper +
                W_RANK_PAGE * pageLike +
                W_RANK_RECT * rectangularity +
                W_RANK_ANGLE * angleScore +
                W_RANK_CENTER * centerScore -
                W_RANK_FRAME * frame -
                W_RANK_CUT * cut
            )
        return Candidate(
            quad, score, source, areaRatio, pageLike, paper, frame,
            rectangularity, angleScore, centerScore, rank
        )
    }

    /**
     * True when [pts] (an ordered quad) essentially coincides with the image border — i.e. its
     * bounding box reaches all four image edges within [FRAME_HUG_TOL_FRAC]. Used to down-weight the
     * whole-frame candidate produced by a saturated threshold.
     */
    private fun isFrameHugging(pts: List<Point>, w: Double, h: Double): Boolean {
        if (pts.isEmpty()) return false
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (p in pts) {
            if (p.x < minX) minX = p.x.toDouble()
            if (p.y < minY) minY = p.y.toDouble()
            if (p.x > maxX) maxX = p.x.toDouble()
            if (p.y > maxY) maxY = p.y.toDouble()
        }
        val tolX = max(2.0, w * FRAME_HUG_TOL_FRAC)
        val tolY = max(2.0, h * FRAME_HUG_TOL_FRAC)
        return minX <= tolX && maxX >= w - tolX && minY <= tolY && maxY >= h - tolY
    }

    /**
     * "Page-likeness" of [quad]'s interior: how DISTINCT and how much more uniform it is than the
     * surrounding ring. SUBTASK H38 — hue-agnostic: a page can be any colour (green paper), so the
     * interior is compared to the surround by absolute level difference (either direction) plus
     * uniformity, never by "brighter than the table". Uses the supplied single-channel reference
     * image ([refGray]). Returns `[0,1]`; a degenerate/empty mask returns 0.0 (so it never *adds*
     * confidence to a bogus quad). Never throws.
     */
    private fun pageLikeness(quad: Quad, refGray: Mat): Double {
        if (refGray.empty()) return 0.0
        val mask = Mat.zeros(refGray.rows(), refGray.cols(), CvType.CV_8UC1)
        val inv = Mat()
        val mp = MatOfPoint(*quad.points.map { CvPoint(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val meanIn = MatOfDouble(); val stdIn = MatOfDouble()
        val meanOut = MatOfDouble(); val stdOut = MatOfDouble()
        try {
            Imgproc.fillConvexPoly(mask, mp, Scalar(255.0))
            val total = mask.rows() * mask.cols()
            if (Core.countNonZero(mask) < total / 50) return 0.0
            Core.bitwise_not(mask, inv)
            if (Core.countNonZero(inv) < 16) return 0.0
            Core.meanStdDev(refGray, meanIn, stdIn, mask)
            Core.meanStdDev(refGray, meanOut, stdOut, inv)
            // H38: |interior - surround| — distinctness in EITHER direction (dyed paper on a
            // white table scores the same as white paper on a dark table).
            val distinct = (abs(meanIn.toArray()[0] - meanOut.toArray()[0]) / PAGE_BRIGHT_DELTA)
                .coerceIn(0.0, 1.0)
            val uniform = (1.0 - stdIn.toArray()[0] / PAGE_STD_FULL).coerceIn(0.0, 1.0)
            return (0.55 * distinct + 0.45 * uniform).coerceIn(0.0, 1.0)
        } catch (t: Throwable) {
            return 0.0
        } finally {
            mask.release(); inv.release(); mp.release()
            meanIn.release(); stdIn.release(); meanOut.release(); stdOut.release()
        }
    }

    /** Compact `%.3f` formatter (US locale) for the detection diagnostics log. */
    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)
    private fun fmt(v: Float): String = fmt(v.toDouble())

    /** Rejects clearly degenerate aspect ratios (e.g. a sliver) before scoring. */
    private fun isSaneAspect(pts: Array<CvPoint>): Boolean {
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (p in pts) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        val w = maxX - minX
        val h = maxY - minY
        if (w <= 1.0 || h <= 1.0) return false
        val ratio = max(w, h) / min(w, h)
        return ratio <= MAX_ASPECT_RATIO
    }

    /** Average over the 4 corners of `1 - |cos(angle)|` (1.0 when every corner is a right angle). */
    private fun angleRegularity(pts: List<Point>): Double {
        val n = pts.size
        if (n < 3) return 0.0
        var acc = 0.0
        for (i in 0 until n) {
            val prev = pts[(i + n - 1) % n]
            val cur = pts[i]
            val next = pts[(i + 1) % n]
            val v1x = (prev.x - cur.x).toDouble(); val v1y = (prev.y - cur.y).toDouble()
            val v2x = (next.x - cur.x).toDouble(); val v2y = (next.y - cur.y).toDouble()
            val m1 = hypot(v1x, v1y); val m2 = hypot(v2x, v2y)
            if (m1 < 1e-6 || m2 < 1e-6) continue
            val cosA = ((v1x * v2x + v1y * v2y) / (m1 * m2)).coerceIn(-1.0, 1.0)
            acc += 1.0 - abs(cosA)
        }
        return acc / n
    }

    /** Shoelace polygon area (absolute). */
    private fun polygonArea(pts: List<Point>): Double {
        if (pts.size < 3) return 0.0
        var sum = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            sum += a.x.toDouble() * b.y.toDouble() - b.x.toDouble() * a.y.toDouble()
        }
        return abs(sum) / 2.0
    }

    private fun minAreaRectArea(pts: List<Point>): Double {
        if (pts.size < 3) return 0.0
        val m2f = MatOfPoint2f(*pts.map { CvPoint(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        return try {
            val rr = Imgproc.minAreaRect(m2f)
            (rr.size.width * rr.size.height).coerceAtLeast(0.0)
        } catch (t: Throwable) {
            0.0
        } finally {
            m2f.release()
        }
    }

    /**
     * Robust TL, TR, BR, BL ordering via the sum/difference heuristic:
     *  - `x + y` minimum → top-left, maximum → bottom-right;
     *  - `x - y` maximum → top-right, minimum → bottom-left.
     */
    private fun orderCorners(pts: Array<CvPoint>): Quad {
        var tl = pts[0]; var br = pts[0]; var tr = pts[0]; var bl = pts[0]
        var minSum = Double.MAX_VALUE; var maxSum = -Double.MAX_VALUE
        var minDiff = Double.MAX_VALUE; var maxDiff = -Double.MAX_VALUE
        for (p in pts) {
            val s = p.x + p.y
            val d = p.x - p.y
            if (s < minSum) { minSum = s; tl = p }
            if (s > maxSum) { maxSum = s; br = p }
            if (d < minDiff) { minDiff = d; bl = p }
            if (d > maxDiff) { maxDiff = d; tr = p }
        }
        return Quad(
            topLeft = Point(tl.x.toFloat(), tl.y.toFloat()),
            topRight = Point(tr.x.toFloat(), tr.y.toFloat()),
            bottomRight = Point(br.x.toFloat(), br.y.toFloat()),
            bottomLeft = Point(bl.x.toFloat(), bl.y.toFloat())
        )
    }

    // ==========================================================================================
    // M1 DexiNed (optional ONNX path)
    // ==========================================================================================

    /**
     * // M1 DexiNed — optional boundary-model path (preferred when the artifact is present).
     *
     * **Pinned artifact:** the OpenCV Zoo DexiNed export
     * `edge_detection_dexined_2024sep.onnx` (MIT, `opencv/edge_detection_dexined`). Verified
     * graph I/O (see `MODEL_SOURCES.md`): single input `img` **fixed `[1,3,480,640]`** and **7
     * outputs** (`out_1..out_6` + `block_cat`); the final fused edge map is `block_cat`
     * (OpenCV's reference pipeline uses the last output).
     *
     * Pipeline: plain resize (aspect not preserved, exactly like OpenCV Zoo) to the graph size →
     * RGB→**BGR** → subtract the Caffe BGR mean (no /255) → NCHW float32 → run → `sigmoid` the
     * **logit** map → threshold → close → contours → `approxPolyDP` → best convex quad. The
     * result is returned in `[bitmap]` pixel coordinates via an inverse resize.
     */
    private fun detectWithModel(bitmap: Bitmap, model: OnnxModel): Quad? {
        val (modelW, modelH) = resolveModelInputSize(model)
        val input = preprocessForDexiNed(bitmap, modelW, modelH)
        if (input == null) {
            Diag.log(TAG, "neural detect: preprocess failed (${bitmap.width}x${bitmap.height} -> ${modelW}x$modelH)")
            return null
        }

        // Prefer the fused output by name; fall back to the last declared output.
        val outputName = if (model.outputNames.contains(FUSED_OUTPUT_NAME)) {
            FUSED_OUTPUT_NAME
        } else {
            model.outputNames.lastOrNull()
        }

        val edgeMap = model.runFloat(
            input,
            longArrayOf(1L, 3L, modelH.toLong(), modelW.toLong()),
            outputName = outputName
        )
        if (edgeMap == null) {
            Diag.log(TAG, "neural detect: inference returned null (outputName=$outputName)")
            return null
        }
        // SUBTASK H3b diagnosis: this silent return was the primary reason "AI ON finds nothing"
        // was undiagnosable — now it is logged with the exact sizes.
        if (edgeMap.size < modelW * modelH) {
            Diag.log(
                TAG,
                "neural detect: output too small size=${edgeMap.size} need>=${modelW * modelH} " +
                    "(outputName=$outputName)"
            )
            return null
        }
        return postprocessEdgeMap(edgeMap, modelW, modelH, bitmap.width, bitmap.height)
    }

    /**
     * Reads the graph's spatial input size (`[1,3,H,W]`) and returns `(W, H)`. Falls back to the
     * pinned export's `480×640` when the shape is dynamic/unknown.
     */
    private fun resolveModelInputSize(model: OnnxModel): Pair<Int, Int> {
        val shape = model.inputShape
        if (shape != null && shape.size == 4 && shape[2] > 0 && shape[3] > 0) {
            return shape[3].toInt() to shape[2].toInt() // (W, H)
        }
        return DEXINED_DEFAULT_W to DEXINED_DEFAULT_H
    }

    /**
     * // M1 DexiNed preprocess — plain resize to `modelW × modelH` (aspect NOT preserved, matching
     * the OpenCV Zoo reference), channels reordered RGB→**BGR**, Caffe BGR mean subtracted on the
     * 0–255 scale (no /255), packed as NCHW float32.
     */
    private fun preprocessForDexiNed(bitmap: Bitmap, modelW: Int, modelH: Int): FloatBuffer? {
        if (modelW <= 0 || modelH <= 0) return null
        val resized = Bitmap.createScaledBitmap(bitmap, modelW, modelH, true)
        val pixels = IntArray(modelW * modelH)
        resized.getPixels(pixels, 0, modelW, 0, 0, modelW, modelH)
        if (resized !== bitmap) resized.recycle()

        val plane = modelW * modelH
        val buffer = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // NCHW with BGR channel order (DexiNed / Caffe convention), mean on the 0–255 scale.
            buffer.put(i, b - DEXINED_BGR_MEAN[0])
            buffer.put(plane + i, g - DEXINED_BGR_MEAN[1])
            buffer.put(2 * plane + i, r - DEXINED_BGR_MEAN[2])
        }
        buffer.rewind()
        return buffer
    }

    /**
     * // M1 DexiNed postprocess (SUBTASK H3b) — turn the boundary model's heat map into a quad.
     *
     * The DexiNed `block_cat` output is a **saliency map whose numeric range is not guaranteed**
     * (raw logits on some exports, already-sigmoid probabilities on others). The former code did
     * `sigmoid()` then a hard `> 0.5` gate, which collapses to an empty (or fully-saturated) mask
     * on real photos and therefore yields NO candidate quad — the primary reason "AI ON finds
     * nothing". This version is range-agnostic:
     *   1. log the map's `[1,1,H,W]` shape and min/max range (diagnosability);
     *   2. normalise to the model plane `[0,1]` (min–max) and convert to 8-bit;
     *   3. **Otsu**-threshold the 8-bit saliency (adaptive — no fixed constant);
     *   4. close so broken boundary edges join into a closed page outline;
     *   5. resize the mask to the frame and run the SAME multi-epsilon contour → quad scoring as
     *      the classical cues ([collectContourCandidates]);
     *   6. return the best candidate that clears [MIN_SCORE], else `null` (→ classical fallback).
     * Never throws; every intermediate `Mat` is released.
     */
    private fun postprocessEdgeMap(
        edgeMap: FloatArray,
        modelW: Int,
        modelH: Int,
        frameW: Int,
        frameH: Int
    ): Quad? {
        val plane = modelW * modelH
        if (plane <= 0 || edgeMap.size < plane) {
            Diag.log(TAG, "neural boundary map too small: size=${edgeMap.size} need=$plane")
            return null
        }
        val sal = Mat(modelH, modelW, CvType.CV_32FC1)
        val u8 = Mat()
        val bin = Mat()
        val closed = Mat()
        val resized = Mat()
        try {
            // 1) Range probe (logits vs probabilities both supported).
            var mn = Float.MAX_VALUE
            var mx = -Float.MAX_VALUE
            for (i in 0 until plane) {
                val v = edgeMap[i]
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            val span = (mx - mn).coerceAtLeast(1e-6f)
            val norm = FloatArray(plane) { (edgeMap[it] - mn) / span }
            sal.put(0, 0, norm)
            sal.convertTo(u8, CvType.CV_8U, 255.0)

            // 2) Adaptive (Otsu) threshold — works for logits AND probabilities.
            Imgproc.threshold(u8, bin, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)

            // 3) Close gaps so the boundary edges form a closed contour around the page.
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
            try {
                Imgproc.morphologyEx(bin, closed, Imgproc.MORPH_CLOSE, k)
            } finally {
                k.release()
            }

            // 4) Resize the mask over the full frame (inverse of the squash).
            Imgproc.resize(
                closed, resized,
                Size(frameW.toDouble(), frameH.toDouble()),
                0.0, 0.0, Imgproc.INTER_NEAREST
            )

            val candidates = ArrayList<Candidate>()
            collectContourCandidates(
                resized, resized, frameW.toDouble(), frameH.toDouble(), "neural", candidates
            )
            val best = candidates.filter { it.score >= MIN_SCORE }.maxByOrNull { it.score }

            Diag.log(
                TAG,
                "neural boundary map shape=[1,1,$modelH,$modelW] range=[${fmt(mn)},${fmt(mx)}] " +
                    "candidates=${candidates.size} decision=" +
                    (best?.let { "quad(${it.source},score=${fmt(it.score)})" } ?: "null(->classical)")
            )
            return best?.quad
        } catch (t: Throwable) {
            Log.w(TAG, "neural boundary postprocess failed", t)
            return null
        } finally {
            sal.release(); u8.release(); bin.release(); closed.release(); resized.release()
        }
    }

    // --- geometry helpers (all coordinates in a single, documented space) --------------------

    /** Scales a quad by independent x/y factors, preserving corner order. */
    private fun scaleQuad(quad: Quad, sx: Double, sy: Double): Quad = Quad(
        topLeft = Point((quad.topLeft.x * sx).toFloat(), (quad.topLeft.y * sy).toFloat()),
        topRight = Point((quad.topRight.x * sx).toFloat(), (quad.topRight.y * sy).toFloat()),
        bottomRight = Point((quad.bottomRight.x * sx).toFloat(), (quad.bottomRight.y * sy).toFloat()),
        bottomLeft = Point((quad.bottomLeft.x * sx).toFloat(), (quad.bottomLeft.y * sy).toFloat())
    )

    /** Translates a quad by `(dx, dy)`, preserving corner order. */
    private fun offsetQuad(quad: Quad, dx: Float, dy: Float): Quad = Quad(
        topLeft = Point(quad.topLeft.x + dx, quad.topLeft.y + dy),
        topRight = Point(quad.topRight.x + dx, quad.topRight.y + dy),
        bottomRight = Point(quad.bottomRight.x + dx, quad.bottomRight.y + dy),
        bottomLeft = Point(quad.bottomLeft.x + dx, quad.bottomLeft.y + dy)
    )

    // ==========================================================================================
    // SUBTASK H26 — seed-consistency guard for the seeded refine() path
    // ==========================================================================================

    /** Verdict of [seedDecision]; logged one line per refine() decision. */
    private data class SeedDecision(
        val accept: Boolean,
        val iou: Double,
        val areaRatio: Double,
        val inset: Double,
        val reason: String
    )

    /**
     * Decides whether [candidate] (the 4-gon that won inside the refine crop) may replace [seed].
     *
     * The seeded path exists to *tighten* a good seed, never to re-choose the page. Inside a
     * 1.15x crop the page border can be outside or clipped at the crop edge, so the strongest
     * complete 4-gon is frequently an inner text block, a table frame or a ruled margin. The
     * candidate is therefore accepted only when it is demonstrably the same page as the seed:
     *
     *  - `IoU(candidate, seed) >= [SEED_MIN_IOU]`;
     *  - `area(candidate) / area(seed)` in `[[SEED_MIN_AREA_RATIO], [SEED_MAX_AREA_RATIO]]`;
     *  - **containment sanity**: the candidate may not sit strictly inside the seed with a
     *    corner inset larger than [REFINE_MARGIN] (a shrunken sub-region of the seed).
     *
     * Rejections are never silent: the caller logs `[Refine] seedIou=… area=… accept=… reason=…`.
     * Never throws (a failure to measure rejects, i.e. keeps the seed).
     */
    private fun seedDecision(candidate: Quad, seed: Quad): SeedDecision {
        return try {
            val seedArea = polygonArea(seed.points)
            val candArea = polygonArea(candidate.points)
            if (seedArea <= 1e-6 || candArea <= 1e-6) {
                return SeedDecision(false, 0.0, 0.0, 0.0, "degenerate")
            }
            val iou = AutoCaptureStability.iouOf(candidate, seed)
            val areaRatio = candArea / seedArea
            val inset = maxContainedInset(candidate, seed) ?: -1.0
            val reason = when {
                iou < SEED_MIN_IOU -> "iou"
                areaRatio < SEED_MIN_AREA_RATIO -> "area_small"
                areaRatio > SEED_MAX_AREA_RATIO -> "area_large"
                inset > REFINE_MARGIN -> "contained"
                else -> "ok"
            }
            SeedDecision(reason == "ok", iou, areaRatio, inset, reason)
        } catch (t: Throwable) {
            Diag.logThrowable("Refine", t)
            SeedDecision(false, 0.0, 0.0, 0.0, "error")
        }
    }

    /**
     * Returns the largest normalized corner inset when **every** corner of [candidate] is inside
     * [seed] (a strict sub-region), or `null` when the candidate is not fully contained.
     *
     * The inset is the perpendicular distance from the candidate's corner to the nearest seed edge,
     * divided by the seed's shorter bounding-box side, so it is comparable with [REFINE_MARGIN].
     */
    private fun maxContainedInset(candidate: Quad, seed: Quad): Double? {
        val seedPts = seed.points
        if (seedPts.size != 4 || candidate.points.size != 4) return null
        if (candidate.points.any { !pointInConvexQuad(it, seedPts) }) return null
        val xs = seedPts.map { it.x }
        val ys = seedPts.map { it.y }
        val shortSide = min((xs.max() - xs.min()).toDouble(), (ys.max() - ys.min()).toDouble())
            .coerceAtLeast(1.0)
        var worst = 0.0
        for (p in candidate.points) {
            var nearest = Double.MAX_VALUE
            for (i in 0 until 4) {
                val d = distanceToSegment(
                    p.x.toDouble(), p.y.toDouble(),
                    seedPts[i].x.toDouble(), seedPts[i].y.toDouble(),
                    seedPts[(i + 1) % 4].x.toDouble(), seedPts[(i + 1) % 4].y.toDouble()
                )
                if (d < nearest) nearest = d
            }
            if (nearest.isFinite() && nearest / shortSide > worst) worst = nearest / shortSide
        }
        return worst
    }

    /** True when [p] lies inside (or on) the convex polygon [poly] (any vertex order). */
    private fun pointInConvexQuad(p: Point, poly: List<Point>): Boolean {
        if (poly.size < 3) return false
        var positive = false
        var negative = false
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val cross = (b.x - a.x).toDouble() * (p.y - a.y).toDouble() -
                (b.y - a.y).toDouble() * (p.x - a.x).toDouble()
            if (cross > 1e-6) positive = true
            if (cross < -1e-6) negative = true
            if (positive && negative) return false
        }
        return true
    }

    /** Perpendicular distance from `(px,py)` to the segment `(ax,ay)-(bx,by)`. */
    private fun distanceToSegment(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-9) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** `%.4f` formatter (US locale) for the `[Refine]` decision line. */
    private fun fmt4(v: Double): String = String.format(java.util.Locale.US, "%.4f", v)

    /**
     * Bounding box of [quad] expanded by [marginFraction] (of the box size), clamped to
     * `[0, width] × [0, height]`. Returns `null` for a degenerate box.
     */
    private fun expandBounds(quad: Quad, marginFraction: Float, width: Int, height: Int): Region? {
        val xs = quad.points.map { it.x }
        val ys = quad.points.map { it.y }
        var left = xs.min()
        var top = ys.min()
        var right = xs.max()
        var bottom = ys.max()
        if (right <= left || bottom <= top) return null

        val mx = (right - left) * marginFraction
        val my = (bottom - top) * marginFraction
        left -= mx; right += mx
        top -= my; bottom += my

        val l = left.toInt().coerceIn(0, width - 1)
        val t = top.toInt().coerceIn(0, height - 1)
        val r = right.toInt().coerceIn(l + 1, width)
        val b = bottom.toInt().coerceIn(t + 1, height)
        if (r - l < MIN_REGION_EDGE || b - t < MIN_REGION_EDGE) return null
        return Region(l, t, r - l, b - t)
    }

    /**
     * Resolves the optional boundary model.
     *
     * **SUBTASK F latch fix.** Previously this latched the *first* result — including a `null` —
     * against a provider that returns `null` while AI is OFF. Re-enabling the AI toggle at runtime
     * then did nothing until an app restart, because the latch kept returning the stale `null`.
     *
     * Now the latch is keyed on the current provider result: a **non-null** model is cached for good
     * (exactly one session), while a **`null` result leaves the latch OPEN** so the next [detect]
     * re-evaluates the provider. When AI is OFF the provider returns `null` *without touching*
     * `ServiceLocator.boundaryModel`, so this still creates/uses **zero** ONNX sessions; when the
     * user flips AI ON at runtime the very next detect loads the DexiNed model immediately.
     */
    private fun resolveModel(): OnnxModel? {
        // Already resolved a real model (or explicitly released): reuse the cached answer.
        if (modelResolved || released) return boundaryModel
        synchronized(modelLock) {
            if (modelResolved || released) return boundaryModel
            val resolved = try {
                modelProvider?.invoke()
            } catch (t: Throwable) {
                Log.w(TAG, "Boundary model load failed", t)
                null
            }
            boundaryModel = resolved
            // Latch ONLY a real model; a null keeps the latch open so a later AI-ON flip re-resolves.
            modelResolved = resolved != null
            if (resolved != null) {
                Diag.log("Detector", "boundary model resolved (latch closed)")
            }
            return resolved
        }
    }

    // ==========================================================================================
    // M1c DocAligner corner detector (SUBTASK H4 — PRIMARY AI detection path)
    // ==========================================================================================

    /**
     * // M1c DocAligner — corner-heatmap detector (PRIMARY when the artifact is present).
     *
     * **Pinned artifact:** `lcnet100_h_e_bifpn_256_fp32.onnx` (DocAligner LCNet100, Apache-2.0;
     * re-hosted on Hugging Face — see `MODEL_SOURCES.md`). Verified graph I/O: single input `img`
     * **fixed `[1,3,256,256]`** float32 (RGB, 0..1 via `/255`, NCHW, **no** mean/std) and a single
     * output `heatmap` `[1,4,128,128]` float32 that is **already sigmoid-activated**; the 4 channels
     * are the TL, TR, BR, BL corner heat maps.
     *
     * Pipeline: bounded resize (squash, aspect not preserved — matching upstream) to `256×256` →
     * RGB `/255` NCHW → run → per channel: peak confidence gate → resize the heat map to a bounded
     * working size → threshold at 0.3 → largest blob centroid (upstream's postprocess) → collect 4
     * points → order TL,TR,BR,BL → [isPlausiblePageQuad] gate. Any miss returns `null` so the caller
     * falls back to the classical path. Never throws; every `Mat` is released; input is bounded.
     */
    private fun detectWithCornerModel(
        bitmap: Bitmap,
        model: OnnxModel,
        trace: TraceDump.Session? = null
    ): Quad? {
        val start = System.currentTimeMillis()
        val edge: Int
        val heat: FloatArray
        val plane: Int
        if (cornerInputEdge > 0) {
            var run = runCornerInference(bitmap, model, cornerInputEdge)
            // If a previously-latched higher scale fails at runtime, fall back to the pinned 256.
            if (run == null && cornerInputEdge != CORNER_INPUT_EDGE) {
                cornerInputEdge = CORNER_INPUT_EDGE
                run = runCornerInference(bitmap, model, CORNER_INPUT_EDGE)
            }
            if (run == null) {
                Diag.log(TAG, "corner detect: inference failed at scale=$cornerInputEdge")
                return null
            }
            edge = cornerInputEdge
            heat = run.first
            plane = run.second
        } else {
            // H7 Tier A probe: prefer the higher input resolution; the pinned graph declares a
            // static [1,3,256,256] input, so ORT may reject 512. We try 512 first, then 256, and
            // latch whichever actually produced a valid heat map.
            var chosenEdge = 0
            var chosen: Pair<FloatArray, Int>? = null
            for (candidate in intArrayOf(CORNER_INPUT_PROBE_EDGE, CORNER_INPUT_EDGE)) {
                val run = runCornerInference(bitmap, model, candidate)
                if (run != null) {
                    chosenEdge = candidate
                    chosen = run
                    break
                }
            }
            if (chosen == null) {
                Diag.log(TAG, "corner detect: no valid input scale; classical")
                return null
            }
            cornerInputEdge = chosenEdge
            edge = chosenEdge
            heat = chosen.first
            plane = chosen.second
            Diag.log(
                "Detect",
                "corner model scale=$edge subpixel=" +
                    (if (CORNER_SUBPIXEL_ENABLED) "on" else "off") + " err_px=-1 (runtime probe)"
            )
        }
        val quad = postprocessCornerHeat(heat, plane, bitmap.width, bitmap.height)
        // SUBTASK H39 — AI evidence, written BEFORE any gate/repair decision: the exact resized
        // tensor input and the four raw corner-heat channels (plus the max composite and a
        // labelled montage). This is the artifact that answers "is the model's map good enough?"
        if (trace != null) {
            runCatching {
                val inputBmp = Bitmap.createScaledBitmap(bitmap, edge, edge, true)
                try {
                    trace.png("corner_input", inputBmp)
                } finally {
                    if (inputBmp !== bitmap) inputBmp.recycle()
                }
                trace.heatDump("heat", heat, plane, bitmap.width, bitmap.height)
                val section = trace.section("corner_post")
                section.put("input_edge", edge)
                section.put("subpixel_refine", subpixelRefineEnabled())
                section.put("peak_gate", CORNER_PEAK_MIN.toDouble())
                // SUBTASK H40: which floor was actually applied (the relaxed one fires only when
                // three channels are >= CORNER_PEAK_STRONG). The per-channel peaks themselves are
                // in the `corner_heat` section written by heatDump above.
                section.put("peak_floor_applied", lastCornerPeakFloor.toDouble())
                section.put(
                    "plausible",
                    quad != null
                )
                section.put("quad", if (quad == null) JSONObject.NULL else quadJson(quad))
            }
        }
        // SUBTASK H16 STEP 2: sub-pixel corner refinement on the ORIGINAL image. The heat-map
        // peaks are quantized (128² or 256²); a small corner bias bends straight text lines.
        // Refinement is clamped to 1.5 % of the diagonal and re-gated; on any failure the
        // original quad is kept unchanged.
        val refined = if (quad != null && subpixelRefineEnabled()) {
            refineCornersSubpixel(bitmap, quad)
        } else {
            quad
        }
        Diag.log(
            TAG,
            "corner detect: scale=$edge plane=${isqrt(plane)}x${isqrt(plane)} " +
                "ms=${System.currentTimeMillis() - start}"
        )
        return refined
    }

    /**
     * One inference attempt at [edge]×[edge]. Returns `(flatHeat, planeElements)` where
     * `planeElements = heat.size / 4`, or `null` when the graph rejects the shape / output is
     * malformed.
     */
    private fun runCornerInference(
        bitmap: Bitmap,
        model: OnnxModel,
        edge: Int
    ): Pair<FloatArray, Int>? {
        val input = preprocessForCornerModel(bitmap, edge, edge) ?: run {
            Diag.log(TAG, "corner detect: preprocess failed (${bitmap.width}x${bitmap.height})")
            return null
        }
        val heat = model.runFloat(
            input,
            longArrayOf(1L, 3L, edge.toLong(), edge.toLong()),
            outputName = model.outputNames.firstOrNull()
        ) ?: return null
        if (heat.isEmpty() || heat.size % 4 != 0) {
            Diag.log(TAG, "corner detect: bad output size=${heat.size}")
            return null
        }
        return heat to (heat.size / 4)
    }

    /**
     * // M1c preprocess — plain resize to `w × h` (aspect NOT preserved, matching DocAligner's
     * `cb.imresize(img, size=(256,256))`), channels packed RGB→NCHW on the `0..1` scale (÷255), no
     * mean/std. Bounded by construction (`w`,`h` are the fixed graph size).
     */
    private fun preprocessForCornerModel(bitmap: Bitmap, w: Int, h: Int): FloatBuffer? {
        if (w <= 0 || h <= 0) return null
        val resized = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        resized.getPixels(pixels, 0, w, 0, 0, w, h)
        if (resized !== bitmap) resized.recycle()

        val plane = w * h
        val buffer = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            buffer.put(i, ((p shr 16) and 0xFF) / 255f)
            buffer.put(plane + i, ((p shr 8) and 0xFF) / 255f)
            buffer.put(2 * plane + i, (p and 0xFF) / 255f)
        }
        buffer.rewind()
        return buffer
    }

    /**
     * // M1c postprocess (SUBTASK H4) — turn the `[1,4,128,128]` corner heat maps into one ordered,
     * gated quad in the frame's pixel space. Returns `null` when any corner is below
     * [CORNER_PEAK_MIN] or the assembled quad fails [isPlausiblePageQuad].
     */
    private fun postprocessCornerHeat(
        heat: FloatArray,
        plane: Int,
        frameW: Int,
        frameH: Int
    ): Quad? {
        if (plane <= 0 || heat.size < 4 * plane) return null
        // The heat map is square; its side is inferred from the runtime output (128 at a 256 input,
        // 256 if the runtime probe accepted a 512 input).
        val hw = isqrt(plane)
        if (hw < 2 || hw * hw != plane) {
            Diag.warn(TAG, "corner detect: non-square heat plane=$plane -> classical")
            return null
        }
        val corners = ArrayList<CvPoint>(4)
        val confs = FloatArray(4)
        val heatMat = Mat(hw, hw, CvType.CV_32FC1)
        try {
            // Pass 1 — every channel's peak, so the gate can judge them together (SUBTASK H40:
            // a single weak channel is tolerated when its three siblings are clearly confident).
            for (c in 0 until 4) {
                val off = c * plane
                var mx = 0f
                for (i in 0 until plane) {
                    val v = heat[off + i]
                    if (v > mx) mx = v
                }
                confs[c] = mx
            }
            val strong = confs.count { it >= CORNER_PEAK_STRONG }
            val floor = if (strong >= 3) CORNER_PEAK_MIN_RELAXED else CORNER_PEAK_MIN
            lastCornerPeakFloor = floor
            for (c in 0 until 4) {
                val off = c * plane
                val mx = confs[c]
                if (mx < floor) {
                    Diag.log(
                        TAG,
                        "corner detect: channel $c peak=${fmt(mx)} < ${fmt(floor)} " +
                            "(peaks=${confs.joinToString(",") { fmt(it) }}) -> classical"
                    )
                    return null
                }
                var mi = 0
                for (i in 0 until plane) if (heat[off + i] == mx) { mi = i; break }
                val px = mi % hw
                val py = mi / hw
                // H7 sub-pixel: 3-point quadratic interpolation of the heat-map peak in x and y.
                val sub = if (CORNER_SUBPIXEL_ENABLED) subpixelPeak(heat, off, px, py, hw) else null
                val pt = if (sub != null) {
                    CvPoint(
                        (sub.first + 0.5) * frameW.toDouble() / hw,
                        (sub.second + 0.5) * frameH.toDouble() / hw
                    )
                } else {
                    val chan = FloatArray(plane) { heat[off + it] }
                    heatMat.put(0, 0, chan)
                    largestBlobCentroid(heatMat, frameW, frameH)
                        ?: CvPoint(
                            (px + 0.5) * frameW.toDouble() / hw,
                            (py + 0.5) * frameH.toDouble() / hw
                        )
                }
                corners.add(pt)
            }
        } finally {
            heatMat.release()
        }
        val quad = orderCorners(corners.toTypedArray())
        val plausible = isPlausiblePageQuad(quad, frameW.toDouble(), frameH.toDouble())
        Diag.log(
            TAG,
            "corner detect: conf=${fmt(confs.minOrNull() ?: 0f)} plausible=$plausible corners=[" +
                corners.joinToString(" ") { "(${it.x.toInt()},${it.y.toInt()})" } + "]"
        )
        return if (plausible) quad else null
    }

    // ==========================================================================================
    // SUBTASK H16 STEP 2: sub-pixel corner refinement from robust edge-line fits
    // ==========================================================================================

    /** Test-only override for the H16 A/B harness. Production uses [REFINE_ENABLED]. */
    @Volatile private var debugRefineOverride: Boolean? = null

    fun setSubpixelRefineOverride(enabled: Boolean?) {
        debugRefineOverride = enabled
    }

    private fun subpixelRefineEnabled(): Boolean = debugRefineOverride ?: REFINE_ENABLED

    /** A fitted image line `a·x + b·y + c = 0` with unit normal. */
    private data class FitLine(
        val a: Double,
        val b: Double,
        val c: Double,
        val rms: Double,
        val n: Int
    )

    /**
     * Refines the 4 corners of [seed] by fitting a robust line to each page edge on the ORIGINAL
     * [bitmap], then intersecting adjacent lines. Every refined corner is clamped to at most
     * [REFINE_MAX_MOVE_FRAC] of the image diagonal from the original estimate; the refined quad is
     * re-checked with the same plausibility gate, and the original is kept on any failure.
     */
    private fun refineCornersSubpixel(bitmap: Bitmap, seed: Quad): Quad {
        val w = bitmap.width
        val h = bitmap.height
        val pts = seed.points
        if (w <= 0 || h <= 0 || pts.size != 4) return seed
        val diag = hypot(w.toDouble(), h.toDouble())
        val maxMove = REFINE_MAX_MOVE_FRAC * diag
        val band = min(REFINE_BAND_MAX, max(5.0, REFINE_BAND_FRAC * diag))
        val order = intArrayOf(0, 1, 2, 3, 0) // TL->TR->BR->BL->TL
        // Edge order: 0 top, 1 right, 2 bottom, 3 left.
        val lines = arrayOfNulls<FitLine>(4)
        for (e in 0 until 4) {
            lines[e] = fitEdgeLine(bitmap, pts[order[e]], pts[order[e + 1]], band)
        }
        // Corner i = intersection of the two adjacent edges.
        val pairs = arrayOf(
            intArrayOf(3, 0), // TL = left ∩ top
            intArrayOf(0, 1), // TR = top ∩ right
            intArrayOf(1, 2), // BR = right ∩ bottom
            intArrayOf(2, 3)  // BL = bottom ∩ left
        )
        val refined = arrayOfNulls<CvPoint>(4)
        var movedSum = 0.0
        var movedCount = 0
        for (i in 0 until 4) {
            val orig = pts[i]
            val l1 = lines[pairs[i][0]]
            val l2 = lines[pairs[i][1]]
            val inter = if (l1 != null && l2 != null) intersectLines(l1, l2) else null
            if (inter == null) {
                refined[i] = CvPoint(orig.x.toDouble(), orig.y.toDouble())
                continue
            }
            var dx = inter.first - orig.x
            var dy = inter.second - orig.y
            val mag = hypot(dx, dy)
            if (!mag.isFinite()) {
                refined[i] = CvPoint(orig.x.toDouble(), orig.y.toDouble())
                continue
            }
            val appliedMove = min(mag, maxMove)
            if (mag > maxMove) {
                val s = maxMove / mag
                dx *= s
                dy *= s
            }
            refined[i] = CvPoint(orig.x + dx, orig.y + dy)
            movedSum += appliedMove
            movedCount++
        }
        val quad = Quad(
            topLeft = Point(refined[0]!!.x.toFloat(), refined[0]!!.y.toFloat()),
            topRight = Point(refined[1]!!.x.toFloat(), refined[1]!!.y.toFloat()),
            bottomRight = Point(refined[2]!!.x.toFloat(), refined[2]!!.y.toFloat()),
            bottomLeft = Point(refined[3]!!.x.toFloat(), refined[3]!!.y.toFloat())
        )
        val plausible = isPlausiblePageQuad(quad, w.toDouble(), h.toDouble())
        val moved = if (movedCount > 0) movedSum / movedCount else 0.0
        Diag.log(TAG, "refine moved=${fmt(moved)} corners=$movedCount ok=$plausible")
        return if (plausible) quad else seed
    }

    /**
     * Samples perpendicular gradient profiles along one edge and robustly fits a line through the
     * strongest gradient per sample. Returns `null` when too few edge samples or the fit is noisy.
     */
    private fun fitEdgeLine(bitmap: Bitmap, p0: Point, p1: Point, band: Double): FitLine? {
        val dx = p1.x - p0.x
        val dy = p1.y - p0.y
        val len = hypot(dx.toDouble(), dy.toDouble())
        if (len < 8.0) return null
        val tx = dx / len
        val ty = dy / len
        val nx = -ty
        val ny = tx
        val samples = ((len * 0.76) / 8.0).toInt().coerceIn(16, 48)
        val points = ArrayList<DoubleArray>(samples)
        for (s in 0 until samples) {
            val t = 0.12 + 0.76 * s.toDouble() / (samples - 1).toDouble()
            val bx = p0.x + dx * t
            val by = p0.y + dy * t
            var bestOff = 0.0
            var bestGrad = 0.0
            var off = -band
            while (off <= band) {
                val x = bx + nx * off
                val y = by + ny * off
                val g = luminance(bitmap, x + nx, y + ny) - luminance(bitmap, x - nx, y - ny)
                val ag = abs(g)
                if (ag > bestGrad) {
                    bestGrad = ag
                    bestOff = off
                }
                off += 1.0
            }
            if (bestGrad >= REFINE_MIN_GRAD) {
                points.add(doubleArrayOf(bx + nx * bestOff, by + ny * bestOff))
            }
        }
        if (points.size < 10) return null
        return robustLineFit(points)
    }

    /** Principal-axis line fit with 2-pass MAD outlier rejection. */
    private fun robustLineFit(points: List<DoubleArray>): FitLine? {
        if (points.size < 6) return null
        var kept = points
        var a = 0.0
        var b = 0.0
        var c = 0.0
        repeat(3) {
            if (kept.size < 6) return null
            var mx = 0.0
            var my = 0.0
            for (p in kept) { mx += p[0]; my += p[1] }
            mx /= kept.size
            my /= kept.size
            var sxx = 0.0
            var syy = 0.0
            var sxy = 0.0
            for (p in kept) {
                val ux = p[0] - mx
                val uy = p[1] - my
                sxx += ux * ux
                syy += uy * uy
                sxy += ux * uy
            }
            val theta = 0.5 * kotlin.math.atan2(2.0 * sxy, sxx - syy)
            val nx = -kotlin.math.sin(theta)
            val ny = kotlin.math.cos(theta)
            a = nx
            b = ny
            c = -(nx * mx + ny * my)
            val residuals = kept.map { abs(a * it[0] + b * it[1] + c) }
            val median = residuals.sorted()[residuals.size / 2]
            val mad = residuals.map { abs(it - median) }.sorted()[residuals.size / 2]
            val limit = max(0.75, 2.5 * 1.4826 * mad)
            val next = kept.filterIndexed { idx, _ -> residuals[idx] <= limit }
            if (next.size == kept.size) {
                kept = next
                return@repeat
            }
            kept = next
        }
        var sumSq = 0.0
        for (p in kept) {
            val r = a * p[0] + b * p[1] + c
            sumSq += r * r
        }
        val rms = kotlin.math.sqrt(sumSq / kept.size)
        if (kept.size < 10 || rms > REFINE_MAX_LINE_RMS) return null
        return FitLine(a, b, c, rms, kept.size)
    }

    private fun intersectLines(l1: FitLine, l2: FitLine): Pair<Double, Double>? {
        val det = l1.a * l2.b - l2.a * l1.b
        if (!det.isFinite() || abs(det) < 1e-6) return null
        val x = (l1.b * l2.c - l2.b * l1.c) / det
        val y = (l1.c * l2.a - l2.c * l1.a) / det
        if (!x.isFinite() || !y.isFinite()) return null
        return x to y
    }

    /** Bilinear luminance sample, clamped to the bitmap. */
    private fun luminance(bitmap: Bitmap, x: Double, y: Double): Double {
        val maxX = (bitmap.width - 1).toDouble()
        val maxY = (bitmap.height - 1).toDouble()
        val cx = x.coerceIn(0.0, maxX)
        val cy = y.coerceIn(0.0, maxY)
        val x0 = kotlin.math.floor(cx).toInt().coerceIn(0, bitmap.width - 1)
        val y0 = kotlin.math.floor(cy).toInt().coerceIn(0, bitmap.height - 1)
        val x1 = min(bitmap.width - 1, x0 + 1)
        val y1 = min(bitmap.height - 1, y0 + 1)
        val fx = cx - x0
        val fy = cy - y0
        fun value(px: Int, py: Int): Double {
            val c = bitmap.getPixel(px, py)
            return 0.299 * ((c shr 16) and 0xFF) +
                0.587 * ((c shr 8) and 0xFF) +
                0.114 * (c and 0xFF)
        }
        val top = value(x0, y0) * (1.0 - fx) + value(x1, y0) * fx
        val bottom = value(x0, y1) * (1.0 - fx) + value(x1, y1) * fx
        return top * (1.0 - fy) + bottom * fy
    }

    /**
     * H7: sub-pixel peak location. Fits a parabola through the peak and its two neighbours in x
     * and y (3-point quadratic interpolation) and returns the refined heat-map coordinates.
     * Values are clamped to ±0.5 cell (a quadratic fit cannot move the peak past its neighbours).
     */
    private fun subpixelPeak(
        heat: FloatArray,
        off: Int,
        px: Int,
        py: Int,
        hw: Int
    ): Pair<Double, Double>? {
        val center = heat[off + py * hw + px]
        fun delta(a: Float, b: Float, c: Float): Double {
            val denom = (a - 2f * b + c).toDouble()
            if (kotlin.math.abs(denom) < 1e-9) return 0.0
            return (0.5 * (a - c).toDouble() / denom).coerceIn(-0.5, 0.5)
        }
        val dx = if (px > 0 && px < hw - 1) {
            delta(heat[off + py * hw + px - 1], center, heat[off + py * hw + px + 1])
        } else 0.0
        val dy = if (py > 0 && py < hw - 1) {
            delta(heat[off + (py - 1) * hw + px], center, heat[off + (py + 1) * hw + px])
        } else 0.0
        val x = px + dx
        val y = py + dy
        if (!x.isFinite() || !y.isFinite()) return null
        return x to y
    }

    /** Integer square root (floor). */
    private fun isqrt(n: Int): Int {
        if (n <= 0) return 0
        var r = kotlin.math.sqrt(n.toDouble()).toInt()
        while (r > 0 && r.toLong() * r > n) r--
        while ((r + 1).toLong() * (r + 1) <= n) r++
        return r
    }

    /**
     * Centroid of the largest blob above [CORNER_HEATMAP_THRESHOLD] in a single-channel heat map,
     * after upscaling to a bounded working size (long edge [CORNER_WORK_EDGE]); mapped back to the
     * frame. Mirrors DocAligner's largest-area-contour centroid; returns `null` on any failure.
     */
    private fun largestBlobCentroid(heat: Mat, frameW: Int, frameH: Int): CvPoint? {
        if (frameW <= 0 || frameH <= 0) return null
        val wWork = min(frameW, CORNER_WORK_EDGE)
        val hWork = min(frameH, CORNER_WORK_EDGE)
        val up = Mat()
        val u8 = Mat()
        val bin = Mat()
        val contours = ArrayList<MatOfPoint>()
        val hier = Mat()
        try {
            Imgproc.resize(heat, up, Size(wWork.toDouble(), hWork.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            up.convertTo(u8, CvType.CV_8U, 255.0)
            Imgproc.threshold(u8, bin, (CORNER_HEATMAP_THRESHOLD * 255.0), 255.0, Imgproc.THRESH_BINARY)
            Imgproc.findContours(bin, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            var bestArea = 0.0
            var best: MatOfPoint? = null
            for (ct in contours) {
                val a = abs(Imgproc.contourArea(ct))
                if (a > bestArea) { bestArea = a; best = ct }
            }
            if (best == null || bestArea <= 0.0) return null
            val m = Imgproc.moments(best)
            if (m.m00 <= 1e-6) return null
            val cx = m.m10 / m.m00
            val cy = m.m01 / m.m00
            return CvPoint(cx / wWork * frameW, cy / hWork * frameH)
        } catch (t: Throwable) {
            Log.w(TAG, "corner centroid extraction failed", t)
            return null
        } finally {
            up.release(); u8.release(); bin.release(); hier.release()
            contours.forEach { runCatching { it.release() } }
        }
    }

    /**
     * Sanity gate for a detected page quad (SUBTASK H4). Rejects:
     *  - degenerate/empty area or an area fraction outside `[MIN_QUAD_AREA_RATIO, MAX_QUAD_AREA_RATIO]`;
     *  - a min-area-rect aspect beyond [MAX_QUAD_ASPECT_RATIO] (slivers) or a tiny min-area-rect side;
     *  - low rectangularity `area / minAreaRectArea < MIN_QUAD_FILL` (crossed/self-intersecting sets);
     *  - non-convex corner order;
     *  - **frame-hugging** quads (bounding box reaching every image edge) — this is precisely the
     *    "corners on the frame border" runaway mode SUBTASK H4 must eliminate. A page that genuinely
     *    fills the frame is handled downstream by the existing rectified/full-frame guards, so
     *    rejecting it here is safe.
     */
    private fun isPlausiblePageQuad(quad: Quad, w: Double, h: Double): Boolean {
        val pts = quad.points
        if (pts.size != 4) return false
        val frameArea = (w * h).coerceAtLeast(1.0)
        val area = polygonArea(pts)
        val areaRatio = area / frameArea
        if (areaRatio < MIN_QUAD_AREA_RATIO || areaRatio > MAX_QUAD_AREA_RATIO) return false

        val sides = minAreaRectSides(pts) ?: return false
        val (rw, rh) = sides
        if (rw < MIN_QUAD_SIDE || rh < MIN_QUAD_SIDE) return false
        val aspect = max(rw, rh) / min(rw, rh)
        if (aspect > MAX_QUAD_ASPECT_RATIO) return false
        if (area / (rw * rh).coerceAtLeast(1.0) < MIN_QUAD_FILL) return false

        val cvPts = Array(4) { CvPoint(pts[it].x.toDouble(), pts[it].y.toDouble()) }
        if (!Imgproc.isContourConvex(MatOfPoint(*cvPts))) return false

        if (isFrameHugging(pts, w, h)) return false
        return true
    }

    /** Min-area-rect `(width, height)` of an ordered quad, or `null` on failure. */
    private fun minAreaRectSides(pts: List<Point>): Pair<Double, Double>? {
        if (pts.size < 3) return null
        val m2f = MatOfPoint2f(*pts.map { CvPoint(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        return try {
            val rr = Imgproc.minAreaRect(m2f)
            rr.size.width to rr.size.height
        } catch (t: Throwable) {
            null
        } finally {
            m2f.release()
        }
    }

    /** Resolves the primary corner detector (mirrors [resolveModel]'s latch semantics). */
    private fun resolveCornerModel(): OnnxModel? {
        if (cornerResolved || released) return cornerModel
        synchronized(modelLock) {
            if (cornerResolved || released) return cornerModel
            val resolved = try {
                cornerModelProvider?.invoke()
            } catch (t: Throwable) {
                Log.w(TAG, "Corner model load failed", t)
                null
            }
            cornerModel = resolved
            cornerResolved = resolved != null
            if (resolved != null) {
                Diag.log("Detector", "corner model resolved (latch closed)")
            }
            return resolved
        }
    }

    /** An axis-aligned pixel region (non-empty). */
    private data class Region(val left: Int, val top: Int, val width: Int, val height: Int)

    companion object {
        private const val TAG = "OpenCvPageDetector"

        /** Working size cap for the classical path (px, long edge). */
        private const val MAX_WORK_EDGE = 720

        /** Working size cap for full-res refinement (px, long edge). */
        private const val REFINE_WORK_EDGE = 720

        /** Fraction by which the seed quad's bbox is expanded for region-restricted refinement. */
        private const val REFINE_MARGIN = 0.15f

        // --- SUBTASK H26: seed-consistency guard for refine() --------------------------------
        /**
         * Minimum IoU between the refined quad and the seed for the refined quad to be accepted.
         * **Never lower this to make a measurement pass**: the whole point is that a different
         * rectangle inside the crop (text block / table frame / ruled margin) is not the page.
         */
        private const val SEED_MIN_IOU = 0.85

        /** Minimum `area(refined) / area(seed)` for the refined quad to be accepted. */
        private const val SEED_MIN_AREA_RATIO = 0.90

        /** Maximum `area(refined) / area(seed)` for the refined quad to be accepted. */
        private const val SEED_MAX_AREA_RATIO = 1.10

        // --- SUBTASK H16 STEP 2 / H18: sub-pixel edge refinement ----------------------------
        /**
         * Production toggle after the H18 guardrail gate: **off**. The same-build A/B
         * (`raw + refine` vs `raw`) improved the 19-page border RMS by 8.76 % relative and stroke
         * sharpness by 3.42 %, but mean corner error regressed past the 3.44 % gate in the full
         * corpus lane (3.585 % at the 1.5 % clamp, 3.522 % at the one allowed 0.75 % mitigation).
         * The 0.3.3 corner model is therefore kept. The implementation remains behind this constant
         * for future measurement (see docs/RESULTS.md and tooling/README.md "Geometry refinement").
         *
         * Set this to `true` to re-run the experiment; refinement is clamped to
         * [REFINE_MAX_MOVE_FRAC] of the image diagonal and the refined quad is re-checked with the
         * same plausibility gate.
         */
        private const val REFINE_ENABLED = false

        /** Maximum movement of one refined corner, as a fraction of the image diagonal. */
        private const val REFINE_MAX_MOVE_FRAC = 0.015

        /** Half-width of the perpendicular gradient search band, as a fraction of the diagonal. */
        private const val REFINE_BAND_FRAC = 0.02

        /** Hard cap (px) on the perpendicular search band. */
        private const val REFINE_BAND_MAX = 24.0

        /** Minimum absolute perpendicular luminance gradient (0..255) to accept an edge sample. */
        private const val REFINE_MIN_GRAD = 6.0

        /** Maximum robust RMS (px) of an accepted edge-line fit. */
        private const val REFINE_MAX_LINE_RMS = 2.5

        /** Minimum quad area as a fraction of the frame (TECHNICAL_SPEC.md ">20–25%"). */
        private const val MIN_AREA_RATIO = 0.20

        /**
         * `approxPolyDP` epsilon values (as fractions of the contour perimeter) tried in order
         * (SUBTASK H3b). A single fixed epsilon rarely lands on exactly 4 vertices on a real photo;
         * trying a small ladder — tight to loose — is what lets a page outline be recovered.
         */
        /**
         * SUBTASK H36 fix e — the corner-extremes (curved-page) candidate cue + epsilon tail.
         * SUBTASK H38: re-enabled for the bumpy/curvy-book-page class with the 1a+1b rank guards
         * active — measured (tag=h38fix): detection 91 -> 108 and ALL 5 new photos detected
         * (0 wrong-page), but the aggregate gate FAILED (wrong-page 24 > 20, merged 29 > 25;
         * old-set wrong 18 -> 24, merged 23 -> 26) — its extra candidates still win as
         * wrong/merged on the owner-110 set. Per the standing rule (keep wins, revert failures)
         * it stays OFF; the new-5 recall is delivered by the hue-agnostic paper fix alone.
         */
        private const val CURVE_CUE = false

        /**
         * SUBTASK H36 fix e (curvature-tolerant epsilon handling): the tail 0.085/0.12 rounds a
         * slightly BOWED page border into 4 clean vertices where every smaller epsilon fails to
         * find exactly 4 (the curved-page MISS case). The loop breaks at the first success, so the
         * larger epsilons only ever run when the strict ones produced nothing. GATED with
         * [CURVE_CUE] so each H36 fix is measured ONE AT A TIME (it generates candidates, so it
         * can change the detection count — measured: +8 detections in the combined run).
         */
        private val EPSILON_FRACTIONS = if (CURVE_CUE) {
            doubleArrayOf(0.010, 0.020, 0.030, 0.045, 0.060, 0.085, 0.120)
        } else {
            doubleArrayOf(0.010, 0.020, 0.030, 0.045, 0.060)
        }

        /**
         * SUBTASK H36 fix f — the locally normalized (shadow-robust) gradient band in
         * [buildEdges]. Gated so the H36 lane can measure fix e and fix f ONE AT A TIME
         * (the standing owner rule: one change, one re-measure).
         */
        private const val SHADOW_BAND = false

        /**
         * Minimum combined confidence for a quad to be reported (anti-jitter/false-positive).
         * SUBTASK H38: measured 0.45 (tag=h38min) and REVERTED — a low-scoring wrong quad beat the
         * page on one new photo (new-5 wrong-page 1) while merged stayed 27. Acceptance stays 0.55.
         */
        private const val MIN_SCORE = 0.55f

        /** Reject candidates whose bounding-box aspect ratio exceeds this (slivers). */
        private const val MAX_ASPECT_RATIO = 6.0

        /** Minimum side of the region crop for refinement. */
        private const val MIN_REGION_EDGE = 24

        // SUBTASK H3b multi-cue tunables ------------------------------------------------------
        /** Block size for the adaptive Gaussian threshold cue (odd, phone-cheap). */
        private const val ADAPT_BLOCK = 35

        /** Constant subtracted in the adaptive threshold cue (handles uneven illumination). */
        private const val ADAPT_C = 12.0

        /** Hough accumulator votes required to accept a line segment. */
        private const val HOUGH_THRESHOLD = 80

        /** Minimum accepted Hough segment length, as a fraction of the shorter frame side. */
        private const val HOUGH_MIN_LEN_FRAC = 0.25

        /** Maximum gap (fraction of the shorter frame side) between points joined into a segment. */
        private const val HOUGH_MAX_GAP_FRAC = 0.03

        /**
         * Page-likeness term (SUBTASK H3b / H38): interior-vs-surround level difference (0–255
         * grey) that saturates the "distinct" sub-score at 1.0 — measured as an absolute gap in
         * EITHER direction (hue-agnostic), so a dyed sheet on a light table saturates it exactly
         * like a white sheet on a dark desk.
         */
        private const val PAGE_BRIGHT_DELTA = 64.0

        /** Interior standard deviation (0–255 grey) at which the "uniform" sub-score reaches 0. */
        private const val PAGE_STD_FULL = 80.0

        /**
         * Fraction of the frame within which a quad counts as "hugging the image border"
         * (SUBTASK H3b precision guard). Frame-hugging quads are REJECTED outright: a real page
         * boundary always leaves some background visible, so the whole-frame rectangle produced by
         * a saturated threshold is never a page (see [isFrameHugging]).
         */
        private const val FRAME_HUG_TOL_FRAC = 0.03

        // Scoring weights (sum = 1.0) ----------------------------------------------------------
        private const val W_AREA = 0.25
        private const val W_RECT = 0.20
        private const val W_ANGLE = 0.20
        private const val W_CENTER = 0.10
        private const val W_PAGE = 0.25

        // --- SUBTASK H36 fix a/b: winner-ranking weights (selection only, never a gate) -------
        /**
         * Weight of the paper-interior signal in [Candidate.rank] (fix a). Selection-only: a
         * candidate is still ACCEPTED by the unchanged `score >= [MIN_SCORE]` rule, so the
         * detection count on the owner-110 set is preserved by construction; this only decides
         * which accepted candidate wins.
         */
        /** Residual weight of the paper-interior signal in [Candidate.rank] (SUBTASK H36 fix a). */
        private const val W_RANK_PAPER = 0.35

        /** Residual weight of the old interior-vs-surround term in [Candidate.rank]. */
        private const val W_RANK_PAGE = 0.15

        /** Shape-quality terms of the rank (non-size-biased shares of the old score). */
        private const val W_RANK_RECT = 0.15
        private const val W_RANK_ANGLE = 0.15
        private const val W_RANK_CENTER = 0.10

        /**
         * Weight of the frame-contact penalty in [Candidate.rank] (fix b). One signal among many —
         * the H35 frame-cut VETO (reject any touching quad) was measured and REJECTED (-58
         * detections: 91 -> 33); this soft rank penalty keeps a clean touching quad winnable
         * whenever no better candidate exists.
         */
        private const val W_RANK_FRAME = 0.30

        /**
         * Weight of the H32 frame-cut penalty in [Candidate.rank] (fix d). Selection-only,
         * like [W_RANK_FRAME]: a candidate is still ACCEPTED by `score >= [MIN_SCORE]`.
         */
        private const val W_RANK_CUT = 0.0

        /** Coarse luma-grid resolution for [paperInteriorOf] / [frameContactOf] (per detection). */
        private const val GRID_W = 64
        private const val GRID_H = 64

        // --- M1 DexiNed constants (pinned OpenCV Zoo export — see MODEL_SOURCES.md) ---------
        /** Name of the fused edge-map output in the pinned DexiNed graph. */
        private const val FUSED_OUTPUT_NAME = "block_cat"

        /** Spatial input size of the pinned export (W×H) — used only when the graph is dynamic. */
        private const val DEXINED_DEFAULT_W = 640
        private const val DEXINED_DEFAULT_H = 480

        /** Caffe BGR mean subtracted on the 0–255 scale (DexiNed / OpenCV Zoo convention). */
        private val DEXINED_BGR_MEAN = floatArrayOf(103.5f, 116.2f, 123.6f)

        // --- M1c DocAligner corner detector (SUBTASK H4 — pinned export, see MODEL_SOURCES.md) ---
        /** Pinned/default spatial input size of the corner export (W × H). */
        private const val CORNER_INPUT_W = 256
        private const val CORNER_INPUT_H = 256

        /** H7 Tier A: pinned input edge and the higher-resolution probe edge. */
        private const val CORNER_INPUT_EDGE = 256
        private const val CORNER_INPUT_PROBE_EDGE = 512

        /** H7 Tier A: enable 3-point quadratic sub-pixel peak interpolation. */
        private const val CORNER_SUBPIXEL_ENABLED = true

        /**
         * Declared fallback output side. The runtime heat-map plane is now inferred from the actual
         * output length, so these are documentation/legacy constants only.
         */
        private const val CORNER_HEAT_W = 128
        private const val CORNER_HEAT_H = 128

        /** Minimum per-corner heat-map peak to accept a detection (upstream default is 0.3). */
        private const val CORNER_PEAK_MIN = 0.30f

        /**
         * SUBTASK H40 — the "one weak channel" relaxation.
         *
         * Measured on all 116 owner photos (`tooling/out/h39/h40all`, corner heat peaks per
         * channel): 20 photos end as no-detection. In 19 of them the model is genuinely blind —
         * two or more channels are at ~0.00-0.10. Exactly ONE (`20261024_185748`) has three
         * confident corners (0.36 / 0.73 / 0.51) and a single weak one at 0.26, i.e. the page was
         * found and only the per-channel floor rejected it. Its peak-quad scores IoU 0.57 against
         * the oracle, so it is a real page, not a spurious blob.
         *
         * The relaxation therefore only fires when the other three channels are clearly above the
         * normal floor; a photo where the model is blind cannot reach it. With
         * [CORNER_PEAK_STRONG] = 0.35 it changes exactly 1 of the 116 photos.
         */
        private const val CORNER_PEAK_MIN_RELAXED = 0.22f

        /** Per-channel peak a channel must clear for its siblings to relax [CORNER_PEAK_MIN]. */
        private const val CORNER_PEAK_STRONG = 0.35f

        /** Heat-map binarisation threshold used to isolate a corner blob (upstream default). */
        private const val CORNER_HEATMAP_THRESHOLD = 0.30f

        /** Bounded working size (long edge, px) for corner centroid extraction. */
        private const val CORNER_WORK_EDGE = 512

        // Sanity-gate thresholds (SUBTASK H4) ------------------------------------------------
        /**
         * Minimum page area as a fraction of the frame. Deliberately low (0.03): a real page can be
         * a small part of a wide scene, and the frame-hugging + confidence checks — not an area
         * floor — are what reject the runaway mode.
         */
        private const val MIN_QUAD_AREA_RATIO = 0.03

        /** Maximum page area fraction (a genuine full-frame page is handled by the rectified guard). */
        private const val MAX_QUAD_AREA_RATIO = 0.98

        /** Minimum min-area-rect side (px) — rejects collapsed corners. */
        private const val MIN_QUAD_SIDE = 8.0

        /** Maximum min-area-rect aspect ratio — rejects slivers. */
        private const val MAX_QUAD_ASPECT_RATIO = 6.0

        /** Minimum `area / minAreaRectArea` — rejects crossed/self-intersecting corner sets. */
        private const val MIN_QUAD_FILL = 0.30

        // --- SUBTASK H30: M-LSD candidate gate ---------------------------------------------
        /**
         * Minimum line support (fraction of the quad's perimeter backed by near-collinear
         * detected segments) for a line-derived square to replace the corner-model quad.
         * Measured offline: below ~0.55 the winning candidate is usually a text-block rectangle.
         */
        private const val CANDIDATE_MIN_SUPPORT = 0.55

        /**
         * Area window `area(candidate) / area(page-side quad)`. The upper bound is tight on
         * purpose: a line-derived quad *bigger* than the page side is the spread coming back,
         * which is exactly what the merge rule just removed. Measured offline: widening it to
         * 1.30 restored M-LSD's spread-taking squares and moved merged slivers 3 -> 5.
         */
        private const val CANDIDATE_AREA_LO = 0.80
        private const val CANDIDATE_AREA_HI = 1.05
    }
}
