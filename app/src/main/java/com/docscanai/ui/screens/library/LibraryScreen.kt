package com.docscanai.ui.screens.library

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.docscanai.pdf.AndroidPdfExporter
import com.docscanai.ui.components.PdfIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A previously exported PDF discovered in MediaStore. */
data class ExportedPdf(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val dateMillis: Long
)

/**
 * Previously exported PDFs (TECHNICAL_SPEC.md §6.1).
 *
 * Queries [MediaStore] for `application/pdf` files whose `RELATIVE_PATH` sits under our
 * `Documents/DocScanAI`, `Downloads/DocScanAI` or `DCIM/DocScanAI` folders (the API 29+ path) — or
 * whose absolute path contains `/DocScanAI/` on API 26–28. The list refreshes on resume and via the
 * toolbar action. Tapping opens the PDF with [PdfIntents.openPdf]; the delete action confirms first
 * and then removes the MediaStore row/file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var items by remember { mutableStateOf<List<ExportedPdf>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<ExportedPdf?>(null) }

    // Refresh whenever the screen resumes (e.g. after returning from the Export screen).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reloadKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(reloadKey) {
        loading = true
        items = withContext(Dispatchers.IO) { queryExportedPdfs(context) }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { reloadKey++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                items.isEmpty() -> EmptyLibrary(Modifier.align(Alignment.Center))
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { it.uri.toString() }) { item ->
                        LibraryRow(
                            item = item,
                            onOpen = { PdfIntents.openPdf(context, item.uri) },
                            onDelete = { pendingDelete = item }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete PDF?") },
            text = { Text("\"${item.name}\" will be permanently deleted from storage.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteExportedPdf(context, item)
                    pendingDelete = null
                    reloadKey++
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun LibraryRow(
    item: ExportedPdf,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onOpen() }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.PictureAsPdf,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatDate(item.dateMillis)}  •  ${PdfIntents.formatFileSize(item.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.PictureAsPdf,
            contentDescription = null,
            modifier = Modifier.size(48.dp)
        )
        Text("No exported PDFs", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Scanned documents you export appear here (Documents/DocScanAI).",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

// -------------------------------------------------------------------------------------------------
// MediaStore access (off the main thread)
// -------------------------------------------------------------------------------------------------

private const val TAG = "LibraryScreen"

/** Queries MediaStore for the PDFs published by this app. Never throws. */
private fun queryExportedPdfs(context: Context): List<ExportedPdf> {
    val resolver = context.contentResolver
    val isQ = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val collection = if (isQ) {
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Files.getContentUri("external")
    }
    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_ADDED
    )
    val selection: String
    val args: Array<String>
    if (isQ) {
        selection = "${MediaStore.MediaColumns.MIME_TYPE}=? AND (" +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?)"
        args = arrayOf(
            AndroidPdfExporter.MIME_TYPE_PDF,
            "Documents/${AndroidPdfExporter.SUBFOLDER}%",
            "Downloads/${AndroidPdfExporter.SUBFOLDER}%",
            "DCIM/${AndroidPdfExporter.SUBFOLDER}%"
        )
    } else {
        selection = "${MediaStore.MediaColumns.MIME_TYPE}=? AND ${MediaStore.MediaColumns.DATA} LIKE ?"
        args = arrayOf(AndroidPdfExporter.MIME_TYPE_PDF, "%/${AndroidPdfExporter.SUBFOLDER}/%")
    }

    val out = ArrayList<ExportedPdf>()
    runCatching {
        resolver.query(
            collection,
            projection,
            selection,
            args,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                out.add(
                    ExportedPdf(
                        uri = ContentUris.withAppendedId(collection, id),
                        name = cursor.getString(nameIdx) ?: "document.pdf",
                        sizeBytes = cursor.getLong(sizeIdx),
                        dateMillis = cursor.getLong(dateIdx) * 1000L
                    )
                )
            }
        }
    }.onFailure { Log.w(TAG, "Failed to query exported PDFs", it) }
    return out
}

/** Deletes the MediaStore row/file backing [item], surfacing failures as a Toast. */
private fun deleteExportedPdf(context: Context, item: ExportedPdf) {
    val deleted = runCatching { context.contentResolver.delete(item.uri, null, null) }
        .getOrDefault(0)
    if (deleted <= 0) {
        Toast.makeText(context, "Could not delete ${item.name}", Toast.LENGTH_SHORT).show()
    }
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
