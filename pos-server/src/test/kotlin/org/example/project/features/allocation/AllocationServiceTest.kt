package org.example.project.features.allocation

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.allocation.data.repository.AllocationQueueRepositoryImpl
import org.example.project.features.allocation.data.repository.AllocationRepositoryImpl
import org.example.project.features.allocation.data.service.AllocationServiceImpl
import org.example.project.features.allocation.data.service.AllocationWriter
import org.example.project.features.allocation.domain.models.PaymentAllocation
import org.example.project.features.audit.domain.models.TransactionAuditLog
import org.example.project.features.contract.data.repository.ContractRepositoryImpl
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.finance.data.service.BookkeepingStartServiceImpl
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every guard, one test each. These are the rules that keep an operator's mistake out of the
 * database — the writer below them is deliberately permissive so that a bad row which arrived some
 * other way stays visible rather than being silently clamped.
 */
@DisplayName("Allocation service")
class AllocationServiceTest {
    private val service =
        AllocationServiceImpl(
            queueRepository = AllocationQueueRepositoryImpl(),
            writer = AllocationWriter(AllocationRepositoryImpl()),
            allocationRepository = AllocationRepositoryImpl(),
            contractRepository = ContractRepositoryImpl(),
            // A real one: no test here sets a Buchungsbeginn, so `cutoff()` stays null and nothing
            // is filtered — exactly the expectations these tests already carry.
            bookkeepingStart = BookkeepingStartServiceImpl(ConfigurationRepository()),
            configManager = ShopConfigurationManager,
        )

    private fun setup() {
        TestData.createShopInfo()
        TestData.createManager()
    }

    private fun request(
        transactionId: Long,
        invoiceId: Long,
        amount: String,
    ) = AllocationDto.CreateRequestDto(
        sourceKind = "BANK_TRANSACTION",
        targetKind = "INVOICE",
        amount = amount,
        bankTransactionId = transactionId,
        invoiceId = invoiceId,
    )

    @Test
    fun `assigning part of a payment returns both new residuals`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, invoice) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        AllocationFixtures.newInvoice(BigDecimal("80.0000")).id.value
                }

            val response = runBlocking { service.create(request(tx, invoice, "60.00"), "tester") }

            assertEquals("MANUAL", response.allocation.matchedBy)
            assertEquals(0, BigDecimal("40.0000").compareTo(BigDecimal(response.sourceOpenAmount!!)))
            assertEquals(0, BigDecimal("20.0000").compareTo(BigDecimal(response.targetOpenAmount!!)))
        }
    }

    @Test
    fun `assigning more than the payment has left is rejected`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, invoice) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        AllocationFixtures.newInvoice(BigDecimal("500.0000")).id.value
                }

            val error =
                assertFailsWith<PosException> {
                    runBlocking { service.create(request(tx, invoice, "100.01"), "tester") }
                }
            assertEquals(PosErrorCode.ALLOCATION_EXCEEDS_SOURCE, error.code)
        }
    }

    @Test
    fun `the second allocation is measured against what the first one left`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, invoice) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        AllocationFixtures.newInvoice(BigDecimal("500.0000")).id.value
                }

            runBlocking { service.create(request(tx, invoice, "70.00"), "tester") }

            val error =
                assertFailsWith<PosException> {
                    runBlocking { service.create(request(tx, invoice, "31.00"), "tester") }
                }
            assertEquals(PosErrorCode.ALLOCATION_EXCEEDS_SOURCE, error.code)
        }
    }

    @Test
    fun `a transaction on an unresolved statement cannot be assigned`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, invoice) =
                transaction {
                    AllocationFixtures.newBankTransaction(BigDecimal("100.0000")).id.value to
                        AllocationFixtures.newInvoice(BigDecimal("80.0000")).id.value
                }

            val error =
                assertFailsWith<PosException> {
                    runBlocking { service.create(request(tx, invoice, "50.00"), "tester") }
                }
            assertEquals(PosErrorCode.ALLOCATION_SOURCE_UNRESOLVED, error.code)
        }
    }

    /** The target itself is unlocked; `InstalmentAllocationTest` covers it. What is missing is the id. */
    @Test
    fun `a contract instalment target without an instalment id is refused`() {
        testApp(setupData = { setup() }) { _ ->
            val tx =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value
                }

            val error =
                assertFailsWith<PosException> {
                    runBlocking {
                        service.create(
                            AllocationDto.CreateRequestDto(
                                sourceKind = "BANK_TRANSACTION",
                                targetKind = "CONTRACT_INSTALMENT",
                                amount = "50.00",
                                bankTransactionId = tx,
                            ),
                            "tester",
                        )
                    }
                }
            assertEquals(PosErrorCode.INVALID_REQUEST, error.code)
        }
    }

    @Test
    fun `an unknown kind, a non-numeric amount and a non-positive amount are all rejected`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, invoice) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        AllocationFixtures.newInvoice(BigDecimal("80.0000")).id.value
                }

            for (bad in listOf("nicht-eine-zahl", "0", "-5.00")) {
                val error =
                    assertFailsWith<PosException> {
                        runBlocking { service.create(request(tx, invoice, bad), "tester") }
                    }
                assertEquals(PosErrorCode.INVALID_REQUEST, error.code, "amount '$bad'")
            }

            val unknownKind =
                assertFailsWith<PosException> {
                    runBlocking {
                        service.create(request(tx, invoice, "10.00").copy(targetKind = "MOND"), "tester")
                    }
                }
            assertEquals(PosErrorCode.INVALID_REQUEST, unknownKind.code)
        }
    }

    @Test
    fun `a missing source or target is a 404`() {
        testApp(setupData = { setup() }) { _ ->
            val invoice = transaction { AllocationFixtures.newInvoice(BigDecimal("80.0000")).id.value }
            val tx =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value
                }

            assertEquals(
                PosErrorCode.RESOURCE_NOT_FOUND,
                assertFailsWith<PosException> {
                    runBlocking { service.create(request(999_999, invoice, "10.00"), "tester") }
                }.code,
            )
            assertEquals(
                PosErrorCode.RESOURCE_NOT_FOUND,
                assertFailsWith<PosException> {
                    runBlocking { service.create(request(tx, 999_999, "10.00"), "tester") }
                }.code,
            )
        }
    }

    @Test
    fun `reversing an allocation restores both residuals`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, invoice) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        AllocationFixtures.newInvoice(BigDecimal("80.0000")).id.value
                }

            val created = runBlocking { service.create(request(tx, invoice, "60.00"), "tester") }
            runBlocking { service.reverse(created.allocation.id, null) }

            val queue = runBlocking { service.queue(AllocationDto.QueueFilter.NICHT_ZUGEORDNET, 50, 0) }
            val row = queue.items.single()
            assertEquals(0, BigDecimal("100.0000").compareTo(BigDecimal(row.openAmount)))
            assertEquals(emptyList(), row.allocations)

            val open = runBlocking { service.openInvoices(supplierId = null, limit = 50, offset = 0) }
            assertEquals(0, BigDecimal("80.0000").compareTo(BigDecimal(open.items.single().openAmount)))
        }
    }

    /**
     * T9-12: the allocation row is hard-deleted on reversal, so without an audit row nothing at all
     * records that the assignment existed or who undid it.
     */
    @Test
    fun `reversing an allocation writes a REVERSE_ALLOCATION audit row naming it`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, invoice) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        AllocationFixtures.newInvoice(BigDecimal("80.0000")).id.value
                }
            val managerId =
                transaction {
                    Employee
                        .all()
                        .first { it.employeeNumber == "MGR001" }
                        .id.value
                        .toString()
                }

            val created = runBlocking { service.create(request(tx, invoice, "60.00"), "tester") }
            runBlocking { service.reverse(created.allocation.id, managerId) }

            transaction {
                val row = TransactionAuditLog.all().single { it.operation == "REVERSE_ALLOCATION" }
                assertEquals("PAYMENT_ALLOCATION", row.entityType)
                assertEquals(created.allocation.id.toString(), row.entityId)
                assertEquals(managerId, row.employeeId)
                assertEquals(0, BigDecimal("60.00").compareTo(assertNotNull(row.amountImpact)), "the reversed amount")
                assertContains(assertNotNull(row.entityName), "BANK_TRANSACTION $tx -> INVOICE $invoice")
            }
        }
    }

    @Test
    fun `reversing an allocation that does not exist is a 404`() {
        testApp(setupData = { setup() }) { _ ->
            assertEquals(
                PosErrorCode.RESOURCE_NOT_FOUND,
                assertFailsWith<PosException> { runBlocking { service.reverse(999_999, null) } }.code,
            )
        }
    }

    /**
     * Without these three the queue renders a bare `CONTRACT_INSTALMENT` with no indication of
     * *which* instalment was settled.
     */
    @Test
    fun `an allocation to a contract instalment carries the instalment it settled`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, instalment) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        ContractFixtures.newInstalment(BigDecimal("80.0000")).id.value
                }

            runBlocking {
                service.create(
                    AllocationDto.CreateRequestDto(
                        sourceKind = "BANK_TRANSACTION",
                        targetKind = "CONTRACT_INSTALMENT",
                        amount = "80.00",
                        bankTransactionId = tx,
                        contractInstalmentId = instalment,
                    ),
                    "tester",
                )
            }

            val row = runBlocking { service.queue(AllocationDto.QueueFilter.ALLE, 50, 0) }.items.single()

            val allocation = row.allocations.single()
            assertEquals(instalment, allocation.contractInstalmentId)
            assertEquals("Miete Ladenlokal", allocation.contractName)
            assertEquals("2026-06-01", allocation.instalmentDueDate)
        }
    }

    @Test
    fun `a create without matchedBy still records MANUAL`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, invoice) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        AllocationFixtures.newInvoice(BigDecimal("80.0000")).id.value
                }

            val response = runBlocking { service.create(request(tx, invoice, "10.00"), "tester") }

            assertEquals("MANUAL", response.allocation.matchedBy)
        }
    }

    @Test
    fun `an accepted suggestion is recorded as SUGGESTION_ACCEPTED`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, invoice) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        AllocationFixtures.newInvoice(BigDecimal("80.0000")).id.value
                }

            val response =
                runBlocking {
                    service.create(
                        request(tx, invoice, "10.00").copy(matchedBy = "SUGGESTION_ACCEPTED"),
                        "tester",
                    )
                }

            assertEquals("SUGGESTION_ACCEPTED", response.allocation.matchedBy)
        }
    }

    /**
     * Nothing applies an allocation automatically in this release, so a client claiming `AUTO_RULE`
     * would poison the one field 5d's reporting reads to learn which rules actually help.
     */
    @Test
    fun `a client claiming AUTO_RULE is refused, and so is an unknown matchedBy`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, invoice) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        AllocationFixtures.newInvoice(BigDecimal("80.0000")).id.value
                }

            for (bad in listOf("AUTO_RULE", "IRGENDWIE")) {
                val error =
                    assertFailsWith<PosException> {
                        runBlocking {
                            service.create(request(tx, invoice, "10.00").copy(matchedBy = bad), "tester")
                        }
                    }
                assertEquals(PosErrorCode.INVALID_REQUEST, error.code, "matchedBy '$bad'")
            }
        }
    }

    @Test
    fun `createAll writes every allocation and recomputes each residual`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, invoices) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        listOf(
                            AllocationFixtures.newInvoice(BigDecimal("80.0000"), "RE-A").id.value,
                            AllocationFixtures.newInvoice(BigDecimal("50.0000"), "RE-B").id.value,
                        )
                }

            val written =
                runBlocking {
                    service.createAll(
                        listOf(request(tx, invoices[0], "60.00"), request(tx, invoices[1], "30.00")),
                        "tester",
                    )
                }

            assertEquals(2, written.size)
            // The source residual falls as the batch proceeds; each target's is its own.
            assertEquals(0, BigDecimal("40.0000").compareTo(BigDecimal(written[0].sourceOpenAmount!!)))
            assertEquals(0, BigDecimal("10.0000").compareTo(BigDecimal(written[1].sourceOpenAmount!!)))
            assertEquals(0, BigDecimal("20.0000").compareTo(BigDecimal(written[0].targetOpenAmount!!)))
            assertEquals(0, BigDecimal("20.0000").compareTo(BigDecimal(written[1].targetOpenAmount!!)))
        }
    }

    /**
     * The case the batch endpoint exists for. Three separate POSTs can half-succeed, leaving a
     * payment partly assigned against a set that no longer sums — worse than not offering the button.
     *
     * The row count alone is not enough: a rollback that left a recomputed residual behind would be
     * exactly the silent corruption this refuses, so the first target's `open_amount` is asserted
     * still **null** — untouched, not merely restored.
     */
    @Test
    fun `createAll writes neither allocation when the second is invalid`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, invoices) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        listOf(
                            AllocationFixtures.newInvoice(BigDecimal("80.0000"), "RE-A").id.value,
                            AllocationFixtures.newInvoice(BigDecimal("500.0000"), "RE-B").id.value,
                        )
                }

            val error =
                assertFailsWith<PosException> {
                    runBlocking {
                        service.createAll(
                            listOf(request(tx, invoices[0], "60.00"), request(tx, invoices[1], "60.00")),
                            "tester",
                        )
                    }
                }
            assertEquals(PosErrorCode.ALLOCATION_EXCEEDS_SOURCE, error.code)

            transaction {
                assertEquals(0L, PaymentAllocation.all().count())
                assertNull(Invoice.findById(invoices[0])!!.openAmount, "first target's residual was touched")
                assertNull(BankStatementTransaction.findById(tx)!!.openAmount, "the payment's residual was touched")
            }
        }
    }

    @Test
    fun `an empty batch is refused rather than silently doing nothing`() {
        testApp(setupData = { setup() }) { _ ->
            val error =
                assertFailsWith<PosException> { runBlocking { service.createAll(emptyList(), "tester") } }
            assertEquals(PosErrorCode.INVALID_REQUEST, error.code)
        }
    }

    /**
     * The four terminal kinds are reachable only by API today, so tightening them cannot break a
     * caller — and an unmatched half of a Geldtransit means cash left the till and never reached
     * the bank.
     */
    @Test
    fun `CASH_TRANSIT without a counterKassenbuchHeaderId is 400`() {
        testApp(setupData = { setup() }) { _ ->
            val tx =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value
                }

            val error =
                assertFailsWith<PosException> {
                    runBlocking {
                        service.create(
                            AllocationDto.CreateRequestDto(
                                sourceKind = "BANK_TRANSACTION",
                                targetKind = "CASH_TRANSIT",
                                amount = "50.00",
                                bankTransactionId = tx,
                            ),
                            "tester",
                        )
                    }
                }
            assertEquals(PosErrorCode.INVALID_REQUEST, error.code)
            assertContains(error.message.orEmpty(), "counterKassenbuchHeaderId")
        }
    }

    @Test
    fun `CASH_TRANSIT naming an unknown cash-book header is 404`() {
        testApp(setupData = { setup() }) { _ ->
            val tx =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value
                }

            val error =
                assertFailsWith<PosException> {
                    runBlocking {
                        service.create(
                            AllocationDto.CreateRequestDto(
                                sourceKind = "BANK_TRANSACTION",
                                targetKind = "CASH_TRANSIT",
                                amount = "50.00",
                                bankTransactionId = tx,
                                counterKassenbuchHeaderId = 999_999L,
                            ),
                            "tester",
                        )
                    }
                }
            assertEquals(PosErrorCode.RESOURCE_NOT_FOUND, error.code)
        }
    }

    @Test
    fun `INTERNAL_TRANSFER without a counterBankAccountId is 400`() {
        testApp(setupData = { setup() }) { _ ->
            val tx =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value
                }

            val error =
                assertFailsWith<PosException> {
                    runBlocking {
                        service.create(
                            AllocationDto.CreateRequestDto(
                                sourceKind = "BANK_TRANSACTION",
                                targetKind = "INTERNAL_TRANSFER",
                                amount = "50.00",
                                bankTransactionId = tx,
                            ),
                            "tester",
                        )
                    }
                }
            assertEquals(PosErrorCode.INVALID_REQUEST, error.code)
            assertContains(error.message.orEmpty(), "counterBankAccountId")
        }
    }

    @Test
    fun `INTERNAL_TRANSFER naming an unknown bank account is 404`() {
        testApp(setupData = { setup() }) { _ ->
            val tx =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value
                }

            val error =
                assertFailsWith<PosException> {
                    runBlocking {
                        service.create(
                            AllocationDto.CreateRequestDto(
                                sourceKind = "BANK_TRANSACTION",
                                targetKind = "INTERNAL_TRANSFER",
                                amount = "50.00",
                                bankTransactionId = tx,
                                counterBankAccountId = 999_999L,
                            ),
                            "tester",
                        )
                    }
                }
            assertEquals(PosErrorCode.RESOURCE_NOT_FOUND, error.code)
        }
    }

    /** A blank string must not slip through: an all-whitespace text records nothing a null does not. */
    @Test
    fun `EXPENSE_NO_DOC without a bookingText is 400`() {
        testApp(setupData = { setup() }) { _ ->
            val tx =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value
                }

            for (bad in listOf(null, "   ")) {
                val error =
                    assertFailsWith<PosException> {
                        runBlocking {
                            service.create(
                                AllocationDto.CreateRequestDto(
                                    sourceKind = "BANK_TRANSACTION",
                                    targetKind = "EXPENSE_NO_DOC",
                                    amount = "50.00",
                                    bankTransactionId = tx,
                                    bookingText = bad,
                                ),
                                "tester",
                            )
                        }
                    }
                assertEquals(PosErrorCode.INVALID_REQUEST, error.code, "bookingText '$bad'")
                assertContains(error.message.orEmpty(), "bookingText")
            }
        }
    }

    /** A fee is evident from the transaction it sits on, so it stays free-form. */
    @Test
    fun `FEE needs no bookingText`() {
        testApp(setupData = { setup() }) { _ ->
            val tx =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value
                }

            val response =
                runBlocking {
                    service.create(
                        AllocationDto.CreateRequestDto(
                            sourceKind = "BANK_TRANSACTION",
                            targetKind = "FEE",
                            amount = "3.50",
                            bankTransactionId = tx,
                        ),
                        "tester",
                    )
                }

            assertEquals(0, BigDecimal("96.5000").compareTo(BigDecimal(response.sourceOpenAmount!!)))
            assertNull(response.targetOpenAmount)
        }
    }

    @Test
    fun `a queue row carries the allocations already made against it`() {
        testApp(setupData = { setup() }) { _ ->
            val (tx, invoice) =
                transaction {
                    AllocationFixtures.newResolvedBankTransaction(BigDecimal("100.0000")).id.value to
                        AllocationFixtures.newInvoice(BigDecimal("80.0000")).id.value
                }
            runBlocking { service.create(request(tx, invoice, "60.00"), "tester") }

            val row =
                runBlocking { service.queue(AllocationDto.QueueFilter.ALLE, 50, 0) }.items.single()

            val allocation = row.allocations.single()
            assertEquals(invoice, allocation.invoiceId)
            assertNotNull(allocation.invoiceNumber)
            assertEquals(0, BigDecimal("60.0000").compareTo(BigDecimal(allocation.amount)))
            assertFalse(row.reversalIndicator, "an ordinary debit is not a return")
        }
    }

    /**
     * T5-9: a returned direct debit is queued like any unassigned entry, but it is not a payment to
     * assign — it is why another debit's allocation disappeared. The row says so.
     */
    @Test
    fun `a returned debit is flagged on its queue row`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                AllocationFixtures.newResolvedBankTransaction(BigDecimal("129.9900")).apply {
                    creditDebit = "CREDIT"
                    reversalIndicator = true
                }
            }

            val row = runBlocking { service.queue(AllocationDto.QueueFilter.ALLE, 50, 0) }.items.single()

            assertTrue(row.reversalIndicator)
        }
    }
}
