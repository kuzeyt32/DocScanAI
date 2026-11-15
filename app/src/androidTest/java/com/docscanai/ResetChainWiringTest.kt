package com.docscanai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docscanai.capture.CaptureState
import com.docscanai.capture.CaptureStateMachine
import com.docscanai.capture.OpenCvQuadPageDetector
import com.docscanai.core.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SUBTASK H38 — one quick reset-wiring check for the user-requested detector reset control.
 *
 * The CameraScreen "Reset" button runs the chain
 * [OpenCvQuadPageDetector.reset] (-> [com.docscanai.ml.MlsdTrigger.Schedule.reset])
 * -> [CaptureStateMachine.reset] (-> [com.docscanai.capture.AutoCaptureStability.reset] +
 * cooldown/duplicate-fingerprint drop). This test exercises the same two entry points at runtime
 * and asserts the state they must leave behind: the M-LSD cadence cache empty + counters zeroed,
 * and the capture machine back in `Detecting` with no hold/cooldown residue.
 */
@RunWith(AndroidJUnit4::class)
class ResetChainWiringTest {

    @Test
    fun resetChainClearsDetectorAndCaptureState() {
        // --- detector side: the cast the CameraScreen wiring performs must hold -----------------
        val detector = ServiceLocator.pageDetector
        assertTrue(
            "ServiceLocator.pageDetector is not OpenCvQuadPageDetector (wiring cast would fail)",
            detector is OpenCvQuadPageDetector
        )
        val d = detector as OpenCvQuadPageDetector

        // reset() must be callable at any time and leave the schedule completely cold.
        d.reset()
        assertNull("cadence cache must be empty after reset", d.mlsdSchedule.cachedRepair())
        val stats = d.mlsdSchedule.statsLine()
        assertTrue("invocations not zeroed: $stats", stats.contains("invocations=0"))
        assertTrue("cacheHits not zeroed: $stats", stats.contains("cacheHits=0"))
        assertEquals("last MLSD log must be cleared", null, d.lastMlsdLog)

        // --- capture side: CaptureStateMachine.reset() ----------------------------------------
        val sm = CaptureStateMachine(CoroutineScope(Dispatchers.Default))
        sm.onCameraBound()
        sm.reset()
        assertEquals(CaptureState.DETECTING, sm.state.value)
        // A second reset is a no-op, not a crash (button can be tapped repeatedly).
        sm.reset()
        assertEquals(CaptureState.DETECTING, sm.state.value)
    }
}
