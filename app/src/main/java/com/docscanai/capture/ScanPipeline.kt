package com.docscanai.capture

import android.content.Context
import android.graphics.Bitmap
import com.docscanai.BuildConfig
import com.docscanai.core.util.BitmapUtils
import com.docscanai.core.util.Diag
import com.docscanai.data.PageEnhanceSettings
import com.docscanai.data.Point
import com.docscanai.data.Quad
import com.docscanai.data.ScannedPage
import com.docscanai.imaging.DewarpProcessor
import com.docscanai.imaging.Enhancer
import com.docscanai.imaging.HandwritingEnhancer
import com.docscanai.imaging.QuadOverlayRenderer
import com.docscanai.ml.MlsdTrigger
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Discrete stages of the per-page processing pipeline (ARCHITECTURE.md §3). */
enum class PipelineStage { IDLE, DETECTING, WARPING, ENHANCING, STORING, DONE, FAILED }

/**
 * Orchestrates the full-page processing pipeline for a captured still (ARCHITECTURE.md §3,
 * TECHNICAL_SPEC.md §4.2):
 *
 *   detect corners (PageDetector) -> perspective/dewarp (DewarpProcessor)
 *   -> enhance (Enhancer) -> persist raw + processed + thumbnail.
 *
 * Threading: everything runs on a single-threaded [dispatcher] so pages are processed in order
 * and at most one heavy page is in flight, keeping the camera loop responsive (ARCHITECTURE.md §4.4).
 *
 * ### SUBTASK H13 — page-ness gate before any warp
 * A detected quad that fails [`PagePlausibility`] is treated as **NO DETECTION**: the page is
 * still saved (manual shutter / import always work) but it is **never warped**, so the neural
 * dewarp can never run on a cluttered non-document frame. This is additive to the existing
 * geometry checks in the detector.
 *
 * ### Robustness (SUBTASK 9a hardening)
 *  - Every stage is wrapped in `try/catch (Throwable)` — including `OutOfMemoryError` — and falls
 *    back to the previous stage's bitmap, so a failing stage can never crash the app.
 *  - The heavy working image is downscaled to ≤ [MAX_PROCESS_LONG_EDGE] before warp/enhance to
 *    bound Java + native allocation (OOM/native-crash safety); the full-resolution capture is still
 *    saved as the raw page.
 *  - [process] **always returns a usable [ScannedPage]** — worst case it returns the raw capture —
 *    so callers never have to branch on failure.
 *  - Every stage logs entry/exit, bitmap dims/config, chosen mode, elapsed ms and memory under the
 *    stable `DocScan` tag ([Diag]).
 *
 * File layout (TECHNICAL_SPEC.md §8.3): app-private
 *   `filesDir/scans/<sessionId>/raw/<pageId>.jpg`
 *   `filesDir/scans/<sessionId>/page/<pageId>.jpg`
 *   `filesDir/scans/<sessionId>/thumb/<pageId>.jpg`
 *
 * NOTE: [process] does NOT recycle [source] — the caller retains ownership (typically the still
 * decoded by the camera layer / the imported gallery bitmap). Intermediate bitmaps created by the
 * stages ARE recycled.
 */
class ScanPipeline(
    private val context: Context,
    private val pageDetector: PageDetector,
    private val dewarpProcessor: DewarpProcessor,
    private val enhancer: Enhancer,
    /**
     * SUBTASK H23 — opt-in faint pencil stage. Runs after [Enhancer] and before save, only when
     * [PageEnhanceSettings.handwriting] is true and the mode is not
     * [com.docscanai.data.EnhancementMode.ORIGINAL]. The raw capture never passes through it.
     */
    private val handwritingEnhancer: HandwritingEnhancer = HandwritingEnhancer()
) {

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "docscan-pipeline") }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private val _stage = MutableStateFlow(PipelineStage.IDLE)

    /** Latest pipeline stage, observable by the UI for a subtle progress indicator. */
    val stage: StateFlow<PipelineStage> = _stage.asStateFlow()

    /**
     * Runs the pipeline for a single captured page and returns the resulting [ScannedPage].
     *
     * @param sessionId destination session folder under `filesDir/scans/`.
     * @param source full-resolution captured bitmap (NOT recycled here).
     * @param quad the live/seed quad, or null to detect on the still.
     * @param enhancement per-page enhancement settings (mode selected in the UI).
     * @param onStage optional per-stage callback (invoked on the pipeline thread).
     * @param debugOverlaySink F4 (SUBTASK H3) optional **DEBUG-only** sink that receives a separate
     *   diagnostic bitmap with the detected quad drawn on the pre-warp input (see
     *   [`QuadOverlayRenderer`]). Only invoked on `BuildConfig.DEBUG` builds when non-null;
     *   production callers pass nothing. It NEVER alters the stored page or any other output.
     */
    suspend fun process(
        sessionId: String,
        source: Bitmap,
        quad: Quad?,
        enhancement: PageEnhanceSettings,
        onStage: ((PipelineStage) -> Unit)? = null,
        debugOverlaySink: ((Bitmap) -> Unit)? = null
    ): ScannedPage = withContext(dispatcher) {
        val pageId = UUID.randomUUID().toString()
        val dir = sessionDir(sessionId)
        val rawFile = File(File(dir, DIR_RAW), "$pageId.jpg")
        val pageFile = File(File(dir, DIR_PAGE), "$pageId.jpg")
        val thumbFile = File(File(dir, DIR_THUMB), "$pageId.jpg")

        val overallStart = System.currentTimeMillis()
        Diag.stageEnter(
            "Pipeline",
            "session=$sessionId page=$pageId mode=${enhancement.mode.id} " +
                "quad=${quad != null} ${Diag.describe(source)}"
        )

        try {
            // --- Stage 1: corner detection / full-res refinement -----------------------------
            // Detection runs on the ORIGINAL source; the detector downscales internally, so this is
            // already memory-bounded and yields the quad in source-pixel coordinates.
            report(PipelineStage.DETECTING, onStage)
            val detectStart = System.currentTimeMillis()
            // SUBTASK H31: a capture is settled by definition and gets exactly ONE M-LSD attempt
            // (`still = true` bypasses the preview cadence), and only when the merge signature is
            // actually present on the corner quad — a clean page capture pays nothing at all.
            // The gate's paper/text numbers are not available yet at this point (they are computed
            // on the quad we are about to produce), so the still path uses the geometric terms
            // only; the evidence term is a live-path refinement.
            (pageDetector as? OpenCvQuadPageDetector)?.mlsdHint = MlsdTrigger.Hint.STILL
            val detectedRaw: Quad? = try {
                if (quad != null) pageDetector.refine(source, quad) else pageDetector.detect(source)
            } catch (t: Throwable) {
                Diag.logThrowable("Detect", t)
                quad
            } finally {
                // Leave the detector in its live default so a stale "still" hint can never make a
                // later preview frame bypass the cadence.
                (pageDetector as? OpenCvQuadPageDetector)?.mlsdHint = MlsdTrigger.Hint.LIVE_UNKNOWN
            }
            // H13: content page-ness gate on the ORIGINAL still. Fail-closed (an evaluation error
            // yields passed=false). A failing quad is downgraded to "no detection" so NO warp (and
            // in particular no neural dewarp) can run on a non-document frame.
            // SUBTASK H21: the still gate receives the same provenance as the live gate, so a
            // heuristic fallback quad cannot be treated as a detected page on the capture path.
            // SUBTASK H24: the text-line proof is an AUTO-CAPTURE requirement (H21 live-room fix);
            // on the post-shutter still path the user already pressed the shutter, so a fallback
            // quad that clears the score/content gate is still warped. Passing `false` here is what
            // restores benchmark AI-ON 19/20 and AI-OFF 14/20 without weakening the live gate.
            val provenance = pageDetector.lastProvenance
            val gate = detectedRaw?.let {
                PagePlausibility.evaluate(source, it, "still", provenance, requireLineProof = false)
            }
            val gateRejected = detectedRaw != null && gate != null && !gate.passed
            val detected: Quad? = if (gate == null || gate.passed) detectedRaw else null
            // F1 (SUBTASK H3): a *detection miss* is a null detector result. We still surface a
            // full-frame quad on the ScannedPage for downstream consumers, but we DO NOT treat it
            // as a real page and we DO NOT warp it. `detectedOk` is the single source of truth for
            // the warp decision, so AI ON and AI OFF behave identically when nothing was found.
            val detectedOk = detected != null
            val detectedAreaFrac =
                detected?.let { quadAreaFraction(it, source.width, source.height) } ?: -1.0
            val effectiveQuad = detected ?: BitmapUtils.fullFrameQuad(source.width, source.height)
            Diag.stageExit(
                "Detect", System.currentTimeMillis() - detectStart,
                "found=$detectedOk (fallbackToFullFrame=${!detectedOk}) gateRejected=$gateRejected " +
                    "gateScore=${fmt5(gate?.score ?: -1.0)} areaFrac=${fmt5(detectedAreaFrac)}"
            )

            // --- Bounded working image for warp + enhance ------------------------------------
            // Downscale the (potentially 12 MP) capture to ≤ MAX_PROCESS_LONG_EDGE and scale the
            // quad into that space so the perspective/dewarp/enhance stages never allocate huge
            // Mats/bitmaps. Falls back to the source if the downscale itself fails.
            var working: Bitmap = source
            var workingOwned = false
            var workingQuad: Quad = effectiveQuad
            val sourceLongEdge = max(source.width, source.height)
            if (sourceLongEdge > MAX_PROCESS_LONG_EDGE) {
                val scale = MAX_PROCESS_LONG_EDGE.toFloat() / sourceLongEdge.toFloat()
                val w = (source.width * scale).roundToInt().coerceAtLeast(1)
                val h = (source.height * scale).roundToInt().coerceAtLeast(1)
                val scaled = try {
                    Bitmap.createScaledBitmap(source, w, h, true)
                } catch (t: Throwable) {
                    Diag.logThrowable("Pipeline", t)
                    null
                }
                if (scaled != null && scaled !== source) {
                    working = scaled
                    workingOwned = true
                    workingQuad = scaleQuad(
                        effectiveQuad,
                        scaled.width.toFloat() / source.width.toFloat(),
                        scaled.height.toFloat() / source.height.toFloat()
                    )
                    Diag.log(
                        "Pipeline",
                        "bounded working image ${source.width}x${source.height} -> " +
                            "${scaled.width}x${scaled.height} (cap=$MAX_PROCESS_LONG_EDGE)"
                    )
                }
            }

            // --- F4 (SUBTASK H3): DEBUG-only detected-quad overlay on the PRE-WARP input ----------
            // Purely diagnostic: it builds a SEPARATE bitmap and hands it to the caller-supplied
            // sink (the corpus lane saves it; production passes none). It never touches `working`
            // or any stored output, and the whole block is skipped on release builds
            // (BuildConfig.DEBUG == false) so it is zero-cost when disabled.
            if (DEBUG_QUAD_OVERLAY && debugOverlaySink != null) {
                try {
                    val verdict = buildString {
                        append("detected=").append(if (detectedOk) "YES" else "NO")
                        append(" fullFrameFallback=").append(if (detectedOk) "no" else "yes")
                        append(" areaFrac=").append(fmt5(detectedAreaFrac))
                        append(" mode=").append(enhancement.mode.id)
                    }
                    val overlay = QuadOverlayRenderer.render(working, workingQuad, verdict)
                    if (overlay != null) debugOverlaySink.invoke(overlay)
                } catch (t: Throwable) {
                    Diag.logThrowable("Warp.overlay", t)
                }
            }

            // --- Stage 2: perspective / dewarp ----------------------------------------------
            report(PipelineStage.WARPING, onStage)
            val warped: Bitmap = if (!detectedOk) {
                // F1: NEVER warp a frame in which no page was found. Carry it forward unchanged
                // (AI ON and AI OFF identical). A frame with no page must never be warped.
                Diag.log("Warp", "skipped (no detection)")
                working
            } else try {
                val out = dewarpProcessor.dewarp(working, workingQuad)
                if (out.isRecycled) working else out
            } catch (t: Throwable) {
                Diag.logThrowable("Warp", t)
                working
            }

            // --- Stage 3: enhancement -------------------------------------------------------
            report(PipelineStage.ENHANCING, onStage)
            val enhanced: Bitmap = try {
                val out = enhancer.enhance(warped, enhancement)
                if (out.isRecycled) warped else out
            } catch (t: Throwable) {
                Diag.logThrowable("Enhance", t)
                warped
            }

            // --- Stage 3b (SUBTASK H23): opt-in faint pencil / handwriting enhancement --------
            // Placed AFTER the mode-selected enhancer and BEFORE any file is written. Skipped for
            // ORIGINAL (the user asked for the untouched page) and never applied to the raw capture
            // (which is always written from `source`). A failure falls back to the previous stage.
            //
            // SUBTASK H24: the feature is DARK by default. [shouldApplyHandwriting] is the single
            // production predicate that guards BOTH the read-only `measure` and the `enhance` call,
            // so with the flag off (the shipped default, `PageEnhanceSettings.handwriting = false`)
            // no HandwritingEnhancer code runs at all. `HandwritingDarkPathTest` asserts this.
            val handwritingApplied = shouldApplyHandwriting(enhancement)
            val handwritingSuggested: Boolean = if (handwritingApplied) try {
                handwritingEnhancer.measure(warped).suggested
            } catch (t: Throwable) {
                Diag.logThrowable("Handwriting.measure", t)
                false
            } else {
                false
            }
            val finalPage: Bitmap = if (handwritingApplied) {
                val hwStart = System.currentTimeMillis()
                val produced = try {
                    val out = handwritingEnhancer.enhance(enhanced)
                    if (out.isRecycled) enhanced else out
                } catch (t: Throwable) {
                    Diag.logThrowable("Handwriting", t)
                    enhanced
                }
                Diag.stageExit(
                    "Handwriting", System.currentTimeMillis() - hwStart,
                    "page=${pageId.take(8)} suggested=$handwritingSuggested"
                )
                produced
            } else {
                enhanced
            }

            // --- Stage 4: persist raw + processed + thumbnail -------------------------------
            report(PipelineStage.STORING, onStage)
            val storeStart = System.currentTimeMillis()
            val rawSaved = try {
                BitmapUtils.saveJpeg(source, rawFile, 92)
            } catch (t: Throwable) {
                Diag.logThrowable("Store", t)
                false
            }
            val pageSaved = try {
                BitmapUtils.saveJpeg(finalPage, pageFile, 92)
            } catch (t: Throwable) {
                Diag.logThrowable("Store", t)
                false
            }
            val thumbPath: String? = try {
                val thumb = BitmapUtils.createThumbnail(finalPage, THUMB_LONG_EDGE)
                try {
                    if (BitmapUtils.saveJpeg(thumb, thumbFile, 85)) thumbFile.absolutePath else null
                } finally {
                    if (!thumb.isRecycled) thumb.recycle()
                }
            } catch (t: Throwable) {
                Diag.logThrowable("Store.thumb", t)
                null
            }
            Diag.stageExit(
                "Store", System.currentTimeMillis() - storeStart,
                "rawSaved=$rawSaved pageSaved=$pageSaved thumb=${thumbPath != null}"
            )

            // Recycle every intermediate we allocated, but never the caller-owned source. An
            // identity-based set guarantees each distinct bitmap is released exactly once, including
            // the case where a later stage failed and fell back to the previous stage's bitmap.
            val owned = LinkedHashSet<Bitmap>()
            if (workingOwned && working !== source) owned.add(working)
            if (warped !== working && warped !== source) owned.add(warped)
            if (enhanced !== working && enhanced !== source && enhanced !== warped) owned.add(enhanced)
            if (finalPage !== enhanced && finalPage !== working && finalPage !== source &&
                finalPage !== warped
            ) {
                owned.add(finalPage)
            }
            owned.forEach { bmp -> runCatching { if (!bmp.isRecycled) bmp.recycle() } }

            report(PipelineStage.DONE, onStage)
            Diag.stageExit(
                "Pipeline", System.currentTimeMillis() - overallStart,
                "rawSaved=$rawSaved pageSaved=$pageSaved ${Diag.memString()}"
            )

            ScannedPage(
                id = pageId,
                // Prefer the processed page; if that failed, still surface the raw capture so the
                // page is never a dangling reference.
                imageFile = if (pageSaved) pageFile.absolutePath else rawFile.absolutePath,
                rawFile = if (rawSaved) rawFile.absolutePath else null,
                thumbnailFile = thumbPath,
                corners = effectiveQuad,
                order = 0,
                enhancement = enhancement,
                handwritingSuggested = handwritingSuggested
            )
        } catch (t: Throwable) {
            // Catastrophic, non-stage failure: log, do a best-effort raw save and STILL return a
            // usable page (raw capture), so the capture loop is never broken.
            Diag.logThrowable("Pipeline", t)
            report(PipelineStage.DONE, onStage)
            runCatching { BitmapUtils.saveJpeg(source, rawFile, 92) }
            ScannedPage(
                id = pageId,
                imageFile = rawFile.absolutePath,
                rawFile = rawFile.absolutePath,
                thumbnailFile = null,
                corners = quad ?: BitmapUtils.fullFrameQuad(source.width, source.height),
                order = 0,
                enhancement = enhancement,
                handwritingSuggested = false
            )
        }
    }

    /** App-private folder for a session's captured files. */
    fun sessionDir(sessionId: String): File = File(File(context.filesDir, SCANS_DIR), sessionId)

    /** Removes a session's captured files (used when a session is discarded/exported). */
    fun deleteSession(sessionId: String) {
        runCatching { sessionDir(sessionId).deleteRecursively() }
    }

    /**
     * Storage hygiene (ARCHITECTURE.md §6): deletes any session folder under `filesDir/scans/`
     * that is **not** [activeSessionId]. Safe by construction — the active session's live files
     * are always preserved and every failure is swallowed. Intended to run once per app start so
     * abandoned sessions don't accumulate.
     */
    fun pruneOrphanSessions(activeSessionId: String) {
        runCatching {
            val root = File(context.filesDir, SCANS_DIR)
            root.listFiles()?.forEach { child ->
                if (child.isDirectory && child.name != activeSessionId) {
                    child.deleteRecursively()
                }
            }
        }
    }

    private fun report(stage: PipelineStage, onStage: ((PipelineStage) -> Unit)?) {
        _stage.value = stage
        runCatching { onStage?.invoke(stage) }
    }

    /** Scales a quad by independent x/y factors, preserving corner order. */
    private fun scaleQuad(quad: Quad, sx: Float, sy: Float): Quad = Quad(
        topLeft = Point(quad.topLeft.x * sx, quad.topLeft.y * sy),
        topRight = Point(quad.topRight.x * sx, quad.topRight.y * sy),
        bottomRight = Point(quad.bottomRight.x * sx, quad.bottomRight.y * sy),
        bottomLeft = Point(quad.bottomLeft.x * sx, quad.bottomLeft.y * sy)
    )

    /** Absolute polygon-area fraction of an (ordered) quad relative to a `w x h` image. */
    private fun quadAreaFraction(quad: Quad, w: Int, h: Int): Double {
        if (w <= 0 || h <= 0) return -1.0
        val p = quad.points
        var sum = 0.0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            sum += a.x.toDouble() * b.y.toDouble() - b.x.toDouble() * a.y.toDouble()
        }
        val area = kotlin.math.abs(sum) / 2.0
        return area / (w.toDouble() * h.toDouble())
    }

    /** Four-decimal US-locale formatter; negative (unknown) values render as `-1`. */
    private fun fmt5(v: Double): String =
        if (v < 0) "-1" else String.format(java.util.Locale.US, "%.4f", v)

    companion object {
        const val SCANS_DIR = "scans"
        const val DIR_RAW = "raw"
        const val DIR_PAGE = "page"
        const val DIR_THUMB = "thumb"
        private const val THUMB_LONG_EDGE = 320

        /**
         * SUBTASK H24 — THE dark-launch guard for the pencil/handwriting stage.
         *
         * The stage (both its read-only `measure` and its `enhance`) may run only when the page
         * explicitly opted in AND the user did not ask for the untouched image. The shipped default
         * is `PageEnhanceSettings.handwriting = false`, and the persisted flag
         * (`SettingsRepository.handwritingEnhanceEnabled`) also defaults to `false`, so this
         * predicate is `false` on every shipped capture. Extracted from the stage-3b expression so
         * `HandwritingDarkPathTest` can assert the production decision rather than a copy of it.
         */
        internal fun shouldApplyHandwriting(settings: PageEnhanceSettings): Boolean =
            settings.handwriting && settings.mode != com.docscanai.data.EnhancementMode.ORIGINAL

        /**
         * F4 (SUBTASK H3): detected-quad overlay on the pre-warp input. Compiles to `false` on
         * release builds; the overlay is ALSO only produced when [process] receives a non-null
         * `debugOverlaySink`. Diagnostic aid for the corpus lane / on-device debugging only — it
         * never alters a stored page.
         */
        private val DEBUG_QUAD_OVERLAY: Boolean = BuildConfig.DEBUG

        /**
         * Long-edge cap for the dewarp/enhance working image (SUBTASK 9a memory safety). Larger
         * captures are downscaled to this before the heavy OpenCV/ORT stages run. ~2000 px keeps
         * text crisp while roughly quartering the pixel/MB footprint vs. a 12 MP capture.
         */
        private const val MAX_PROCESS_LONG_EDGE = 2000
    }
}
