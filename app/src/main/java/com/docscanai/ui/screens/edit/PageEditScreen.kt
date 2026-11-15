package com.docscanai.ui.screens.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.docscanai.data.EnhancementMode
import com.docscanai.ui.session.ScanSessionViewModel
import java.io.File
import kotlinx.coroutines.launch

/**
 * Single-page edit (TECHNICAL_SPEC.md §6.1).
 *
 * Shows the page with a **before/after** toggle (raw capture vs. processed image), a **rotate**
 * control that updates [com.docscanai.data.ScannedPage.rotationDegrees] (non-destructively, honoured
 * by the UI and the exported PDF), an **enhancement-mode** control that re-runs the pipeline
 * ([ScanSessionViewModel.reEnhance]) and a **delete** action.
 *
 * TODO(page-edit): manual corner-drag re-crop. The auto-detected corners
 * ([com.docscanai.data.ScannedPage.corners]) are preserved and re-used by [ScanSessionViewModel.reEnhance];
 * an interactive corner editor is intentionally left out to keep this screen robust.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditScreen(
    pageId: String,
    viewModel: ScanSessionViewModel,
    onDone: () -> Unit
) {
    val document by viewModel.document.collectAsStateWithLifecycle()
    // SUBTASK H23: the faint-writing control is DARK by default. Measurement on the real pencil
    // pages did not meet the keep gates (docs/HANDWRITING_ENHANCEMENT.md §6), so the affordance is
    // only rendered when the (off-by-default) repository flag is explicitly enabled.
    val handwritingEnabled by viewModel.handwritingDefault.collectAsStateWithLifecycle()
    val page = document.pages.firstOrNull { it.id == pageId }
    val scope = rememberCoroutineScope()

    var showProcessed by rememberSaveable { mutableStateOf(true) }
    var reEnhancing by remember { mutableStateOf(false) }
    var handwritingBusy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit page") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Done")
                    }
                }
            )
        }
    ) { padding ->
        if (page == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
            ) {
                Text("Page not found", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "It may have been deleted.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Button(onClick = onDone) { Text("Back to review") }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- Preview (honours the page rotation) --------------------------------------------
            Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val previewPath = if (showProcessed) {
                        page.imageFile
                    } else {
                        page.rawFile ?: page.imageFile
                    }
                    AsyncImage(
                        model = File(previewPath),
                        contentDescription = "Page preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .rotate(page.rotationDegrees.toFloat())
                    )
                }
            }

            // --- Before / after toggle + rotation readout ---------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = !showProcessed, onClick = { showProcessed = false }, label = { Text("Before") })
                FilterChip(selected = showProcessed, onClick = { showProcessed = true }, label = { Text("After") })
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Rotation: ${page.rotationDegrees}°",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // --- Rotate control -----------------------------------------------------------------
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { viewModel.rotatePage(pageId, -90) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Rotate left")
                }
                OutlinedButton(
                    onClick = { viewModel.rotatePage(pageId, 90) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Rotate right")
                }
            }

            // --- Enhancement mode (re-runs the pipeline for this page) --------------------------
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enhancement", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EnhancementMode.entries.forEach { mode ->
                        FilterChip(
                            selected = document.enhancementMode == mode,
                            enabled = !reEnhancing,
                            onClick = {
                                viewModel.setEnhancementMode(mode)
                                reEnhancing = true
                                scope.launch {
                                    viewModel.reEnhance(pageId)
                                    showProcessed = true
                                    reEnhancing = false
                                }
                            },
                            label = { Text(enhancementLabel(mode)) }
                        )
                    }
                }
                if (reEnhancing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            // --- Handwriting (SUBTASK H23): one-tap, only offered when measured as faint writing ---
            if (handwritingEnabled) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Handwriting", style = MaterialTheme.typography.titleSmall)
                if (page.enhancement.handwriting) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Faint-writing enhancement is ON",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            enabled = !handwritingBusy && !reEnhancing,
                            onClick = {
                                handwritingBusy = true
                                scope.launch {
                                    viewModel.setHandwritingEnhance(pageId, false)
                                    showProcessed = true
                                    handwritingBusy = false
                                }
                            }
                        ) { Text("Turn off") }
                    }
                } else if (page.handwritingSuggested) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Faint writing detected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            enabled = !handwritingBusy && !reEnhancing,
                            onClick = {
                                handwritingBusy = true
                                scope.launch {
                                    viewModel.setHandwritingEnhance(pageId, true)
                                    showProcessed = true
                                    handwritingBusy = false
                                }
                            }
                        ) { Text("Enhance handwriting") }
                    }
                } else {
                    Text(
                        text = "No faint writing detected on this page.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (handwritingBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            // --- Delete -------------------------------------------------------------------------
            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Delete page")
            }
        }
    }

    if (confirmDelete && page != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete page?") },
            text = { Text("This page will be removed from the document.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.removePage(pageId)
                    onDone()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

private fun enhancementLabel(mode: EnhancementMode): String = when (mode) {
    EnhancementMode.ORIGINAL -> "Original"
    EnhancementMode.ENHANCED -> "Enhanced"
    EnhancementMode.BW -> "B&W"
}
