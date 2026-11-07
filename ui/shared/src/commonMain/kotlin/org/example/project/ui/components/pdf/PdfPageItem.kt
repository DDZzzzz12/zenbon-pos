package org.example.project.ui.components.pdf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.PdfViewerKeys
import org.example.project.core.presentation.strings.str

/**
 * One page: a placeholder of the page's exact size (so nothing jumps when the bitmap lands) showing the latest
 * [bitmap] scaled to fit. Asks [state] for a render at the slot's width while composed and releases the page when
 * it leaves composition, which cancels a render that has not finished.
 */
@Composable
internal fun PdfPageItem(
    slot: PdfPageSlot,
    bitmap: ImageBitmap?,
    failed: Boolean,
    state: PdfViewerState,
) {
    LaunchedEffect(slot.pageIndex, slot.renderWidthPx) { state.requestRender(slot.pageIndex, slot.renderWidthPx) }
    DisposableEffect(slot.pageIndex) { onDispose { state.releasePage(slot.pageIndex) } }

    val label = str(PdfViewerKeys.pageOf, slot.pageIndex + 1, slot.pageCount)
    Box(
        modifier =
            Modifier
                .size(slot.widthDp, slot.widthDp * (slot.size.heightPt / slot.size.widthPt))
                // Paper is white in either theme: the PDF itself is drawn on white.
                .background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        PdfPageContent(bitmap, failed)
    }
}

@Composable
private fun PdfPageContent(
    bitmap: ImageBitmap?,
    failed: Boolean,
) {
    when {
        bitmap != null ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        failed ->
            Text(
                text = str(PdfViewerKeys.pageRenderFailed),
                modifier = Modifier.padding(16.dp),
                color = Color.DarkGray,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        else -> CircularProgressIndicator(modifier = Modifier.size(32.dp), color = Color.Gray)
    }
}
