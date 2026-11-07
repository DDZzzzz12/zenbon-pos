package org.example.project.ui.screens.financialdocs.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import org.example.project.core.presentation.components.docUtil.DocumentPanelBody
import org.example.project.core.presentation.components.docUtil.DocumentViewerPanelFrame
import org.example.project.core.presentation.components.docUtil.EmbeddedDocumentFrame
import org.example.project.core.presentation.components.docUtil.rememberDocumentFile
import org.example.project.core.presentation.strings.keys.DocumentViewerKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isAtLeastExpanded
import org.example.project.features.financialdocuments.presentation.state.KassenbuchState
import org.example.project.features.financialdocuments.presentation.viewmodel.KassenbuchViewModel

/**
 * Renders the document attached to the selected Kassenbuch entry.
 *
 * The till is view-only for documents: it shows the Beleg linked to a cash-book
 * entry so the operator can check what was booked. Invoice metadata capture and
 * the document archive live in adminApp.
 *
 * @param kassenbuchState Current Kassenbuch state
 * @param kassenbuchViewModel Kassenbuch ViewModel for clearing the selection
 * @param modifier Modifier controlling preview panel width
 */
@Composable
fun FinancialDocumentsPdfPreview(
    kassenbuchState: KassenbuchState,
    kassenbuchViewModel: KassenbuchViewModel,
    modifier: Modifier,
) {
    val documentId =
        kassenbuchState.selectedLinkedDocumentId
            ?.takeIf { kassenbuchState.showLinkedPdfViewer }
            ?: return

    val windowSize = LocalWindowSizeClass.current
    val title =
        kassenbuchState.selectedEntryDetail
            ?.linkedDocument
            ?.let { if (it.id == documentId) it.filename else null } ?: str(DocumentViewerKeys.previewTitle)

    val onClose = { kassenbuchViewModel.clearSelectedEntry() }
    // Loaded once here, above the width switch, and the body moved between the two frames rather than recreated
    // (movableContentOf): crossing 840dp (a tablet rotation) keeps the fetched file and the viewer's page, zoom
    // and scroll instead of refetching and starting again on page 1.
    val load = rememberDocumentFile(documentId)
    val currentLoad by rememberUpdatedState(load)
    val currentTitle by rememberUpdatedState(title)
    val currentOnClose by rememberUpdatedState(onClose)
    val body =
        remember {
            movableContentOf {
                DocumentPanelBody(
                    load = currentLoad,
                    fileName = currentTitle,
                    modifier = Modifier.fillMaxSize(),
                    onBack = { currentOnClose() },
                )
            }
        }

    if (windowSize.isAtLeastExpanded()) {
        EmbeddedDocumentFrame(modifier = modifier) { body() }
    } else {
        DocumentViewerPanelFrame(modifier = modifier.fillMaxWidth(), isVisible = true, onClose = onClose) { body() }
    }
}
