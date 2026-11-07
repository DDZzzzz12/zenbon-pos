package org.example.project.features.salesinvoice

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceRepositoryImpl
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

class SalesInvoiceListTest {
    private val repo = SalesInvoiceRepositoryImpl()

    private fun invoice(
        number: String?,
        status: SalesInvoiceStatus,
        typeCode: String = "380",
        day: Int,
    ): SalesInvoice =
        SalesInvoice.new {
            this.shop = ShopInfo.all().first()
            this.revenueOrigin = RevenueOrigin.STANDALONE
            this.buyerName = "Musterfirma GmbH"
            this.issueDate = LocalDateTime(2027, 1, day, 10, 0)
            this.serviceDate = LocalDateTime(2027, 1, day, 10, 0)
            this.invoiceNumber = number
            this.status = status
            this.invoiceTypeCode = typeCode
            this.taxInclusiveTotal = BigDecimal("28.3000")
        }

    private fun seedAll() {
        invoice(null, SalesInvoiceStatus.DRAFT, day = 1)
        invoice("RG-2027-00001", SalesInvoiceStatus.ISSUED, day = 2)
        invoice("RG-2027-00002", SalesInvoiceStatus.ISSUED, day = 3)
        invoice("RG-2027-00003", SalesInvoiceStatus.ISSUED, typeCode = "381", day = 4)
        invoice("RG-2027-00004", SalesInvoiceStatus.CANCELLED, day = 5)
    }

    @Test
    fun `each bucket returns only its own rows`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                seedAll()

                assertEquals(1L, repo.list(SalesInvoiceStatus.DRAFT, null, false, false, null, 50, 0).second, "Entwürfe")
                assertEquals(1L, repo.list(SalesInvoiceStatus.CANCELLED, null, false, false, null, 50, 0).second, "Storniert")
                assertEquals(1L, repo.list(null, "381", false, false, null, 50, 0).second, "Gutschriften")
                assertEquals(
                    2L,
                    repo.list(SalesInvoiceStatus.ISSUED, "380", false, false, null, 50, 0).second,
                    "Ausgestellt excludes the Gutschrift, which is not a sale",
                )
            }
        }
    }

    @Test
    fun `rows come back newest first`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                seedAll()

                val issued = repo.list(SalesInvoiceStatus.ISSUED, "380", false, false, null, 50, 0).first
                assertEquals(
                    listOf("RG-2027-00002", "RG-2027-00001"),
                    issued.map { it.invoiceNumber },
                    "newest issue_date first",
                )
            }
        }
    }

    @Test
    fun `paging returns a page and the unpaged total`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                seedAll()

                val (page, total) = repo.list(null, null, false, false, debtorId = null, limit = 2, offset = 0)
                assertEquals(2, page.size, "the page honours the limit")
                assertEquals(5L, total, "the total counts every matching row, not just the page")

                val (second, _) = repo.list(null, null, false, false, debtorId = null, limit = 2, offset = 2)
                assertEquals(2, second.size)
                assertEquals(
                    emptyList(),
                    page.map { it.id.value }.intersect(second.map { it.id.value }.toSet()).toList(),
                    "pages must not overlap",
                )
            }
        }
    }
}
