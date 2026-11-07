package org.example.project.features.salesinvoice

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.features.audit.domain.models.TransactionAuditLog
import org.example.project.features.salesinvoice.data.repository.ArNumberSequenceRepositoryImpl
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceRepositoryImpl
import org.example.project.features.salesinvoice.data.service.BillInvoiceFactsGatherer
import org.example.project.features.salesinvoice.data.service.SalesInvoiceServiceImpl
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceLine
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceVatBreakdown
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T9-12: cancelling an issued outgoing invoice is a § 14 correction and must leave a
 * `CANCEL_SALES_INVOICE` row in `transaction_audit_log` — on success naming the original invoice,
 * and on a refusal as a FAILED row. Before the fix neither was written.
 *
 * A file of its own rather than more methods on [SalesInvoiceServiceTest], so the audit guard does
 * not collide with parallel work on that class's issue/cancel fixtures.
 */
@DisplayName("Sales-invoice cancellation is audited (T9-12)")
class SalesInvoiceCancelAuditTest {
    private fun service() =
        SalesInvoiceServiceImpl(
            repository = SalesInvoiceRepositoryImpl(),
            numbers = ArNumberSequenceRepositoryImpl(),
            facts = BillInvoiceFactsGatherer(),
            pdf = GlobalContext.get().get(),
            billRepository = GlobalContext.get().get(),
            terminalRepository = GlobalContext.get().get(),
            saleSigning = GlobalContext.get().get(),
            configManager = GlobalContext.get().get(),
            allocationWriter = GlobalContext.get().get(),
        )

    private fun issuer(): UUID = transaction { TestData.createAdmin(employeeNumber = "ISS001", username = "issuer").id.value }

    /** A draft with one consistent line and matching totals — the shape `issue()` accepts. */
    private fun createCompleteDraft(): Long =
        transaction {
            val invoice =
                SalesInvoice.new {
                    this.shop = ShopInfo.all().first()
                    this.revenueOrigin = RevenueOrigin.STANDALONE
                    this.buyerName = "Musterfirma GmbH"
                    this.issueDate = currentTimeStamp()
                    this.serviceDate = currentTimeStamp()
                    this.taxInclusiveTotal = BigDecimal("37.8900")
                    this.taxTotal = BigDecimal("6.0500")
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
            invoice.id.value
        }

    @Test
    fun `a cancellation writes a CANCEL_SALES_INVOICE row naming the original invoice and who cancelled`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val id = createCompleteDraft()
            val employee = issuer()
            runBlocking { service().issue(id, employee) }

            runBlocking { service().cancel(id, cancelledBy = employee, reason = "Tippfehler") }

            transaction {
                val row = TransactionAuditLog.all().single { it.operation == "CANCEL_SALES_INVOICE" }
                assertEquals("SALES_INVOICE", row.entityType)
                assertEquals(id.toString(), row.entityId)
                assertEquals(employee.toString(), row.employeeId)
            }
        }
    }

    @Test
    fun `a refused cancellation still leaves a failed audit row naming the invoice`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val id = createCompleteDraft()

            assertFailsWith<PosException> { runBlocking { service().cancel(id, issuer(), "Tippfehler") } }

            transaction {
                val row = TransactionAuditLog.all().single { it.operation == "CANCEL_SALES_INVOICE" }
                // E2E 2026-09-27: the refused row said entity_id "FAILED", so an auditor reading the
                // invoice's trail never found the attempt. The id is the invoice; the failure is
                // carried by the entity name and the ERROR reason, as on every failed row.
                assertEquals(id.toString(), row.entityId)
                assertEquals("Operation failed due to error", row.entityName)
                assertTrue(row.reason.orEmpty().startsWith("ERROR: "), row.reason)
            }
        }
    }
}
