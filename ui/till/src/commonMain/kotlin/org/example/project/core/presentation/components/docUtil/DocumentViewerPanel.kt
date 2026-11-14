/**
 * Unified Document Viewer Panel Component
 *
 * Displays a document (PDF/image) in a slide-in side panel. PDFs render in shared-ui's PdfViewer, whose
 * toolbar carries download plus this panel's system print and IPP print actions ([PrintableDocumentViewer]).
 * Supports two modes:
 * - **Bytes mode**: Pass `documentBytes` directly (e.g., from on-the-fly PDF generation)
 * - **Document ID mode**: Pass `documentId` to fetch document from the server via API
 *
 * IPP printer loading and printing is handled internally via PdfViewerViewModel.
 *
 * @since 1.0.0
 */
package org.example.project.core.presentation.components.docUtil

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.navigation.BackHandler
import org.example.project.core.presentation.strings.keys.DocumentViewerKeys
import org.example.project.core.presentation.strings.str

/**
 * Document Viewer Panel — bytes mode.
 *
 * Use when document bytes are already available (e.g., generated formal invoice).
 *
 * @param documentBytes The document file content as ByteArray
 * @param fileName Default filename for saving / panel title
 * @param onClose Callback when user closes the viewer
 */
@Composable
fun DocumentViewerPanel(
    modifier: Modifier = Modifier,
    isVisible: Boolean,
    documentBytes: ByteArray,
    fileName: String,
    onClose: () -> Unit,
) {
    val load = remember(documentBytes) { DocumentFileLoad.Loaded(documentBytes) }
    DocumentViewerPanelFrame(modifier = modifier, isVisible = isVisible, onClose = onClose) {
        DocumentPanelBody(load = load, fileName = fileName, modifier = Modifier.fillMaxSize(), onBack = onClose)
    }
}

/**
 * Document Viewer Panel — document ID mode.
 *
 * Fetches document bytes from the server by document ID, then displays them.
 * Shows loading and error states within the panel.
 *
 * @param documentId ID of the document to fetch and display
 * @param title Panel title and save file name (default: the localized "PDF preview")
 * @param onClose Callback when user closes the viewer
 */
@Composable
fun DocumentViewerPanel(
    modifier: Modifier = Modifier,
    isVisible: Boolean,
    documentId: Long,
    title: String = str(DocumentViewerKeys.previewTitle),
    onClose: () -> Unit,
) {
    val load = rememberDocumentFile(documentId)
    DocumentViewerPanelFrame(modifier = modifier, isVisible = isVisible, onClose = onClose) {
        DocumentPanelBody(load = load, fileName = title, modifier = Modifier.fillMaxSize(), onBack = onClose)
    }
}

/**
 * The panel around a document body, shared by both modes: the scrim and the slide-in surface around [body]
 * (normally a [DocumentPanelBody], whose viewer toolbar carries close, download and the print actions).
 *
 * Internal so a host that switches between this panel and [EmbeddedDocumentViewer] can pass one body that it
 * keeps across the switch (see `FinancialDocumentsPdfPreview`) instead of each panel loading its own.
 */
@Composable
internal fun DocumentViewerPanelFrame(
    modifier: Modifier = Modifier,
    isVisible: Boolean,
    onClose: () -> Unit,
    body: @Composable () -> Unit,
) {
    BackHandler {
        onClose()
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Scrim/overlay background
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClose,
                        ),
            )

            // Side panel surface. Drawn edge to edge on Android, so the viewer toolbar would sit under the
            // status bar and its buttons could not be tapped: the body starts below it. The surface still
            // paints behind the bar. A no-op wherever the status bar is not an inset (desktop, browser) or
            // an ancestor already consumed it.
            Surface(
                modifier = modifier.align(Alignment.CenterEnd),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
            ) {
                Box(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))) {
                    body()
                }
            }
        }
    }
}
