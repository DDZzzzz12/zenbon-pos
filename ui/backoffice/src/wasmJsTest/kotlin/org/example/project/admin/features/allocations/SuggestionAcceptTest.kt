package org.example.project.admin.features.allocations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.domain.dto.allocation.SuggestionDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun suggestionQueueRow(id: Long) =
    AllocationDto.QueueRowDto(
        transactionId = id,
        bookingDate = "2026-03-01T00:00",
        valueDate = "2026-03-01T00:00",
        amount = "300.0000",
        openAmount = "300.0000",
        creditDebit = "DEBIT",
        counterpartyName = "Metro AG",
        remittance = "RE-2026-0001",
    )

private fun suggestionInvoice(
    id: Long,
    open: String,
) = AllocationDto.OpenInvoiceDto(
    invoiceId = id,
    invoiceNumber = "RE-$id",
    supplierName = "Metro AG",
    openAmount = open,
)

private fun invoiceSuggestion(
    ids: List<Long>,
    amount: String,
) = SuggestionDto.ItemDto(
    targetKind = "INVOICE",
    signal = if (ids.size > 1) "SUBSET_SUM" else "STRUCTURED_REFERENCE",
    strength = if (ids.size > 1) "MEDIUM" else "EXACT",
    amount = amount,
    currency = "EUR",
    label = ids.joinToString(", ") { "RE-$it" },
    invoiceIds = ids,
)

private val instalmentSuggestion =
    SuggestionDto.ItemDto(
        targetKind = "CONTRACT_INSTALMENT",
        signal = "CONTRACT_WINDOW",
        strength = "STRONG",
        amount = "300.0000",
        currency = "EUR",
        label = "Miete · 2026-03-01",
        contractInstalmentId = 55,
    )

/**
 * Accepting a suggestion. The subset case is the one that matters: three invoices go out as **one**
 * batch, each carrying its **own** open amount — never a client-side split of the payment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SuggestionAcceptTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun api(
        suggestions: List<SuggestionDto.ItemDto>,
        invoices: List<AllocationDto.OpenInvoiceDto> = listOf(suggestionInvoice(3, "300.0000")),
    ): FakeAllocationApiClient =
        FakeAllocationApiClient().apply {
            rowsByFilter = mapOf(AllocationDto.QueueFilter.NICHT_ZUGEORDNET to listOf(suggestionQueueRow(7)))
            this.invoices = invoices
            suggestionsByTransaction = mapOf(7L to suggestions)
        }

    private fun expanded(api: FakeAllocationApiClient) = allocationsViewModel(api)

    @Test
    fun `expanding a row fetches its suggestions`() =
        runTest(dispatcher) {
            val api = api(listOf(invoiceSuggestion(listOf(3), "300.0000")))
            val vm = expanded(api)
            vm.load()
            advanceUntilIdle()

            vm.toggleExpanded(7)
            advanceUntilIdle()

            assertEquals(listOf(7L), api.suggestionCalls)
            assertEquals(1, vm.state.suggestions.size)
        }

    @Test
    fun `collapsing clears the suggestions`() =
        runTest(dispatcher) {
            val api = api(listOf(invoiceSuggestion(listOf(3), "300.0000")))
            val vm = expanded(api)
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()

            vm.toggleExpanded(7)
            advanceUntilIdle()

            assertTrue(vm.state.suggestions.isEmpty())
        }

    @Test
    fun `accepting a single-invoice suggestion sends one request marked as accepted`() =
        runTest(dispatcher) {
            val api = api(listOf(invoiceSuggestion(listOf(3), "300.0000")))
            val vm = expanded(api)
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()

            vm.acceptSuggestion(vm.state.suggestions.single())
            advanceUntilIdle()

            val request = api.createdBatches.single().single()
            assertEquals("BANK_TRANSACTION", request.sourceKind)
            assertEquals("INVOICE", request.targetKind)
            assertEquals(7L, request.bankTransactionId)
            assertEquals(3L, request.invoiceId)
            assertEquals("300.0000", request.amount)
            assertEquals("SUGGESTION_ACCEPTED", request.matchedBy)
            assertEquals(
                "STRUCTURED_REFERENCE",
                request.matchedSignal,
                "which signal produced it, or the telemetry cannot say which rules earn their place",
            )
            assertTrue(api.created.isEmpty(), "an accepted suggestion never uses the single-write path")
        }

    @Test
    fun `accepting a subset suggestion sends one batch of three, each with its own open amount`() =
        runTest(dispatcher) {
            val api =
                api(
                    suggestions = listOf(invoiceSuggestion(listOf(3, 4, 5), "300.0000")),
                    invoices =
                        listOf(
                            suggestionInvoice(3, "100.0000"),
                            suggestionInvoice(4, "120.0000"),
                            suggestionInvoice(5, "80.0000"),
                        ),
                )
            val vm = expanded(api)
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()

            vm.acceptSuggestion(vm.state.suggestions.single())
            advanceUntilIdle()

            val batch = api.createdBatches.single()
            assertEquals(3, batch.size)
            assertEquals(listOf(3L, 4L, 5L), batch.map { it.invoiceId })
            assertEquals(listOf("100.0000", "120.0000", "80.0000"), batch.map { it.amount })
            assertTrue(batch.all { it.matchedBy == "SUGGESTION_ACCEPTED" })
            assertTrue(
                batch.all { it.matchedSignal == "SUBSET_SUM" },
                "every item of the batch carries the signal that produced the suggestion",
            )
        }

    @Test
    fun `a subset naming an invoice the editor cannot see sends nothing and surfaces an error`() =
        runTest(dispatcher) {
            val api =
                api(
                    suggestions = listOf(invoiceSuggestion(listOf(3, 9), "300.0000")),
                    invoices = listOf(suggestionInvoice(3, "100.0000")),
                )
            val vm = expanded(api)
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()

            vm.acceptSuggestion(vm.state.suggestions.single())
            advanceUntilIdle()

            assertTrue(api.createdBatches.isEmpty(), "a guessed amount is worse than no write")
            assertNotNull(vm.state.error)
        }

    @Test
    fun `accepting a contract instalment suggestion names the instalment and no invoice`() =
        runTest(dispatcher) {
            val api = api(listOf(instalmentSuggestion))
            val vm = expanded(api)
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()

            vm.acceptSuggestion(vm.state.suggestions.single())
            advanceUntilIdle()

            val request = api.createdBatches.single().single()
            assertEquals("CONTRACT_INSTALMENT", request.targetKind)
            assertEquals(55L, request.contractInstalmentId)
            assertEquals(null, request.invoiceId)
            assertEquals("300.0000", request.amount)
        }

    @Test
    fun `accepting re-reads the queue and the open invoices`() =
        runTest(dispatcher) {
            val api = api(listOf(invoiceSuggestion(listOf(3), "300.0000")))
            val vm = expanded(api)
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()

            vm.acceptSuggestion(vm.state.suggestions.single())
            advanceUntilIdle()

            assertEquals(2, api.queueCalls.size, "the residuals moved, so the queue must be re-read")
            assertEquals(2, api.openInvoiceCalls.size, "the candidates must be re-read too")
            assertEquals(
                2,
                api.suggestionCalls.size,
                "the proposals must be re-read too: the accepted one no longer applies, and leaving " +
                    "it on screen invites a second Übernehmen that only the server would refuse",
            )
            assertFalse(vm.state.isSaving)
        }

    @Test
    fun `a rejected accept surfaces an error and leaves the suggestions on screen`() =
        runTest(dispatcher) {
            val api = api(listOf(invoiceSuggestion(listOf(3), "300.0000")))
            val vm = expanded(api)
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()
            api.failWith = PosApiException(PosErrorCode.ALLOCATION_EXCEEDS_SOURCE, 409, "too much", null)

            vm.acceptSuggestion(vm.state.suggestions.single())
            advanceUntilIdle()

            assertNotNull(vm.state.error)
            assertFalse(vm.state.isSaving)
            assertEquals(1, vm.state.suggestions.size, "the operator must still be able to try the other one")
        }
}
