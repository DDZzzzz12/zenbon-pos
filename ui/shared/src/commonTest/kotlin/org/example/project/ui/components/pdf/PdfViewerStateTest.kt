package org.example.project.ui.components.pdf

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PdfViewerStateTest {
    private val source = PdfSource.Bytes(byteArrayOf(1))

    private fun TestScope.openedState(
        renderer: FakePdfPageRenderer = FakePdfPageRenderer(),
        onDownloadFailed: () -> Unit = {},
    ): PdfViewerState {
        val state = PdfViewerState(renderer, this, onDownloadFailed)
        state.open(source)
        advanceUntilIdle()
        return state
    }

    @Test
    fun startsLoading() =
        runTest {
            val state = PdfViewerState(FakePdfPageRenderer(), this)
            assertEquals(PdfLoadState.Loading, state.loadState.value)
        }

    @Test
    fun loadSuccessSetsReadyWithPageCount() =
        runTest {
            val state = openedState()
            val ready = state.loadState.value as PdfLoadState.Ready
            assertEquals(3, ready.info.pages.size)
        }

    @Test
    fun corruptDocumentSetsFailedCorrupt() =
        runTest {
            val state = openedState(FakePdfPageRenderer(openFailure = PdfLoadException(PdfLoadError.Corrupt)))
            assertEquals(PdfLoadState.Failed(PdfLoadError.Corrupt), state.loadState.value)
        }

    @Test
    fun unexpectedOpenFailureIsReportedAsCorrupt() =
        runTest {
            val state = openedState(FakePdfPageRenderer(openFailure = IllegalStateException("boom")))
            assertEquals(PdfLoadState.Failed(PdfLoadError.Corrupt), state.loadState.value)
        }

    @Test
    fun openIsCalledOnlyOnce() =
        runTest {
            val renderer = FakePdfPageRenderer()
            val state = openedState(renderer)
            state.open(source)
            advanceUntilIdle()
            assertEquals(1, renderer.openCalls)
        }

    @Test
    fun zoomInTenTimesStopsAtMax() =
        runTest {
            val state = openedState()
            repeat(10) { state.zoomIn() }
            assertEquals(PdfZoom.MAX, state.zoom.value)
        }

    @Test
    fun zoomOutStopsAtMinAndFitWidthResets() =
        runTest {
            val state = openedState()
            repeat(10) { state.zoomOut() }
            assertEquals(PdfZoom.MIN, state.zoom.value)
            state.fitWidth()
            assertEquals(PdfZoom.FIT_WIDTH, state.zoom.value)
        }

    @Test
    fun setZoomClamps() =
        runTest {
            val state = openedState()
            state.setZoom(100f)
            assertEquals(PdfZoom.MAX, state.zoom.value)
        }

    @Test
    fun jumpPastLastPageClampsToLastPage() =
        runTest {
            val state = openedState()
            state.requestJump(99)
            assertEquals(3, state.currentPage.value)
            assertEquals(2, state.pendingJump.value)
        }

    @Test
    fun jumpToZeroClampsToFirstPage() =
        runTest {
            val state = openedState()
            state.requestJump(0)
            assertEquals(1, state.currentPage.value)
            assertEquals(0, state.pendingJump.value)
        }

    /** A two-page document that fits the viewport: the list cannot scroll, so it never reports a new top page. */
    @Test
    fun jumpOnADocumentThatFitsKeepsTheTargetAsTheCurrentPage() =
        runTest {
            val state = openedState(FakePdfPageRenderer(pages = List(2) { PdfPageSize(595f, 842f) }))
            state.requestJump(12)
            state.onJumpConsumed()
            assertEquals(2, state.currentPage.value)
        }

    @Test
    fun consumedJumpIsCleared() =
        runTest {
            val state = openedState()
            state.requestJump(2)
            state.onJumpConsumed()
            assertNull(state.pendingJump.value)
        }

    @Test
    fun firstVisiblePageIndexBecomesOneBasedCurrentPage() =
        runTest {
            val state = openedState()
            state.onFirstVisiblePage(1)
            assertEquals(2, state.currentPage.value)
        }

    @Test
    fun firstRenderHasNoSettleDelay() =
        runTest {
            val state = openedState()
            state.requestRender(0, 800)
            runCurrent()
            assertEquals(800, state.pageBitmaps.value[0]?.width)
        }

    @Test
    fun cachedRenderIsNotRepeated() =
        runTest {
            val renderer = FakePdfPageRenderer()
            val state = openedState(renderer)
            state.requestRender(0, 800)
            advanceUntilIdle()
            state.releasePage(0)
            state.requestRender(0, 800)
            advanceUntilIdle()
            assertEquals(1, renderer.renderRequests.size)
            assertNotNull(state.pageBitmaps.value[0])
        }

    @Test
    fun previousBitmapStaysVisibleUntilZoomedRenderLands() =
        runTest {
            val renderer = FakePdfPageRenderer()
            val state = openedState(renderer)
            state.requestRender(0, 800)
            advanceUntilIdle()
            renderer.renderGate = CompletableDeferred()
            state.requestRender(0, 1000)
            advanceUntilIdle()
            assertEquals(800, state.pageBitmaps.value[0]?.width)
            renderer.renderGate?.complete(Unit)
            advanceUntilIdle()
            assertEquals(1000, state.pageBitmaps.value[0]?.width)
        }

    @Test
    fun zoomChangeCancelsStaleRenderAndDebounces() =
        runTest {
            val renderer = FakePdfPageRenderer()
            val state = openedState(renderer)
            state.requestRender(0, 800)
            advanceUntilIdle()
            renderer.renderGate = CompletableDeferred()
            state.requestRender(0, 1000)
            advanceUntilIdle()
            state.requestRender(0, 1100)
            state.requestRender(0, 1200)
            advanceTimeBy(PdfViewerState.ZOOM_SETTLE_MS / 2)
            runCurrent()
            // 1000 was cancelled while waiting on the gate; 1100 never started (debounced away).
            assertEquals(listOf(0 to 800, 0 to 1000), renderer.renderRequests)
            renderer.renderGate?.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(0 to 800, 0 to 1200), renderer.completedRenders)
            assertEquals(1200, state.pageBitmaps.value[0]?.width)
        }

    @Test
    fun releasedPageCancelsItsRenderAndDropsItsBitmap() =
        runTest {
            val renderer = FakePdfPageRenderer()
            renderer.renderGate = CompletableDeferred()
            val state = openedState(renderer)
            state.requestRender(1, 800)
            runCurrent()
            state.releasePage(1)
            renderer.renderGate?.complete(Unit)
            advanceUntilIdle()
            assertTrue(renderer.completedRenders.isEmpty())
            assertNull(state.pageBitmaps.value[1])
        }

    @Test
    fun renderFailureMarksPageFailedWithoutFailingTheDocument() =
        runTest {
            val renderer = FakePdfPageRenderer(pages = listOf(PdfPageSize(595f, 842f)))
            val state = openedState(renderer)
            state.requestRender(3, 800)
            advanceUntilIdle()
            assertTrue(3 in state.failedPages.value)
            assertTrue(state.loadState.value is PdfLoadState.Ready)
        }

    @Test
    fun downloadHandsDocumentBytesToSaver() =
        runTest {
            var failures = 0
            val state = openedState(onDownloadFailed = { failures++ })
            var saved: ByteArray? = null
            state.download { bytes, _ -> saved = bytes }
            advanceUntilIdle()
            assertEquals(5, saved?.size)
            assertEquals(0, failures)
            assertFalse(state.downloading.value)
        }

    @Test
    fun cancelledSaveIsNotReportedAsFailure() =
        runTest {
            var failures = 0
            val state = openedState(onDownloadFailed = { failures++ })
            // A cancelled dialog: the saver returns without calling onFailed (FileSaver's onCancelled path).
            state.download { _, _ -> }
            advanceUntilIdle()
            assertEquals(0, failures)
        }

    @Test
    fun saverErrorIsReportedOnce() =
        runTest {
            var failures = 0
            val state = openedState(onDownloadFailed = { failures++ })
            state.download { _, onFailed ->
                onFailed()
                onFailed()
            }
            advanceUntilIdle()
            assertEquals(1, failures)
        }

    @Test
    fun readFailureIsReportedOnceAndDownloadCanBeRetried() =
        runTest {
            var failures = 0
            val state =
                openedState(FakePdfPageRenderer(readFailure = PdfLoadException(PdfLoadError.Network)), onDownloadFailed = { failures++ })
            state.download { _, _ -> error("must not be called") }
            advanceUntilIdle()
            assertEquals(1, failures)
            assertFalse(state.downloading.value)
        }

    @Test
    fun disposeClosesRendererOnce() =
        runTest {
            val renderer = FakePdfPageRenderer()
            val state = openedState(renderer)
            state.dispose()
            assertEquals(1, renderer.closeCalls)
        }

    @Test
    fun disposeDuringOpenCancelsItAndNeverReportsReady() =
        runTest {
            val renderer = FakePdfPageRenderer()
            renderer.openGate = CompletableDeferred()
            val state = PdfViewerState(renderer, this)
            state.open(source)
            runCurrent()
            state.dispose()
            renderer.openGate?.complete(Unit)
            advanceUntilIdle()
            assertEquals(PdfLoadState.Loading, state.loadState.value)
            assertEquals(1, renderer.closeCalls)
        }
}
