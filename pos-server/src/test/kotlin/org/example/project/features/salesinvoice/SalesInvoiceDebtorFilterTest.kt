package org.example.project.features.salesinvoice

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceRepositoryImpl
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * `debtorId` narrows the sales-invoice list to one customer.
 *
 * The Debitoren detail pane shows a debtor's recent invoices, and it must not do that by fetching a
 * page of all invoices and filtering it: the list is paged newest-first, so a debtor whose last
 * invoice is older than the page would show as having none.
 */
class SalesInvoiceDebtorFilterTest {
    private val repo = SalesInvoiceRepositoryImpl()

    /** `partner_id` is non-null, so the debtor role row needs a party to point at. */
    private fun debtor(name: String): Debtor =
        Debtor.new {
            this.partner =
                BusinessPartner.new {
                    this.shop = ShopInfo.all().first()
                    this.name = name
                }
            this.shop = ShopInfo.all().first()
        }

    private fun invoice(
        number: String,
        day: Int,
        forDebtor: Debtor?,
    ): SalesInvoice =
        SalesInvoice.new {
            this.shop = ShopInfo.all().first()
            this.revenueOrigin = RevenueOrigin.STANDALONE
            this.buyerName = forDebtor?.partner?.name ?: "Laufkundschaft"
            this.debtor = forDebtor
            this.issueDate = LocalDateTime(2027, 1, day, 10, 0)
            this.serviceDate = LocalDateTime(2027, 1, day, 10, 0)
            this.invoiceNumber = number
            this.status = SalesInvoiceStatus.ISSUED
            this.invoiceTypeCode = "380"
            this.taxInclusiveTotal = BigDecimal("28.3000")
        }

    @Test
    fun `debtorId returns only that debtor's invoices`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val acme = debtor("Acme GmbH")
                val other = debtor("Andere GmbH")
                invoice("RG-2027-00001", day = 1, forDebtor = acme)
                invoice("RG-2027-00002", day = 2, forDebtor = other)
                invoice("RG-2027-00003", day = 3, forDebtor = acme)

                val (page, total) = repo.list(null, null, false, false, acme.id.value, 50, 0)

                assertEquals(2L, total)
                assertEquals(listOf("RG-2027-00003", "RG-2027-00001"), page.map { it.invoiceNumber })
            }
        }
    }

    @Test
    fun `an invoice with no debtor is never returned by a debtor filter`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val acme = debtor("Acme GmbH")
                invoice("RG-2027-00001", day = 1, forDebtor = acme)
                invoice("RG-2027-00002", day = 2, forDebtor = null)

                assertEquals(1L, repo.list(null, null, false, false, acme.id.value, 50, 0).second)
            }
        }
    }

    @Test
    fun `a null debtorId is not a filter`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val acme = debtor("Acme GmbH")
                invoice("RG-2027-00001", day = 1, forDebtor = acme)
                invoice("RG-2027-00002", day = 2, forDebtor = null)

                assertEquals(2L, repo.list(null, null, false, false, null, 50, 0).second)
            }
        }
    }

    @Test
    fun `debtorId composes with the status filter rather than replacing it`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val acme = debtor("Acme GmbH")
                invoice("RG-2027-00001", day = 1, forDebtor = acme)
                invoice("RG-2027-00002", day = 2, forDebtor = acme).status = SalesInvoiceStatus.CANCELLED

                val issued = repo.list(SalesInvoiceStatus.ISSUED, null, false, false, acme.id.value, 50, 0)
                assertEquals(1L, issued.second)
                assertEquals("RG-2027-00001", issued.first.single().invoiceNumber)
            }
        }
    }

    @Test
    fun `an unknown debtor id returns nothing rather than everything`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                invoice("RG-2027-00001", day = 1, forDebtor = debtor("Acme GmbH"))

                assertEquals(0L, repo.list(null, null, false, false, 999_999L, 50, 0).second)
            }
        }
    }
}
