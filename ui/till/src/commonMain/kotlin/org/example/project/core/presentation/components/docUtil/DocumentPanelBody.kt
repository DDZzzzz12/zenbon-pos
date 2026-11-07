package org.example.project.core.presentation.components.docUtil

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.example.project.core.data.remote.DocumentManagementApiClient
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.DocumentViewerKeys
import org.example.project.core.presentation.strings.str
import org.koin.compose.koinInject

/**
 * The body of a document panel: a spinner while [load] is loading, a localized message when it failed, and
 * the [PrintableDocumentViewer] once the bytes are there. The bytes' media type is resolved from their content
 * and [fileName] ([DocumentMimeType]), so an image or XML file gets the "no preview" state instead of a PDF error.
 */
@Composable
internal fun DocumentPanelBody(
    load: DocumentFileLoad,
    fileName: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (load) {
            DocumentFileLoad.Loading -> DocumentLoading()
            is DocumentFileLoad.Failed ->
                Text(
                    text = load.reason?.let { str(DocumentViewerKeys.loadFailedWithReason, it) } ?: str(DocumentViewerKeys.loadFailed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            is DocumentFileLoad.Loaded -> {
                val document =
                    remember(load.bytes, fileName) {
                        ViewableDocument(
                            name = fileName,
                            bytes = load.bytes,
                            mimeType = DocumentMimeType.resolve(fileName, load.bytes),
                        )
                    }
                PrintableDocumentViewer(document = document, modifier = Modifier.fillMaxSize(), onBack = onBack)
            }
        }
    }
}

/**
 * Fetches document [documentId]'s file from the server; restarts when the id changes. A failed fetch keeps the
 * reason only when the failure was not an API error (those carry no message worth showing), as before.
 */
@Composable
internal fun rememberDocumentFile(documentId: Long): DocumentFileLoad {
    val apiClient = koinInject<DocumentManagementApiClient>()
    var load by remember(documentId) { mutableStateOf<DocumentFileLoad>(DocumentFileLoad.Loading) }
    LaunchedEffect(documentId) {
        load = DocumentFileLoad.Loading
        load =
            try {
                runCatchingApi { apiClient.getDocumentFile(documentId) }.fold(
                    onSuccess = { DocumentFileLoad.Loaded(it) },
                    onFailure = { DocumentFileLoad.Failed(reason = null) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DocumentFileLoad.Failed(reason = e.message)
            }
    }
    return load
}

@Composable
private fun DocumentLoading() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = str(CommonKeys.loadingPdf),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
