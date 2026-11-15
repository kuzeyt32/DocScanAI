package com.docscanai.ui.screens.onboarding

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.docscanai.core.di.ServiceLocator
import com.docscanai.models.ModelState
import com.docscanai.models.ModelStatus
import com.docscanai.models.ModelSpec
import com.docscanai.models.ModelRegistry
import kotlinx.coroutines.launch

/**
 * Real first-launch model-download screen (TECHNICAL_SPEC.md §6.1 step 2 / §10, ARCHITECTURE.md §9).
 *
 * Lists only the models that matter to the user in plain language — the **Page detector**
 * (`m1_boundary`, DexiNed) and **Page flattening** (`m2_dewarp`, UVDoc) — with their approximate
 * size and live per-model state driven by [ModelDownloadManager.statuses]. A primary
 * **Download AI models** button enqueues the resumable, SHA-256-verified background download; an
 * overall + per-model determinate progress bar is shown while it runs; failures expose a **Retry**;
 * and a clearly-labelled **Skip — use built-in processing** action lets the user proceed with the
 * classical fallback. The app is fully usable either way.
 *
 * On completion ([ModelDownloadManager.isFastTierAvailable]) the screen sets
 * `firstLaunchCompleted = true` and calls [onContinue]; skipping does the same after also setting
 * `modelsSkipped = true` (TECHNICAL_SPEC.md §10). The intentionally-unpinned
 * Quality/fallback/optional models are shown as **not available yet** rather than as broken.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDownloadScreen(
    onContinue: () -> Unit
) {
    val settings = ServiceLocator.settingsRepository
    val manager = ServiceLocator.modelDownloadManager
    val registry = ServiceLocator.modelRegistry
    val scope = rememberCoroutineScope()

    // Models that unlock the default (Fast) pipeline — the ones the user actually needs.
    // SUBTASK H42: `downloadableSpecs` is Fast-tier AND user-facing, so the legacy DexiNed graph
    // and the image-to-image UVDoc fallback no longer appear as extra rows.
    val relevantSpecs = remember(registry) { registry.downloadableSpecs }

    // Live download state, exposed by the manager/worker as Compose state.
    val statuses by manager.statuses.collectAsStateWithLifecycle()
    val modelsSkipped by settings.modelsSkipped.collectAsStateWithLifecycle(initialValue = false)
    val online = rememberIsOnline()

    // Disk-existence snapshot. Re-derived only when a model's *state* changes (not on every
    // byte-progress tick) to avoid a file stat storm during the download.
    val stateKey = remember(statuses) { statuses.mapValues { it.value.state } }
    val available: Map<String, Boolean> = remember(stateKey) {
        relevantSpecs.associate { it.id to manager.isModelAvailable(it.id) }
    }

    // Overall progress across exactly the models offered here (present -> 1f). NOTE: the manager's
    // `observeOverallProgress()` averages over *all* registry models, including the intentionally
    // unpinned placeholders, so it would cap at ~0.4 here; we therefore derive the bar over the
    // Fast-tier models to give the user a truthful 0..1 progress.
    val overallProgress = remember(statuses, available) {
        if (relevantSpecs.isEmpty()) {
            0f
        } else {
            relevantSpecs.map { spec ->
                statuses[spec.id]?.progress ?: if (available[spec.id] == true) 1f else 0f
            }.average().toFloat().coerceIn(0f, 1f)
        }
    }

    val anyFailed = relevantSpecs.any { statuses[it.id]?.state == ModelState.FAILED }
    val anyActive = relevantSpecs.any {
        val s = statuses[it.id]?.state
        s == ModelState.DOWNLOADING || s == ModelState.VERIFYING
    }
    val fastTierReady = relevantSpecs.isNotEmpty() &&
        relevantSpecs.all { available[it.id] == true }

    var navigated by rememberSaveable { mutableStateOf(false) }

    // Auto-advance the moment every Fast-tier model is present on disk.
    LaunchedEffect(fastTierReady) {
        if (fastTierReady && !navigated) {
            navigated = true
            settings.setFirstLaunchCompleted(true)
            onContinue()
        }
    }

    fun startDownload() {
        manager.enqueueDownload(relevantSpecs.map { it.id })
    }

    fun skip() {
        if (navigated) return
        navigated = true
        scope.launch {
            settings.setModelsSkipped(true)
            settings.setFirstLaunchCompleted(true)
            onContinue()
        }
    }

    val totalBytes = remember(relevantSpecs) { relevantSpecs.sumOf { it.approxSizeBytes } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Set up AI models") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Optional on-device AI",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Two small on-device models give the best automatic page detection and " +
                    "flattening. They download once, over HTTPS, and then the app works fully " +
                    "offline. You can skip this and still scan — built-in processing is always " +
                    "available.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Reassurance: the app works without the download.
            InfoCard(
                icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                title = "Works without the download",
                body = "Skipping just uses built-in perspective correction and enhancement. " +
                    "No feature is locked."
            )

            if (modelsSkipped && !fastTierReady) {
                InfoCard(
                    icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    title = "Using built-in processing",
                    body = "You chose to skip the AI models. You can download them any time from here."
                )
            }

            if (!online) {
                InfoCard(
                    icon = { Icon(Icons.Filled.CloudOff, contentDescription = null) },
                    title = "You're offline",
                    body = "Connect to the internet to download the models. You can also skip and " +
                        "start scanning now — the download will wait for a connection."
                )
            }

            // Overall progress (only while a download is in flight or has been started).
            val showProgress = anyActive || overallProgress > 0f
            if (showProgress) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Overall progress", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = "${(overallProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    LinearProgressIndicator(
                        progress = { overallProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Per-model rows.
            relevantSpecs.forEach { spec ->
                ModelRow(
                    spec = spec,
                    status = statuses[spec.id],
                    available = available[spec.id] == true,
                    onRetry = { manager.enqueueDownload(listOf(spec.id)) }
                )
            }

            // Primary action.
            Button(
                onClick = { startDownload() },
                enabled = !anyActive && !fastTierReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    if (anyFailed) "Retry download (~${formatSize(totalBytes)})"
                    else "Download AI models (~${formatSize(totalBytes)})"
                )
            }

            // Skip — always available, clearly labelled.
            OutlinedButton(
                onClick = { skip() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Skip — use built-in processing")
            }

        }
    }
}

/** One user-facing model: friendly name, engine, size, live state and an inline progress bar. */
@Composable
private fun ModelRow(
    spec: ModelSpec,
    status: ModelStatus?,
    available: Boolean,
    onRetry: () -> Unit
) {
    val (title, subtitle) = friendlyModelLabels(spec)
    val label = stateLabel(status, available)
    val isError = status?.state == ModelState.FAILED &&
        status.error?.contains("not pinned", ignoreCase = true) != true
    val isReady = available || status?.state == ModelState.READY
    val active = status?.state == ModelState.DOWNLOADING || status?.state == ModelState.VERIFYING

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = when {
                        isReady -> Icons.Filled.CheckCircle
                        isError -> Icons.Filled.ErrorOutline
                        else -> Icons.Filled.CloudDownload
                    },
                    contentDescription = null,
                    tint = when {
                        isReady -> MaterialTheme.colorScheme.primary
                        isError -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = formatSize(spec.approxSizeBytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        isReady -> MaterialTheme.colorScheme.primary
                        isError -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (isError) {
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }

            // Per-model determinate progress while downloading/verifying.
            if (active) {
                LinearProgressIndicator(
                    progress = { status?.progress ?: 0f },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Surface a download/verify error message when we have one (skip the placeholder note).
            val errorText = status?.error
            if (isError && !errorText.isNullOrBlank()) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** Small informational card used for the fallback / offline / skipped callouts. */
@Composable
private fun InfoCard(
    icon: @Composable () -> Unit,
    title: String,
    body: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Plain-language title + engine subtitle for the models the screen offers. */
private fun friendlyModelLabels(spec: ModelSpec): Pair<String, String> = when (spec.id) {
    ModelRegistry.ID_M1C_DETECT -> "Page detector" to "Finds the page corners (DocAligner LCNet100)"
    ModelRegistry.ID_M2_DEWARP_GRID -> "Page flattening" to "Corrects page curvature (UVDoc)"
    ModelRegistry.ID_M2_DEWARP -> "Page flattening (compatibility)" to "Image-to-image UVDoc"
    ModelRegistry.ID_M1_BOUNDARY -> "Edge detector (legacy)" to "DexiNed - not used by detection"
    else -> spec.displayName to spec.sourceRepository
}

/** Human-readable label for a model's current state. */
private fun stateLabel(status: ModelStatus?, available: Boolean): String = when {
    available || status?.state == ModelState.READY -> "Ready"
    status?.state == ModelState.DOWNLOADING -> "Downloading…"
    status?.state == ModelState.VERIFYING -> "Verifying…"
    status?.state == ModelState.FAILED ->
        if (status.error?.contains("not pinned", ignoreCase = true) == true) {
            "Not available yet"
        } else {
            "Download failed"
        }
    else -> "Not downloaded"
}

/** Formats an approximate byte count as `MB`/`GB`. */
private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
    } else {
        String.format(java.util.Locale.US, "%.0f MB", mb)
    }
}

/**
 * Observes connectivity so the screen can show a friendly offline message. Uses only the
 * platform [ConnectivityManager] (no new dependency) and unregisters its callback on dispose.
 */
@Composable
private fun rememberIsOnline(): Boolean {
    val context = LocalContext.current
    var online by remember { mutableStateOf(isOnlineNow(context)) }
    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            onDispose { }
        } else {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    online = true
                }

                override fun onLost(network: Network) {
                    online = isOnlineNow(context)
                }
            }
            runCatching {
                cm.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    callback
                )
            }
            onDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
        }
    }
    return online
}

/** Best-effort current connectivity check (assumes online when the service is unavailable). */
private fun isOnlineNow(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
