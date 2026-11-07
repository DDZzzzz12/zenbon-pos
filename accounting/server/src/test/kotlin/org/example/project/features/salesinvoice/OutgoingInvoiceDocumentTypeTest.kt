package org.example.project.features.salesinvoice

import org.example.project.core.domain.enums.document.DocumentType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class OutgoingInvoiceDocumentTypeTest {
    @Test
    fun `an issued invoice files under its own type, not the incoming-invoice archive`() {
        assertNotEquals(
            DocumentType.INVOICE.folderName,
            DocumentType.OUTGOING_INVOICE.folderName,
            "our sales must not land in the creditor archive that document search and the AP export read",
        )
        assertEquals(DocumentType.OUTGOING_INVOICE, DocumentType.fromStringOrNull("OUTGOING_INVOICE"))
    }
}
