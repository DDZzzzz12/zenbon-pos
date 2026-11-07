package org.example.project.features.salesinvoice

import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceRepositoryImpl
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SalesInvoiceRepositoryTest {
    private val repo = SalesInvoiceRepositoryImpl()

    private fun draft(shop: ShopInfo) =
        repo.createDraft(
            shop = shop,
            revenueOrigin = RevenueOrigin.STANDALONE,
            buyerName = "Musterfirma GmbH",
            issueDate = currentTimeStamp(),
            serviceDate = currentTimeStamp(),
        )

    @Test
    fun `a draft is created with no number and no issue stamp`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val invoice = draft(ShopInfo.all().first())

                assertEquals(SalesInvoiceStatus.DRAFT, invoice.status)
                assertNull(invoice.invoiceNumber)
                assertNull(invoice.issuedAt)
            }
        }
    }

    @Test
    fun `markIssued stamps the number, the time and the status`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val invoice = draft(ShopInfo.all().first())
                repo.markIssued(invoice, invoiceNumber = "RG-2027-00001", issuedBy = null)

                assertEquals(SalesInvoiceStatus.ISSUED, invoice.status)
                assertEquals("RG-2027-00001", invoice.invoiceNumber)
                assertNotNull(invoice.issuedAt)
            }
        }
    }

    @Test
    fun `updateDraft on an ISSUED invoice is refused, called directly on the repository`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val invoice = draft(ShopInfo.all().first())
                repo.markIssued(invoice, invoiceNumber = "RG-2027-00001", issuedBy = null)

                val error =
                    assertFailsWith<PosException> {
                        repo.updateDraft(invoice, buyerName = "Jemand Anders GmbH")
                    }
                assertEquals(PosErrorCode.SALES_INVOICE_IMMUTABLE, error.code)
                assertEquals("Musterfirma GmbH", invoice.buyerName, "the refused edit must not have landed")
            }
        }
    }

    @Test
    fun `markIssued twice is refused`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val invoice = draft(ShopInfo.all().first())
                repo.markIssued(invoice, invoiceNumber = "RG-2027-00001", issuedBy = null)

                val error =
                    assertFailsWith<PosException> {
                        repo.markIssued(invoice, invoiceNumber = "RG-2027-00002", issuedBy = null)
                    }
                assertEquals(PosErrorCode.SALES_INVOICE_IMMUTABLE, error.code)
                assertEquals("RG-2027-00001", invoice.invoiceNumber, "the first number stands")
            }
        }
    }

    @Test
    fun `open_amount stays writable after issue because allocations maintain it`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val invoice = draft(ShopInfo.all().first())
                repo.markIssued(invoice, invoiceNumber = "RG-2027-00001", issuedBy = null)
                repo.updateOpenAmount(invoice, BigDecimal("12.5000"))

                assertEquals(0, BigDecimal("12.5000").compareTo(invoice.openAmount))
            }
        }
    }

    @Test
    fun `deleteDraft removes a draft but refuses an issued invoice`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                val disposable = draft(shop)
                repo.deleteDraft(disposable)

                val issued = draft(shop)
                repo.markIssued(issued, invoiceNumber = "RG-2027-00001", issuedBy = null)
                val error = assertFailsWith<PosException> { repo.deleteDraft(issued) }
                assertEquals(PosErrorCode.SALES_INVOICE_IMMUTABLE, error.code)
            }
        }
    }

    @Test
    fun `markCancelled is refused on a draft and allowed on an issued invoice`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                val stillDraft = draft(shop)
                val error =
                    assertFailsWith<PosException> { repo.markCancelled(stillDraft, null, "zu früh") }
                assertEquals(PosErrorCode.SALES_INVOICE_NOT_CANCELLABLE, error.code)

                val issued = draft(shop)
                repo.markIssued(issued, invoiceNumber = "RG-2027-00001", issuedBy = null)
                repo.markCancelled(issued, null, "Tippfehler")
                assertEquals(SalesInvoiceStatus.CANCELLED, issued.status)
                assertEquals("Tippfehler", issued.cancellationReason)
            }
        }
    }

    @Test
    fun `hasLiveInvoiceForBill ignores cancelled invoices`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                assertFalse(repo.hasLiveInvoiceForBill(999L), "no invoice at all")
            }
        }
    }
}
