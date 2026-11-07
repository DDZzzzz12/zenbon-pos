package org.example.project.admin.features.documents

import org.example.project.admin.features.documents.presentation.components.partyLabel
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ProcessingStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The "Lieferant / Bank" column promises the vendor for an invoice and the bank for a bank
 * statement. It silently delivered neither for statements until `bankName` was projected onto the
 * list DTO, because the server mapper fills `vendorName` and `reference` from invoice metadata only.
 */
class PartyLabelTest {
    private fun item(
        fileType: String,
        vendorName: String? = null,
        bankName: String? = null,
        reference: String? = null,
    ) = DocumentDto.DocumentListItemDto(
        id = 1L,
        filename = "doc.pdf",
        fileType = fileType,
        processingStatus = ProcessingStatus.COMPLETED,
        isActive = true,
        linkCount = 0,
        vendorName = vendorName,
        bankName = bankName,
        reference = reference,
    )

    @Test
    fun invoice_showsTheVendor() {
        assertEquals("ACME GmbH", partyLabel(item("INVOICE", vendorName = "ACME GmbH")))
    }

    // The regression this whole change exists for: before bankName was projected onto the list DTO,
    // every bank-statement row rendered an em dash under a column headed "Bank".
    @Test
    fun bankStatement_showsTheBank() {
        assertEquals("Sparkasse", partyLabel(item("BANK_STATEMENT", bankName = "Sparkasse")))
    }

    // `reference` is the invoice number, not a party. It must never reach this column — doing so
    // printed an invoice number under "Lieferant / Bank".
    @Test
    fun referenceIsNeverUsedAsAParty() {
        assertEquals("—", partyLabel(item("CONTRACTS", reference = "RE-2026-0312")))
    }

    @Test
    fun vendorWinsWhenBothArePresent() {
        assertEquals(
            "ACME GmbH",
            partyLabel(item("INVOICE", vendorName = "ACME GmbH", bankName = "Sparkasse")),
        )
    }

    @Test
    fun documentWithNeither_showsEmDash() {
        assertEquals("—", partyLabel(item("ZBON_REPORT")))
    }
}
