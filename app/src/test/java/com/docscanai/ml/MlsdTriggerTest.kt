package com.docscanai.ml

import com.docscanai.ml.MlsdGeometry.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ============================================================================================
 * SUBTASK H31 — executable spec for the M-LSD trigger, the cadence and the geometric cache.
 * ============================================================================================
 *
 * This is the part of H31 that can be proven without a device: the trigger is pure geometry, the
 * schedule is a counter, and the cache is two lerps. Each test pins one of the properties the
 * subtask asks for — "cheap triggers", "do not run while moving", "at most every Nth frame with
 * the last decision cached", "cache invalidation must be explicit" — so a later refactor cannot
 * quietly turn M-LSD back into an unconditional cost.
 *
 * The measured thresholds (`aspectMin` = 0.74) come from `tooling/h31_trigger_probe.py`, which
 * separated the seven merged fixtures (w/h 0.747…0.971) from the eleven clean ones (0.647…0.751).
 */
class MlsdTriggerTest {

    // --- helpers --------------------------------------------------------------------------

    private fun rect(w: Double, h: Double): List<Pt> = listOf(
        Pt(0.0, 0.0), Pt(w, 0.0), Pt(w, h), Pt(0.0, h)
    )

    private fun frame(w: Int, h: Int): Pair<Int, Int> = w to h

    private fun sig(
        quad: List<Pt>,
        w: Int = 1000,
        h: Int = 1000,
        hint: MlsdTrigger.Hint = MlsdTrigger.Hint.LIVE_UNKNOWN,
        policy: MlsdTrigger.Policy = MlsdTrigger.Policy()
    ) = MlsdTrigger.signature(quad, w, h, hint, policy)

    /** A live, settled hint (the cadence path). */
    private val LIVE = MlsdTrigger.Hint(moving = false, settledFrames = 99, still = false)

    // --- 1) the signature itself ----------------------------------------------------------

    @Test
    fun a4PageIsNotASpread() {
        // A4/Letter in this app's orientation: the gate's PAPER_ASPECT is 1.41 (h/w) => w/h 0.709.
        val page = rect(709.0, 1000.0)
        val s = sig(page)
        assertEquals(0.709, s.aspect, 1e-9)
        assertFalse("a single page must take the fast path", s.fired)
        // Inside the hysteresis band (>= 0.68) an *existing* repair would be held; with no cache
        // this is just "hold", never "enter" — nothing is scheduled from it.
        assertTrue(s.reasons, s.reasons == "none" || s.reasons.startsWith("hold"))
    }

    @Test
    fun aWideQuadIsTheMergeSignature() {
        // _The_ failure shape: two corners on the page, two on the facing page.
        val spread = rect(900.0, 1000.0)
        val s = sig(spread)
        assertTrue(s.fired)
        assertTrue("aspect must be the reason: ${s.reasons}", s.reasons.contains("aspect"))
    }

    @Test
    fun theFixtureBoundaryBehavesAsMeasured() {
        // Lowest merged fixture aspect measured offline: 0.7473. Highest clean: 0.7513 is the
        // curved-spine still, which the rule accepts as a (harmless) false positive.
        assertTrue(sig(rect(747.3, 1000.0)).fired)
        assertTrue(sig(rect(751.3, 1000.0)).fired)
        assertFalse(sig(rect(730.0, 1000.0)).fired)
        assertFalse(sig(rect(647.0, 1000.0)).fired)
    }

    @Test
    fun aQuadThatIsNotAPageAtAllIsNotASpread() {
        // The H31 corpus case that damaged an image: asp 3.861, area 0.130, repaired at strip
        // 0.254, corner error 0.18 % -> 1.78 %. Two A4 pages side by side are 1.42 wide per unit
        // height, so 3.86 is neither a page nor a spread.
        val slab = rect(3861.0, 1000.0)
        val s = sig(slab, 1000, 1000)
        assertEquals(3.861, s.aspect, 1e-9)
        assertFalse("w/h 3.86 must not be read as a spread: ${s.reasons}", s.fired)
        // Just outside the band on the correct side: a real two-page spread does fire.
        assertTrue(sig(rect(1420.0, 1000.0)).fired)
    }

    @Test
    fun borderAndEvidenceTermsAreOffUntilMeasuredToPay() {
        // A clean page that fills the frame: a corner sits 1 % from the border, and the gate sees
        // bright paper with little text. Both optional terms are OFF because the offline probe
        // showed border-hug fires on all three clean controls.
        val quad = rect(700.0, 1000.0)
        val hint = MlsdTrigger.Hint(
            moving = false, settledFrames = 99, still = false,
            hasEvidence = true, paperScore = 0.95, textScore = 0.05
        )
        val s = sig(quad, 707, 1000, hint)
        assertFalse("border/evidence must not fire by default: ${s.reasons}", s.fired)
        assertTrue("border margin is still measured for the log", s.borderMargin < 0.02)
    }

    @Test
    fun optionalTermsFireWhenExplicitlyEnabled() {
        val quad = rect(700.0, 1000.0)
        val hint = MlsdTrigger.Hint(
            moving = false, settledFrames = 99, hasEvidence = true,
            paperScore = 0.95, textScore = 0.05
        )
        val borderOnly = MlsdTrigger.Policy(useAspect = false, useArea = false, useBorder = true)
        assertTrue(sig(quad, 707, 1000, hint, borderOnly).reasons.contains("border"))

        val evidenceOnly = MlsdTrigger.Policy(useAspect = false, useArea = false, useEvidence = true)
        assertTrue(sig(quad, 707, 1000, hint, evidenceOnly).reasons.contains("evidence"))

        val areaOnly = MlsdTrigger.Policy(useAspect = false, useBorder = false, useArea = true)
        assertTrue(sig(rect(800.0, 1000.0), 1000, 1000, hint, areaOnly).reasons.contains("area"))
    }

    @Test
    fun theAreaTermIsOffByDefaultBecauseItFiresOnALargeSinglePage() {
        // A page filling 70 % of the frame is normal; it must not be read as a spread.
        val bigPage = rect(709.0, 1000.0)
        assertEquals(0.709, MlsdTrigger.areaFraction(bigPage, 1000, 1000), 1e-9)
        assertFalse(sig(bigPage).fired)
    }

    // --- 2) hysteresis: the overlay must not flicker at the threshold ---------------------

    @Test
    fun anExistingRepairIsHeldInsideTheHysteresisBandThenReleased() {
        val schedule = MlsdTrigger.Schedule(MlsdTrigger.Policy())
        val spread = rect(780.0, 1000.0) // w/h 0.78: clearly a signature
        val first = schedule.decide(sig(spread), spread, 0L, LIVE)
        assertTrue(first.invoke)
        schedule.noteInvocation(spread, MlsdMergeRepair.Fractional("left", 0.2), 0L)

        // 0.73 < 0.74 (enter) but >= 0.68 (hold), and the page barely moved (IoU 0.94): the
        // repair is kept and nothing is scheduled — this is the anti-flicker band.
        val drifting = rect(730.0, 1000.0)
        val held = schedule.decide(sig(drifting), drifting, 10L, LIVE)
        assertFalse(held.invoke)
        assertTrue(held.useCache)
        assertTrue(held.reason.startsWith("held"))
        assertNotNull("the fold must survive inside the band", schedule.cachedRepair())

        // 0.60 is clearly outside: the repair is released and counted as such.
        val gone = rect(600.0, 1000.0)
        val released = schedule.decide(sig(gone), gone, 20L, LIVE)
        assertFalse(released.invoke)
        assertFalse(released.useCache)
        assertNull(schedule.cachedRepair())
        assertEquals("signature_lost", schedule.lastInvalidation())
    }

    // --- 3) motion / stability -------------------------------------------------------------

    @Test
    fun aMovingSceneNeverInvokes() {
        val schedule = MlsdTrigger.Schedule()
        val spread = rect(900.0, 1000.0)
        val moving = MlsdTrigger.Hint(moving = true, settledFrames = 99)
        repeat(5) {
            val d = schedule.decide(sig(spread, hint = moving), spread, it.toLong(), moving)
            assertFalse("moving frame $it must not invoke", d.invoke)
            assertEquals("moving", d.reason)
        }
        assertEquals(5, schedule.skippedMoving)
        assertEquals(0, schedule.invocations)
    }

    @Test
    fun anUnsettledSceneWaitsForTheSettleWindow() {
        val schedule = MlsdTrigger.Schedule(MlsdTrigger.Policy(settleFrames = 3))
        val spread = rect(900.0, 1000.0)
        val settling = MlsdTrigger.Hint(moving = false, settledFrames = 2)
        val d = schedule.decide(sig(spread, hint = settling), spread, 0L, settling)
        assertFalse(d.invoke)
        assertTrue(d.reason.startsWith("settling"))

        val settled = MlsdTrigger.Hint(moving = false, settledFrames = 3)
        val d2 = schedule.decide(sig(spread, hint = settled), spread, 1L, settled)
        assertTrue(d2.invoke)
    }

    // --- 4) cadence -----------------------------------------------------------------------

    @Test
    fun cadenceInvokesOncePerNthEligibleFrameAndServesTheRestFromCache() {
        val cadence = 4
        val schedule = MlsdTrigger.Schedule(MlsdTrigger.Policy(cadence = cadence))
        val spread = rect(900.0, 1000.0)
        var invocations = 0
        var cachedFrames = 0
        val invokedAt = ArrayList<Int>()
        for (frame in 0 until 20) {
            val d = schedule.decide(sig(spread), spread, frame * 10L, LIVE)
            if (d.invoke) {
                invocations++
                invokedAt.add(frame)
                schedule.noteInvocation(spread, MlsdMergeRepair.Fractional("left", 0.25), frame * 10L)
            } else {
                assertTrue("non-invoked frames must be served from the cache", d.useCache)
                cachedFrames++
            }
        }
        assertEquals("frame 0 then every $cadence frames", listOf(0, 4, 8, 12, 16), invokedAt)
        assertEquals(5, invocations)
        assertEquals(15, cachedFrames)
        assertEquals(5, schedule.invocations)
        assertEquals(15, schedule.cacheHits)
    }

    @Test
    fun theShippedCadenceKeepsASpreadUnderFivePercentOfFrames() {
        // 200-frame sequence, every frame settled on a spread: the acceptance sequence.
        val schedule = MlsdTrigger.Schedule(MlsdTrigger.Policy())
        val spread = rect(900.0, 1000.0)
        var invocations = 0
        for (frame in 0 until 200) {
            val d = schedule.decide(sig(spread), spread, frame * 33L, LIVE)
            if (d.invoke) {
                invocations++
                schedule.noteInvocation(spread, MlsdMergeRepair.Fractional("left", 0.25), frame * 33L)
            }
        }
        assertTrue(
            "worst case (an all-spread sequence) must stay below 5 % of frames so the p95 " +
                "delta stays at zero; got $invocations/200",
            invocations <= 9
        )
        assertEquals(9, invocations)
    }

    @Test
    fun aStillIsAlwaysAllowedOneAttempt() {
        val schedule = MlsdTrigger.Schedule(MlsdTrigger.Policy(cadence = 1000))
        val spread = rect(900.0, 1000.0)
        val still = MlsdTrigger.Hint.STILL
        val cold = schedule.decide(sig(spread, hint = still), spread, 0L, still)
        assertTrue("a capture must not wait for a cadence slot", cold.invoke)
        assertTrue(cold.reason, cold.reason.startsWith("signature") || cold.reason.startsWith("still"))

        // Even with a warm cache, a capture re-measures rather than trusting a preview's answer.
        schedule.noteInvocation(spread, MlsdMergeRepair.Fractional("left", 0.2), 0L)
        val warm = schedule.decide(sig(spread, hint = still), spread, 10L, still)
        assertTrue(warm.invoke)
        assertTrue(warm.reason, warm.reason.startsWith("still"))
        assertEquals(1, schedule.invocations)
    }

    // --- 5) explicit cache invalidation ----------------------------------------------------

    @Test
    fun aChangedPageInvalidatesTheCache() {
        val schedule = MlsdTrigger.Schedule()
        val spread = rect(900.0, 1000.0)
        val d = schedule.decide(sig(spread), spread, 0L, LIVE)
        assertTrue(d.invoke)
        schedule.noteInvocation(spread, MlsdMergeRepair.Fractional("left", 0.2), 0L)

        // Same shape, but the page has moved by 20 % of its width: IoU far below 0.90.
        val moved = listOf(
            Pt(200.0, 0.0), Pt(900.0, 0.0), Pt(900.0, 1000.0), Pt(200.0, 1000.0)
        )
        val d2 = schedule.decide(sig(moved), moved, 10L, LIVE)
        assertTrue("a moved page must be re-measured", d2.invoke)
        assertEquals("cache_iou", schedule.lastInvalidation())
    }

    @Test
    fun aStaleCacheIsInvalidatedByTtl() {
        val policy = MlsdTrigger.Policy(cacheTtlMs = 500L)
        val schedule = MlsdTrigger.Schedule(policy)
        val spread = rect(900.0, 1000.0)
        schedule.decide(sig(spread), spread, 0L, LIVE)
        schedule.noteInvocation(spread, MlsdMergeRepair.Fractional("left", 0.2), 0L)

        val fresh = schedule.decide(sig(spread), spread, 400L, LIVE)
        assertFalse("400 ms < ttl: nothing to do", fresh.invoke)

        val stale = schedule.decide(sig(spread), spread, 900L, LIVE)
        assertTrue("900 ms > ttl: re-validate", stale.invoke)
        assertEquals("ttl", schedule.lastInvalidation())
    }

    @Test
    fun resetDropsEverythingAndZeroesTheCounters() {
        val schedule = MlsdTrigger.Schedule()
        val spread = rect(900.0, 1000.0)
        schedule.decide(sig(spread), spread, 0L, LIVE)
        schedule.noteInvocation(spread, MlsdMergeRepair.Fractional("left", 0.2), 0L)
        assertNotNull(schedule.cachedRepair())

        schedule.reset()
        assertNull(schedule.cachedRepair())
        assertEquals(0, schedule.invocations)
        assertEquals(0, schedule.cacheHits)
        assertEquals("reset", schedule.lastInvalidation())
        // Cold again: the next eligible frame must invoke.
        assertTrue(schedule.decide(sig(spread), spread, 10L, LIVE).invoke)
    }

    // --- 6) the cache is the same fold the model found ------------------------------------

    @Test
    fun theCachedFoldProjectsTheSameQuadTheModelProduced() {
        // SUBTASK H32 changed this case's premise: the rule now applies the detector's own
        // acceptance guard (MlsdTrigger.acceptsRepair: the split must move the quad TOWARDS the
        // paper aspect) at SELECTION time, so a sliver split off a PAGE-sized quad — which makes
        // the kept half narrower than a page and was measured as damage — is refused outright
        // instead of being produced and then rejected downstream. The round-trip property this
        // test guards is unchanged; the scene is now a SPREAD quad, where a 20 % fold is a real
        // facing-page sliver and the kept half is a better-shaped page.
        val quad = rect(1420.0, 1000.0)
        // A spine-parallel fold 20 % in from the left, a bit taller than the spread.
        val fold = MlsdGeometry.Seg(284.0, -100.0, 284.0, 1100.0)
        val outcome = MlsdMergeRepair.apply(quad, listOf(fold))
        assertTrue("the H32 rule must accept a clear interior fold on a spread", outcome.applied)
        assertEquals("left", outcome.side)
        assertEquals(0.20, outcome.stripFrac, 0.02)

        val cached = MlsdMergeRepair.fractionalOf(outcome)
        assertNotNull("an applied outcome must be cacheable", cached)
        val projected = MlsdMergeRepair.applyFractional(quad, cached!!)
        assertNotNull(projected)
        assertEquals(
            "projecting the cached fold must reproduce the model's own quad",
            MlsdMergeRepair.pct(MlsdGeometry.polygonArea(outcome.quad!!)),
            MlsdMergeRepair.pct(MlsdGeometry.polygonArea(projected!!))
        )
        assertEquals(4, projected.size)
        // Corner order preserved: TL, TR, BR, BL (the kept half is the right 80 %).
        assertEquals(284.0, projected[0].x, 2.0)
        assertEquals(1420.0, projected[1].x, 1e-9)
        assertEquals(1420.0, projected[2].x, 1e-9)
        assertEquals(284.0, projected[3].x, 2.0)
    }

    @Test
    fun theProjectionFollowsTheQuadContinuously() {
        // Two consecutive frames of a hand-held page: the projection must move with the quad,
        // i.e. no jump between "model ran" and "model did not run".
        val a = rect(900.0, 1300.0)
        val b = listOf(Pt(3.0, 2.0), Pt(903.0, 1.0), Pt(905.0, 1302.0), Pt(2.0, 1301.0))
        val repair = MlsdMergeRepair.Fractional("left", 0.18)
        val pa = MlsdMergeRepair.applyFractional(a, repair)!!
        val pb = MlsdMergeRepair.applyFractional(b, repair)!!
        for (i in 0 until 4) {
            assertTrue(
                "corner $i moved further than the quad did",
                kotlin.math.abs(pa[i].x - pb[i].x) < 10.0 && kotlin.math.abs(pa[i].y - pb[i].y) < 10.0
            )
        }
        assertEquals(
            "the fold keeps its relative position: both quads lose the same width fraction",
            (1.0 - repair.t) * MlsdGeometry.polygonArea(a),
            MlsdGeometry.polygonArea(pa),
            1.0
        )
    }

    @Test
    fun aBadQuadIsNotCacheable() {
        assertNull(MlsdMergeRepair.applyFractional(rect(10.0, 10.0).take(3), MlsdMergeRepair.Fractional("left", 0.2)))
        assertNull(
            MlsdMergeRepair.fractionalOf(
                MlsdMergeRepair.Outcome(null, false, "no_interior_line")
            )
        )
    }

    // --- 7) "do no harm": the guard that closes the H30 OPEN item --------------------------

    @Test
    fun aGutterSplitIsAcceptedBecauseItRestoresThePaperAspect() {
        // A two-page spread is nearly one and a half times as wide as it is tall; splitting at a
        // real fold gives a sheet of paper back, which is what the gate's aspect prior wants.
        val spread = rect(1400.0, 1000.0)
        val page = rect(710.0, 1000.0)
        assertTrue(MlsdTrigger.improvesPaperAspect(spread, page))
        assertTrue(
            "a spread must score worse than a page",
            MlsdTrigger.paperAspectPrior(spread) < MlsdTrigger.paperAspectPrior(page)
        )
    }

    @Test
    fun aTextEdgeSplitIsRejectedBecauseItMakesThePageNarrowerThanAPage() {
        // Both already page-shaped: narrowing one can only move it away from the paper prior.
        val page = rect(709.0, 1000.0)
        val narrowed = rect(650.0, 1000.0)
        assertFalse(MlsdTrigger.improvesPaperAspect(page, narrowed))
    }

    @Test
    fun theMeasuredFixtureCasesBehaveAsMeasured() {
        // Device quad aspects and the strip fractions the H31 run logged.
        // bs01 preview — the clean control H30's rule used to split at 8 % — must be refused.
        assertFalse(
            "clean control: 0.703 -> 0.647 must be rejected",
            MlsdTrigger.improvesPaperAspect(rect(703.0, 1000.0), rect(647.0, 1000.0))
        )
        // bs06 preview (clean, gutter shadow) and bs09 preview (clean, strong perspective).
        assertFalse(MlsdTrigger.improvesPaperAspect(rect(665.0, 1000.0), rect(611.8, 1000.0)))
        assertFalse(MlsdTrigger.improvesPaperAspect(rect(676.0, 1000.0), rect(621.9, 1000.0)))
        // bs05 still — the ONE merged still — 0.934 -> 0.607 at a 35 % strip: accepted.
        assertTrue(MlsdTrigger.improvesPaperAspect(rect(934.0, 1000.0), rect(607.1, 1000.0)))
        // bs02 preview (thin merged sliver, 8 % strip): 0.775 -> 0.713: accepted.
        assertTrue(MlsdTrigger.improvesPaperAspect(rect(775.0, 1000.0), rect(713.0, 1000.0)))
        // bs08 preview (thinnest merged sliver): 0.747 -> 0.687: accepted, but only just.
        assertTrue(MlsdTrigger.improvesPaperAspect(rect(747.0, 1000.0), rect(687.2, 1000.0)))
    }

    @Test
    fun aMarginSizedStripIsNotAPageSliver() {
        // The measured split between good and damaging folds: every repair that helped cut
        // 0.076 .. 0.259 of the width, every one that damaged a page cut 0.061 .. 0.067.
        val page = rect(1000.0, 1300.0)
        val spread = rect(1500.0, 1300.0)
        val repaired = rect(1000.0, 1300.0)
        assertTrue("a real sliver at 7.6 % must be kept",
            MlsdTrigger.acceptsRepair(spread, repaired, 0.076))
        assertTrue("a large sliver at 25.9 % must be kept",
            MlsdTrigger.acceptsRepair(spread, repaired, 0.259))
        assertFalse("a 6.7 % strip is a margin: the corpus damage case",
            MlsdTrigger.acceptsRepair(spread, repaired, 0.067))
        assertFalse("a 6.1 % strip is a margin: the bs05-still wrong split",
            MlsdTrigger.acceptsRepair(spread, repaired, 0.061))
        // And a good-sized strip still has to move towards the paper aspect.
        assertFalse("narrowing an already page-shaped quad is damage at any size",
            MlsdTrigger.acceptsRepair(page, rect(650.0, 1300.0), 0.35))
    }

    @Test
    fun describeMentionsTheShippedNumbers() {
        val text = MlsdTrigger.describe(MlsdTrigger.Policy())
        assertTrue(text, text.contains("aspect>=0.740"))
        assertTrue(text, text.contains("cadence=24"))
        assertTrue(text, text.contains("border<=0.020(off)"))
    }

    @Test
    fun frameHelperIsConsistent() {
        // Guards the helper itself: the tests above assume a 1000x1000 frame unless stated.
        assertEquals(1000 to 1000, frame(1000, 1000))
    }
}
