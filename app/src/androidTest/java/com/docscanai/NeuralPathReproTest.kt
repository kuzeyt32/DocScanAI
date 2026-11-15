package com.docscanai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscanai.core.di.ServiceLocator
import com.docscanai.data.EnhancementMode
import com.docscanai.data.PageEnhanceSettings
import com.docscanai.models.ModelRegistry
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
 * SUBTASK B (H2) — deterministic **AI-ON** regression gate.
 * ============================================================================================
 *
 * This is the *camera-free* reproduction of the reported "AI ON → capture → ~2 s → crash"
 * failure, kept as a permanent regression gate for **"AI ON does not crash"**. It drives the
 * app's **real** [com.docscanai.capture.ScanPipeline] (detector → dewarp → enhance → store)
 * directly — no UI driving, no camera — exactly like [`PipelineInstrumentedTest`] but pinned to
 * the *neural* path.
 *
 * ## Why this class exists (and how it differs from PipelineInstrumentedTest)
 * [`PipelineInstrumentedTest.pipeline_allEnhancementModes_returnsUsablePage`] runs with whatever
 * models happen to be on the device and treats "no crash" as success. That is **not** enough to
 * prove the AI path works: if the neural models are absent the pipeline silently uses the
 * classical path, so a green run would exercise **no ONNX session at all**.
 *
 * This test therefore makes three guarantees:
 *  1. **AI ON is forced.** [`NeuralRepro.setUp`] sets `ServiceLocator.neuralProcessingEnabled =
 *     true` and `ServiceLocator.useXnnpack = false` (plain CPU EP, the SUBTASK 9c default) *before
 *     any pipeline use*, and freezes `ServiceLocator.ortSessionFactory` so its execution-provider
 *     choice is deterministic (the factory reads `useXnnpack` exactly once, at lazy init).
 *  2. **Model presence is enforced.** The ONNX artifacts are **NOT bundled in `assets/`**
 *     (only `assets/testdocs/test_page.jpg` is). They are **downloaded at runtime** into
 *     `filesDir/models/<id>/model.onnx` and reported by
 *     [`ServiceLocator.modelPresenceSummary()`]. If `m1_boundary` / `m2_dewarp` are missing this
 *     test **fails loudly** with the sentinel `NEURAL_RUN_INVALID models-absent` — it must never
 *     "pass" without touching the neural path.
 *  3. **AI ON actually engaged.** After the run it asserts the two lazily-loaded [`OnnxModel`]
 *     singletons are non-null (`ServiceLocator.boundaryModel`, `ServiceLocator.dewarpModel`).
 *     Those are only non-null when `OnnxModel.load` → `OrtSessionFactory.createSession` really
 *     produced a native ORT session. Zero sessions ⇒ sentinel `NEURAL_RUN_INVALID
 *     session-not-created` (the run is INVALID, not a pass).
 *
 * The harness ([`tooling/e2e.cmd`](../../../../../../tooling/e2e.cmd)) additionally greps the
 * `DocScan` logcat for the SUBTASK 9c EP log line (`OrtSessionFactory: session EP=…`) so the
 * *actual* execution provider used for each session is recorded in the report.
 *
 * ## What is exercised
 * For **every** [`EnhancementMode`] (`ORIGINAL`, `ENHANCED`, `BW`) it decodes the bundled
 * `assets/testdocs/test_page.jpg` and calls
 * `ScanPipeline.process(sessionId, bitmap, quad = null, enhancement = …)`. Because `quad = null`
 * the detector runs on the still (→ the optional **DexiNed** boundary model / M1), and the dewarp
 * stage runs the optional **UVDoc** neural dewarp (→ M2). Acceptance: the process survives every
 * mode and each page references an on-disk image; the UVDoc stage may produce a page **or** cleanly
 * fall back to classical (both are OK) — only a crash / native signal is a failure.
 *
 * ## Ordering note
 * A second, *separate* class [`NeuralPathXnnpackTest`] exercises the opt-in XNNPACK path. XNNPACK
 * can hard-abort the process (SIGSEGV/SIGABRT) — an uncatchable native fault — so the harness runs
 * **this CPU-EP class first, in its own `am instrument` process**, so its result is always recorded
 * before any XNNPACK-induced native kill can mask it.
 */
@RunWith(AndroidJUnit4::class)
class NeuralPathReproTest {

    companion object {
        /**
         * Force AI ON with the **CPU execution provider** (XNNPACK off — the safe SUBTASK 9c
         * default) before any pipeline/model access in this process.
         */
        @JvmStatic
        @BeforeClass
        fun setUp() {
            NeuralRepro.setUp(enableXnnpack = false, tag = "NeuralPathReproTest/CPU-EP")
        }
    }

    /**
     * AI-ON / CPU-EP regression gate: runs the real pipeline (detector + neural dewarp) across all
     * enhancement modes and asserts it survives **and** that real ONNX sessions were created.
     */
    @Test
    fun neuralPath_cpuEp_survivesAllEnhancementModes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // (2) Fail loudly (INVALID) if the neural models are absent — never silently pass.
        val presence = NeuralRepro.requireModels()

        val pipeline = ServiceLocator.pipeline
        val sessionId = "neural-cpu-${System.currentTimeMillis()}"
        val modesRun = mutableListOf<String>()
        val modeTimings = LinkedHashMap<String, Long>()
        try {
            EnhancementMode.entries.forEach { mode ->
                val source = NeuralRepro.loadBitmap(context)
                assertNotNull("assets/${NeuralRepro.TEST_ASSET} must decode for mode=$mode", source)

                // `onStage` runs on the pipeline thread; a synchronized list makes the captured
                // per-stage timings safe to read after `process(...)` returns.
                val stages = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
                val started = System.currentTimeMillis()
                val page = runBlocking {
                    pipeline.process(
                        sessionId = sessionId,
                        source = source!!,
                        quad = null, // no camera seed → detector runs on the still (M1 neural path)
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
                    "${NeuralRepro.SENTINEL_MODE_OK} class=NeuralPathReproTest mode=${mode.id} " +
                        "totalMs=$totalMs " +
                        "stages=${stages.joinToString(",") { "${it.first}@${it.second}ms" }} " +
                        "imageExists=$imageExists rawExists=$rawExists"
                )
                source?.recycle()
            }

            // (3) Prove AI ON really engaged: the lazily-loaded ONNX sessions must exist.
            val boundary = ServiceLocator.boundaryModel
            val dewarp = ServiceLocator.dewarpModel
            val engaged = boundary != null && dewarp != null
            Log.i(
                NeuralRepro.TAG,
                "${NeuralRepro.SENTINEL_ENGAGED} class=NeuralPathReproTest " +
                    "boundaryModel=${boundary != null} dewarpModel=${dewarp != null} presence=$presence"
            )
            if (!engaged) {
                val msg = "${NeuralRepro.SENTINEL_INVALID_NO_SESSION} :: neural path did NOT engage " +
                    "(boundaryModel=${boundary != null}, dewarpModel=${dewarp != null}) even though " +
                    "the model files were present. A 'no crash' result is meaningless without real " +
                    "ONNX sessions — this run is INVALID, not a pass. presence=$presence"
                Log.e(NeuralRepro.TAG, msg)
                throw AssertionError(msg)
            }

            Log.i(
                NeuralRepro.TAG,
                "${NeuralRepro.SENTINEL_SUMMARY} class=NeuralPathReproTest cpuEp=true xnnpackRequested=false " +
                    "modes=${modesRun.joinToString("+")} timings=${modeTimings.entries.joinToString(",") { "${it.key}:${it.value}ms" }} " +
                    "sessionsCreated=2"
            )
        } finally {
            // Always clean up the session dirs we created (never leave scratch on the device).
            pipeline.deleteSession(sessionId)
        }
    }
}

/**
 * Shared, heavily-documented support for the AI-ON regression classes.
 *
 * Kept `internal` (module-private) — it is **not** a JUnit test class, only a helper.
 */
internal object NeuralRepro {

    /** The single stable diagnostic tag the whole pipeline logs under (see `core/util/Diag.kt`). */
    const val TAG = "DocScan"

    /** The only bundled, non-build image in the repo; already used by `PipelineInstrumentedTest`. */
    const val TEST_ASSET = "testdocs/test_page.jpg"

    // ---- Harness sentinels. `tooling/e2e.cmd` greps the instrumentation output for these to
    // ---- classify the run as PASS / FAIL / INVALID. Do not change without updating the harness.
    /** Emitted once a class completes successfully with real ONNX sessions. */
    const val SENTINEL_SUMMARY = "NEURAL_RUN_SUMMARY"
    /** Emitted per mode with wall-clock + per-stage timings. */
    const val SENTINEL_MODE_OK = "NEURAL_RUN_MODE_OK"
    /** Emitted when real ONNX sessions were confirmed created. */
    const val SENTINEL_ENGAGED = "NEURAL_RUN_ENGAGED"
    /** INVALID: the ONNX model files are absent → the neural path cannot be exercised. */
    const val SENTINEL_INVALID_MODELS = "NEURAL_RUN_INVALID models-absent"
    /** INVALID: models were present but no ONNX session was created → the neural path did not run. */
    const val SENTINEL_INVALID_NO_SESSION = "NEURAL_RUN_INVALID session-not-created"

    /**
     * Deterministic AI-ON bootstrap. Safe to call from a `@BeforeClass`.
     *
     * Ordering is deliberate:
     *  1. persist the same switch values to `SettingsRepository` so the `Application`'s async
     *     settings-mirror coroutines (`DocScanApplication.onCreate`) converge to the **same**
     *     values we set here — this removes the otherwise-racy "collector overwrites our flag"
     *     hazard;
     *  2. set the in-process runtime switches directly (`neuralProcessingEnabled = true`,
     *     `useXnnpack = <requested>`), as the task requires;
     *  3. force the single `ServiceLocator.ortSessionFactory` **now**, so its lazy initialiser
     *     captures our execution-provider choice before anything else can create it.
     *
     * @param enableXnnpack `false` for the CPU-EP class, `true` for the opt-in XNNPACK class.
     * @param tag free-form label logged for traceability.
     */
    fun setUp(enableXnnpack: Boolean, tag: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Bootstrap the manual DI + native libs exactly like DocScanApplication does.
        ServiceLocator.init(context)
        ServiceLocator.ensureOpenCv()

        // (1) Persist the requested switches (best-effort) so the app's mirrors agree with (2).
        runBlocking {
            runCatching {
                ServiceLocator.settingsRepository.setNeuralProcessingEnabled(true)
                ServiceLocator.settingsRepository.setUseXnnpack(enableXnnpack)
            }
        }

        // (2) Force the in-process runtime switches.
        ServiceLocator.neuralProcessingEnabled = true
        ServiceLocator.useXnnpack = enableXnnpack

        // (3) Freeze the ORT session factory with the chosen EP.
        val factory = ServiceLocator.ortSessionFactory
        Log.i(
            TAG,
            "NEURAL_RUN_SETUP tag=$tag neuralProcessingEnabled=${ServiceLocator.neuralProcessingEnabled} " +
                "useXnnpack=${ServiceLocator.useXnnpack} ortEnvironmentPresent=${factory.environment != null}"
        )
        Log.i(TAG, "NEURAL_RUN_SETUP modelPresence: ${ServiceLocator.modelPresenceSummary()}")
    }

    /**
     * Enforces model availability. Returns the presence summary on success; throws an
     * [`AssertionError`] carrying [SENTINEL_INVALID_MODELS] when either Fast-tier model
     * (`m1_boundary`, `m2_dewarp`) is missing, so the harness can report the run as **INVALID**
     * rather than as a pass.
     *
     * Presence is exactly what [`ServiceLocator.modelPresenceSummary()`] reports — the artifacts
     * live at `filesDir/models/<id>/model.onnx` and are downloaded at runtime (never in `assets/`).
     */
    fun requireModels(): String {
        val m1 = ServiceLocator.modelDownloadManager.isModelAvailable(ModelRegistry.ID_M1_BOUNDARY)
        val m2 = ServiceLocator.modelDownloadManager.isModelAvailable(ModelRegistry.ID_M2_DEWARP)
        val summary = ServiceLocator.modelPresenceSummary()
        Log.i(TAG, "NEURAL_RUN_MODEL_CHECK m1_boundary=$m1 m2_dewarp=$m2 :: $summary")
        if (!m1 || !m2) {
            val msg = "$SENTINEL_INVALID_MODELS :: the neural path cannot be exercised because the " +
                "ONNX model files are absent (m1_boundary=$m1, m2_dewarp=$m2). Models are NOT " +
                "bundled in assets/ — they must exist at filesDir/models/<id>/model.onnx. " +
                "The harness must provision them before the AI-ON run, otherwise the result is " +
                "INVALID. presence=$summary"
            Log.e(TAG, msg)
            throw AssertionError(msg)
        }
        return summary
    }

    /** Decodes a fresh copy of the bundled test asset (the caller owns and recycles it). */
    fun loadBitmap(context: Context): Bitmap? = try {
        context.assets.open(TEST_ASSET).use { input -> BitmapFactory.decodeStream(input) }
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to load assets/$TEST_ASSET", t)
        null
    }
}
