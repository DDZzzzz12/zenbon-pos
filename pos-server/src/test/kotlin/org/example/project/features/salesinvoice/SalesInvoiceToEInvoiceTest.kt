package org.example.project.features.salesinvoice

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.einvoice.check.EInvoiceInvariants
import org.example.project.einvoice.check.En16931RequiredFields
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceService
import org.example.project.features.salesinvoice.einvoice.SalesInvoiceToEInvoice
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Task 5 of slice 4e: the pure `sales_invoice` -> [org.example.project.einvoice.model.EInvoice]
 * mapper that [org.example.project.einvoice.syntax.CiiInvoiceWriter] (task 3) will serialize.
 */
@DisplayName("SalesInvoiceToEInvoice")
class SalesInvoiceToEInvoiceTest {
    private fun invoices(): SalesInvoiceService = GlobalContext.get().get()

    @Test
    fun `maps an issued STANDALONE invoice into a valid EN 16931 EInvoice`() =
        testApp(setupData = {
            TestData.createShopInfo()
        }) { _ ->
            val admin = TestData.createAdmin()

            val created =
                runBlocking {
                    invoices().createStandaloneAsDto(
                        request =
                            SalesInvoiceDto.CreateStandaloneRequestDto(
                                buyerName = "Musterfirma GmbH",
                                buyerVatId = "DE123456789",
                                buyerStreet = "Industriestr. 5",
                                buyerZip = "10117",
                                buyerCity = "Berlin",
                                buyerCountry = "DE",
                                serviceDate = "2026-08-16T10:00:00",
                                lines =
                                    listOf(
                                        SalesInvoiceDto.StandaloneLineInputDto(
                                            itemName = "Raummiete",
                                            quantity = "1",
                                            grossUnitPrice = "119.00",
                                            vatRate = "19.00",
                                        ),
                                        SalesInvoiceDto.StandaloneLineInputDto(
                                            itemName = "Beratung",
                                            quantity = "2",
                                            grossUnitPrice = "59.50",
                                            vatRate = "7.00",
                                        ),
                                    ),
                            ),
                        createdBy = null,
                    )
                }

            runBlocking { invoices().issue(created.id, issuedBy = admin.id.value) }

            transaction {
                val invoice = SalesInvoice.findById(created.id)!!
                val lines = invoice.lines.toList()
                val vatBreakdown = invoice.vatBreakdown.toList()
                val shop = ShopInfo.all().first()

                val result = SalesInvoiceToEInvoice.map(invoice, lines, vatBreakdown)

                // seller — the invoice's own snapshot, written from ShopInfo at creation time.
                assertEquals(shop.companyName, result.seller.name)
                assertEquals(shop.ustIdNr, result.seller.vatId)
                assertEquals(shop.shopTaxNumber, result.seller.taxNumber)
                assertEquals(shop.shopAddress, result.seller.street)
                assertEquals(shop.zipCode.toString(), result.seller.zip)
                assertEquals(shop.shopCity, result.seller.city)
                assertEquals(shop.shopCountry, result.seller.country)

                // buyer
                assertEquals("Musterfirma GmbH", result.buyer?.name)
                assertEquals("DE123456789", result.buyer?.vatId)
                assertEquals("Industriestr. 5", result.buyer?.street)
                assertEquals("10117", result.buyer?.zip)
                assertEquals("Berlin", result.buyer?.city)
                assertEquals("DE", result.buyer?.country)

                // lines / vat breakdown — one EInvoiceLine per sales_invoice_line, one
                // EInvoiceVatBreakdown per sales_invoice_vat_breakdown.
                assertEquals(lines.size, result.lines.size)
                assertEquals(vatBreakdown.size, result.vatBreakdown.size)
                assertEquals(setOf("Raummiete", "Beratung"), result.lines.map { it.itemName }.toSet())

                // totals — the same values, brought from the stored four decimals to the two EN 16931
                // allows (BR-DEC), so compared by value.
                assertEquals(0, invoice.prepaidAmount!!.compareTo(result.prepaidAmount))
                assertEquals(0, invoice.amountDue!!.compareTo(result.amountDue))
                assertEquals(0, invoice.lineNetTotal!!.compareTo(result.lineNetTotal))
                // 119.00 € at 7 % has no split satisfying BR-CO-17 (fiscal 111.21 + 7.79, but
                // 111.21 × 7 % = 7.78): the XML states VAT 7.78, BT-112 one cent lower, and gives the
                // cent back as BT-114 so BT-115 is still the stored amount due.
                assertEquals(java.math.BigDecimal("0.01"), result.roundingAmount)
                assertEquals(0, invoice.taxTotal!!.compareTo(result.taxTotal!! + result.roundingAmount!!))
                assertEquals(0, invoice.taxInclusiveTotal!!.compareTo(result.taxInclusiveTotal!! + result.roundingAmount!!))
                assertEquals(2, result.taxInclusiveTotal!!.scale())

                assertEquals("380", result.invoiceTypeCode)
                assertEquals(invoice.invoiceNumber, result.invoiceNumber)
                assertEquals(invoice.currency, result.currency)
                assertEquals(invoice.issueDate.date, result.issueDate)

                // The whole point: a mapped, well-formed standalone invoice is a valid EN 16931
                // EInvoice by both the arithmetic-invariant and the required-field checks.
                assertNull(EInvoiceInvariants.check(result), "arithmetic invariants must hold")
                assertNull(En16931RequiredFields.check(result), "required EN 16931 fields must all be present")
                assertNull(
                    org.example.project.einvoice.check.En16931AmountRules
                        .check(result),
                    "BR-DEC / BR-CO-16 / BR-CO-17 must hold",
                )
            }
        }
}
