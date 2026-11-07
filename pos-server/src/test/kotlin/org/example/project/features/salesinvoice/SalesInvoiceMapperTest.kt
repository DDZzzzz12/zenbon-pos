package org.example.project.features.salesinvoice

import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceLine
import org.example.project.features.salesinvoice.mapper.toDto
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SalesInvoiceMapperTest {
    @Test
    fun `money crosses the wire as a string with its scale intact`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val invoice =
                    SalesInvoice.new {
                        this.shop = ShopInfo.all().first()
                        this.revenueOrigin = RevenueOrigin.POS_SETTLED
                        this.buyerName = "Musterfirma GmbH"
                        this.issueDate = currentTimeStamp()
                        this.serviceDate = currentTimeStamp()
                        this.taxInclusiveTotal = BigDecimal("28.3000")
                        this.taxTotal = BigDecimal("4.5185")
                    }
                SalesInvoiceLine.new {
                    this.salesInvoice = invoice
                    this.lineNo = 1
                    this.itemName = "Wiener Schnitzel"
                    this.quantity = BigDecimal.ONE
                    this.unitCode = "C62"
                    this.netUnitPrice = BigDecimal("20.0000")
                    this.lineNetAmount = BigDecimal("20.0000")
                    this.vatRate = BigDecimal("19.00")
                    this.vatCategoryCode = "S"
                    this.vatAmount = BigDecimal("3.8000")
                    this.lineGrossAmount = BigDecimal("23.8000")
                }

                val dto = invoice.toDto()

                assertEquals("28.3000", dto.taxInclusiveTotal, "money is a string, at the DB's scale")
                assertEquals(
                    "4.5185",
                    dto.taxTotal,
                    "sub-cent precision survives: a scale-2 wire would round this to 4.52",
                )
                assertEquals("DRAFT", dto.status)
                assertEquals("POS_SETTLED", dto.revenueOrigin)
                assertEquals(1, dto.lines.size)
                assertEquals("19.00", dto.lines.single().vatRate, "percent units survive the wire")
                assertNull(dto.invoiceNumber, "a draft has no number")
            }
        }
    }
}
