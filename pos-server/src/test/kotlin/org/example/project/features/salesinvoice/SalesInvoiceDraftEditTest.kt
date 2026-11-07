package org.example.project.features.salesinvoice

import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.error.PosErrorCode
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceRepositoryImpl
import org.example.project.features.salesinvoice.domain.service.StandaloneInvoiceContent
import org.example.project.features.salesinvoice.domain.service.StandaloneLineContent
import org.example.project.features.salesinvoice.domain.service.StandaloneVatRowContent
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SalesInvoiceDraftEditTest {
    private val repo = SalesInvoiceRepositoryImpl()

    private fun content(lines: List<StandaloneLineContent> = listOf(line())) =
        StandaloneInvoiceContent(
            buyerName = "Musterfirma GmbH",
            buyerVatId = null,
            buyerStreet = "Industriestr. 5",
            buyerZip = "10117",
            buyerCity = "Berlin",
            buyerCountry = null,
            buyerEmail = null,
            serviceDate = currentTimeStamp(),
            dueDate = null,
            paymentTermsText = null,
            notes = null,
            lines = lines,
            vatRows = listOf(StandaloneVatRowContent(BigDecimal("19.00"), "S", BigDecimal("100.0000"), BigDecimal("19.0000"))),
            lineNetTotal = BigDecimal("100.0000"),
            taxTotal = BigDecimal("19.0000"),
            taxInclusiveTotal = BigDecimal("119.0000"),
        )

    private fun line(name: String = "Raummiete") =
        StandaloneLineContent(
            itemName = name,
            itemDescription = null,
            quantity = BigDecimal.ONE,
            unitCode = "C62",
            grossUnitPrice = BigDecimal("119.0000"),
            netUnitPrice = BigDecimal("100.0000"),
            lineNetAmount = BigDecimal("100.0000"),
            vatRate = BigDecimal("19.00"),
            vatCategoryCode = "S",
            vatAmount = BigDecimal("19.0000"),
            lineGrossAmount = BigDecimal("119.0000"),
        )

    private fun draft(shop: ShopInfo) =
        repo.createDraft(
            shop = shop,
            revenueOrigin = RevenueOrigin.STANDALONE,
            buyerName = "Musterfirma GmbH",
            issueDate = currentTimeStamp(),
            serviceDate = currentTimeStamp(),
        )

    @Test
    fun `replaceDraftContent replaces lines and breakdown and updates the totals`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val invoice = draft(ShopInfo.all().first())
                repo.replaceDraftContent(invoice, content())

                assertEquals("Musterfirma GmbH", invoice.buyerName)
                assertEquals(1, invoice.lines.toList().size)
                assertEquals(1, invoice.vatBreakdown.toList().size)
                assertEquals(0, BigDecimal("119.0000").compareTo(invoice.taxInclusiveTotal))
                assertEquals("Raummiete", invoice.lines.first().itemName)
            }
        }
    }

    @Test
    fun `replaceDraftContent replaces the previous content rather than appending`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val invoice = draft(ShopInfo.all().first())
                repo.replaceDraftContent(invoice, content(listOf(line(), line(name = "Frühstück"))))
                repo.replaceDraftContent(invoice, content(listOf(line(name = "Nur noch eine"))))

                assertEquals(listOf("Nur noch eine"), invoice.lines.map { it.itemName })
                assertEquals(1, invoice.vatBreakdown.toList().size)
            }
        }
    }

    @Test
    fun `replaceDraftContent on an ISSUED invoice is refused, called directly on the repository`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val invoice = draft(ShopInfo.all().first())
                repo.markIssued(invoice, invoiceNumber = "RG-2027-00001", issuedBy = null)

                val error = assertFailsWith<PosException> { repo.replaceDraftContent(invoice, content()) }
                assertEquals(PosErrorCode.SALES_INVOICE_IMMUTABLE, error.code)
                assertEquals(0, invoice.lines.toList().size, "the refused edit must not have landed")
            }
        }
    }

    @Test
    fun `a cancelled invoice is not listed as open`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                val invoice = draft(shop)
                repo.replaceDraftContent(invoice, content())
                repo.markIssued(invoice, invoiceNumber = "RG-2027-00001", issuedBy = null)
                repo.markCancelled(invoice, cancelledBy = null, reason = "storno")

                val (_, total) = repo.list(status = null, invoiceTypeCode = null, open = true, overdue = false, limit = 50, offset = 0)
                assertEquals(0L, total)
            }
        }
    }

    @Test
    fun `overdue alone implies open, so a draft is never returned`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                val plainDraft = draft(shop)
                repo.replaceDraftContent(plainDraft, content())
                // A draft that is also "overdue" in the naive reading — old, but not issued.
                val (_, total) =
                    repo.list(
                        status = null,
                        invoiceTypeCode = null,
                        open = false,
                        overdue = true,
                        limit = 50,
                        offset = 0,
                    )
                assertEquals(0L, total, "an overdue-only request must not degrade to an unfiltered list")
            }
        }
    }

    @Test
    fun `an overdue-only request returns only overdue issued invoices`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                val overdue = draft(shop)
                repo.replaceDraftContent(overdue, content())
                repo.markIssued(overdue, invoiceNumber = "RG-2027-00001", issuedBy = null)
                transaction {
                    overdue.dueDate = kotlinx.datetime.LocalDateTime(2020, 1, 1, 0, 0)
                }
                val notOverdue = draft(shop)
                repo.replaceDraftContent(notOverdue, content())
                repo.markIssued(notOverdue, invoiceNumber = "RG-2027-00002", issuedBy = null)
                transaction {
                    notOverdue.dueDate = kotlinx.datetime.LocalDateTime(2099, 1, 1, 0, 0)
                }

                val (page, total) =
                    repo.list(
                        status = null,
                        invoiceTypeCode = null,
                        open = false,
                        overdue = true,
                        limit = 50,
                        offset = 0,
                    )
                assertEquals(1L, total)
                assertEquals(listOf("RG-2027-00001"), page.map { it.invoiceNumber })
            }
        }
    }

    @Test
    fun `an issued STANDALONE draft is listed as open`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                val invoice = draft(shop)
                repo.replaceDraftContent(invoice, content())
                repo.markIssued(invoice, invoiceNumber = "RG-2027-00001", issuedBy = null)

                val (page, total) = repo.list(status = null, invoiceTypeCode = null, open = true, overdue = false, limit = 50, offset = 0)
                assertEquals(1L, total)
                assertEquals(invoice.id, page.single().id)
            }
        }
    }
}
