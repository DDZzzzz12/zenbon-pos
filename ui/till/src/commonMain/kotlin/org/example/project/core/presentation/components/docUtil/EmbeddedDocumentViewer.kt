package org.example.project.core.presentation.components.docUtil

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Inline (non-overlay) PDF viewer for embedding in Row/Column layouts.
 *
 * Unlike [DocumentViewerPanel], this composable has no scrim, no slide-in animation,
 * and no overlay behavior. It renders the PDF content directly within its parent layout,
 * with the same toolbar actions (download, system print, IPP print, close).
 *
 * @param documentId ID of the document to fetch and display
 * @param title Display title / filename for save operations
 * @param modifier Layout modifier
 */
@Composable
fun EmbeddedDocumentViewer(
    documentId: Long,
    title: String,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
) {
    val load = rememberDocumentFile(documentId)
    EmbeddedDocumentFrame(modifier = modifier) {
        DocumentPanelBody(load = load, fileName = title, modifier = Modifier.fillMaxSize(), onBack = onClose)
    }
}

/**
 * The inline surface around a document body (normally a [DocumentPanelBody]). Internal so a host that switches
 * between this and [DocumentViewerPanel] can pass one body that it keeps across the switch.
 */
@Composable
internal fun EmbeddedDocumentFrame(
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        tonalElevation = 1.dp,
    ) {
        body()
    }
}
