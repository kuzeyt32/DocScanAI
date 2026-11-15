package com.docscanai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscanai.core.di.ServiceLocator
import com.docscanai.core.util.Diag
import com.docscanai.data.EnhancementMode
import com.docscanai.data.PageEnhanceSettings
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Repeatable, camera-free reproduction of the post-capture processing path (SUBTASK 9a).
 *
 * Loads the bundled asset `assets/testdocs/test_page.jpg` and runs the app's **real** pipeline
 * ([ServiceLocator.pipeline] → detector → warp/dewarp → enhance → store) for every
 * [EnhancementMode], logging per-stage timings and any throwable under the `DocScan` tag.
 *
 * The test does NOT need the camera or a downloaded model: with no models present the classical
 * OpenCV path must pass. If a model file happens to be present on the device, the neural path is
 * exercised automatically (and logged) — helping to isolate the AI-only crash the user reported.
 *
 * Run on an emulator/device with:
 *   gradlew.bat :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class PipelineInstrumentedTest {

    companion object {
        private const val TAG = "DocScan"
        private const val TEST_ASSET = "testdocs/test_page.jpg"

        private lateinit var context: Context

        @JvmStatic
        @BeforeClass
        fun setUp() {
            context = InstrumentationRegistry.getInstrumentation().targetContext
            // Initialise the app's manual DI + native libs exactly like DocScanApplication does.
            ServiceLocator.init(context)
            ServiceLocator.ensureOpenCv()
            // SUBTASK F determinism fix: DocScanApplication.onCreate() mirrors the *persisted*
            // neuralProcessingEnabled into ServiceLocator asynchronously. The AI-OFF test below only
            // flips the in-process flag, so a previously-persisted `true` (e.g. left behind by the
            // AI-ON regression lane) can race and flip the flag back on mid-test -> a spurious ONNX
            // session. Persist `false` up-front and wait for it to be observed before any pipeline
            // use, exactly like NeuralRepro.setUp() does, so `neuralProcessingEnabled=false` is
            // stable for the whole test.
            runBlocking {
                runCatching { ServiceLocator.settingsRepository.setNeuralProcessingEnabled(false) }
                runCatching {
                    withTimeoutOrNull(3_000) {
                        ServiceLocator.settingsRepository.neuralProcessingEnabled.first { !it }
                    }
                }
            }
            // Let the Application's mirror coroutine observe the persisted `false` and converge.
            Thread.sleep(500)
            ServiceLocator.neuralProcessingEnabled = false
            Log.d(TAG, "PipelineInstrumentedTest setUp | ${Diag.memString()}")
            Log.d(TAG, "modelPresence: ${ServiceLocator.modelPresenceSummary()}")
        }
    }

    @Test
    fun bundledTestImage_decodes() {
        val bitmap = loadTestBitmap()
        assertNotNull("assets/$TEST_ASSET must decode", bitmap)
        assertTrue("decoded bitmap must be non-empty", bitmap!!.width > 0 && bitmap.height > 0)
        Log.d(TAG, "asset decoded ${Diag.describe(bitmap)}")
        bitmap.recycle()
    }

    @Test
    fun pipeline_allEnhancementModes_returnsUsablePage() {
        val pipeline = ServiceLocator.pipeline
        val sessionId = "itest-${System.currentTimeMillis()}"

        EnhancementMode.entries.forEach { mode ->
            val source = loadTestBitmap()
            assertNotNull("test asset must decode for mode=$mode", source)

            Log.d(TAG, "=== pipeline run mode=${mode.id} (neuralEnabled=${ServiceLocator.neuralProcessingEnabled}) ===")
            val started = System.currentTimeMillis()
            val page = runBlocking {
                pipeline.process(
                    sessionId = sessionId,
                    source = source!!,
                    quad = null, // no camera seed -> detector runs on the still
                    enhancement = PageEnhanceSettings(mode = mode)
                ) { stage ->
                    Log.d(TAG, "stage=$stage elapsed=${System.currentTimeMillis() - started}ms")
                }
            }

            // The hardened pipeline ALWAYS returns a usable page (worst case the raw capture).
            assertNotNull("process() must return a page for mode=$mode", page)
            val imageExists = File(page.imageFile).exists()
            val rawExists = page.rawFile?.let { File(it).exists() } ?: false
            assertTrue(
                "page must reference an on-disk image (mode=$mode, image=$imageExists, raw=$rawExists)",
                imageExists || rawExists
            )
            Log.d(
                TAG,
                "page ok mode=${mode.id} id=${page.id} image=${page.imageFile} " +
                    "raw=${page.rawFile} thumb=${page.thumbnailFile} " +
                    "totalMs=${System.currentTimeMillis() - started} ${Diag.memString()}"
            )

            source?.recycle()
        }

        pipeline.deleteSession(sessionId)
    }

    @Test
    fun pipeline_neuralDisabled_usesClassicalPath() {
        val previous = ServiceLocator.neuralProcessingEnabled
        // Persist too (see setUp) so the app's settings mirror cannot re-enable the neural path.
        runBlocking { runCatching { ServiceLocator.settingsRepository.setNeuralProcessingEnabled(false) } }
        ServiceLocator.neuralProcessingEnabled = false
        try {
            val pipeline = ServiceLocator.pipeline
            val sessionId = "itest-nn-off-${System.currentTimeMillis()}"
            val source = loadTestBitmap()
            assertNotNull(source)

            val page = runBlocking {
                pipeline.process(
                    sessionId = sessionId,
                    source = source!!,
                    quad = null,
                    enhancement = PageEnhanceSettings(mode = EnhancementMode.ENHANCED)
                )
            }
            assertNotNull("classical path must still return a page", page)
            Log.d(TAG, "classical-only page=${page.id} image=${page.imageFile}")
            source?.recycle()
            pipeline.deleteSession(sessionId)
        } finally {
            ServiceLocator.neuralProcessingEnabled = previous
            runBlocking {
                runCatching { ServiceLocator.settingsRepository.setNeuralProcessingEnabled(previous) }
            }
        }
    }

    /** Decodes a fresh copy of the bundled test asset (caller owns/recycles it). */
    private fun loadTestBitmap(): Bitmap? = try {
        context.assets.open(TEST_ASSET).use { input ->
            BitmapFactory.decodeStream(input)
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to load assets/$TEST_ASSET", t)
        null
    }
}
