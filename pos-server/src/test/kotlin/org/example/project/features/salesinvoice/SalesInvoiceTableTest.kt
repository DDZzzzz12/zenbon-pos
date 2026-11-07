package org.example.project.features.salesinvoice

import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SalesInvoiceTableTest {
    @Test
    fun `a draft has no number and no issue stamp`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                val draft =
                    SalesInvoice.new {
                        this.shop = shop
                        this.revenueOrigin = RevenueOrigin.STANDALONE
                        this.buyerName = "Musterfirma GmbH"
                        this.serviceDate = currentTimeStamp()
                        this.issueDate = currentTimeStamp()
                    }

                assertEquals(SalesInvoiceStatus.DRAFT, draft.status, "a new invoice is a draft")
                assertNull(draft.invoiceNumber, "a draft consumes no number — that is what keeps the sequence gapless")
                assertNull(draft.issuedAt)
                assertEquals("380", draft.invoiceTypeCode, "an invoice is an ordinary invoice unless said otherwise")
                assertEquals("EUR", draft.currency)
            }
        }
    }

    @Test
    fun `totals and the TSE block round-trip`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                val invoice =
                    SalesInvoice.new {
                        this.shop = shop
                        this.revenueOrigin = RevenueOrigin.POS_SETTLED
                        this.buyerName = "Musterfirma GmbH"
                        this.serviceDate = currentTimeStamp()
                        this.issueDate = currentTimeStamp()
                        this.taxInclusiveTotal = BigDecimal("119.0000")
                        this.taxTotal = BigDecimal("19.0000")
                        this.prepaidAmount = BigDecimal("119.0000")
                        this.amountDue = BigDecimal.ZERO
                        this.openAmount = BigDecimal.ZERO
                        this.tseSerialNumber = "abc123"
                        this.tseTransactionNumber = 4711L
                        this.tseSignatureCounter = 99L
                        this.tseSignature = "MEUCIQD..."
                    }

                assertEquals(0, BigDecimal("119.0000").compareTo(invoice.taxInclusiveTotal))
                assertEquals(0, BigDecimal.ZERO.compareTo(invoice.openAmount))
                assertEquals(4711L, invoice.tseTransactionNumber)
                assertEquals("abc123", invoice.tseSerialNumber)
            }
        }
    }

    @Test
    fun `a cancellation points at the invoice it reverses`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                val original =
                    SalesInvoice.new {
                        this.shop = shop
                        this.revenueOrigin = RevenueOrigin.STANDALONE
                        this.buyerName = "Musterfirma GmbH"
                        this.serviceDate = currentTimeStamp()
                        this.issueDate = currentTimeStamp()
                        this.status = SalesInvoiceStatus.CANCELLED
                    }
                val storno =
                    SalesInvoice.new {
                        this.shop = shop
                        this.revenueOrigin = RevenueOrigin.STANDALONE
                        this.buyerName = "Musterfirma GmbH"
                        this.serviceDate = currentTimeStamp()
                        this.issueDate = currentTimeStamp()
                        this.invoiceTypeCode = "381"
                        this.precedingSalesInvoice = original
                    }

                assertEquals(original.id, storno.precedingSalesInvoice?.id)
            }
        }
    }
}
