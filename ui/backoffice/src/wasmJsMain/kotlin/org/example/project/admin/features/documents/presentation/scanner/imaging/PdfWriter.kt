package org.example.project.admin.features.documents.presentation.scanner.imaging

/**
 * Minimal PDF 1.4 writer for scans: one image-only page per [PdfPage], JPEG embedded as-is
 * (`/DCTDecode`), no fonts, no compression of our own. Object layout is fixed so offsets are easy
 * to audit: 1 catalog, 2 page tree, then per page `3+3i` page, `4+3i` content, `5+3i` image.
 * Page size is the pixel size at [DPI]. Header is pure ASCII so tests can read bytes as text.
 */
object PdfWriter {
    const val DPI = 150
    const val DEFAULT_MAX_BYTES = 18 * 1024 * 1024

    fun write(
        pages: List<PdfPage>,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): PdfWriteResult {
        require(pages.isNotEmpty()) { "a PDF needs at least one page" }
        val out = ByteSink()
        val offsets = mutableListOf<Int>()

        fun obj(
            n: Int,
            body: () -> Unit,
        ) {
            check(n == offsets.size + 1) { "objects must be written in order" }
            offsets += out.size
            out.ascii("$n 0 obj\n")
            body()
            out.ascii("endobj\n")
        }

        out.ascii("%PDF-1.4\n")
        obj(1) { out.ascii("<< /Type /Catalog /Pages 2 0 R >>\n") }
        val kids = pages.indices.joinToString(" ") { "${3 + 3 * it} 0 R" }
        obj(2) { out.ascii("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>\n") }
        pages.forEachIndexed { i, p ->
            val wPt = p.widthPx * 72.0 / DPI
            val hPt = p.heightPx * 72.0 / DPI
            val content = "q $wPt 0 0 $hPt 0 0 cm /Im0 Do Q"
            obj(3 + 3 * i) {
                out.ascii(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $wPt $hPt] " +
                        "/Resources << /XObject << /Im0 ${5 + 3 * i} 0 R >> >> /Contents ${4 + 3 * i} 0 R >>\n",
                )
            }
            obj(4 + 3 * i) {
                out.ascii("<< /Length ${content.length} >>\nstream\n$content\nendstream\n")
            }
            obj(5 + 3 * i) {
                out.ascii(
                    "<< /Type /XObject /Subtype /Image /Width ${p.widthPx} /Height ${p.heightPx} " +
                        "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${p.jpeg.size} >>\nstream\n",
                )
                out.bytes(p.jpeg)
                out.ascii("\nendstream\n")
            }
        }
        val xrefAt = out.size
        out.ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.ascii("${it.toString().padStart(10, '0')} 00000 n \n") }
        out.ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xrefAt\n%%EOF\n")

        return if (out.size > maxBytes) PdfWriteResult.TooLarge(out.size) else PdfWriteResult.Success(out.toByteArray())
    }
}
