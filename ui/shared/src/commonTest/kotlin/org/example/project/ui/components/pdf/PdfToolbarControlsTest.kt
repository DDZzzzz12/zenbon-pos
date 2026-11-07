package org.example.project.ui.components.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfToolbarControlsTest {
    private val twoPages = PdfLoadState.Ready(PdfDocumentInfo(listOf(PdfPageSize(595f, 842f), PdfPageSize(595f, 842f))))

    private fun assertAllOff(controls: PdfToolbarControls) {
        assertEquals(PdfToolbarControls.Disabled, controls)
        assertFalse(controls.showPageIndicator, "no '1 / 0' while there is no document")
        assertFalse(controls.previousEnabled)
        assertFalse(controls.nextEnabled)
        assertFalse(controls.zoomOutEnabled)
        assertFalse(controls.zoomInEnabled)
        assertFalse(controls.fitWidthEnabled)
        assertFalse(controls.downloadEnabled)
        assertFalse(controls.actionsEnabled, "a host's print / open-in-tab waits for an open document")
    }

    @Test
    fun whileLoadingEveryDocumentControlIsOff() {
        assertAllOff(PdfToolbarControls.of(PdfLoadState.Loading, currentPage = 1, zoom = PdfZoom.FIT_WIDTH, downloading = false))
    }

    @Test
    fun aFailedOpenTurnsEveryDocumentControlOff() {
        PdfLoadError.entries.forEach { error ->
            assertAllOff(PdfToolbarControls.of(PdfLoadState.Failed(error), currentPage = 1, zoom = 2f, downloading = false))
        }
    }

    @Test
    fun anOpenDocumentOnItsFirstPageOffersNextButNotPrevious() {
        val controls = PdfToolbarControls.of(twoPages, currentPage = 1, zoom = PdfZoom.FIT_WIDTH, downloading = false)
        assertTrue(controls.showPageIndicator)
        assertEquals(2, controls.pageCount)
        assertFalse(controls.previousEnabled)
        assertTrue(controls.nextEnabled)
        assertTrue(controls.zoomInEnabled)
        assertTrue(controls.zoomOutEnabled)
        assertFalse(controls.fitWidthEnabled, "already at fit width")
        assertTrue(controls.downloadEnabled)
        assertTrue(controls.actionsEnabled)
    }

    @Test
    fun onTheLastPageNextIsOffAndPreviousOn() {
        val controls = PdfToolbarControls.of(twoPages, currentPage = 2, zoom = PdfZoom.FIT_WIDTH, downloading = false)
        assertTrue(controls.previousEnabled)
        assertFalse(controls.nextEnabled)
    }

    @Test
    fun zoomLimitsTurnTheirButtonOff() {
        val atMax = PdfToolbarControls.of(twoPages, currentPage = 1, zoom = PdfZoom.MAX, downloading = false)
        val atMin = PdfToolbarControls.of(twoPages, currentPage = 1, zoom = PdfZoom.MIN, downloading = false)
        assertFalse(atMax.zoomInEnabled)
        assertTrue(atMax.fitWidthEnabled)
        assertFalse(atMin.zoomOutEnabled)
    }

    @Test
    fun aRunningDownloadDisablesOnlyTheDownload() {
        val controls = PdfToolbarControls.of(twoPages, currentPage = 1, zoom = PdfZoom.FIT_WIDTH, downloading = true)
        assertFalse(controls.downloadEnabled)
        assertTrue(controls.actionsEnabled)
    }
}
