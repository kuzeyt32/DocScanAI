package com.docscanai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscanai.core.di.ServiceLocator
import com.docscanai.data.EnhancementMode
import com.docscanai.data.PageEnhanceSettings
import java.io.File
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ============================================================================================
 * SUBTASK B (H2) — opt-in **XNNPACK** path + CPU-only fallback (SUBTASK 9c).
 * ============================================================================================
 *
 * Runs the **same** AI-ON flow as [`NeuralPathReproTest`] but with `ServiceLocator.useXnnpack =
 * true`, so [`com.docscanai.ml.OrtSessionFactory`] first attempts an **XNNPACK** session and — if
 * XNNPACK cannot build the graph — transparently recreates it with the **plain CPU EP**.
 *
 * ## Why a separate class *and* a separate process
 * XNNPACK is a native graph backend that can hard-abort the process (`SIGSEGV` / `SIGABRT`) — a
 * fault Kotlin cannot catch. The harness therefore runs this class in its **own** `am instrument`
 * process **after** the CPU-EP class has completed and been recorded, so an XNNPACK-induced native
 * kill can never hide the CPU-EP result.
 *
 * ## Which EP was actually used?
 * The test cannot read logcat (no `READ_LOGS`), so it proves *a* session was created (the lazily
 * loaded [`com.docscanai.ml.OnnxModel`] singletons are non-null) and the **harness** records the
 * concrete provider from the SUBTASK 9c log line: `OrtSessionFactory: session EP=XNNPACK …` or
 * `… session EP=CPU …` (and the `XNNPACK session failed …; recreating with CPU EP only` warning).
 */
@RunWith(AndroidJUnit4::class)
class NeuralPathXnnpackTest {

    companion object {
        /**
         * Force AI ON with the **opt-in XNNPACK** provider before any pipeline/model access in
         * this process. `NeuralRepro.setUp` freezes the ORT factory with the XNNPACK choice.
         */
        @JvmStatic
        @BeforeClass
        fun setUp() {
            NeuralRepro.setUp(enableXnnpack = true, tag = "NeuralPathXnnpackTest/XNNPACK")
        }
    }

    /**
     * Exercises the XNNPACK opt-in (with CPU-EP fallback) across all enhancement modes. A native
     * kill here is captured by the harness as a FAIL with the native signal / crash report.
     */
    @Test
    fun neuralPath_xnnpackOptIn_survivesOrFallsBackToCpu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Fail loudly (INVALID) if the neural models are absent — never silently pass.
        NeuralRepro.requireModels()

        val pipeline = ServiceLocator.pipeline
        val sessionId = "neural-xnn-${System.currentTimeMillis()}"
        val modesRun = mutableListOf<String>()
        val modeTimings = LinkedHashMap<String, Long>()
        try {
            EnhancementMode.entries.forEach { mode ->
                val source = NeuralRepro.loadBitmap(context)
                assertNotNull("assets/${NeuralRepro.TEST_ASSET} must decode for mode=$mode", source)

                val stages = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
                val started = System.currentTimeMillis()
                val page = runBlocking {
                    pipeline.process(
                        sessionId = sessionId,
                        source = source!!,
                        quad = null,
                        enhancement = PageEnhanceSettings(mode = mode),
                        // NOTE: `process` now takes `debugOverlaySink` as its LAST parameter, so a
                        // trailing lambda would bind to that instead of `onStage`; name it explicitly.
                        onStage = { stage ->
                            stages.add(stage.name to (System.currentTimeMillis() - started))
                        }
                    )
                }
                val totalMs = System.currentTimeMillis() - started
                modeTimings[mode.id] = totalMs

                assertNotNull("process() must return a page for mode=$mode", page)
                val imageExists = File(page.imageFile).exists()
                val rawExists = page.rawFile?.let { File(it).exists() } ?: false
                assertTrue(
                    "page must reference an on-disk image (mode=$mode, image=$imageExists, raw=$rawExists)",
                    imageExists || rawExists
                )

                modesRun.add(mode.id)
                Log.i(
                    NeuralRepro.TAG,
                    "${NeuralRepro.SENTINEL_MODE_OK} class=NeuralPathXnnpackTest mode=${mode.id} " +
                        "totalMs=$totalMs " +
                        "stages=${stages.joinToString(",") { "${it.first}@${it.second}ms" }} " +
                        "imageExists=$imageExists rawExists=$rawExists"
                )
                source?.recycle()
            }

            // Prove a session was created (via XNNPACK, or the CPU-EP fallback). The concrete EP is
            // recorded by the harness from logcat. If NEITHER model loaded, the run is INVALID.
            val boundary = ServiceLocator.boundaryModel
            val dewarp = ServiceLocator.dewarpModel
            val anySession = boundary != null || dewarp != null
            Log.i(
                NeuralRepro.TAG,
                "${NeuralRepro.SENTINEL_ENGAGED} class=NeuralPathXnnpackTest xnnpackRequested=true " +
                    "boundaryModel=${boundary != null} dewarpModel=${dewarp != null}"
            )
            if (!anySession) {
                val msg = "${NeuralRepro.SENTINEL_INVALID_NO_SESSION} :: no ONNX session was created " +
                    "on the XNNPACK path (boundaryModel=${boundary != null}, " +
                    "dewarpModel=${dewarp != null}) even though the model files were present. " +
                    "This run is INVALID, not a pass."
                Log.e(NeuralRepro.TAG, msg)
                throw AssertionError(msg)
            }

            Log.i(
                NeuralRepro.TAG,
                "${NeuralRepro.SENTINEL_SUMMARY} class=NeuralPathXnnpackTest cpuEp=false xnnpackRequested=true " +
                    "modes=${modesRun.joinToString("+")} timings=${modeTimings.entries.joinToString(",") { "${it.key}:${it.value}ms" }}"
            )
        } finally {
            pipeline.deleteSession(sessionId)
        }
    }
}
