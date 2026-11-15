package com.docscanai.ui.screens.export

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.docscanai.core.di.ServiceLocator
import com.docscanai.data.PageSize
import com.docscanai.pdf.PdfExportOptions
import com.docscanai.pdf.PdfExportResult
import com.docscanai.ui.components.PdfIntents
import com.docscanai.ui.session.ScanSessionViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * PDF options + save (TECHNICAL_SPEC.md §6.1/§8/§9).
 *
 * Lets the user pick a file name, page size (A4/Letter/Fit), margins and quality, then runs
 * [ServiceLocator.pdfExporter] off the main thread (indeterminate progress) and shows the saved
 * location with **Open** / **Share** actions. Empty-session and error states are handled explicitly;
 * after a successful export the user can start a **New scan** (clears the session) or go **Back to
 * pages**.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportPdfScreen(
    viewModel: ScanSessionViewModel,
    onBack: () -> Unit,
    onNewScan: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val document by viewModel.document.collectAsStateWithLifecycle()

    var fileName by rememberSaveable { mutableStateOf(defaultExportFileName()) }
    var pageSize by rememberSaveable { mutableStateOf(PageSize.A4) }
    var marginsEnabled by rememberSaveable { mutableStateOf(true) }
    var quality by rememberSaveable { mutableStateOf(DEFAULT_QUALITY) }
    var exporting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<PdfExportResult?>(null) }

    fun startExport() {
        if (exporting) return
        val pages = document.pages
        if (pages.isEmpty()) return
        exporting = true
        result = null
        val options = PdfExportOptions(
            pageSize = pageSize,
            quality = quality,
            fileName = fileName,
            marginPt = if (marginsEnabled && pageSize != PageSize.AUTO) DEFAULT_MARGIN_PT else 0f
        )
        scope.launch {
            result = ServiceLocator.pdfExporter.exportAndPublish(pages, options)
            exporting = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export PDF") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (document.pages.isEmpty()) {
            EmptyExportState(
                modifier = Modifier.fillMaxSize().padding(padding),
                onAddPages = onBack
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("File name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Page size", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectableChip("A4", pageSize == PageSize.A4) { pageSize = PageSize.A4 }
                        SelectableChip("Letter", pageSize == PageSize.LETTER) { pageSize = PageSize.LETTER }
                        SelectableChip("Fit", pageSize == PageSize.AUTO) { pageSize = PageSize.AUTO }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Add margins", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Centred margins on A4/Letter. \"Fit\" uses the full image.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = marginsEnabled && pageSize != PageSize.AUTO,
                        onCheckedChange = { marginsEnabled = it },
                        enabled = pageSize != PageSize.AUTO
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Quality", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectableChip("High", quality == 90) { quality = 90 }
                        SelectableChip("Medium", quality == 70) { quality = 70 }
                        SelectableChip("Low", quality == 50) { quality = 50 }
                    }
                }

                Text(
                    text = if (document.pageCount == 1) "1 page  •  ${pageSizeLabel(pageSize)}"
                    else "${document.pageCount} pages  •  ${pageSizeLabel(pageSize)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (exporting) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Building PDF…", style = MaterialTheme.typography.bodyMedium)
                        }
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    Button(onClick = { startExport() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Export PDF")
                    }
                }

                when (val r = result) {
                    is PdfExportResult.Success -> ExportSuccessCard(
                        result = r,
                        onOpen = { PdfIntents.openPdf(context, r.uri) },
                        onShare = { PdfIntents.sharePdf(context, r.uri) },
                        onNewScan = onNewScan,
                        onBackToPages = onBack,
                        onOpenLibrary = onOpenLibrary
                    )
                    is PdfExportResult.Failure -> ExportErrorCard(
                        message = r.message,
                        onRetry = { startExport() }
                    )
                    null -> Unit
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun EmptyExportState(modifier: Modifier = Modifier, onAddPages: () -> Unit) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        Icon(
            imageVector = Icons.Filled.PictureAsPdf,
            contentDescription = null,
            modifier = Modifier.size(48.dp)
        )
        Text("Nothing to export", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Capture at least one page before exporting a PDF.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Button(onClick = onAddPages) { Text("Add pages") }
    }
}

@Composable
private fun ExportSuccessCard(
    result: PdfExportResult.Success,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onNewScan: () -> Unit,
    onBackToPages: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("PDF saved", style = MaterialTheme.typography.titleMedium)
            Text(
                text = result.displayPath,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${result.pageCount} page(s)  •  ${PdfIntents.formatFileSize(result.byteSize)}" +
                    if (result.isAppPrivate) "  •  app-private storage" else "",
                style = MaterialTheme.typography.bodySmall
            )
            if (result.isAppPrivate) {
                Text(
                    text = "Saved to app storage because public storage was unavailable. " +
                        "Use Open or Share to reach the file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open")
                }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
            }
            OutlinedButton(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Collections, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open library")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onNewScan, modifier = Modifier.weight(1f)) { Text("New scan") }
                OutlinedButton(onClick = onBackToPages, modifier = Modifier.weight(1f)) {
                    Text("Back to pages")
                }
            }
        }
    }
}

@Composable
private fun ExportErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Export failed", style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun SelectableChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

private fun pageSizeLabel(size: PageSize): String = when (size) {
    PageSize.A4 -> "A4"
    PageSize.LETTER -> "Letter"
    PageSize.AUTO -> "Fit"
}

private fun defaultExportFileName(): String =
    "Scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

/** Default centred margin for A4/Letter pages, in PDF points (1/72"), ≈ 6.35 mm. */
private const val DEFAULT_MARGIN_PT = 18f
private const val DEFAULT_QUALITY = 90
