package org.example.project.export.datevxml

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class DatevBeleglinkDocumentGeneratorTest {
    @Test
    fun `emits one document per beleg with guid and file extension only`() {
        val xml =
            DatevBeleglinkDocumentGenerator
                .generate(
                    belege =
                        listOf(
                            DatevBeleglinkDocumentGenerator.BelegRef(guid = "AAAA0000-0000-0000-0000-000000000001", pdfFileName = "a.pdf"),
                            DatevBeleglinkDocumentGenerator.BelegRef(guid = "BBBB0000-0000-0000-0000-000000000002", pdfFileName = "b.pdf"),
                        ),
                    description = "DATEV Buchungsstapel mit Belegen 2026-01-01 bis 2026-01-31",
                    consultantNumber = "1001",
                    clientNumber = "55001",
                    clientName = "Testshop",
                    generatingSystem = "POS-KMP",
                ).toString(Charsets.UTF_8)

        assertContains(xml, "http://xml.datev.de/bedi/tps/document/v06.0")
        assertContains(xml, "guid=\"AAAA0000-0000-0000-0000-000000000001\"")
        assertContains(xml, "name=\"a.pdf\"")
        assertContains(xml, "guid=\"BBBB0000-0000-0000-0000-000000000002\"")
        assertContains(xml, "name=\"b.pdf\"")
        assertEquals(false, xml.contains("cashLedger"))
        assertEquals(false, xml.contains("datafile"))
    }
}
