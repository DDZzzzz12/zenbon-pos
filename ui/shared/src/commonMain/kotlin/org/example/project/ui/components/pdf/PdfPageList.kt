package org.example.project.ui.components.pdf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.example.project.ui.components.PosHorizontalScroller

private val PAGE_GUTTER = 8.dp

/**
 * The pages, one below the other. At zoom 1 a page fills the width; above it the column grows and pans
 * sideways through [PosHorizontalScroller]; below it the column keeps the viewport's width and the pages sit
 * centred in it ([pdfColumnWidth]). Only composed pages (visible, plus the list's prefetch) are rendered — see [PdfPageItem].
 */
@Composable
internal fun PdfPageList(
    state: PdfViewerState,
    info: PdfDocumentInfo,
    modifier: Modifier = Modifier,
) {
    val zoom by state.zoom.collectAsState()
    val bitmaps by state.pageBitmaps.collectAsState()
    val failed by state.failedPages.collectAsState()
    val listState = rememberLazyListState()
    PdfListScrollSync(state, listState)
    BoxWithConstraints(modifier = modifier.pdfPinchZoom(state)) {
        val fitWidth = (maxWidth - PAGE_GUTTER * 2).coerceAtLeast(1.dp)
        val pageWidth = fitWidth * zoom
        val columnWidth = pdfColumnWidth(maxWidth, pageWidth, PAGE_GUTTER)
        val density = LocalDensity.current
        val fitWidthPx = with(density) { fitWidth.roundToPx() }
        PdfKeepReadingPositionOnResize(
            listState = listState,
            pageWidthPx = with(density) { pageWidth.roundToPx() },
            pageHeightPx = { index -> with(density) { (pageWidth * info.pages[index].aspectRatio()).roundToPx() } },
        )
        // The sanctioned strip: on wasm a mouse wheel can still pan a zoomed page sideways once the list is at its end,
        // and arrow buttons appear for a mouse. Vertical wheel reaches the list first, since it sits inside.
        PosHorizontalScroller(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.width(columnWidth).fillMaxHeight(),
                contentPadding = PaddingValues(PAGE_GUTTER),
                verticalArrangement = Arrangement.spacedBy(PAGE_GUTTER),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(count = info.pages.size, key = { it }) { index ->
                    val size = info.pages[index]
                    val slot =
                        PdfPageSlot(
                            pageIndex = index,
                            pageCount = info.pages.size,
                            size = size,
                            widthDp = pageWidth,
                            renderWidthPx = PdfZoom.pageRenderWidthPx(fitWidthPx, zoom, size),
                        )
                    PdfPageItem(slot = slot, bitmap = bitmaps[index], failed = index in failed, state = state)
                }
            }
        }
    }
}

/** Reports the top page to [state] as the list scrolls, and scrolls the list when [state] asks for a jump. */
@Composable
private fun PdfListScrollSync(
    state: PdfViewerState,
    listState: LazyListState,
) {
    val pendingJump by state.pendingJump.collectAsState()
    LaunchedEffect(pendingJump) {
        val target = pendingJump ?: return@LaunchedEffect
        listState.scrollToItem(target)
        state.onJumpConsumed()
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            pdfTopPageIndex(
                firstVisible = listState.firstVisibleItemIndex,
                lastVisible = lastVisibleIndex(listState),
                canScrollForward = listState.canScrollForward,
                canScrollBackward = listState.canScrollBackward,
            )
        }.distinctUntilChanged().collect(state::onFirstVisiblePage)
    }
}

/**
 * Keeps the page in view when the pages change size — a wider or narrower viewport, or a zoom. The list keeps its
 * offset in pixels, which would land in another page once the heights change; so when the page width changes, the
 * reading position of the last layout ([readingAnchor]) is re-applied against the new page height.
 *
 * Runs as a [SideEffect]: after the composition at the new width, before the list measures at it, so the list never
 * draws a frame at the stale offset. It acts only on a width change, never while the user scrolls at a stable width,
 * and reloads nothing.
 */
@Composable
private fun PdfKeepReadingPositionOnResize(
    listState: LazyListState,
    pageWidthPx: Int,
    pageHeightPx: (pageIndex: Int) -> Int,
) {
    // Read only inside the SideEffect, so writing it does not recompose.
    val laidOutWidthPx = remember { mutableIntStateOf(pageWidthPx) }
    SideEffect {
        if (laidOutWidthPx.intValue == pageWidthPx) return@SideEffect
        laidOutWidthPx.intValue = pageWidthPx
        val anchor = listState.readingAnchor() ?: return@SideEffect
        listState.requestScrollToItem(anchor.pageIndex, anchor.offsetPx(pageHeightPx(anchor.pageIndex)))
    }
}

/** The page the indicator names in the current layout, and how far into it the viewport's top sits. */
private fun LazyListState.readingAnchor(): PdfScrollAnchor? {
    val first = layoutInfo.visibleItemsInfo.firstOrNull { it.index == firstVisibleItemIndex } ?: return null
    val named =
        pdfTopPageIndex(
            firstVisible = firstVisibleItemIndex,
            lastVisible = lastVisibleIndex(this),
            canScrollForward = canScrollForward,
            canScrollBackward = canScrollBackward,
        )
    return PdfScrollAnchor.forNamedPage(named, PdfScrollAnchor.capture(first.index, firstVisibleItemScrollOffset, first.size))
}

/** Height over width, as [PdfPageItem] lays the page out. */
private fun PdfPageSize.aspectRatio(): Float = heightPt / widthPt

private fun lastVisibleIndex(listState: LazyListState): Int =
    listState.layoutInfo.visibleItemsInfo
        .lastOrNull()
        ?.index ?: 0
