package org.example.project.export.datevbuchungsstapelbelege

import org.example.project.export.datevxml.PdfAttachment
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatevBuchungsstapelBelegeZipBuilderTest {
    private fun entries(zip: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                names.add(e.name)
                e = zis.nextEntry
            }
        }
        return names
    }

    @Test
    fun `zip is flat with csv document-xml and pdfs`() {
        val zip =
            DatevBuchungsstapelBelegeZipBuilder.build(
                csvBytes = "csv".toByteArray(),
                documentXmlBytes = "<archive/>".toByteArray(),
                pdfs = listOf(PdfAttachment("2026-01-02", "a.pdf", ByteArray(10))),
            )
        val names = entries(zip)
        assertEquals(setOf("EXTF_Buchungsstapel.csv", "document.xml", "a.pdf"), names.toSet())
        assertTrue(names.none { it.contains("/") }, "no folders")
    }

    @Test
    fun `rejects a single file over 20 MB`() {
        val big = ByteArray(20 * 1024 * 1024 + 1)
        assertFailsWith<IllegalArgumentException> {
            DatevBuchungsstapelBelegeZipBuilder.build(
                csvBytes = "csv".toByteArray(),
                documentXmlBytes = "<archive/>".toByteArray(),
                pdfs = listOf(PdfAttachment("2026-01-02", "big.pdf", big)),
            )
        }
    }

    @Test
    fun `rejects more than 5000 belege incl xml files`() {
        val pdfs = (1..5000).map { PdfAttachment("2026-01-02", "f$it.pdf", ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> {
            DatevBuchungsstapelBelegeZipBuilder.build("csv".toByteArray(), "<archive/>".toByteArray(), pdfs)
        }
    }

    @Test
    fun `rejects beleg filename with path separator`() {
        assertFailsWith<IllegalArgumentException> {
            DatevBuchungsstapelBelegeZipBuilder.build(
                "csv".toByteArray(),
                "<archive/>".toByteArray(),
                listOf(PdfAttachment("2026-01-02", "subdir/x.pdf", ByteArray(1))),
            )
        }
    }

    @Test
    fun `rejects duplicate beleg filenames`() {
        val pdfs =
            listOf(
                PdfAttachment("2026-01-02", "dup.pdf", ByteArray(1)),
                PdfAttachment("2026-01-03", "dup.pdf", ByteArray(1)),
            )
        assertFailsWith<IllegalArgumentException> {
            DatevBuchungsstapelBelegeZipBuilder.build("csv".toByteArray(), "<archive/>".toByteArray(), pdfs)
        }
    }
}
