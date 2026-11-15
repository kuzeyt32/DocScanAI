package com.docscanai.capture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.docscanai.core.util.BitmapUtils
import com.docscanai.core.util.Diag
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Thin CameraX wrapper that binds `Preview` + `ImageAnalysis` (live boundary detection) +
 * `ImageCapture` (full-resolution still) to a lifecycle (ARCHITECTURE.md §7).
 *
 * Configuration (locked):
 *  - Preview: `PreviewView` (`FIT_CENTER`, letterboxed full field of view), pinned to **4:3**.
 *    SUBTASK H33 ("show what it sees"): Preview and ImageCapture share ONE aspect — and therefore
 *    one sensor field of view — so the preview shows exactly what the still delivers.
 *  - ImageAnalysis: `RGBA_8888`, `KEEP_ONLY_LATEST`, target ~1280x720 (16:9 centred crop of that
 *    same field of view; detection downscales further). Deliberately UNCHANGED in H33:
 *    `SeedMapping`/`PreviewFrameMapping` map through the 16:9 -> 4:3 centred crop.
 *  - ImageCapture: `CAPTURE_MODE_MAXIMIZE_QUALITY`, JPEG quality 95, pinned to **4:3** (native max).
 *
 * Lifecycle: call [bind] on `ON_START` and [unbind] on `ON_STOP` (see `CameraScreen`); call
 * [release] when the screen leaves the composition.
 */
class CameraController(
    private val context: Context,
    private val previewView: PreviewView
) {

    /** Analyzer attached to [ImageAnalysis] at bind time; set before calling [bind]. */
    var analyzer: ImageAnalysis.Analyzer? = null

    private val analysisExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "docscan-analysis") }
    private val captureExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "docscan-capture") }

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null

    /** True once the use cases have been bound. */
    val isBound: Boolean get() = camera != null

    /** True when the bound camera has a flash/torch unit. */
    val hasFlashUnit: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true

    /** Binds the three use cases to [lifecycleOwner]; [onReady] runs on the main thread when bound. */
    fun bind(lifecycleOwner: LifecycleOwner, onReady: () -> Unit = {}) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider

                // SUBTASK H33: pin Preview to 4:3 so its field of view is exactly the still's.
                val preview = Preview.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(
                                AspectRatioStrategy(
                                    AspectRatio.RATIO_4_3,
                                    AspectRatioStrategy.FALLBACK_RULE_AUTO
                                )
                            )
                            .build()
                    )
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .build()
                analyzer?.let { analysis.setAnalyzer(analysisExecutor, it) }
                imageAnalysis = analysis

                // SUBTASK H33: pin ImageCapture to 4:3 — the same aspect (and field of view) as
                // the preview stream above; this is the aspect `PreviewFrameMapping.PREVIEW_ASPECT`
                // describes in upright (portrait) coordinates.
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(95)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(
                                AspectRatioStrategy(
                                    AspectRatio.RATIO_4_3,
                                    AspectRatioStrategy.FALLBACK_RULE_AUTO
                                )
                            )
                            .build()
                    )
                    .build()
                imageCapture = capture

                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                    capture
                )
                onReady()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to bind camera use cases", t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Unbinds all use cases (safe to call repeatedly / when not bound). */
    fun unbind() {
        imageCapture = null
        runCatching { imageAnalysis?.clearAnalyzer() }
        imageAnalysis = null
        runCatching { provider?.unbindAll() }
        camera = null
    }

    /** Enables/disables the torch (continuous flash). No-op when unavailable. */
    fun setTorchEnabled(enabled: Boolean) {
        runCatching { camera?.cameraControl?.enableTorch(enabled) }
            .onFailure { Log.w(TAG, "Torch toggle failed", it) }
    }

    /**
     * Takes a full-resolution still, decoding it to an upright [Bitmap] on a background thread.
     * [onResult] receives `null` on any failure. The returned bitmap is owned by the caller.
     *
     * Hardened (SUBTASK 9a): the whole callback is wrapped so a decode/decode-adjacent failure can
     * never crash the capture thread, and the [onResult] invocation itself is guarded (a throwing
     * consumer must not kill the executor thread).
     */
    fun capture(onResult: (Bitmap?) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            Diag.warn("Camera", "capture() called before the use cases were bound")
            safeResult(onResult, null)
            return
        }
        val start = System.currentTimeMillis()
        Diag.stageEnter("Camera.capture")
        capture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = try {
                    BitmapUtils.imageProxyToBitmap(image)
                } catch (t: Throwable) {
                    // Catch Throwable (incl. OutOfMemoryError) so decode failures degrade to null.
                    Diag.logThrowable("Camera.capture", t)
                    null
                } finally {
                    runCatching { image.close() }
                }
                Diag.stageExit(
                    "Camera.capture", System.currentTimeMillis() - start,
                    Diag.describe(bitmap)
                )
                safeResult(onResult, bitmap)
            }

            override fun onError(exception: ImageCaptureException) {
                Diag.error("Camera.capture", "ImageCapture error: ${exception.message}", exception)
                safeResult(onResult, null)
            }
        })
    }

    /** Invokes [onResult] defensively so a throwing consumer cannot kill the camera thread. */
    private fun safeResult(onResult: (Bitmap?) -> Unit, bitmap: Bitmap?) {
        try {
            onResult(bitmap)
        } catch (t: Throwable) {
            Diag.logThrowable("Camera.capture", t)
        }
    }

    /** Unbinds and shuts down the background executors. The controller cannot be reused after. */
    fun release() {
        unbind()
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraController"
    }
}
