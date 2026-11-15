package com.docscanai

import android.app.Application
import com.docscanai.core.di.ServiceLocator
import com.docscanai.core.util.CrashReporter
import com.docscanai.core.util.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point (composition root).
 *
 * Builds the manual DI [ServiceLocator] on startup and performs the best-effort native
 * bootstrap:
 *  - loads OpenCV once (needed by the boundary detector's classical path);
 *  - the ONNX Runtime environment and any downloaded models are created lazily on first use
 *    via [ServiceLocator.ortSessionFactory] / [ServiceLocator.boundaryModel], so the app runs
 *    fine with **no models present** (Fallback tier).
 *
 * SUBTASK 9a additions:
 *  - installs the global [`CrashReporter`] so any uncaught throwable is logged under the
 *    `DocScan` tag **and** persisted to `filesDir/crash/last_crash.txt`;
 *  - mirrors the persisted `neuralProcessingEnabled` setting into
 *    [`ServiceLocator.neuralProcessingEnabled`] so the neural dewarp / shadow paths can be
 *    disabled at runtime for isolation without touching the pipeline wiring.
 */
class DocScanApplication : Application() {

    /** Process-lifetime scope for lightweight background mirrors (never cancelled). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // 1) Install crash diagnostics FIRST so even bootstrap failures are captured.
        CrashReporter.install(this)
        Diag.log("App", "onCreate | ${Diag.memString()}")

        // 2) DI + best-effort native init.
        ServiceLocator.init(this)
        ServiceLocator.ensureOpenCv()

        // 3) Keep the runtime neural-processing switch in sync with persisted settings.
        appScope.launch {
            runCatching {
                ServiceLocator.settingsRepository.neuralProcessingEnabled.collect { enabled ->
                    ServiceLocator.neuralProcessingEnabled = enabled
                    Diag.log("Settings", "neuralProcessingEnabled=$enabled")
                }
            }.onFailure { Diag.warn("Settings", "neuralProcessingEnabled mirror failed", it) }
        }

        // 3b) Keep the opt-in XNNPACK execution-provider switch in sync too (SUBTASK 9c).
        // Default is OFF (plain CPU EP); enabling it makes OrtSessionFactory attempt XNNPACK with a
        // CPU fallback. Mirrored early so the first session already sees the persisted value.
        appScope.launch {
            runCatching {
                ServiceLocator.settingsRepository.useXnnpack.collect { enabled ->
                    ServiceLocator.useXnnpack = enabled
                    Diag.log("Settings", "useXnnpack=$enabled")
                }
            }.onFailure { Diag.warn("Settings", "useXnnpack mirror failed", it) }
        }
    }
}
