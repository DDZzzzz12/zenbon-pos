package org.example.project.features.salesinvoice

import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceBooking
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceLine
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceVatBreakdown
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SalesInvoiceChildTablesTest {
    @Test
    fun `lines, vat breakdown and booking hang off one invoice`() {
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
                    }

                SalesInvoiceLine.new {
                    this.salesInvoice = invoice
                    this.lineNo = 1
                    this.itemName = "Wiener Schnitzel"
                    this.quantity = BigDecimal("2.0000")
                    this.unitCode = "C62"
                    this.netUnitPrice = BigDecimal("15.9200")
                    this.lineNetAmount = BigDecimal("31.8400")
                    this.vatRate = BigDecimal("19.00")
                    this.vatCategoryCode = "S"
                    this.vatAmount = BigDecimal("6.0500")
                    this.lineGrossAmount = BigDecimal("37.8900")
                }

                SalesInvoiceVatBreakdown.new {
                    this.salesInvoice = invoice
                    this.vatCategoryCode = "S"
                    this.vatRate = BigDecimal("19.00")
                    this.taxableAmount = BigDecimal("31.8400")
                    this.vatAmount = BigDecimal("6.0500")
                }

                val booking =
                    SalesInvoiceBooking.new {
                        this.salesInvoice = invoice
                        this.revenueAccountNo = "8400"
                    }

                assertEquals(1, invoice.lines.toList().size)
                assertEquals("Wiener Schnitzel", invoice.lines.first().itemName)
                assertEquals(1, invoice.vatBreakdown.toList().size)
                assertEquals("8400", booking.revenueAccountNo)
                assertNull(booking.postedAt, "a booking is unposted until the export writes it")
                assertNull(booking.bpAccountNo, "the debtor account is resolved at posting time")
            }
        }
    }

    @Test
    fun `line numbers are unique within an invoice`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                val invoice =
                    SalesInvoice.new {
                        this.shop = shop
                        this.revenueOrigin = RevenueOrigin.STANDALONE
                        this.buyerName = "Musterfirma GmbH"
                        this.serviceDate = currentTimeStamp()
                        this.issueDate = currentTimeStamp()
                    }
                SalesInvoiceLine.new {
                    this.salesInvoice = invoice
                    this.lineNo = 1
                    this.itemName = "Erste Position"
                    this.quantity = BigDecimal.ONE
                    this.unitCode = "C62"
                    this.netUnitPrice = BigDecimal("10.0000")
                    this.lineNetAmount = BigDecimal("10.0000")
                    this.vatRate = BigDecimal("19.00")
                    this.vatCategoryCode = "S"
                    this.vatAmount = BigDecimal("1.9000")
                    this.lineGrossAmount = BigDecimal("11.9000")
                }
                SalesInvoiceLine.new {
                    this.salesInvoice = invoice
                    this.lineNo = 2
                    this.itemName = "Zweite Position"
                    this.quantity = BigDecimal.ONE
                    this.unitCode = "C62"
                    this.netUnitPrice = BigDecimal("10.0000")
                    this.lineNetAmount = BigDecimal("10.0000")
                    this.vatRate = BigDecimal("19.00")
                    this.vatCategoryCode = "S"
                    this.vatAmount = BigDecimal("1.9000")
                    this.lineGrossAmount = BigDecimal("11.9000")
                }

                assertEquals(listOf(1, 2), invoice.lines.map { it.lineNo }.sorted())
            }
        }
    }
}
