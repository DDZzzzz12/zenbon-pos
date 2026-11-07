package org.example.project.ui.components.pdf

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.PdfViewerKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosHorizontalScroller
import org.example.project.ui.components.PosIconButton

/**
 * The viewer's toolbar: page navigation with a jump-to-page field, zoom, download and the host's
 * [extraActions]. The controls scroll sideways on a narrow screen (their scroll arrows sit beside them, never
 * over the page field); close stays pinned at the end. Until the document is open ([PdfToolbarControls]) every
 * document control is disabled, the page indicator is not shown and [extraActions] receive `enabled = false`.
 */
@Composable
internal fun PdfViewerToolbar(
    state: PdfViewerState,
    onDownload: () -> Unit,
    onClose: (() -> Unit)?,
    extraActions: @Composable RowScope.(enabled: Boolean) -> Unit,
) {
    val loadState by state.loadState.collectAsState()
    val currentPage by state.currentPage.collectAsState()
    val zoom by state.zoom.collectAsState()
    val downloading by state.downloading.collectAsState()
    val controls = PdfToolbarControls.of(loadState, currentPage, zoom, downloading)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosHorizontalScroller(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            arrowsBesideContent = true,
        ) {
            PdfPageNavigation(state, currentPage, controls)
            Spacer(Modifier.width(8.dp))
            PdfZoomControls(state, controls)
            PosIconButton(
                icon = Icons.Filled.Download,
                contentDescription = str(PdfViewerKeys.download),
                onClick = onDownload,
                enabled = controls.downloadEnabled,
            )
            extraActions(controls.actionsEnabled)
        }
        if (onClose != null) {
            PosIconButton(icon = Icons.Filled.Close, contentDescription = str(CommonKeys.close), onClick = onClose)
        }
    }
}

@Composable
private fun PdfPageNavigation(
    state: PdfViewerState,
    currentPage: Int,
    controls: PdfToolbarControls,
) {
    PosIconButton(
        icon = Icons.Filled.KeyboardArrowUp,
        contentDescription = str(PdfViewerKeys.previousPage),
        onClick = { state.requestJump(currentPage - 1) },
        enabled = controls.previousEnabled,
    )
    if (controls.showPageIndicator) {
        PdfPageJumpField(currentPage = currentPage, pageCount = controls.pageCount, onJump = state::requestJump)
    }
    PosIconButton(
        icon = Icons.Filled.KeyboardArrowDown,
        contentDescription = str(PdfViewerKeys.nextPage),
        onClick = { state.requestJump(currentPage + 1) },
        enabled = controls.nextEnabled,
    )
}

@Composable
private fun PdfZoomControls(
    state: PdfViewerState,
    controls: PdfToolbarControls,
) {
    PosIconButton(
        icon = Icons.Filled.ZoomOut,
        contentDescription = str(PdfViewerKeys.zoomOut),
        onClick = state::zoomOut,
        enabled = controls.zoomOutEnabled,
    )
    PosIconButton(
        icon = Icons.Filled.ZoomIn,
        contentDescription = str(PdfViewerKeys.zoomIn),
        onClick = state::zoomIn,
        enabled = controls.zoomInEnabled,
    )
    PosIconButton(
        icon = Icons.Filled.FitScreen,
        contentDescription = str(PdfViewerKeys.fitWidth),
        onClick = state::fitWidth,
        enabled = controls.fitWidthEnabled,
    )
}
