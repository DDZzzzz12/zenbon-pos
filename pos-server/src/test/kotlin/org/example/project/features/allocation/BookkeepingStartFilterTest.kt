package org.example.project.features.allocation

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.features.allocation.data.repository.AllocationQueueRepositoryImpl
import org.example.project.features.allocation.data.repository.AllocationRepositoryImpl
import org.example.project.features.allocation.data.service.AllocationServiceImpl
import org.example.project.features.allocation.data.service.AllocationWriter
import org.example.project.features.contract.data.repository.ContractRepositoryImpl
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.data.service.InvoiceListServiceImpl
import org.example.project.features.finance.data.service.BookkeepingStartServiceImpl
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * The cut-off is what stops this feature's first day from being its last useful one: without it
 * every historical invoice the backfill created lands in *Offen* at its full amount, because the
 * backfill never writes `amount_due` and nothing carries `paid_at`.
 *
 * Both sides are asserted here rather than in the two service tests, because the point is that they
 * agree — a cut-off applied to one list and not the other is worse than none at all.
 */
@DisplayName("Buchungsbeginn filter")
class BookkeepingStartFilterTest {
    private val bookkeepingStart = BookkeepingStartServiceImpl(ConfigurationRepository())
    private val queueRepository = AllocationQueueRepositoryImpl()

    private fun allocationService() =
        AllocationServiceImpl(
            queueRepository = queueRepository,
            writer = AllocationWriter(AllocationRepositoryImpl()),
            allocationRepository = AllocationRepositoryImpl(),
            contractRepository = ContractRepositoryImpl(),
            bookkeepingStart = bookkeepingStart,
            configManager = ShopConfigurationManager,
        )

    private fun invoiceListService() = InvoiceListServiceImpl(DocumentRepositoryImpl(), queueRepository, bookkeepingStart)

    private fun setup() {
        TestData.createShopInfo()
        TestData.createManager()
    }

    @Test
    fun `an invoice issued before the cut-off is not open`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                AllocationFixtures
                    .newInvoice(BigDecimal("500.0000"), number = "RE-ALT")
                    .apply { issueDate = LocalDateTime(2020, 1, 1, 0, 0) }
                AllocationFixtures
                    .newInvoice(BigDecimal("100.0000"), number = "RE-NEU")
                    .apply { issueDate = LocalDateTime(2099, 1, 1, 0, 0) }
            }
            runBlocking { bookkeepingStart.update("2026-01-01") }

            val open = runBlocking { invoiceListService().list(InvoiceListDto.Bucket.OFFEN, 50, 0) }

            assertEquals(listOf("RE-NEU"), open.items.map { it.invoiceNumber })
            assertEquals(1L, open.bucketCounts["OFFEN"])
        }
    }

    @Test
    fun `an invoice with no issue date stays open`() {
        testApp(setupData = { setup() }) { _ ->
            transaction { AllocationFixtures.newInvoice(BigDecimal("100.0000"), number = "RE-OHNE") }
            runBlocking { bookkeepingStart.update("2026-01-01") }

            val open = runBlocking { invoiceListService().list(InvoiceListDto.Bucket.OFFEN, 50, 0) }

            assertEquals(
                listOf("RE-OHNE"),
                open.items.map { it.invoiceNumber },
                "an unknown issue date cannot be shown to be out of scope, so it stays listed",
            )
        }
    }

    @Test
    fun `a transaction dated before the cut-off leaves the queue`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                AllocationFixtures.newResolvedBankTransaction(BigDecimal("40.0000")).apply {
                    valueDate = LocalDateTime(2020, 5, 5, 0, 0)
                }
            }
            runBlocking { bookkeepingStart.update("2026-01-01") }

            val queue =
                runBlocking {
                    allocationService().queue(AllocationDto.QueueFilter.NICHT_ZUGEORDNET, 50, 0)
                }

            assertEquals(0, queue.items.size)
            assertEquals(0L, queue.total)
        }
    }

    /**
     * The same row, through the other filter. A cut-off that hides a transaction from
     * *Nicht zugeordnet* but leaves it in *Alle* is a confusing half-rule.
     */
    @Test
    fun `the ALLE filter honours the cut-off too`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                AllocationFixtures.newResolvedBankTransaction(BigDecimal("40.0000")).apply {
                    valueDate = LocalDateTime(2020, 5, 5, 0, 0)
                }
            }
            runBlocking { bookkeepingStart.update("2026-01-01") }

            val queue =
                runBlocking { allocationService().queue(AllocationDto.QueueFilter.ALLE, 50, 0) }

            assertEquals(0, queue.items.size)
            assertEquals(0L, queue.total)
        }
    }

    /**
     * The regression this file exists for. Reading the invoice list must establish the cut-off by
     * itself.
     *
     * Before this, only `get()` — reachable solely from the Zuordnung screen — created the default,
     * and `cutoff()` returned null while the setting was missing, meaning "filter nothing". An
     * operator whose first Finanzen screen was *Rechnungen*, which sits above Zuordnung in the
     * sidebar, therefore saw the shop's entire invoice history reported as unpaid. That is the exact
     * day-one failure the Buchungsbeginn exists to prevent.
     *
     * The fixture is the shape `InvoiceBackfill` produces: an old `issueDate`, a
     * `taxInclusiveTotal`, and no `amountDue`.
     */
    @Test
    fun `reading the invoice list is itself enough to establish the cut-off`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                AllocationFixtures
                    .newInvoice(BigDecimal("500.0000"), number = "RE-ALT")
                    .apply { issueDate = LocalDateTime(2020, 1, 1, 0, 0) }
                AllocationFixtures.newResolvedBankTransaction(BigDecimal("40.0000")).apply {
                    valueDate = LocalDateTime(2020, 5, 5, 0, 0)
                }
            }

            // No get(), no update(), no finance endpoint at all — the list read is the first call.
            val open = runBlocking { invoiceListService().list(InvoiceListDto.Bucket.OFFEN, 50, 0) }
            val queue =
                runBlocking {
                    allocationService().queue(AllocationDto.QueueFilter.NICHT_ZUGEORDNET, 50, 0)
                }

            assertEquals(
                emptyList(),
                open.items.map { it.invoiceNumber },
                "a pre-Buchungsbeginn invoice must not be reported open just because nobody opened Zuordnung",
            )
            assertEquals(0L, open.bucketCounts["OFFEN"])
            assertEquals(0, queue.items.size, "the queue must see the same cut-off")
        }
    }
}
