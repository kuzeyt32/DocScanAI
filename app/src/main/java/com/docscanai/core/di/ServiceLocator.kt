package com.docscanai.core.di

import android.content.Context
import android.util.Log
import com.docscanai.capture.OpenCvQuadPageDetector
import com.docscanai.capture.PageDetector
import com.docscanai.capture.ScanPipeline
import com.docscanai.data.ScanSessionRepository
import com.docscanai.data.SessionStore
import com.docscanai.data.SettingsRepository
import com.docscanai.core.util.Diag
import com.docscanai.imaging.ClassicalEnhancer
import com.docscanai.imaging.DewarpProcessor
import com.docscanai.imaging.Enhancer
import com.docscanai.imaging.PerspectiveDewarpProcessor
import com.docscanai.ml.MlsdModel
import com.docscanai.ml.MlsdTrigger
import com.docscanai.ml.OnnxModel
import com.docscanai.ml.OrtSessionFactory
import com.docscanai.models.ModelDownloadManager
import com.docscanai.models.ModelRegistry
import com.docscanai.pdf.AndroidPdfExporter
import com.docscanai.pdf.PdfExporter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader

/**
 * Minimal manual DI container (ARCHITECTURE.md §1). No annotation processor (KSP/Hilt),
 * which keeps the build toolchain simple. Call [init] from `Application.onCreate()`.
 *
 * Also owns two process-wide native initialisations:
 *  - [ensureOpenCv] loads the OpenCV native library once (used by the boundary detector);
 *  - [ortSessionFactory] owns the single ONNX Runtime environment.
 *
 * Both are **best-effort**: if a native library is unavailable the app still runs using the
 * classical fallbacks and never crashes.
 */
object ServiceLocator {

    private const val TAG = "ServiceLocator"

    @Volatile
    private var applicationContext: Context? = null

    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    /**
     * Runtime switch (SUBTASK 9a) mirroring `SettingsRepository.neuralProcessingEnabled`.
     * When `false`, the neural dewarp (UVDoc `m2_dewarp`) and neural shadow (`m3_shadow`) paths
     * are bypassed and the classical OpenCV paths are used — lets us isolate whether a neural/ONNX
     * stage is responsible for a crash without a rebuild. Default `true` (neural enabled when a
     * model is present).
     */
    @Volatile
    var neuralProcessingEnabled: Boolean = true

    /**
     * Runtime switch (SUBTASK 9c) mirroring `SettingsRepository.useXnnpack`. XNNPACK is a native
     * graph backend that can hard-abort the process (SIGSEGV / SIGABRT) on some models — a fault
     * Kotlin cannot catch — so it is **OFF by default** and the plain CPU execution provider is
     * used. When `true`, [ortSessionFactory] attempts an XNNPACK session with a CPU-EP fallback.
     * Must be set before the first session is created (mirrored from settings at app start).
     */
    @Volatile
    var useXnnpack: Boolean = false

    private fun requireContext(): Context =
        applicationContext ?: error("ServiceLocator.init(Application) must be called before use")

    private val openCvReady = AtomicBoolean(false)

    /**
     * Loads the OpenCV native library exactly once. Returns `true` when OpenCV is usable.
     * Safe to call repeatedly and from any thread; never throws.
     */
    fun ensureOpenCv(): Boolean {
        if (openCvReady.get()) return true
        synchronized(openCvReady) {
            if (openCvReady.get()) return true
            val ok = try {
                OpenCVLoader.initLocal()
            } catch (t: Throwable) {
                Log.e(TAG, "OpenCV native load failed; classical detection disabled", t)
                false
            }
            openCvReady.set(ok)
            return ok
        }
    }

    /** Single-thread executor for live frame analysis (ARCHITECTURE.md §6). */
    private val frameExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "docscan-frame") }
    }

    /** Dispatcher used by [com.docscanai.capture.FrameAnalyzer] so detection never touches main. */
    val frameDispatcher: CoroutineDispatcher by lazy { frameExecutor.asCoroutineDispatcher() }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(requireContext()) }

    val sessionStore: SessionStore by lazy { SessionStore(requireContext()) }

    val scanSessionRepository: ScanSessionRepository by lazy { ScanSessionRepository(sessionStore) }

    /**
     * Renders the active session to a PDF and publishes it to public storage via MediaStore
     * (`Documents/DocScanAI` → `Downloads/DocScanAI` → `DCIM/DocScanAI` → app-specific dir).
     * See [AndroidPdfExporter] (TECHNICAL_SPEC.md §8–§9).
     */
    val pdfExporter: PdfExporter by lazy { AndroidPdfExporter(requireContext()) }

    /**
     * Single ONNX Runtime environment/session factory shared by every model wrapper.
     * SUBTASK 9c: defaults to the plain **CPU execution provider**; XNNPACK is only attempted when
     * [useXnnpack] (mirrored from `SettingsRepository.useXnnpack`) is enabled.
     */
    val ortSessionFactory: OrtSessionFactory by lazy {
        OrtSessionFactory(enableXnnpack = useXnnpack)
    }

    /**
     * SUBTASK H27: dedicated session factory for the **grid dewarp** model, requesting ONNX
     * Runtime's deterministic-compute mode (`setDeterministicCompute(true)`).
     *
     * Measured cost on the debug x86_64 emulator (WarpDeterminismTest, N=5, page 1343x1760):
     * grid graph 87 ms -> 94 ms avg, full delivered warp 558 ms -> 563 ms (+1.2%, inside the
     * run-to-run spread). The guarantee that matters is the *page bytes*: with and without the
     * flag the delivered page and JPEG hashes are already identical, so this is belt-and-braces
     * against a future EP/thread-reduction change silently breaking the guarantee that
     * [com.docscanai.WarpDeterminismTest] now asserts.
     */
    val dewarpGridSessionFactory: OrtSessionFactory by lazy {
        OrtSessionFactory(
            enableXnnpack = false,
            deterministicCompute = true
        )
    }

    val modelRegistry: ModelRegistry by lazy { ModelRegistry() }

    val modelDownloadManager: ModelDownloadManager by lazy {
        ModelDownloadManager(requireContext(), modelRegistry)
    }

    /**
     * The optional DexiNed boundary model (`m1_boundary`, MODELS.md §2.1), or `null` when the
     * artifact has not been downloaded / cannot be loaded. Created **lazily** and cached, so the
     * app runs fine with no models present (the detector then uses its classical path).
     */
    private val boundaryModelLazy: Lazy<OnnxModel?> = lazy { createBoundaryModel() }

    val boundaryModel: OnnxModel? by boundaryModelLazy

    private fun createBoundaryModel(): OnnxModel? {
        ensureOpenCv()
        val spec = modelRegistry.byId(ModelRegistry.ID_M1_BOUNDARY) ?: return null
        if (!modelDownloadManager.isModelAvailable(spec.id)) return null
        val file = modelDownloadManager.modelFile(spec)
        return try {
            OnnxModel.load(ortSessionFactory, file)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load boundary model", t)
            null
        }
    }

    /**
     * SUBTASK H4: the DocAligner LCNet100 page-corner detector (`m1c_detect`) — the **primary AI
     * detection path** (MODELS.md §2.1). `null` when the artifact is absent or cannot be loaded,
     * in which case the detector uses its classical fallback. Created lazily and cached, so app
     * start is unaffected.
     *
     * Crash safety: pinned to the **plain CPU execution provider** (`allowXnnpack = false`) like
     * the dewarp model — the graph is fixed-shape `[1,3,256,256]` but we do not gamble on XNNPACK.
     */
    private val detectionModelLazy: Lazy<OnnxModel?> = lazy { createDetectionModel() }

    val detectionModel: OnnxModel? by detectionModelLazy

    private fun createDetectionModel(): OnnxModel? {
        ensureOpenCv()
        val spec = modelRegistry.byId(ModelRegistry.ID_M1C_DETECT) ?: return null
        if (!modelDownloadManager.isModelAvailable(spec.id)) return null
        val file = modelDownloadManager.modelFile(spec)
        return try {
            OnnxModel.load(ortSessionFactory, file, allowXnnpack = false)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load detection model", t)
            null
        }
    }

    /**
     * SUBTASK H30: the optional M-LSD line-evidence model, or `null` when its artifact has not
     * been provisioned. Loaded from the **same directory contract** the downloader uses
     * (`filesDir/models/<id>/model.onnx`), so a future direct download can drop the file there
     * without touching the detector.
     *
     * Pinned to the **deterministic CPU session factory** (H27 pattern) with XNNPACK disabled, as
     * for every other pinned graph: one provider, one thread policy, reproducible output.
     *
     * Crash safety: session creation is wrapped; a bad/missing file yields `null` and the
     * detector keeps today's behaviour exactly.
     */
    private val mlsdModelLazy: Lazy<MlsdModel?> = lazy { createMlsdModel() }
    val mlsdModel: MlsdModel? by mlsdModelLazy

    private fun createMlsdModel(): MlsdModel? {
        ensureOpenCv()
        val file = java.io.File(
            java.io.File(requireContext().filesDir, "models/${ModelRegistry.ID_M2_MLSD}"),
            "model.onnx"
        )
        if (!file.isFile || file.length() == 0L) return null
        return try {
            val onnx = OnnxModel.load(dewarpGridSessionFactory, file, allowXnnpack = false)
                ?: return null
            val edge = MlsdModel.clampEdge(MlsdModel.edgeFor(onnx))
            Diag.log(TAG, "M-LSD model loaded edge=$edge bytes=${file.length()}")
            MlsdModel(onnx, edge)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load M-LSD model", t)
            null
        }
    }

    // --- Optional dewarp / enhancement models (guarded; app works without them) -------------
    /**
     * The optional image→image dewarp model — `m2_dewarp` (UVDoc) by default, resolved through the
     * registry's dewarp fallback order — or `null` when no artifact is present / it cannot be
     * loaded. Loaded lazily on first dewarp so app start is unaffected.
     */
    private val dewarpModelLazy: Lazy<OnnxModel?> = lazy { createDewarpModel() }
    val dewarpModel: OnnxModel? by dewarpModelLazy

    /**
     * SUBTASK H7: the optional **grid-output** UVDoc model (`m2_dewarp_grid`, 45x31 grid +
     * external-data sidecar). When present and valid it is the PRIMARY dewarp path: the grid is
     * composed with the homography and applied as ONE remap from the original still. `null`
     * (missing/invalid file) transparently enables the image→image fallback at 712 px.
     */
    private val dewarpGridModelLazy: Lazy<OnnxModel?> = lazy { createDewarpGridModel() }
    val dewarpGridModel: OnnxModel? by dewarpGridModelLazy

    private fun createDewarpModel(): OnnxModel? {
        ensureOpenCv()
        val id = modelDownloadManager.resolveDewarpModelId() ?: return null
        val spec = modelRegistry.byId(id) ?: return null
        val file = modelDownloadManager.modelFile(spec)
        return try {
            // SUBTASK D fix: pin the dewarp session to CPU EP. Reproduced (x86_64 emulator,
            // tooling/out/logcat_dump_*): XNNPACK *session creation* for the pinned UVDoc
            // graph (dynamic [N,3,H,W]) aborts natively inside libonnxruntime.so
            // (Fatal signal 6 / SIGABRT, uncatchable), so the CPU-EP fallback in
            // OrtSessionFactory cannot cover it. The fixed-shape DexiNed boundary model is
            // unaffected and may still use XNNPACK when opted in.
            OnnxModel.load(ortSessionFactory, file, allowXnnpack = false)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load dewarp model $id", t)
            null
        }
    }

    private fun createDewarpGridModel(): OnnxModel? {
        ensureOpenCv()
        val spec = modelRegistry.byId(ModelRegistry.ID_M2_DEWARP_GRID) ?: return null
        if (!modelDownloadManager.isModelAvailable(spec.id)) return null
        val file = modelDownloadManager.modelFile(spec)
        return try {
            // CPU EP only (same crash history as the dynamic-shape UVDoc graph; a native abort
            // during XNNPACK session creation is uncatchable). The grid graph is fixed-shape
            // [1,3,720,496], but we do not gamble.
            // SUBTASK H27: deterministic compute so the same (bitmap, quad) always yields the
            // same grid floats, hence the same map, page and JPEG bytes.
            OnnxModel.load(dewarpGridSessionFactory, file, allowXnnpack = false)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load grid dewarp model ${spec.id}", t)
            null
        }
    }

    /** Optional // M3 neural shadow-removal model (`m3_shadow`), or `null` when absent. */
    private val shadowModelLazy: Lazy<OnnxModel?> = lazy { createShadowModel() }
    val shadowModel: OnnxModel? by shadowModelLazy

    private fun createShadowModel(): OnnxModel? {
        ensureOpenCv()
        val spec = modelRegistry.byId(ModelRegistry.ID_M3_SHADOW) ?: return null
        if (!modelDownloadManager.isModelAvailable(spec.id)) return null
        val file = modelDownloadManager.modelFile(spec)
        return try {
            OnnxModel.load(ortSessionFactory, file)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load shadow model", t)
            null
        }
    }

    // --- Processing pipeline (real detection + dewarp + enhancement) -----------------------
    /**
     * Real document boundary detector: classical OpenCV by default, DexiNed ONNX when present.
     *
     * SUBTASK 9c: the DexiNed boundary model is now gated behind [neuralProcessingEnabled] —
     * when AI is OFF the provider returns `null` **without ever touching [boundaryModel]**, so the
     * lazy `OnnxModel.load(...)` (and therefore any ONNX session) is never triggered. With AI OFF
     * the app creates/uses ZERO ONNX sessions (pure classical path).
     */
    val pageDetector: PageDetector by lazy {
        ensureOpenCv()
        OpenCvQuadPageDetector(
            // SUBTASK H4: the DocAligner corner detector is the PRIMARY AI detector; the legacy
            // DexiNed boundary provider is retained but no longer consulted by the detector (see
            // OpenCvQuadPageDetector KDoc + MODELS.md §2.1 for the measured rationale).
            modelProvider = { if (neuralProcessingEnabled) boundaryModel else null },
            cornerModelProvider = { if (neuralProcessingEnabled) detectionModel else null },
            // SUBTASK H30: optional line evidence. `null` (not provisioned, or AI OFF) keeps the
            // 0.3.8 behaviour exactly — no extra session, no extra milliseconds.
            mlsdProvider = { if (neuralProcessingEnabled) mlsdModel else null }
        ).apply {
            // SUBTASK H31: line evidence is now SCHEDULED, not unconditional. The mode is the
            // shipped decision measured in tooling/out/h31/ ; the policy is the trigger that keeps
            // M-LSD off clean frames and caps it to one call per `cadence` eligible frames.
            mlsdMode = PRODUCTION_MLSD_MODE
            mlsdPolicy = PRODUCTION_MLSD_POLICY
        }
    }

    /**
     * SUBTASK H31 — the M-LSD mode production runs: **[OpenCvQuadPageDetector.MlsdMode.REPAIR]**.
     *
     * The candidate use (b) — replacing a validated corner quad with a line-derived square — is
     * measured but NOT shipped. H30's own offline matrix is unambiguous on the 18 fixtures:
     * `merge_320` (repair only) 3.6923 % / IoU 0.8319 beats `fuse_320` (repair + candidate)
     * 3.7019 % / 0.8309, i.e. the candidate makes the headline numbers *worse*. The H31 corpus
     * runs agree: `BOTH` let a candidate grow an already-correct corner quad on `syn_persp_mild_2`
     * (corner error 0.18 % -> 2.30 %), because `CANDIDATE_AREA_HI` = 1.05 permits a 5 % growth
     * past a quad the corner model had already found. `REPAIR` can only ever *shrink* a quad at a
     * measured sliver, which is the failure the subtask is about.
     */
    val PRODUCTION_MLSD_MODE: OpenCvQuadPageDetector.MlsdMode
        get() = OpenCvQuadPageDetector.MlsdMode.REPAIR

    /** The shipped trigger thresholds + cadence (see [MlsdTrigger.Policy]). */
    val PRODUCTION_MLSD_POLICY: MlsdTrigger.Policy
        get() = MlsdTrigger.Policy()

    /**
     * SUBTASK H31 — warm the optional M-LSD session **before** anything can need it.
     *
     * `OnnxModel.load` for the 6.17 MB graph (plus OpenCV init) measured +446 ms p95 when it
     * landed on the first capture. The camera screen calls this on open, on the analysis
     * dispatcher, so the capture path only ever pays for inference.
     *
     * Best effort: never throws, returns the wall time spent (0 when AI is off / no artifact).
     */
    suspend fun warmMlsdModel(): Long = withContext(frameDispatcher) {
        if (!neuralProcessingEnabled) return@withContext 0L
        val detector = pageDetector as? OpenCvQuadPageDetector ?: return@withContext 0L
        runCatching { detector.warmMlsd() }.getOrDefault(0L)
    }

    /** Real perspective + (optional UVDoc grid/image) + classical-curvature dewarp processor. */
    val dewarpProcessor: DewarpProcessor by lazy {
        ensureOpenCv()
        PerspectiveDewarpProcessor(
            // Image→image fallback (UVDoc at 712 px) — gated behind the runtime AI switch.
            dewarpModelProvider = { if (neuralProcessingEnabled) dewarpModel else null },
            // SUBTASK H7 primary path: 45x31 grid composed with the homography, one remap from
            // the original still. Also gated behind the same runtime switch.
            dewarpGridModelProvider = { if (neuralProcessingEnabled) dewarpGridModel else null }
        )
    }

    /** Real classical enhancer (+ optional // M3 neural shadow removal when present). */
    val enhancer: Enhancer by lazy {
        ensureOpenCv()
        ClassicalEnhancer(
            // Gate the neural shadow model behind the runtime switch (classical is always used).
            shadowModelProvider = { if (neuralProcessingEnabled) shadowModel else null }
        )
    }

    /** Orchestrates detect -> dewarp -> enhance -> persist on a background queue. */
    val pipeline: ScanPipeline by lazy {
        ScanPipeline(requireContext(), pageDetector, dewarpProcessor, enhancer)
    }

    /**
     * Diagnostic snapshot of which optional neural model *files* are present on disk, used by the
     * pipeline's stage logging (SUBTASK 9a). Deliberately checks availability WITHOUT forcing a
     * lazy model load (loading a model can itself be the crash we are diagnosing). Never throws.
     */
    fun modelPresenceSummary(): String = runCatching {
        "detectionModelFile=${modelDownloadManager.isModelAvailable(ModelRegistry.ID_M1C_DETECT)} " +
            "boundaryModelFile=${modelDownloadManager.isModelAvailable(ModelRegistry.ID_M1_BOUNDARY)} " +
            "dewarpModelFile=${modelDownloadManager.isModelAvailable(ModelRegistry.ID_M2_DEWARP)} " +
            "dewarpGridModelFile=${modelDownloadManager.isModelAvailable(ModelRegistry.ID_M2_DEWARP_GRID)} " +
            "shadowModelFile=${modelDownloadManager.isModelAvailable(ModelRegistry.ID_M3_SHADOW)} " +
            "mlsdModelFile=${isMlsdModelPresent()} " +
            "neuralEnabled=$neuralProcessingEnabled useXnnpack=$useXnnpack"
    }.getOrElse { "modelPresence=<unavailable: ${it.message}>" }

    /**
     * SUBTASK H30: checks the M-LSD artifact **without forcing a lazy load** (same rule as the
     * other models: loading a model can itself be the crash under diagnosis).
     */
    fun isMlsdModelPresent(): Boolean = runCatching {
        java.io.File(
            java.io.File(requireContext().filesDir, "models/${ModelRegistry.ID_M2_MLSD}"),
            "model.onnx"
        ).let { it.isFile && it.length() > 0L }
    }.getOrDefault(false)

    /** Convenience for logging (also emitted once at pipeline start). */
    fun logModelPresence() {
        Diag.log("Models", modelPresenceSummary())
    }
}
