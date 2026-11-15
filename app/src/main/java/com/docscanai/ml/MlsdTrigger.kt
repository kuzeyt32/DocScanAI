package com.docscanai.ml

import kotlin.math.abs
import kotlin.math.hypot

/**
 * ============================================================================================
 * SUBTASK H31 — make M-LSD affordable: run it only when it can help, and do not re-run it.
 * ============================================================================================
 *
 * H30 proved the quality win (merged slivers 7/12 -> 2/12, mean corner 3.9562 % -> 3.5672 %,
 * IoU 0.8279 -> 0.8442, detection 18/18) and got blocked by the *cost* of running M-LSD on every
 * detection: +73.68 ms p50 on a preview frame against a +30 ms budget.
 *
 * H31 does not try to make the model cheaper. It stops calling it:
 *
 *  1. **Merge signature.** A quad that absorbed the facing page is *wide*: the corner model puts
 *     two corners on the page and two on the neighbour, so the quad's width/height ratio moves
 *     towards 1.0 (a single A4/Letter page in this app's orientation is w/h ≈ 0.709 — the gate's
 *     `PAPER_ASPECT` = 1.41). Measured on the 18 fixtures (see `tooling/h31_trigger_probe.py`):
 *     merged quads span **w/h 0.747 … 0.971**, clean quads **0.647 … 0.751** — so `w/h >= 0.74`
 *     catches all seven merged assets and one harmless still. This costs four `hypot` calls.
 *     The other two candidates the subtask names were measured and **rejected**: a corner within
 *     2 % of the frame border fires on every preview (including all three clean controls), and an
 *     area-fraction threshold either misses six of seven merged assets or fires on eight clean
 *     stills — both are on by default and the instrumentation test logs what they decide.
 *  2. **Motion / stability.** M-LSD is never run while the frame content is moving or before the
 *     quad has settled ([Policy.settleFrames]); this is the existing H21 motion guard, reused.
 *  3. **Cadence + cache.** At most one invocation per [Policy.cadence] eligible frames, and the
 *     *result* is cached as geometry — the fold's side and its distance from that side, as a
 *     fraction of the quad width ([MlsdMergeRepair.Fractional]). Every frame in between re-applies
 *     that fold to the **current** quad with [MlsdMergeRepair.applyFractional], which is a couple
 *     of lerps. So the overlay keeps the repaired quad continuously (no flicker) and the quality
 *     win survives frames where the model did not run at all.
 *  4. **Explicit invalidation.** The cache is dropped when the page changes (quad IoU below
 *     [Policy.cacheMinIou]), when it gets old ([Policy.cacheTtlMs]), when the scene moves away from
 *     the signature by more than the hysteresis band, or on [MlsdSchedule.invalidate]. Each of
 *     those is a separate, counted reason.
 *
 * Hysteresis (`Policy.aspectRelease`) is what keeps the overlay from flickering at the threshold:
 * *entering* needs `w/h >= aspectMin`, *staying* only needs `w/h >= aspectMin - aspectRelease`.
 *
 * No Android types: this module runs on the JVM in `MlsdTriggerTest`.
 */
object MlsdTrigger {

    /** Thresholds. One object so the instrumentation test can A/B a whole policy at once. */
    data class Policy(
        /** Enter M-LSD when the quad is wider than this fraction of its height. */
        val aspectMin: Double = 0.74,
        /**
         * ...but not wider than this. Two A4 pages side by side are `2 x 0.709 = 1.42` wide per
         * unit height. H31 set this to 1.75; SUBTASK H32 MEASURED (real book-spread set,
         * `tooling/out/h32/RED_BASELINE.md`) that real spread quads carry w/h 1.85..2.15 under
         * perspective and cropping (rb022 1.848, rb034 2.004, and the trigger never fired on
         * them), so the band is widened to 2.30. The H31 corpus case that motivated the cap
         * (`asp=3.861 area=0.130`) is still outside the band and still refused — the cap's
         * protection is preserved at 2.30 and its reasoning is unchanged.
         */
        /**
         * SUBTASK H38 (fix d, one re-measure): 2.30 -> 3.0. The merge-signature band decides which
         * wide/merged quads get an M-LSD split/repair attempt; 2.30 left several page+table unions
         * (and vertical whole-book captures) outside the band, so they stayed merged. 3.0 lets the
         * repair run on them; revert if wrong-page/merged gates regress.
         */
        val aspectMax: Double = 3.0,
        /** How far below [aspectMin] an existing repair is still held (anti-flicker band). */
        val aspectRelease: Double = 0.06,
        /**
         * Enter when the quad covers more than this fraction of the frame.
         *
         * **Off by default — measured, not assumed.** Offline (`tooling/h31_trigger_probe.py`) an
         * area threshold that is high enough not to fire on the clean fixtures (0.55) catches just
         * 1 of the 7 merged assets and fires on a *large single page*; a threshold low enough to
         * catch them (0.45) fires on 10 of 18 assets including six clean ones. It adds nothing that
         * the aspect term does not already catch, so it stays available and off.
         */
        val areaMin: Double = 0.55,
        /** Release an area-triggered repair once the coverage drops below this. */
        val areaRelease: Double = 0.05,
        /** Enter when a corner is this close to the frame border (fraction of the short side). */
        val borderMargin: Double = 0.02,
        /** Enter when the gate sees paper without text lines (disagreeing evidence). */
        val evidencePaperMin: Double = 0.60,
        val evidenceTextMax: Double = 0.45,

        val useAspect: Boolean = true,
        /** Measured not to pay off (see [areaMin]); off by default. */
        val useArea: Boolean = false,
        /** Measured not to pay off on its own (fires on every clean preview); off by default. */
        val useBorder: Boolean = false,
        /** Live-path refinement; needs the previous frame's gate numbers. */
        val useEvidence: Boolean = false,

        /** Frames the quad must have been stable before M-LSD may run (motion trigger). */
        val settleFrames: Int = 2,
        /** At most one invocation per this many eligible frames (cadence trigger). */
        val cadence: Int = 24,
        /** Cache lifetime, ms. Longer than [cadence] frames at 30 fps so the two do not fight. */
        val cacheTtlMs: Long = 1200L,
        /** Cache is invalid once the page has changed this much (quad IoU). */
        val cacheMinIou: Double = 0.90
    )

    /**
     * Per-frame context supplied by the caller. The live analyser fills it from the H21 motion
     * guard and the previous frame's gate; the still path marks [still] so a capture gets exactly
     * one M-LSD attempt instead of waiting for a cadence slot it will never reach.
     */
    data class Hint(
        val moving: Boolean = false,
        val settledFrames: Int = Int.MAX_VALUE,
        val still: Boolean = false,
        val hasEvidence: Boolean = false,
        val paperScore: Double = 0.0,
        val textScore: Double = 0.0
    ) {
        companion object {
            /** The live analyser's default before it has any gate history. */
            val LIVE_UNKNOWN = Hint(moving = false, settledFrames = 0, still = false)

            /** A capture: settled by definition, one shot. */
            val STILL = Hint(moving = false, settledFrames = Int.MAX_VALUE, still = true)
        }
    }

    /**
     * What the cheap geometry said. [fired] = enter (schedule an invocation), [hold] = keep an
     * existing repair alive (hysteresis band), [reasons] = which terms fired, for the log line.
     */
    data class Signature(
        val fired: Boolean,
        val hold: Boolean,
        val reasons: String,
        val aspect: Double,
        val areaFrac: Double,
        val borderMargin: Double
    )

    /** Quad width / height, in the quad's own axes. 0 when degenerate. */
    fun aspect(quad: List<MlsdGeometry.Pt>): Double {
        if (quad.size != 4) return 0.0
        val (wAxis, hAxis) = MlsdGeometry.quadAxes(quad)
        val w = hypot(wAxis.x, wAxis.y)
        val h = hypot(hAxis.x, hAxis.y)
        return if (h > 1e-9) w / h else 0.0
    }

    /** Quad area / frame area. */
    fun areaFraction(quad: List<MlsdGeometry.Pt>, frameW: Int, frameH: Int): Double {
        if (quad.size != 4 || frameW <= 0 || frameH <= 0) return 0.0
        return MlsdGeometry.polygonArea(quad) / (frameW.toDouble() * frameH.toDouble())
    }

    /** Closest quad corner to the nearest frame border, as a fraction of the frame's short side. */
    fun borderMargin(quad: List<MlsdGeometry.Pt>, frameW: Int, frameH: Int): Double {
        if (quad.isEmpty()) return 0.0
        val short = kotlin.math.min(frameW, frameH).toDouble()
        if (short <= 0.0) return 0.0
        var best = Double.MAX_VALUE
        for (p in quad) {
            val d = kotlin.math.min(
                kotlin.math.min(p.x, p.y),
                kotlin.math.min(frameW - p.x, frameH - p.y)
            )
            if (d < best) best = d
        }
        return (if (best == Double.MAX_VALUE) 0.0 else best.coerceAtLeast(0.0)) / short
    }

    /**
     * Evaluates the merge signature. Cheap by construction: no image access, no allocation beyond
     * the returned object — measured at ~2 µs, which is why the trigger can run on every frame.
     */
    fun signature(
        quad: List<MlsdGeometry.Pt>,
        frameW: Int,
        frameH: Int,
        hint: Hint,
        policy: Policy
    ): Signature {
        if (quad.size != 4) {
            return Signature(false, false, "bad_quad", 0.0, 0.0, 0.0)
        }
        val aspect = aspect(quad)
        val area = areaFraction(quad, frameW, frameH)
        val border = borderMargin(quad, frameW, frameH)

        val enter = ArrayList<String>(4)
        val hold = ArrayList<String>(4)

        if (policy.useAspect) {
            // In-band only: too narrow is a page, too wide is not a page at all.
            val inBand = aspect in policy.aspectMin..policy.aspectMax
            val inHoldBand = aspect >= policy.aspectMin - policy.aspectRelease &&
                aspect <= policy.aspectMax + policy.aspectRelease
            if (inBand) enter.add("aspect")
            if (inHoldBand) hold.add("aspect")
        }
        if (policy.useArea) {
            if (area >= policy.areaMin) enter.add("area")
            if (area >= policy.areaMin - policy.areaRelease) hold.add("area")
        }
        if (policy.useBorder) {
            // No hysteresis: a corner either is or is not at the border; the cache IoU check is
            // the thing that stops this from flapping (an unchanged scene keeps its repair).
            if (border <= policy.borderMargin) {
                enter.add("border")
                hold.add("border")
            }
        }
        if (policy.useEvidence && hint.hasEvidence) {
            val disagree = hint.paperScore >= policy.evidencePaperMin &&
                hint.textScore <= policy.evidenceTextMax
            if (disagree) {
                enter.add("evidence")
                hold.add("evidence")
            }
        }

        val reasons = if (enter.isEmpty()) {
            if (hold.isEmpty()) "none" else "hold(" + hold.joinToString("+") + ")"
        } else {
            enter.joinToString("+")
        }
        return Signature(enter.isNotEmpty(), hold.isNotEmpty(), reasons, aspect, area, border)
    }

    // ==========================================================================================
    // "Do no harm" — the guard that close the H30 OPEN item
    // ==========================================================================================

    /**
     * Paper aspect (h/w) the page gate expects, and the width of its Gaussian falloff.
     *
     * These **mirror** `PagePlausibility.PAPER_ASPECT` (1.41) and `ASPECT_SIGMA` (0.55); they are
     * duplicated rather than shared because the gate's copies are private and this module must stay
     * pure Kotlin. They are not new thresholds: nothing here changes the gate, it only refuses a
     * repair that would move a quad *away* from the shape the gate already prefers.
     */
    const val PAPER_ASPECT_HW = 1.41
    const val PAPER_ASPECT_SIGMA = 0.55

    /**
     * A facing-page sliver is at least this fraction of the page width; anything thinner is a
     * margin, a shadow or a text block edge and must not be cut off a page.
     *
     * **Measured, not assumed.** Every repair that improved the 18 fixtures cut 0.076 … 0.259 of
     * the width; every repair that damaged a page cut 0.061 … 0.067 (bs05 still's wrong split, and
     * the two `syn_persp_*` corpus images whose corner error went 0.18 % -> 1.78 %). The fixture
     * slivers themselves are 0.08 … 0.35 of the page. This is the H30 OPEN item ("the rule also
     * splits the clean controls at ~8 %… a text edge is not [uniform along the fold]") closed with
     * a size prior instead of a uniformity analysis. It sits *above* `MlsdMergeRepair.MIN_STRIP_FRAC`
     * (0.05), which stays as the geometry sanity bound.
     */
    const val REPAIR_MIN_STRIP = 0.075

    /** `0.25 / (1 + 0.25)` — the strip fraction above which a fold is a spread split, not a
     *  sliver removal. See [acceptsRepair]. */
    const val SPREAD_SPLIT_MAX_STRIP = 0.20

    /**
     * True when a merge repair may reach the warp at all: it must cut a sliver of plausible size
     * **and** move the quad towards the paper aspect. Either failure means the fold was a text
     * edge, a margin or a shadow, not the facing page.
     *
     * The upper bound is the owner's spread rule: a fold that discards a quarter or more of the
     * quad is cutting a two-page spread in half, and the owner wants both leaves scanned as one.
     * `stripFrac` is `discard / (keep + discard)`, so `discard / keep >= 0.25` is
     * `stripFrac >= 0.20`. Kept in sync with `MlsdMergeRepair.SECOND_LEAF_KEEP_RATIO`; this is the
     * second line of defence, because [MlsdMergeRepair.apply] already refuses such folds.
     */
    fun acceptsRepair(
        before: List<MlsdGeometry.Pt>,
        after: List<MlsdGeometry.Pt>,
        stripFrac: Double
    ): Boolean = stripFrac >= REPAIR_MIN_STRIP &&
        stripFrac < SPREAD_SPLIT_MAX_STRIP &&
        improvesPaperAspect(before, after)

    /** How well a quad matches the paper aspect prior (0..1), exactly as the gate scores it. */
    fun paperAspectPrior(quad: List<MlsdGeometry.Pt>): Double {
        val w = aspect(quad)
        if (w <= 1e-9) return 0.0
        val hw = 1.0 / w
        val z = (hw - PAPER_ASPECT_HW) / PAPER_ASPECT_SIGMA
        return kotlin.math.exp(-z * z)
    }

    /**
     * True when replacing [before] with [after] makes the quad look **more** like a sheet of paper.
     *
     * This is the H30 OPEN item, implemented as a guard rather than as another threshold. H30
     * measured the merge rule splitting the clean controls at ~8 % ("the fixture's text-block edge
     * is a full-height line"), and H31 measured the same thing on the real corpus: with the repair
     * running on production stills the corpus mean corner error went 0.03440 -> 0.04039 % and
     * placement 24/0/16 -> 21/0/19. Both are the same defect: a split along a *text* edge makes the
     * page narrower than a page, while a split along a real gutter takes a two-page spread back to
     * one page's proportions. So the rule is: only keep the repair when it moves the quad towards
     * the paper aspect by more than [minGain].
     */
    fun improvesPaperAspect(
        before: List<MlsdGeometry.Pt>,
        after: List<MlsdGeometry.Pt>,
        minGain: Double = 0.005
    ): Boolean {
        if (before.size != 4 || after.size != 4) return false
        return paperAspectPrior(after) > paperAspectPrior(before) + minGain
    }

    /** One-line description of the active policy, emitted once per detector session. */
    fun describe(policy: Policy): String {
        fun f(v: Double) = String.format(java.util.Locale.US, "%.3f", v)
        val aspect = "aspect>=${f(policy.aspectMin)}..<=${f(policy.aspectMax)}" +
            "/hold>=${f(policy.aspectMin - policy.aspectRelease)}"
        val area = "area>=${f(policy.areaMin)}${if (policy.useArea) "" else "(off)"}"
        val border = "border<=${f(policy.borderMargin)}${if (policy.useBorder) "" else "(off)"}"
        val evidence = if (policy.useEvidence) {
            "evidence>=${f(policy.evidencePaperMin)}/<=${f(policy.evidenceTextMax)}"
        } else {
            "evidence(off)"
        }
        return "trigger($aspect $area $border $evidence settle>=${policy.settleFrames} " +
            "cadence=${policy.cadence} ttl=${policy.cacheTtlMs}ms cacheIou>=${f(policy.cacheMinIou)})"
    }

    /**
     * The cadence + cache state machine. One instance per detector (a detector is used from a
     * single analysis thread, but the still path may arrive from the pipeline thread, so every
     * entry point is synchronised).
     *
     * Deliberately *not* a policy holder: [policy] is a `@Volatile var` so the instrumentation A/B
     * can change the cadence between passes without rebuilding the detector.
     */
    class Schedule(@Volatile var policy: Policy = Policy()) {

        /**
         * @param invoke   run the model on this frame;
         * @param useCache re-apply the cached fold to this frame's quad;
         * @param reason   counted, loggable reason for the decision.
         */
        data class Decision(val invoke: Boolean, val useCache: Boolean, val reason: String)

        private var sinceInvoke: Int = Int.MAX_VALUE
        private var lastInvokeAtMs: Long = 0L
        private var cache: MlsdMergeRepair.Fractional? = null
        private var cacheQuad: List<MlsdGeometry.Pt>? = null
        private var lastInvalidate: String = "init"

        var invocations: Int = 0
            private set
        var cacheHits: Int = 0
            private set
        var skippedNoSignature: Int = 0
            private set
        var skippedMoving: Int = 0
            private set
        var skippedSettling: Int = 0
            private set
        var skippedCadence: Int = 0
            private set

        /** The fold currently held, or `null` when there is nothing to project. */
        @Synchronized
        fun cachedRepair(): MlsdMergeRepair.Fractional? = cache

        /** Why the last [invalidate] happened (or `init`). */
        @Synchronized
        fun lastInvalidation(): String = lastInvalidate

        /** Explicit, counted invalidation. Every drop path goes through here. */
        @Synchronized
        fun invalidate(reason: String) {
            if (cache != null || cacheQuad != null) lastInvalidate = reason
            cache = null
            cacheQuad = null
        }

        @Synchronized
        fun reset() {
            invalidate("reset")
            sinceInvoke = Int.MAX_VALUE
            lastInvokeAtMs = 0L
            invocations = 0
            cacheHits = 0
            skippedNoSignature = 0
            skippedMoving = 0
            skippedSettling = 0
            skippedCadence = 0
        }

        /**
         * Decides this frame. Must be called once per frame that reached the detector, whether or
         * not an invocation happens: the cadence counter is advanced here.
         */
        @Synchronized
        fun decide(
            signature: Signature,
            quad: List<MlsdGeometry.Pt>,
            nowMs: Long,
            hint: Hint
        ): Decision {
            // Signature gone (and outside the hysteresis band) -> the repair is released.
            if (!signature.fired && !(signature.hold && cache != null)) {
                invalidate(if (signature.hold) "no_signature" else "signature_lost")
                skippedNoSignature++
                return Decision(false, false, "no_signature")
            }
            if (!signature.fired) {
                // Inside the band with a live cache: keep the repair, do not schedule M-LSD.
                if (cacheValid(quad, nowMs)) {
                    cacheHits++
                    sinceInvoke = bump(sinceInvoke)
                    return Decision(false, true, "held(${signature.reasons})")
                }
                invalidate("cache_iou")
                return Decision(true, false, "renew(${signature.reasons})")
            }

            if (!hint.still) {
                if (hint.moving) {
                    skippedMoving++
                    return Decision(false, false, "moving")
                }
                if (hint.settledFrames < policy.settleFrames) {
                    skippedSettling++
                    return Decision(false, false, "settling(${hint.settledFrames})")
                }
            }

            sinceInvoke = bump(sinceInvoke)
            if (!cacheValid(quad, nowMs)) {
                // No cache yet, the page changed, or the fold got too old: pay for a fresh answer.
                invalidate(
                    when {
                        cache == null && cacheQuad == null -> "cold"
                        nowMs - lastInvokeAtMs > policy.cacheTtlMs -> "ttl"
                        else -> "cache_iou"
                    }
                )
                return Decision(true, false, "signature(${signature.reasons})")
            }
            if (hint.still) {
                // One attempt per capture: never serve a still from a preview's cache.
                return Decision(true, false, "still(${signature.reasons})")
            }
            if (sinceInvoke >= policy.cadence) {
                return Decision(true, false, "cadence($sinceInvoke)")
            }
            cacheHits++
            return Decision(false, true, "cached($sinceInvoke/${policy.cadence})")
        }

        /**
         * Records that the model actually ran on [quad]. [repair] is what it decided — the cached
         * fold, or `null` when it found nothing (in which case later frames must not invent one).
         */
        @Synchronized
        fun noteInvocation(
            quad: List<MlsdGeometry.Pt>,
            repair: MlsdMergeRepair.Fractional?,
            nowMs: Long
        ) {
            invocations++
            sinceInvoke = 0
            lastInvokeAtMs = nowMs
            cache = repair
            cacheQuad = if (repair != null) quad.toList() else null
            if (repair == null) lastInvalidate = "model_no_repair"
        }

        private fun cacheValid(quad: List<MlsdGeometry.Pt>, nowMs: Long): Boolean {
            val q = cacheQuad ?: return false
            if (cache == null) return false
            if (nowMs - lastInvokeAtMs > policy.cacheTtlMs) return false
            if (q.size != 4 || quad.size != 4) return false
            return MlsdMergeRepair.iou(quad, q) >= policy.cacheMinIou
        }

        private fun bump(v: Int): Int = if (v == Int.MAX_VALUE) 1 else v + 1

        /** Counters as one log token. */
        @Synchronized
        fun statsLine(): String = "invocations=$invocations cacheHits=$cacheHits " +
            "skipNoSig=$skippedNoSignature skipMoving=$skippedMoving " +
            "skipSettling=$skippedSettling skipCadence=$skippedCadence " +
            "cache=${cache?.let { "${it.side}@${MlsdMergeRepair.pct(it.t)}" } ?: "-"} " +
            "lastInvalidate=$lastInvalidate"
    }
}
