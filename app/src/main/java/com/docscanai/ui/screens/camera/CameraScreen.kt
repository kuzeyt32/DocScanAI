package com.docscanai.ui.screens.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.exifinterface.media.ExifInterface
import coil.compose.AsyncImage
import com.docscanai.capture.CameraController
import com.docscanai.capture.CaptureIndicator
import com.docscanai.capture.CaptureState
import com.docscanai.capture.CaptureStateMachine
import com.docscanai.capture.FrameAnalyzer
import com.docscanai.capture.PreviewFrameMapping
import com.docscanai.capture.SeedMapping
import com.docscanai.core.di.ServiceLocator
import com.docscanai.core.util.BitmapUtils
import com.docscanai.core.util.Diag
import com.docscanai.data.EnhancementMode
import com.docscanai.data.Point
import com.docscanai.data.Quad
import com.docscanai.data.ScannedPage
import com.docscanai.ui.session.ScanSessionViewModel
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Core capture screen (TECHNICAL_SPEC.md §6.1–§6.4): full-screen CameraX preview, live animated
 * 4-corner overlay, big shutter, auto-capture toggle, torch, enhancement-mode chip, page counter,
 * a thumbnail strip, and the vFlat-style ~1 s confirmation thumbnail that returns the user straight
 * to capturing the next page.
 *
 * The heavy lifting lives behind [ScanSessionViewModel] (pipeline + session) and the camera layer
 * classes [CameraController], [FrameAnalyzer] and [CaptureStateMachine].
 */
@Composable
fun CameraScreen(
    viewModel: ScanSessionViewModel,
    onOpenReview: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> permissionGranted = granted }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (permissionGranted) {
        CameraContent(
            viewModel = viewModel,
            onOpenReview = onOpenReview,
            onOpenLibrary = onOpenLibrary
        )
    } else {
        CameraPermissionDenied(
            onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = { openAppSettings(context) }
        )
    }
}

@Composable
private fun CameraContent(
    viewModel: ScanSessionViewModel,
    onOpenReview: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val previewView = remember {
        PreviewView(context).apply {
            // SUBTASK H33 ("show what it sees"): FIT_CENTER letterboxes the preview instead of
            // cropping it. Combined with CameraController pinning Preview + ImageCapture to the
            // same 4:3 aspect, the preview shows exactly the field of view the still delivers —
            // what is on the screen is what gets captured, nothing is hidden off-screen.
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val controller = remember { CameraController(context, previewView) }
    val analyzer = remember {
        FrameAnalyzer(
            pageDetector = ServiceLocator.pageDetector,
            scope = scope,
            dispatcher = ServiceLocator.frameDispatcher
        )
    }
    val stateMachine = remember { CaptureStateMachine(scope) }

    val document by viewModel.document.collectAsStateWithLifecycle()
    val quad by analyzer.quad.collectAsStateWithLifecycle()
    val stableFrames by analyzer.stableFrames.collectAsStateWithLifecycle()
    val frameAspect by analyzer.frameAspectRatio.collectAsStateWithLifecycle()
    // H13: hard page-ness gate verdict + score for the current frame, and the user-visible hint.
    val gatePass by analyzer.gatePass.collectAsStateWithLifecycle()
    val gateScore by analyzer.gateScore.collectAsStateWithLifecycle()
    // H21: gate pass is necessary but not sufficient for AUTO-capture (fallback quads must prove
    // text-line structure), plus the frame fingerprint used to suppress repeat captures.
    val autoCaptureEligible by analyzer.autoCaptureEligible.collectAsStateWithLifecycle()
    val frameSignature by analyzer.frameSignature.collectAsStateWithLifecycle()
    // H21 field feedback: real frame clock + content-motion score. The timestamp is what makes a
    // minimum HOLD TIME possible ("auto-capture is too fast"); the motion score makes the machine
    // wait while the page is still being turned even if the quad looks steady.
    val frameTimestampMs by analyzer.frameTimestampMs.collectAsStateWithLifecycle()
    val captureState by stateMachine.state.collectAsStateWithLifecycle()
    val indicator by stateMachine.indicator.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val processingError by viewModel.processingError.collectAsStateWithLifecycle()
    val autoCapture by ServiceLocator.settingsRepository.autoCaptureEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    // Neural (ONNX) processing switch — disabling it forces the classical CV paths. The user
    // reported the crash only occurs with the AI/neural path, so this is both a diagnostic lever
    // and an instant workaround (SUBTASK 9a).
    val aiEnabled by ServiceLocator.settingsRepository.neuralProcessingEnabled
        .collectAsStateWithLifecycle(initialValue = true)

    // SUBTASK H31 — session warm-up. The optional M-LSD session used to be created by whichever
    // capture/frame first needed line evidence, which measured +446 ms p95 on the very first
    // process (H30). Creating it here, on the analysis dispatcher, keeps that cost off every
    // capture; it is skipped entirely when AI is off or the artifact is absent.
    LaunchedEffect(aiEnabled) {
        if (aiEnabled) {
            val ms = ServiceLocator.warmMlsdModel()
            if (ms > 0L) Diag.log("CameraScreen", "M-LSD prewarmed in ${ms}ms")
        }
    }

    var lastThumbPath by remember { mutableStateOf<String?>(null) }
    var torchOn by remember { mutableStateOf(false) }

    // Gallery "Import photo" (SUBTASK 9a): lets us reproduce the processing path with a static
    // photo (no camera). Picked content:// URIs are decoded (honouring EXIF rotation) and run
    // through the SAME pipeline as the camera path, sequentially, with a visible busy state.
    var importBusy by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMPORT_ITEMS)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            importBusy = true
            Diag.log("Import", "received ${uris.size} uri(s)")
            try {
                uris.forEachIndexed { index, uri ->
                    val bitmap = try {
                        withContext(Dispatchers.IO) { decodeImportedBitmap(context, uri) }
                    } catch (t: Throwable) {
                        Diag.logThrowable("Import", t); null
                    }
                    if (bitmap == null) {
                        Diag.warn("Import", "decode failed for ${index + 1}/${uris.size}: $uri")
                        return@forEachIndexed
                    }
                    try {
                        Diag.log("Import", "processing ${index + 1}/${uris.size} ${Diag.describe(bitmap)}")
                        // seed = null mirrors a still with no trusted live quad: detect on the image.
                        val page = viewModel.processCapture(bitmap, null)
                        lastThumbPath = page?.thumbnailFile
                    } finally {
                        runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
                    }
                }
            } catch (t: Throwable) {
                Diag.logThrowable("Import", t)
            } finally {
                importBusy = false
                Diag.log("Import", "done")
            }
        }
    }

    // Drive the state machine from live detection. H13: a frame whose quad failed the page-ness
    // gate is fed as `null`, which resets the stability window immediately (the smoothed overlay
    // may still show the previous quad for a few frames, but auto-capture never uses it).
    // H21: an auto-capture-INELIGIBLE frame (fallback quad without text-line proof) is fed the
    // same way — it cannot fire unattended, while the manual shutter still works.
    LaunchedEffect(
        quad,
        stableFrames,
        gatePass,
        gateScore,
        autoCaptureEligible,
        frameSignature,
        frameTimestampMs
    ) {
        stateMachine.onQuadUpdate(
            if (gatePass && autoCaptureEligible) quad else null,
            gateScore,
            frameSignature,
            frameTimestampMs
        )
    }
    LaunchedEffect(autoCapture) {
        stateMachine.autoCaptureEnabled = autoCapture
        Diag.log("AutoCapture", "enabled=$autoCapture")
    }
    LaunchedEffect(captureState) {
        Diag.log("AutoCapture", "state=$captureState")
    }

    // Bind/unbind the camera with the lifecycle (unbind on stop to save power).
    DisposableEffect(lifecycleOwner) {
        controller.analyzer = analyzer
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.bind(lifecycleOwner) { stateMachine.onCameraBound() }
                Lifecycle.Event.ON_STOP -> {
                    controller.unbind()
                    stateMachine.onCameraReleased()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            controller.bind(lifecycleOwner) { stateMachine.onCameraBound() }
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.release()
            analyzer.release()
        }
    }

    // Fulfil capture requests coming from the state machine (auto-capture OR manual shutter).
    LaunchedEffect(Unit) {
        stateMachine.captureRequests.collect {
            // Snapshot the live analysis result at capture time. [FrameAnalyzer.quad] is NORMALIZED
            // to [0,1] in the analysis frame; [FrameAnalyzer.stableFrames] tells us whether it is
            // trustworthy enough to seed the full-resolution refinement. H13: only a quad that
            // passed the page-ness gate may seed a warp — a rejected frame is captured but not
            // warped (manual shutter still always captures).
            val liveNormalized = analyzer.quad.value?.takeIf { analyzer.gatePass.value }
            val stableFrames = analyzer.stableFrames.value
            Diag.log("CameraScreen", "capture request stableFrames=$stableFrames quad=${liveNormalized != null}")
            controller.capture { bitmap ->
                scope.launch {
                    // Everything is wrapped (SUBTASK 9a): any failure logs, recycles the bitmap and
                    // drives the state machine back to DETECTING so the user is never stuck.
                    try {
                        if (bitmap == null) {
                            Diag.warn("CameraScreen", "capture returned null bitmap")
                            stateMachine.onCaptureFailed()
                            return@launch
                        }
                        stateMachine.onStillCaptured()
                        // Convert the live normalized quad into the STILL's pixel space and use it as
                        // the seed for full-resolution corner refinement (TECHNICAL_SPEC.md §4.2).
                        // Only trust a stable live detection; otherwise pass null so the pipeline
                        // falls back to full-frame detection on the still.
                        //
                        // SUBTASK H26: the analysis frame and the still are only interchangeable
                        // when they share the same ASPECT RATIO. ImageAnalysis is pinned to 16:9
                        // while ImageCapture resolves the sensor's native (usually 4:3) size, so
                        // per-axis scaling placed the seed off the page. SeedMapping maps through
                        // the actual (centred-crop) transform and both ratios are logged.
                        val analysisAspect = analyzer.frameAspectRatio.value
                        val seed = liveNormalized
                            ?.takeIf { stableFrames >= MIN_STABLE_FRAMES_FOR_SEED }
                            ?.let {
                                val mapped = SeedMapping.normalizedQuadToPixels(
                                    it, bitmap.width, bitmap.height, analysisAspect
                                )
                                Diag.log(
                                    "CameraScreen",
                                    "[Seed] analysisAspect=${fmtAspect(analysisAspect)} " +
                                        "stillAspect=${fmtAspect(bitmap.width.toFloat() / bitmap.height)} " +
                                        "normalized=${fmtQuad(it)} mapped=${fmtQuad(mapped)} " +
                                        "stableFrames=$stableFrames"
                                )
                                mapped
                            }
                        val page = try {
                            viewModel.processCapture(bitmap, seed)
                        } finally {
                            // The pipeline never owns the source; recycle it as soon as it is done.
                            runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
                        }
                        lastThumbPath = page?.thumbnailFile
                        stateMachine.onProcessingComplete()
                    } catch (t: Throwable) {
                        Diag.logThrowable("CameraScreen.capture", t)
                        // Recover to DETECTING so the capture loop never wedges.
                        stateMachine.onCaptureFailed()
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        CaptureOverlay(
            quad = quad,
            frameAspectRatio = frameAspect,
            previewAspect = PreviewFrameMapping.PREVIEW_ASPECT,
            active = captureState == CaptureState.LOCKED,
            modifier = Modifier.fillMaxSize()
        )

        TopControls(
            pageCount = document.pageCount,
            onOpenLibrary = onOpenLibrary,
            aiEnabled = aiEnabled,
            onToggleAi = {
                val next = !aiEnabled
                // Apply immediately (volatile) and persist; the OpenCV/ORT provider lambdas read the
                // flag on every stage, so the change takes effect on the next page.
                ServiceLocator.neuralProcessingEnabled = next
                scope.launch {
                    runCatching { ServiceLocator.settingsRepository.setNeuralProcessingEnabled(next) }
                        .onFailure { Diag.logThrowable("Settings", it) }
                }
            },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // H13: "Document detected" / "Hold steady" so the user can tell the difference between
        // "nothing happening" and "about to capture".
        CaptureHint(
            indicator = indicator,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 188.dp)
        )

        CameraControls(
            modifier = Modifier.align(Alignment.BottomCenter),
            autoCaptureEnabled = autoCapture,
            onToggleAutoCapture = { enabled ->
                scope.launch { ServiceLocator.settingsRepository.setAutoCaptureEnabled(enabled) }
            },
            enhancementMode = document.enhancementMode,
            onCycleEnhancement = { viewModel.setEnhancementMode(nextMode(document.enhancementMode)) },
            torchOn = torchOn,
            onToggleTorch = {
                torchOn = !torchOn
                controller.setTorchEnabled(torchOn)
            },
            // SUBTASK H38 — the detector-reset control ("stuck after a miss" recovery). Runs the
            // full reset chain in order: detector quad cache + M-LSD cadence cache
            // ([OpenCvQuadPageDetector.reset] -> [MlsdTrigger.Schedule.reset]) and the capture
            // side ([CaptureStateMachine.reset] -> [AutoCaptureStability.reset] + cooldown).
            onResetDetector = {
                (ServiceLocator.pageDetector as? com.docscanai.capture.OpenCvQuadPageDetector)
                    ?.reset()
                stateMachine.reset()
                Diag.log("Reset", "detector + capture state reset")
            },
            shutterEnabled = captureState == CaptureState.DETECTING || captureState == CaptureState.LOCKED,
            onShutter = { stateMachine.requestManualCapture() },
            pages = document.pages,
            onOpenReview = onOpenReview,
            importEnabled = !importBusy && !isProcessing,
            onImport = {
                runCatching {
                    importLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }.onFailure { Diag.logThrowable("Import", it) }
            }
        )

        // vFlat-style ~1 s confirmation of the page just captured, then straight back to Detecting.
        AnimatedVisibility(
            visible = captureState == CaptureState.THUMBNAIL_1S && lastThumbPath != null,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        ) {
            lastThumbPath?.let { path ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 6.dp,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                ) {
                    AsyncImage(
                        model = File(path),
                        contentDescription = "Captured page",
                        modifier = Modifier.size(96.dp).padding(4.dp)
                    )
                }
            }
        }

        val showProcessing = (isProcessing && captureState == CaptureState.PROCESSING) || importBusy
        if (showProcessing) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text(
                    text = if (importBusy) "Importing…" else "Processing…",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        // Non-blocking error surface (SUBTASK 9a): the page is kept as raw instead of crashing.
        val errorMessage = processingError
        if (errorMessage != null) {
            Surface(
                color = Color(0xFF7A1F1F).copy(alpha = 0.92f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 180.dp, start = 16.dp, end = 16.dp)
                    .clickable { viewModel.clearProcessingError() }
            ) {
                Text(
                    text = errorMessage,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** H13 auto-capture hint pill ("Document detected" / "Hold steady"). */
@Composable
private fun CaptureHint(
    indicator: CaptureIndicator,
    modifier: Modifier = Modifier
) {
    val visible = indicator != CaptureIndicator.NONE
    val text = when (indicator) {
        CaptureIndicator.HOLD_STEADY -> "Hold steady — capturing"
        CaptureIndicator.DOCUMENT_DETECTED -> "Document detected"
        CaptureIndicator.NONE -> ""
    }
    val accent = when (indicator) {
        CaptureIndicator.HOLD_STEADY -> Color(0xFF66E08A)
        CaptureIndicator.DOCUMENT_DETECTED -> Color(0xFF8AB4F8)
        CaptureIndicator.NONE -> Color.Transparent
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f),
        modifier = modifier
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, accent)
        ) {
            Text(
                text = text,
                color = accent,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/** Live 4-corner overlay drawn from the smoothed normalized [Quad]. */
@Composable
private fun CaptureOverlay(
    quad: Quad?,
    frameAspectRatio: Float,
    previewAspect: Float,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val accent = if (active) Color(0xFF66E08A) else Color(0xFF8AB4F8)
    Canvas(modifier = modifier) {
        val q = quad ?: return@Canvas
        val mapped = q.points.map {
            mapNormalizedToCanvas(it, size.width, size.height, frameAspectRatio, previewAspect)
        }
        val path = Path().apply {
            moveTo(mapped[0].x, mapped[0].y)
            lineTo(mapped[1].x, mapped[1].y)
            lineTo(mapped[2].x, mapped[2].y)
            lineTo(mapped[3].x, mapped[3].y)
            close()
        }
        drawPath(path, color = accent.copy(alpha = 0.9f), style = Stroke(width = 6f))
        mapped.forEach { p ->
            drawCircle(color = accent, radius = 15f, center = p)
            drawCircle(color = Color.White, radius = 6f, center = p)
        }
    }
}

@Composable
private fun TopControls(
    pageCount: Int,
    onOpenLibrary: () -> Unit,
    aiEnabled: Boolean,
    onToggleAi: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color.Black.copy(alpha = 0.4f)) {
            Text(
                text = if (pageCount == 1) "1 page" else "$pageCount pages",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Neural on/off: "On" uses the AI dewarp/enhance models when present; "Off" forces the
            // classical OpenCV paths (the reported crash is AI-only, so Off is the safe mode).
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (aiEnabled) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                } else {
                    Color.Black.copy(alpha = 0.4f)
                },
                modifier = Modifier.clickable { onToggleAi() }
            ) {
                Text(
                    text = if (aiEnabled) "AI: On" else "AI: Off",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onOpenLibrary) {
                Icon(Icons.Filled.Collections, contentDescription = "Library", tint = Color.White)
            }
        }
    }
}

@Composable
private fun CameraControls(
    modifier: Modifier = Modifier,
    autoCaptureEnabled: Boolean,
    onToggleAutoCapture: (Boolean) -> Unit,
    enhancementMode: EnhancementMode,
    onCycleEnhancement: () -> Unit,
    torchOn: Boolean,
    onToggleTorch: () -> Unit,
    onResetDetector: () -> Unit = {},
    shutterEnabled: Boolean,
    onShutter: () -> Unit,
    pages: List<ScannedPage>,
    onOpenReview: () -> Unit,
    importEnabled: Boolean,
    onImport: () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ThumbnailStrip(pages = pages, onOpenReview = onOpenReview)

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControlToggle(
                selected = autoCaptureEnabled,
                icon = Icons.Filled.Bolt,
                label = "Auto",
                onClick = { onToggleAutoCapture(!autoCaptureEnabled) }
            )
            ShutterButton(enabled = shutterEnabled, onClick = onShutter)
            ControlToggle(
                selected = torchOn,
                icon = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                label = "Flash",
                onClick = onToggleTorch
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControlToggle(
                selected = enhancementMode != EnhancementMode.ORIGINAL,
                icon = Icons.Filled.AutoFixHigh,
                label = enhancementLabel(enhancementMode),
                onClick = onCycleEnhancement
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onImport, enabled = importEnabled) { Text("Import") }
                OutlinedButton(onClick = onOpenReview) { Text("Review") }
                OutlinedButton(onClick = onResetDetector) { Text("Reset") }
            }
        }
    }
}

@Composable
private fun ThumbnailStrip(pages: List<ScannedPage>, onOpenReview: () -> Unit) {
    if (pages.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(pages, key = { it.id }) { page ->
            val path = page.thumbnailFile ?: page.imageFile
            AsyncImage(
                model = File(path),
                contentDescription = "Page ${page.order + 1}",
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .clickable { onOpenReview() }
            )
        }
    }
}

@Composable
private fun ControlToggle(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(76.dp).clickable { onClick() }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.size(28.dp)
        )
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(3.dp, Color.White.copy(alpha = if (enabled) 1f else 0.4f), CircleShape)
            .background(
                color = if (enabled) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.1f),
                shape = CircleShape
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (enabled) Color.White else Color.White.copy(alpha = 0.5f), CircleShape)
        )
    }
}

@Composable
private fun CameraPermissionDenied(
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        Icon(
            imageVector = Icons.Filled.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(48.dp)
        )
        Text("Camera permission required", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "DocScan AI needs the camera to scan documents. Grant access to start " +
                "capturing pages.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = onRequest) { Text("Grant camera access") }
        OutlinedButton(onClick = onOpenSettings) { Text("Open app settings") }
    }
}

/**
 * Maps a normalized `[0,1]` point (in the analysis frame) onto the canvas so the overlay lines up
 * with the preview. SUBTASK H33: the preview is `FIT_CENTER` (letterboxed, full sensor field of
 * view of the 4:3 still stream) and the analysis frame is a centred crop of that same field of
 * view; the two-step transform lives in [PreviewFrameMapping.toCanvas] and is verified by
 * `PreviewAlignmentTest`.
 */
private fun mapNormalizedToCanvas(
    point: Point,
    canvasWidth: Float,
    canvasHeight: Float,
    frameAspectRatio: Float,
    previewAspect: Float
): Offset {
    val (x, y) = PreviewFrameMapping.toCanvas(
        point, canvasWidth, canvasHeight,
        if (frameAspectRatio > 0f) frameAspectRatio else previewAspect
    )
    return Offset(x, y)
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
        )
    }
}

private fun enhancementLabel(mode: EnhancementMode): String = when (mode) {
    EnhancementMode.ORIGINAL -> "Original"
    EnhancementMode.ENHANCED -> "Enhanced"
    EnhancementMode.BW -> "B&W"
}

private fun nextMode(mode: EnhancementMode): EnhancementMode = when (mode) {
    EnhancementMode.ORIGINAL -> EnhancementMode.ENHANCED
    EnhancementMode.ENHANCED -> EnhancementMode.BW
    EnhancementMode.BW -> EnhancementMode.ORIGINAL
}

/** Minimum consecutive stable live frames before trusting the live quad as a refinement seed. */
private const val MIN_STABLE_FRAMES_FOR_SEED = 3

/**
 * The normalized→still seed mapping now lives in [`SeedMapping`] (SUBTASK H26) because the old
 * per-axis scaling silently assumed the analysis frame and the still share an aspect ratio, which
 * they do not (16:9 analysis vs 4:3 still). See `SeedMapping.normalizedQuadToPixels`.
 */

/** `%.3f` aspect formatter (US locale) for the `[Seed]` diagnostic line. */
private fun fmtAspect(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)

/** Compact corner dump for the `[Seed]` diagnostic line. */
private fun fmtQuad(q: Quad): String = q.points.joinToString(",") {
    "(${it.x.toInt()},${it.y.toInt()})"
}

/** Max number of photos a single gallery "Import" can pick. */
private const val MAX_IMPORT_ITEMS = 20

/** Long-edge cap applied when decoding an imported photo (memory safety). */
private const val IMPORT_DECODE_LONG_EDGE = 3000

/**
 * Decodes a picked `content://` [uri] into an upright ARGB_8888 bitmap, honouring EXIF rotation
 * when `androidx.exifinterface` is available (it is a project dependency). Bounds the decode to
 * [IMPORT_DECODE_LONG_EDGE] on the long edge to avoid OOM on huge photos. Never throws; returns
 * `null` on any failure.
 */
private fun decodeImportedBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        val resolver = context.contentResolver

        // 1) Bounds-only pass to compute a safe inSampleSize.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, IMPORT_DECODE_LONG_EDGE)
        }

        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null

        // 2) EXIF rotation (androidx.exifinterface is already in the catalog/dependencies).
        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (t: Throwable) {
            Diag.warn("Import", "EXIF read failed; keeping orientation as decoded", t)
            ExifInterface.ORIENTATION_NORMAL
        }
        applyExifOrientation(decoded, orientation)
    } catch (t: Throwable) {
        Diag.logThrowable("Import", t)
        null
    }
}

/** Largest power-of-two sub-sampling that keeps the long edge ≥ [maxLongEdge]. */
private fun computeInSampleSize(width: Int, height: Int, maxLongEdge: Int): Int {
    if (width <= 0 || height <= 0 || maxLongEdge <= 0) return 1
    var sample = 1
    while (max(width, height) / (sample * 2) >= maxLongEdge) sample *= 2
    return sample
}

/** Applies the EXIF rotation to [bitmap] (recycling the input when a rotated copy is made). */
private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
    if (degrees == 0) return bitmap
    return try {
        BitmapUtils.rotate(bitmap, degrees)
    } catch (t: Throwable) {
        Diag.logThrowable("Import", t)
        bitmap
    }
}
