package org.example.project.ui.components.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfRendererSupportTest {
    @Test
    fun magicAtStartIsAPdf() {
        assertTrue(PdfRendererSupport.hasPdfMagic("%PDF-1.7\n".encodeToByteArray()))
    }

    @Test
    fun magicAfterJunkInsideTheWindowIsAPdf() {
        val junk = ByteArray(PdfRendererSupport.MAGIC_WINDOW - 5) { 'x'.code.toByte() }
        assertTrue(PdfRendererSupport.hasPdfMagic(junk + "%PDF-".encodeToByteArray()))
    }

    @Test
    fun magicPastTheWindowIsNotAPdf() {
        val junk = ByteArray(PdfRendererSupport.MAGIC_WINDOW - 4) { 'x'.code.toByte() }
        assertFalse(PdfRendererSupport.hasPdfMagic(junk + "%PDF-".encodeToByteArray()))
    }

    @Test
    fun shortOrForeignBytesAreNotAPdf() {
        assertFalse(PdfRendererSupport.hasPdfMagic(ByteArray(0)))
        assertFalse(PdfRendererSupport.hasPdfMagic("%PDF".encodeToByteArray()))
        assertFalse(PdfRendererSupport.hasPdfMagic("<?xml version=\"1.0\"?>".encodeToByteArray()))
    }

    @Test
    fun renderHeightKeepsTheAspectRatioRounded() {
        assertEquals(849, PdfRendererSupport.renderHeightPx(600, PdfPageSize(595f, 842f)))
        assertEquals(424, PdfRendererSupport.renderHeightPx(600, PdfPageSize(842f, 595f)))
    }

    @Test
    fun renderHeightIsAtLeastOnePixel() {
        assertEquals(1, PdfRendererSupport.renderHeightPx(1, PdfPageSize(1000f, 1f)))
    }

    @Test
    fun renderableNeedsFinitePositiveSides() {
        assertTrue(PdfRendererSupport.isRenderable(PdfPageSize(595f, 842f)))
        assertFalse(PdfRendererSupport.isRenderable(PdfPageSize(0f, 842f)))
        assertFalse(PdfRendererSupport.isRenderable(PdfPageSize(595f, Float.NaN)))
        assertFalse(PdfRendererSupport.isRenderable(PdfPageSize(Float.POSITIVE_INFINITY, 842f)))
    }

    @Test
    fun pixelAreaAboveTheCapIsRejected() {
        PdfRendererSupport.requireRenderablePixelArea(8_000, 8_000)
        assertFailsWith<IllegalArgumentException> { PdfRendererSupport.requireRenderablePixelArea(8_193, 8_193) }
        assertFailsWith<IllegalArgumentException> { PdfRendererSupport.requireRenderablePixelArea(Int.MAX_VALUE, Int.MAX_VALUE) }
    }
}
