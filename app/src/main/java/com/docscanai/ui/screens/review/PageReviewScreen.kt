package com.docscanai.ui.screens.review

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.docscanai.data.EnhancementMode
import com.docscanai.data.ScannedPage
import com.docscanai.ui.session.ScanSessionViewModel
import java.io.File

/**
 * Grid/list of captured pages backed by the shared [ScanSessionViewModel] (TECHNICAL_SPEC.md §6.1).
 *
 * Features: page count, a session-wide enhancement-mode selector, per-page thumbnails (honouring
 * [ScannedPage.rotationDegrees]), reorder via move up/down (using
 * [ScanSessionViewModel.reorderPages]), rotate, delete, open Edit, plus "Add pages" (back to Camera)
 * and "Export PDF" actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageReviewScreen(
    viewModel: ScanSessionViewModel,
    // Tapping a page (or its thumbnail) opens the full-screen zoomable viewer (SUBTASK F).
    onOpenPage: (String) -> Unit,
    onEditPage: (String) -> Unit,
    onExport: () -> Unit,
    onAddMore: () -> Unit
) {
    val document by viewModel.document.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review") },
                navigationIcon = {
                    IconButton(onClick = onAddMore) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to camera")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text(
                text = if (document.pageCount == 1) "1 page captured"
                else "${document.pageCount} pages captured",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Session-wide enhancement mode — applies to newly captured pages and to pages that are
            // re-enhanced from the Edit screen (TECHNICAL_SPEC.md §6.1).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoFixHigh,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Enhance:", style = MaterialTheme.typography.bodyMedium)
                EnhancementMode.entries.forEach { mode ->
                    FilterChip(
                        selected = document.enhancementMode == mode,
                        onClick = { viewModel.setEnhancementMode(mode) },
                        label = { Text(enhancementLabel(mode)) }
                    )
                }
            }

            if (document.pages.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No pages captured yet.\nReturn to the camera to scan a document.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(document.pages, key = { _, page -> page.id }) { index, page ->
                        ReviewPageRow(
                            page = page,
                            index = index,
                            count = document.pages.size,
                            onOpen = { onOpenPage(page.id) },
                            onMoveUp = { movePage(viewModel, document.pages, index, -1) },
                            onMoveDown = { movePage(viewModel, document.pages, index, +1) },
                            onRotate = { viewModel.rotatePage(page.id, 90) },
                            onEdit = { onEditPage(page.id) },
                            onDelete = { viewModel.removePage(page.id) }
                        )
                    }
                }
            }

            Spacer(Modifier.padding(top = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onAddMore, modifier = Modifier.weight(1f)) {
                    Text("Add pages")
                }
                Button(
                    onClick = onExport,
                    enabled = document.pages.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Export PDF")
                }
            }
        }
    }
}

@Composable
private fun ReviewPageRow(
    page: ScannedPage,
    index: Int,
    count: Int,
    onOpen: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRotate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = File(page.thumbnailFile ?: page.imageFile),
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .rotate(page.rotationDegrees.toFloat())
                    .clickable { onOpen() } // tap to view full-screen before exporting
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Page ${index + 1}", style = MaterialTheme.typography.bodyLarge)
                if (page.rotationDegrees != 0) {
                    Text(
                        text = "Rotated ${page.rotationDegrees}°",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onMoveUp, enabled = index > 0) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
            }
            IconButton(onClick = onMoveDown, enabled = index < count - 1) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
            }
            IconButton(onClick = onRotate) {
                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate")
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit page")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete page")
            }
        }
    }
}

/** Swaps the page at [index] with its neighbour [delta] positions away and persists the new order. */
private fun movePage(
    viewModel: ScanSessionViewModel,
    pages: List<ScannedPage>,
    index: Int,
    delta: Int
) {
    val target = index + delta
    if (target !in pages.indices) return
    val ids = pages.map { it.id }.toMutableList()
    val tmp = ids[index]
    ids[index] = ids[target]
    ids[target] = tmp
    viewModel.reorderPages(ids)
}

private fun enhancementLabel(mode: EnhancementMode): String = when (mode) {
    EnhancementMode.ORIGINAL -> "Original"
    EnhancementMode.ENHANCED -> "Enhanced"
    EnhancementMode.BW -> "B&W"
}
