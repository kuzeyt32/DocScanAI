package com.docscanai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscanai.core.di.ServiceLocator
import com.docscanai.data.EnhancementMode
import com.docscanai.data.PageEnhanceSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ============================================================================================
 * SUBTASK F — behavioural verification of the detector latch fix, in ONE process (no restart).
 * ============================================================================================
 *
 * Reproduces the reported defect scenario end-to-end against the **real** pipeline:
 *
 *   1. **AI OFF** — run a capture. The detector's model provider returns `null`, so the classical
 *      OpenCV path is used and **zero** ONNX sessions are created (the model file is present on
 *      the device but the AI gate is closed).
 *   2. **Flip the runtime AI switch ON** — `ServiceLocator.neuralProcessingEnabled = true`, exactly
 *      what the in-app "AI: Off → On" toggle does. **No app restart.**
 *   3. **AI ON** — run one more capture. With the latch FIX the detector re-resolves its provider
 *      and loads the DexiNed boundary model immediately → `ServiceLocator.boundaryModel != null`.
 *
 * With the OLD bug the `null` resolved in step 1 was latched (`modelResolved = true`), so step 3
 * kept returning the stale `null` and the DexiNed session was never created until a restart — the
 * final assertion would fail.
 *
 * The harness (`tooling/latch_check.cmd`) additionally captures logcat and verifies, by ordering of
 * the `LATCHTEST phase=*` markers vs the `session EP=` lines, that **no** ONNX session appears
 * before the `FLIP` marker and at least one appears after it.
 *
 * Models are NOT bundled — the harness provisions them first; if they are missing this test fails
 * loudly with the shared `NEURAL_RUN_INVALID models-absent` sentinel (INVALID, never a false pass).
 */
@RunWith(AndroidJUnit4::class)
class DetectorLatchRuntimeTest {

    companion object {
        private const val TAG = "DocScan"

        @JvmStatic
        @BeforeClass
        fun setUp() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            ServiceLocator.init(context)
            ServiceLocator.ensureOpenCv()
            // Start from AI OFF with the safe CPU EP — the state the bug reproduces from.
            runBlocking {
                runCatching {
                    ServiceLocator.settingsRepository.setNeuralProcessingEnabled(false)
                    ServiceLocator.settingsRepository.setUseXnnpack(false)
                }
            }
            ServiceLocator.neuralProcessingEnabled = false
            ServiceLocator.useXnnpack = false
            // Freeze the ORT factory now so its EP choice is the deterministic CPU EP.
            ServiceLocator.ortSessionFactory
            Log.i(TAG, "LATCHTEST phase=SETUP neuralProcessingEnabled=false useXnnpack=false")
        }
    }

    @Test
    fun detectorLatch_reResolvesAfterRuntimeAiFlip_withoutRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Fail loudly (INVALID) if the neural models are absent — the test is meaningless without them.
        NeuralRepro.requireModels()

        val pipeline = ServiceLocator.pipeline
        val sessionOff = "latch-off-${System.currentTimeMillis()}"

        // ---- Phase 1: AI OFF — classical path, ZERO ONNX sessions ------------------------------
        Log.i(TAG, "LATCHTEST phase=OFF_BEGIN (expect: zero ONNX sessions)")
        runBlocking {
            val bmp = NeuralRepro.loadBitmap(context)
            assertNotNull("assets/${NeuralRepro.TEST_ASSET} must decode", bmp)
            pipeline.process(
                sessionId = sessionOff,
                source = bmp!!,
                quad = null, // detector runs on the still -> exercises the AI gate
                enhancement = PageEnhanceSettings(mode = EnhancementMode.ENHANCED)
            )
            bmp.recycle()
        }
        Log.i(TAG, "LATCHTEST phase=OFF_END")
        // NOTE: deliberately do NOT touch ServiceLocator.boundaryModel here — doing so would force
        // the lazy load and defeat the "zero sessions before the flip" proof.

        // ---- Runtime flip (what the in-app AI toggle does) — NO restart ------------------------
        Log.i(TAG, "LATCHTEST phase=FLIP neuralProcessingEnabled=true (no app restart)")
        ServiceLocator.neuralProcessingEnabled = true

        // ---- Phase 2: AI ON — the very next capture must create the DexiNed session -------------
        val sessionOn = "latch-on-${System.currentTimeMillis()}"
        Log.i(TAG, "LATCHTEST phase=ON_BEGIN (expect: boundary session created)")
        try {
            runBlocking {
                val bmp = NeuralRepro.loadBitmap(context)
                assertNotNull("assets/${NeuralRepro.TEST_ASSET} must decode", bmp)
                pipeline.process(
                    sessionId = sessionOn,
                    source = bmp!!,
                    quad = null,
                    enhancement = PageEnhanceSettings(mode = EnhancementMode.ENHANCED)
                )
                bmp.recycle()
            }
        } finally {
            // Clean up scratch sessions on the device regardless of outcome.
            pipeline.deleteSession(sessionOff)
            pipeline.deleteSession(sessionOn)
        }
        Log.i(TAG, "LATCHTEST phase=ON_END")

        // ---- Discriminator: the DexiNed boundary session MUST exist after the flip --------------
        val boundary = ServiceLocator.boundaryModel
        val dewarp = ServiceLocator.dewarpModel
        Log.i(
            TAG,
            "LATCHTEST_RESULT boundaryModelAfterFlip=${boundary != null} " +
                "dewarpModelAfterFlip=${dewarp != null}"
        )
        if (boundary == null) {
            throw AssertionError(
                "LATCHTEST_FAIL: the DexiNed boundary model was NOT created after a runtime AI-ON " +
                    "flip without a restart — the detector latch did not re-resolve."
            )
        }
        Log.i(TAG, "LATCHTEST_PASS boundary session created immediately after runtime AI-ON flip")
    }
}
