package org.example.project.features.document

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.pdf.util.FileStorageHelper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A stored document must be named after what it actually is.
 *
 * `.xml` uploads have been accepted since the 2026-08-09 e-invoice parsing slice, and the server
 * detects the media type from the file's magic bytes — but `generateFilename` appended `".pdf"` in
 * all six of its `when` arms and took no media type at all. An XRechnung uploaded on 2026-09-20 was
 * therefore filed as `Rechnung_2026-09-20_74fb.pdf` while `file(1)` reported
 * *"XML 1.0 document text"*, and the document row stored `mediaType: application/xml` alongside it
 * (E2E-2026-09-20 #7).
 *
 * That matters beyond tidiness: for an XRechnung the XML *is* the legally binding original, and an
 * auditor downloading it receives a `.pdf` no reader will open.
 */
@DisplayName("Document filenames carry the real extension")
class DocumentFilenameExtensionTest {
    private val now = LocalDateTime(2026, 9, 20, 17, 41, 58)
    private val hash = "74fbaaaabbbbccccdddd"

    @Test
    fun `an XML e-invoice is filed as xml`() {
        val name =
            FileStorageHelper.generateFilename(
                type = DocumentType.INVOICE,
                timestamp = now,
                contentHash = hash,
                vendorName = "Musterlieferant GmbH",
                invoiceNumber = "RE-2026-0001",
                mediaType = "application/xml",
            )

        assertTrue(name.endsWith(".xml"), "expected an .xml name, got $name")
    }

    @Test
    fun `a PDF keeps the extension it always had`() {
        val name =
            FileStorageHelper.generateFilename(
                type = DocumentType.INVOICE,
                timestamp = now,
                contentHash = hash,
                vendorName = "Musterlieferant GmbH",
                invoiceNumber = "RE-2026-0001",
                mediaType = "application/pdf",
            )

        assertEquals("Rechnung_Musterlieferant-GmbH_RE-2026-0001_2026-09-20_74fb.pdf", name)
    }

    /**
     * Every existing caller omits the media type — generated Eigenbelege, Z-Bon PDFs and the rest
     * really are PDFs — so the default must keep producing exactly the names it produced before.
     */
    @Test
    fun `an unspecified media type still means pdf`() {
        val name =
            FileStorageHelper.generateFilename(
                type = DocumentType.INVOICE,
                timestamp = now,
                contentHash = hash,
                vendorName = "Musterlieferant GmbH",
                invoiceNumber = "RE-2026-0001",
            )

        assertEquals("Rechnung_Musterlieferant-GmbH_RE-2026-0001_2026-09-20_74fb.pdf", name)
    }

    @Test
    fun `an unrecognised media type falls back to pdf rather than inventing an extension`() {
        val name =
            FileStorageHelper.generateFilename(
                type = DocumentType.INVOICE,
                timestamp = now,
                contentHash = hash,
                mediaType = "application/octet-stream",
            )

        assertTrue(name.endsWith(".pdf"), "expected the safe default, got $name")
    }

    @Test
    fun `images and bank statements are named after their own type too`() {
        assertTrue(
            FileStorageHelper
                .generateFilename(
                    type = DocumentType.BANK_STATEMENT,
                    timestamp = now,
                    contentHash = hash,
                    bankName = "Sparkasse",
                    statementNumber = "0042",
                    mediaType = "image/jpeg",
                ).endsWith(".jpg"),
        )
    }
}
