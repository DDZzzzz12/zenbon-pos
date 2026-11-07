package org.example.project.ui.components.pdf

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Scanned receipts are images, and PDF.js decodes the usual scanner compressions — CCITT G4 and JBIG2
 * (`jbig2.wasm`) and JPEG 2000 (`openjpeg.wasm`) — with wasm decoders it fetches at runtime. If it cannot
 * reach them it does not fail: it logs a warning and SKIPS the image, so a scanned Beleg opens as a blank white
 * page. These assert the image's own ink arrives in the bitmap. (No JBIG2 fixture: nothing in reach encodes
 * one, but JBIG2 and CCITT share the one `jbig2.wasm` decoder this CCITT case loads.)
 */
class PdfJsImageDecoderTest {
    private suspend fun renderPage(bytes: ByteArray): ImageBitmap {
        val renderer = createPdfPageRenderer()
        try {
            renderer.open(PdfSource.Bytes(bytes))
            return renderer.render(pageIndex = 0, widthPx = 200)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun ccittG4ScanRendersItsInk() =
        runTest {
            val pixels = renderPage(PdfFixtures.ccittG4).toPixelMap()
            val centre = pixels[100, 50]
            val corner = pixels[5, 5]
            assertTrue(centre.red < 0.2f && centre.green < 0.2f && centre.blue < 0.2f, "black rectangle missing: $centre")
            assertTrue(corner.red > 0.8f && corner.green > 0.8f && corner.blue > 0.8f, "white page corner: $corner")
        }

    @Test
    fun jpegTwoThousandImageRendersItsColour() =
        runTest {
            val pixels = renderPage(PdfFixtures.jpx).toPixelMap()
            val centre = pixels[100, 50]
            val corner = pixels[5, 5]
            assertTrue(centre.red > 0.8f && centre.green < 0.25f && centre.blue < 0.25f, "red rectangle missing: $centre")
            assertTrue(corner.red > 0.8f && corner.green > 0.8f && corner.blue > 0.8f, "white page corner: $corner")
        }
}
