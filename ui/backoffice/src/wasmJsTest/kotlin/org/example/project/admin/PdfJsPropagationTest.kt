package org.example.project.admin

import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.test.runTest
import org.example.project.ui.components.pdf.PdfSource
import org.example.project.ui.components.pdf.createPdfPageRenderer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Standing guard, in the CONSUMER, for the shared `PdfViewer`'s wasm engine (model:
 * [MoneyShimPropagationTest]). shared-ui's own `PdfJsBundlingGuardTest` proves PDF.js loads inside
 * shared-ui's test bundle; this proves it still loads when adminApp is the one bundling it — the case
 * the `:platform-money` shim once failed while compiling clean, because a dependency's `wasmJsMain/resources`
 * are never copied into a consumer's webpack directory.
 *
 * What a pass shows, from adminApp's own webpack bundle with no resource file anywhere:
 * - `pdfjs-dist` resolves by bare specifier through the dynamic `import()` (the lazily loaded chunk);
 * - the module worker chunk was emitted and answers: `open` is parsed in that worker, and a port-based
 *   `PDFWorker` never falls back to a main-thread "fake" worker, so a missing worker hangs or rejects;
 * - shared-ui's static `import "./skiko.mjs"` resolved next to adminApp's own generated module (otherwise
 *   this bundle would not build at all), and a rendered page arrives as a Compose bitmap with ink on it.
 *
 * - PDF.js's image decoders (`jbig2.wasm` for CCITT G4/JBIG2 scans) were emitted as assets into adminApp's
 *   own output and are fetched from there: without them PDF.js silently skips the image and a scanned Beleg
 *   opens as a blank page.
 *
 * The fixtures are built in code or embedded as base64, not read from a resource, for the same reason.
 */
class PdfJsPropagationTest {
    @Test
    fun pdfJsAndItsWorkerOpenADocumentFromAdminAppsBundle() =
        runTest {
            val renderer = createPdfPageRenderer()
            try {
                val info = renderer.open(PdfSource.Bytes(twoPagePdf()))
                assertEquals(2, info.pages.size)
                assertEquals(200f, info.pages[0].widthPt)
                assertEquals(300f, info.pages[0].heightPt)
            } finally {
                renderer.close()
            }
        }

    @Test
    fun aPageRendersToAComposeBitmapWithInk() =
        runTest {
            val renderer = createPdfPageRenderer()
            try {
                renderer.open(PdfSource.Bytes(twoPagePdf()))
                val bitmap = renderer.render(pageIndex = 0, widthPx = 100)
                assertEquals(100, bitmap.width)
                assertEquals(150, bitmap.height)
                val pixels = bitmap.toPixelMap()
                // The page draws a black square over its middle on a white page.
                val centre = pixels[50, 75]
                val corner = pixels[2, 2]
                assertTrue(centre.red < 0.2f && centre.green < 0.2f && centre.blue < 0.2f, "ink in the middle: $centre")
                assertTrue(corner.red > 0.8f && corner.green > 0.8f && corner.blue > 0.8f, "white page corner: $corner")
            } finally {
                renderer.close()
            }
        }

    @Test
    fun aCcittG4ScanRendersItsInkFromAdminAppsBundle() =
        runTest {
            val renderer = createPdfPageRenderer()
            try {
                renderer.open(PdfSource.Bytes(ccittG4Pdf()))
                val pixels = renderer.render(pageIndex = 0, widthPx = 200).toPixelMap()
                // A 1-bit CCITT G4 image fills the page: white with a black rectangle over x 50..149, y 25..74.
                val centre = pixels[100, 50]
                val corner = pixels[5, 5]
                assertTrue(centre.red < 0.2f && centre.green < 0.2f && centre.blue < 0.2f, "scan ink in the middle: $centre")
                assertTrue(corner.red > 0.8f && corner.green > 0.8f && corner.blue > 0.8f, "white page corner: $corner")
            } finally {
                renderer.close()
            }
        }

    /** A 200x100pt page filled by a CCITT G4 image (generated with PDFBox's CCITTFactory outside the repo). */
    @OptIn(ExperimentalEncodingApi::class)
    private fun ccittG4Pdf(): ByteArray = Base64.decode(CCITT_G4_PDF_B64)

    /** A minimal, well-formed two-page PDF (200x300pt pages, a black square on page 1) with a correct xref. */
    private fun twoPagePdf(): ByteArray {
        val content = "0 0 0 rg 50 75 100 150 re f"
        val objects =
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] /Contents 5 0 R >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] >>",
                "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            )
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        objects.forEachIndexed { index, body ->
            offsets += out.length
            out.append("${index + 1} 0 obj\n$body\nendobj\n")
        }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().encodeToByteArray()
    }

    private companion object {
        private const val CCITT_G4_PDF_B64 =
            "JVBERi0xLjYKJfbk/N8KMSAwIG9iago8PAovVHlwZSAvQ2F0YWxvZwovVmVyc2lvbiAvMS42Ci9QYWdlcyAyIDAgUgo+PgplbmRv" +
                "YmoKNSAwIG9iago8PAovTGVuZ3RoIDMxCi9GaWx0ZXIgL0ZsYXRlRGVjb2RlCj4+CnN0cmVhbQ0KeJwr5DIyMFAAQUMonZzLpe+Z" +
                "a6jgks8VyAUAYJoGRw0KZW5kc3RyZWFtCmVuZG9iago3IDAgb2JqCjw8Ci9MZW5ndGggMzMKL1R5cGUgL1hPYmplY3QKL1N1YnR5" +
                "cGUgL0ltYWdlCi9GaWx0ZXIgL0NDSVRURmF4RGVjb2RlCi9CaXRzUGVyQ29tcG9uZW50IDEKL1dpZHRoIDIwMAovSGVpZ2h0IDEw" +
                "MAovQ29sb3JTcGFjZSAvRGV2aWNlR3JheQovRGVjb2RlUGFybXMgOCAwIFIKPj4Kc3RyZWFtDQr///+VMDw1P///////////////" +
                "/////////H///+ACACANCmVuZHN0cmVhbQplbmRvYmoKOSAwIG9iago8PAovTGVuZ3RoIDE2OQovVHlwZSAvT2JqU3RtCi9OIDUK" +
                "L0ZpbHRlciAvRmxhdGVEZWNvZGUKL0ZpcnN0IDI3Cj4+CnN0cmVhbQ0KeJxVjk8LgkAQxb/K+wK1/zQTxEOeIqKQDoF4MF3CSDdc" +
                "pfr2za6H6jADb2be/J4Eh0IgEUCoGCuIMMYaIgqRJOz0fmiwY3XVFmzXNhaFIkOOEiwzUz9CIE3/L8H2ummrjXmh4EsOV5K7Lnwv" +
                "3dmgySv9K5Zra6ahJkQwDzLTj7S3CL2eAefD5abrkRJ+Z9tOIPrRmblPXW8djt6ap3VICo6Fj/kBbGk7zg0KZW5kc3RyZWFtCmVu" +
                "ZG9iagoxMCAwIG9iago8PAovTGVuZ3RoIDM5Ci9Sb290IDEgMCBSCi9JRCBbPDJCMDc4Q0YxRTA3QzlFMjVDQjRBNERFMUUwMDc0" +
                "OTBDNkE2NDRCMTgzM0I2NkY1OERFREMyNTFFNDc1NUM0RUM+IDwyQjA3OENGMUUwN0M5RTI1Q0I0QTRERTFFMDA3NDkwQzZBNjQ0" +
                "QjE4MzNCNjZGNThERURDMjUxRTQ3NTVDNEVDPl0KL1R5cGUgL1hSZWYKL1NpemUgMTEKL0luZGV4IFswIDEwXQovVyBbMSAyIDFd" +
                "Ci9GaWx0ZXIgL0ZsYXRlRGVjb2RlCj4+CnN0cmVhbQ0KeJxjYGD4z8jAz8DEwAnCjEDMxMjgB2IzMzJsA9EsjIwzGAA53ALxDQpl" +
                "bmRzdHJlYW0KZW5kb2JqCnN0YXJ0eHJlZgo2ODAKJSVFT0YK"
    }
}
