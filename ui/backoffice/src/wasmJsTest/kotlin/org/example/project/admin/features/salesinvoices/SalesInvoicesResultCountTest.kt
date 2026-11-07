package org.example.project.admin.features.salesinvoices

import org.example.project.admin.features.salesinvoices.presentation.SalesInvoicesState
import org.example.project.admin.features.salesinvoices.presentation.salesInvoicesResultCount
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import kotlin.test.Test
import kotlin.test.assertEquals

class SalesInvoicesResultCountTest {
    private fun rows(n: Int) =
        List(n) {
            SalesInvoiceDto.ListItemDto(
                id = it.toLong(),
                invoiceNumber = null,
                status = "ISSUED",
                invoiceTypeCode = "380",
                issueDate = "2027-01-05T10:00",
                buyerName = "Musterfirma GmbH",
                currency = "EUR",
                taxInclusiveTotal = null,
                openAmount = null,
                hasDocument = false,
            )
        }

    @Test
    fun theCountIsExactWhenEveryInvoiceIsListed() {
        assertEquals("3", salesInvoicesResultCount(SalesInvoicesState(items = rows(3), total = 3)))
        assertEquals("0", salesInvoicesResultCount(SalesInvoicesState()))
    }

    @Test
    fun theCountIsTheServersExactTotalEvenBeyondThePage() {
        assertEquals("120", salesInvoicesResultCount(SalesInvoicesState(items = rows(50), total = 120)))
    }
}
