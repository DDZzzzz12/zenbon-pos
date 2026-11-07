package org.example.project.ui.components.pdf

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.PdfViewerKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.FileSaver
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosSecondaryButton

private const val PDF_MIME_TYPE = "application/pdf"

/**
 * Shows a PDF on every platform, painted entirely in Compose: a toolbar (pages, zoom, download, [extraActions],
 * close) over a lazily rendered page list with pinch-to-zoom. [extraActions] receive `enabled`, true only once the
 * document is open: a host's print or open-in-tab must not act on a document that is loading or failed to open.
 *
 * The viewer owns its renderer: a new [source] gets a new renderer, opened once, and the renderer is closed when
 * the source changes or the viewer leaves composition. [source] is compared by identity, so a caller must
 * `remember` it — building a fresh `PdfSource` on every recomposition would reopen the document each time.
 * Download saves the document's own bytes as [fileName] via [FileSaver], with `.pdf` appended when [fileName] lacks it
 * ([pdfDownloadFileName]); a failure (not a cancelled dialog)
 * is raised in the host's global notification queue ([LocalGlobalOverlayViewModel]). A document that cannot be opened shows one localized message per [PdfLoadError], with [onClose]
 * offered as the way out when given. A transient failure ([isRetryable]) also offers *Retry*, which opens the
 * same source again with a fresh renderer.
 */
@Composable
fun PdfViewer(
    source: PdfSource,
    fileName: String,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    extraActions: @Composable RowScope.(enabled: Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val notifyDownloadFailed by rememberUpdatedState(downloadFailedNotifier())
    // A retry needs a new state: a PdfViewerState opens its source exactly once.
    var attempt by remember(source) { mutableIntStateOf(0) }
    val state = remember(source, attempt) { PdfViewerState(createPdfPageRenderer(), scope) { notifyDownloadFailed() } }
    DisposableEffect(state) {
        state.open(source)
        onDispose { state.dispose() }
    }
    val loadState by state.loadState.collectAsState()
    Column(modifier = modifier.pdfPressClearsFocus(LocalFocusManager.current)) {
        PdfViewerToolbar(
            state = state,
            onDownload = { state.download { bytes, onFailed -> saveDocument(bytes, pdfDownloadFileName(fileName), onFailed) } },
            onClose = onClose,
            extraActions = extraActions,
        )
        HorizontalDivider()
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (val current = loadState) {
                PdfLoadState.Loading -> PdfLoading()
                is PdfLoadState.Failed -> PdfLoadFailure(current.error, onClose, onRetry = { attempt++ })
                is PdfLoadState.Ready -> PdfPageList(state, current.info, Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * A press anywhere in the viewer that no control handled clears focus when the focus is inside the viewer, so the
 * page field drops an unconfirmed number ([PdfPageJumpEdit.focusLost]) as soon as the user clicks the page or the
 * background. Focus elsewhere on the screen (a form beside the viewer) is left alone.
 */
@Composable
private fun Modifier.pdfPressClearsFocus(focusManager: FocusManager): Modifier {
    var focusInside by remember { mutableStateOf(false) }
    return this
        .pdfOnUnclaimedPress(focusManager) { if (focusInside) focusManager.clearFocus() }
        .onFocusChanged { focusInside = it.hasFocus }
        .focusGroup()
}

/** A cancelled save dialog is not a failure: only [FileSaver]'s `onError` reaches [onFailed]. */
private fun saveDocument(
    bytes: ByteArray,
    fileName: String,
    onFailed: () -> Unit,
) {
    FileSaver.saveFile(
        data = bytes,
        filename = fileName,
        mimeType = PDF_MIME_TYPE,
        onError = { onFailed() },
        onCancelled = {},
    )
}

/** Raises a failed download as an error toast in the host's global notification queue. */
@Composable
private fun downloadFailedNotifier(): () -> Unit {
    val overlay = LocalGlobalOverlayViewModel.current
    val title = str(PdfViewerKeys.downloadFailed)
    val message = str(PdfViewerKeys.downloadFailedMessage)
    return { overlay.showError(title = title, message = message, displayMode = DisplayMode.TOAST) }
}

@Composable
private fun PdfLoading() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(text = str(PdfViewerKeys.loading), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PdfLoadFailure(
    error: PdfLoadError,
    onClose: (() -> Unit)?,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(24.dp).widthIn(max = 480.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = str(error.messageKey()),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (error.isRetryable()) {
            PosButton(text = str(CommonKeys.retry), onClick = onRetry)
        }
        if (onClose != null) {
            PosSecondaryButton(text = str(CommonKeys.close), onClick = onClose)
        }
    }
}
