package org.example.project.features.allocation

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.allocation.data.repository.AllocationQueueRepositoryImpl
import org.example.project.features.allocation.data.repository.AllocationRepositoryImpl
import org.example.project.features.allocation.data.service.AllocationServiceImpl
import org.example.project.features.allocation.data.service.AllocationWriter
import org.example.project.features.contract.data.repository.ContractRepositoryImpl
import org.example.project.features.finance.data.service.BookkeepingStartServiceImpl
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A STANDALONE invoice is open from birth; a credit payment against it must close it the same way a
 * debit payment closes a supplier invoice.
 */
@DisplayName("SALES_INVOICE allocation")
class SalesInvoiceAllocationTest {
    private val service =
        AllocationServiceImpl(
            queueRepository = AllocationQueueRepositoryImpl(),
            writer = AllocationWriter(AllocationRepositoryImpl()),
            allocationRepository = AllocationRepositoryImpl(),
            contractRepository = ContractRepositoryImpl(),
            // A real one: no test here sets a Buchungsbeginn, so `cutoff()` stays null and nothing
            // is filtered — the same reasoning as AllocationServiceTest.
            bookkeepingStart = BookkeepingStartServiceImpl(ConfigurationRepository()),
            configManager = ShopConfigurationManager,
        )

    private fun setup() {
        TestData.createShopInfo()
        TestData.createManager()
    }

    private fun request(
        transactionId: Long,
        salesInvoiceId: Long,
        amount: String,
    ) = AllocationDto.CreateRequestDto(
        sourceKind = "BANK_TRANSACTION",
        targetKind = "SALES_INVOICE",
        amount = amount,
        bankTransactionId = transactionId,
        salesInvoiceId = salesInvoiceId,
    )

    @Test
    fun `a credit against a STANDALONE invoice reduces its open amount`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, invoiceId) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("119.0000")).id.value to
                        AllocationFixtures.newSalesInvoice(BigDecimal("119.0000")).id.value
                }

            val response = runBlocking { service.create(request(tx, invoiceId, "119.00"), "tester") }

            assertEquals("SALES_INVOICE", response.allocation.targetKind)
            assertEquals(invoiceId, response.allocation.salesInvoiceId)
            assertEquals("RG-2027-00001", response.allocation.salesInvoiceNumber)
            assertEquals(0, BigDecimal("0.0000").compareTo(BigDecimal(response.targetOpenAmount!!)), "119 - 119 = 0")
            transaction {
                assertEquals(0, BigDecimal.ZERO.compareTo(SalesInvoice.findById(invoiceId)!!.openAmount))
            }
        }
    }

    /**
     * Slice 4c, the **bank route**: a receivable opened at the till is cleared by the bank credit
     * through the machinery 4b-2 already shipped — no new allocation code, and deliberately **no
     * DSFinV-K Vorgang**.
     *
     * DSFinV-K Tz. 3.4 puts the payment of a receivable *"wahlweise über ein nachgelagertes System
     * oder in der Kasse"*, and `Forderungsaufloesung` describes a clearing recorded **at the
     * register**. A bank credit is the downstream-system case: it never touches the Kasse, so
     * synthesising a Vorgang for it would invent a register transaction that never happened —
     * unsigned, and belonging to no Kassenabschluss. It reaches the books through the AR ledger and
     * the Debitoren export (4d) instead.
     */
    @Test
    fun `a credit clears a POS_RECEIVABLE invoice opened at the till`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, invoiceId) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("80.0000")).id.value to
                        AllocationFixtures
                            .newSalesInvoice(
                                gross = BigDecimal("80.0000"),
                                number = "RG-2027-00009",
                                origin = RevenueOrigin.POS_RECEIVABLE,
                            ).id.value
                }

            val response = runBlocking { service.create(request(tx, invoiceId, "80.00"), "tester") }

            assertEquals("SALES_INVOICE", response.allocation.targetKind)
            assertEquals(
                0,
                BigDecimal("0.0000").compareTo(BigDecimal(response.targetOpenAmount!!)),
                "the receivable is settled by the credit",
            )
            transaction {
                val invoice = SalesInvoice.findById(invoiceId)!!
                assertEquals(RevenueOrigin.POS_RECEIVABLE, invoice.revenueOrigin, "origin is untouched by clearing")
                assertEquals(0, BigDecimal.ZERO.compareTo(invoice.openAmount))
            }
        }
    }

    /** The picker must offer a till-opened receivable, or the operator cannot reach it in Zuordnung. */
    @Test
    fun `the open-sales-invoice picker lists a POS_RECEIVABLE invoice`() {
        testApp(setupData = { setup() }) { _ ->
            val invoiceId =
                transaction {
                    AllocationFixtures
                        .newSalesInvoice(
                            gross = BigDecimal("80.0000"),
                            number = "RG-2027-00010",
                            origin = RevenueOrigin.POS_RECEIVABLE,
                        ).id.value
                }

            val open = runBlocking { service.openSalesInvoices(debtorId = null, limit = 50, offset = 0) }

            assertEquals(
                listOf(invoiceId),
                open.items.map { it.salesInvoiceId },
                "an ISSUED invoice with a positive residual qualifies whatever opened it",
            )
        }
    }

    @Test
    fun `an over-allocation goes negative, never clamped`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, invoiceId) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("200.0000")).id.value to
                        AllocationFixtures.newSalesInvoice(BigDecimal("119.0000")).id.value
                }

            runBlocking { service.create(request(tx, invoiceId, "120.00"), "tester") }

            transaction {
                assertEquals(0, BigDecimal("-1.0000").compareTo(SalesInvoice.findById(invoiceId)!!.openAmount))
            }
        }
    }

    @Test
    fun `a missing sales invoice is a 404, not a silent null`() {
        testApp(setupData = { setup() }) { _ ->
            val tx =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value
                }

            val error =
                assertFailsWith<PosException> {
                    runBlocking { service.create(request(tx, 999_999L, "1.00"), "tester") }
                }
            assertEquals(PosErrorCode.RESOURCE_NOT_FOUND, error.code)
        }
    }

    @Test
    fun `a DRAFT sales invoice is refused as a target`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, invoiceId) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        salesInvoiceWithStatus(SalesInvoiceStatus.DRAFT, "119.0000")
                }

            val error =
                assertFailsWith<PosException> {
                    runBlocking { service.create(request(tx, invoiceId, "10.00"), "tester") }
                }
            assertEquals(PosErrorCode.ALLOCATION_TARGET_NOT_OPEN, error.code)

            transaction {
                val invoice = SalesInvoice.findById(invoiceId)!!
                assertEquals(0, BigDecimal("119.0000").compareTo(invoice.openAmount), "a refused allocation must not touch the residual")
            }
        }
    }

    @Test
    fun `a CANCELLED sales invoice is refused as a target`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, invoiceId) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        salesInvoiceWithStatus(SalesInvoiceStatus.CANCELLED, "119.0000")
                }

            val error =
                assertFailsWith<PosException> {
                    runBlocking { service.create(request(tx, invoiceId, "10.00"), "tester") }
                }
            assertEquals(PosErrorCode.ALLOCATION_TARGET_NOT_OPEN, error.code)
        }
    }

    /** A sales invoice in a caller-chosen status — the ISSUED fixture is [AllocationFixtures.newSalesInvoice]. */
    private fun salesInvoiceWithStatus(
        status: SalesInvoiceStatus,
        gross: String,
    ): Long {
        val issued = AllocationFixtures.newSalesInvoice(BigDecimal(gross))
        issued.status = status
        return issued.id.value
    }
}
