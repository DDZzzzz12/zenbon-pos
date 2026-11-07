package org.example.project.core.presentation.components.docUtil

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.DocumentManagementKeys
import org.example.project.core.presentation.strings.keys.DocumentViewerKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.FileSaver
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.pdf.PdfSource
import org.example.project.ui.components.pdf.PdfViewer

/**
 * Renders [document] with shared-ui's [PdfViewer] on every platform (Android, desktop and the browser till).
 *
 * Only a PDF ([ViewableDocument.isPdf]) is previewed, with [extraActions] in the viewer's toolbar (they receive
 * `enabled`, true only once the document is open). Any other file type shows a localized "no preview" message
 * with *Save* (the file in its own media type) and [onBack], so the user is never stranded on a blank panel.
 *
 * @param document The [ViewableDocument] containing file bytes and metadata
 * @param modifier Modifier for sizing and layout
 * @param onBack Called when the user closes the viewer
 * @param extraActions The host's own toolbar actions, after the viewer's download
 */
@Composable
fun DocumentFileViewer(
    document: ViewableDocument,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    extraActions: @Composable RowScope.(enabled: Boolean) -> Unit = {},
) {
    if (!document.isPdf) {
        DocumentPreviewUnavailable(document = document, modifier = modifier, onBack = onBack)
        return
    }
    // PdfViewer compares its source by identity. Keyed on the byte array's identity (remember compares keys
    // with equals, which for an array is identity), never on ViewableDocument's content equality: that would
    // hash every byte on each recomposition.
    val source = remember(document.bytes, document.name) { PdfSource.Bytes(document.bytes) }
    PdfViewer(
        source = source,
        fileName = document.name,
        modifier = modifier,
        onClose = onBack,
        extraActions = extraActions,
    )
}

@Composable
private fun DocumentPreviewUnavailable(
    document: ViewableDocument,
    modifier: Modifier,
    onBack: () -> Unit,
) {
    val save = saveInOwnTypeAction(document)
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = str(DocumentManagementKeys.noPreviewAvailable),
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PosTextButton(text = str(CommonKeys.save), onClick = save)
                PosTextButton(text = str(CommonKeys.back), onClick = onBack)
            }
        }
    }
}

/**
 * Saves [document] in its own media type, reporting the outcome in the global notification queue. A cancelled
 * save dialog is not a failure.
 */
@Composable
private fun saveInOwnTypeAction(document: ViewableDocument): () -> Unit {
    val overlay = LocalGlobalOverlayViewModel.current
    val language = LocalAppLanguage.current
    val title = str(CommonKeys.save)
    val saved = str(DocumentViewerKeys.fileSaved)
    return {
        FileSaver.saveFile(
            data = document.bytes,
            filename = document.name,
            mimeType = document.mimeType,
            onSuccess = { overlay.showSuccess(title = title, message = saved, displayMode = DisplayMode.TOAST) },
            onError = { error ->
                overlay.showWarning(
                    title = title,
                    message = getString(DocumentViewerKeys.saveFailed, language, error),
                    displayMode = DisplayMode.TOAST,
                )
            },
            onCancelled = {},
        )
    }
}
