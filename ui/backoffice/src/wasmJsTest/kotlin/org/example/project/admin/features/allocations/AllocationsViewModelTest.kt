package org.example.project.admin.features.allocations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.allocations.presentation.draftKey
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun openInvoice(
    id: Long,
    open: String = "80.0000",
) = AllocationDto.OpenInvoiceDto(
    invoiceId = id,
    invoiceNumber = "RE-$id",
    supplierName = "Metro AG",
    openAmount = open,
)

private fun openInstalment(
    id: Long,
    open: String = "90.0000",
) = AllocationDto.OpenInstalmentDto(
    instalmentId = id,
    contractId = 11,
    contractName = "Miete Ladenlokal",
    supplierName = "Immo GmbH",
    dueDate = "2026-06-01T00:00",
    expectedAmount = open,
    openAmount = open,
    status = "OPEN",
)

/** The draft key of the one candidate invoice the fake serves. */
private val invoiceKey = draftKey("INVOICE", 3)

/** The draft key of the one candidate instalment the fake serves. */
private val instalmentKey = draftKey("CONTRACT_INSTALMENT", 5)

@OptIn(ExperimentalCoroutinesApi::class)
class AllocationsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun api(): FakeAllocationApiClient =
        FakeAllocationApiClient().apply {
            rowsByFilter =
                mapOf(AllocationDto.QueueFilter.NICHT_ZUGEORDNET to listOf(queueRow(7)))
            invoices = listOf(openInvoice(3))
            instalments = listOf(openInstalment(5))
        }

    @Test
    fun `load fills the queue with the unassigned filter`() =
        runTest(dispatcher) {
            val api = api()
            val vm = allocationsViewModel(api)

            vm.load()
            advanceUntilIdle()

            assertEquals(listOf(AllocationDto.QueueFilter.NICHT_ZUGEORDNET), api.queueCalls)
            assertEquals(1, vm.state.rows.size)
            assertEquals(1L, vm.state.total)
            assertFalse(vm.state.isLoading)
        }

    @Test
    fun `selecting the active filter does not dispatch a request`() =
        runTest(dispatcher) {
            val api = api()
            val vm = allocationsViewModel(api)
            vm.load()
            advanceUntilIdle()

            vm.selectFilter(AllocationDto.QueueFilter.NICHT_ZUGEORDNET)
            advanceUntilIdle()

            assertEquals(1, api.queueCalls.size)
        }

    @Test
    fun `expanding a row loads the open invoices, collapsing clears them and the drafts`() =
        runTest(dispatcher) {
            val api = api()
            val vm = allocationsViewModel(api)
            vm.load()
            advanceUntilIdle()

            vm.toggleExpanded(7)
            advanceUntilIdle()
            vm.setAmount(invoiceKey, "60.00")

            assertEquals(7L, vm.state.expandedTransactionId)
            assertEquals(1, vm.state.openInvoices.size)
            assertEquals("60.00", vm.state.amountDrafts[invoiceKey])

            vm.toggleExpanded(7)
            advanceUntilIdle()

            assertNull(vm.state.expandedTransactionId)
            assertTrue(vm.state.openInvoices.isEmpty())
            assertTrue(vm.state.amountDrafts.isEmpty())
        }

    @Test
    fun `allocating sends the canonical amount and re-reads both lists`() =
        runTest(dispatcher) {
            val api = api()
            val vm = allocationsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()
            vm.setAmount(invoiceKey, "60,50")

            vm.allocate(transactionId = 7, invoiceId = 3)
            advanceUntilIdle()

            val request = api.created.single()
            assertEquals("BANK_TRANSACTION", request.sourceKind)
            assertEquals("INVOICE", request.targetKind)
            assertEquals(7L, request.bankTransactionId)
            assertEquals(3L, request.invoiceId)
            assertEquals("60.50", request.amount)
            assertEquals(2, api.queueCalls.size, "the queue must be re-read from the server")
            assertEquals(2, api.openInvoiceCalls.size, "the candidates must be re-read too")
            assertFalse(vm.state.isSaving)
        }

    @Test
    fun `a draft that exceeds the residual is never sent`() =
        runTest(dispatcher) {
            val api = api()
            val vm = allocationsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()
            vm.setAmount(invoiceKey, "100.01")

            vm.allocate(transactionId = 7, invoiceId = 3)
            advanceUntilIdle()

            assertTrue(api.created.isEmpty())
        }

    @Test
    fun `an unparseable or empty draft is never sent`() =
        runTest(dispatcher) {
            val api = api()
            val vm = allocationsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()

            vm.allocate(transactionId = 7, invoiceId = 3)
            vm.setAmount(invoiceKey, "abc")
            vm.allocate(transactionId = 7, invoiceId = 3)
            advanceUntilIdle()

            assertTrue(api.created.isEmpty())
        }

    @Test
    fun `reversing removes the allocation and re-reads`() =
        runTest(dispatcher) {
            val api = api()
            val vm = allocationsViewModel(api)
            vm.load()
            advanceUntilIdle()

            vm.reverse(42)
            advanceUntilIdle()

            assertEquals(listOf(42L), api.reversed)
            assertEquals(2, api.queueCalls.size)
        }

    @Test
    fun `a rejected allocation surfaces as an error and stops saving`() =
        runTest(dispatcher) {
            val api = api()
            val vm = allocationsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()
            vm.setAmount(invoiceKey, "50.00")
            api.failWith =
                PosApiException(
                    PosErrorCode.ALLOCATION_EXCEEDS_SOURCE,
                    409,
                    "too much",
                    null,
                )

            vm.allocate(transactionId = 7, invoiceId = 3)
            advanceUntilIdle()

            assertNotNull(vm.state.error)
            assertFalse(vm.state.isSaving)
        }

    @Test
    fun `selecting a target kind clears the amount drafts`() =
        runTest(dispatcher) {
            val vm = allocationsViewModel(api())
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()
            vm.setAmount(invoiceKey, "50.00")

            vm.selectTargetKind("FEE")

            assertEquals("FEE", vm.state.targetKind)
            assertTrue(vm.state.amountDrafts.isEmpty(), "an invoice draft must not carry over to a fee")
        }

    @Test
    fun `collapsing a row resets the target kind to INVOICE`() =
        runTest(dispatcher) {
            val vm = allocationsViewModel(api())
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()
            vm.selectTargetKind("FEE")
            vm.setBookingText("Kontoführung")
            vm.setAccountNo("6855")

            vm.toggleExpanded(7)
            advanceUntilIdle()

            assertEquals("INVOICE", vm.state.targetKind)
            assertEquals("", vm.state.bookingTextDraft)
            assertEquals("", vm.state.accountNoDraft)
        }

    @Test
    fun `a fee is posted with no invoice id`() =
        runTest(dispatcher) {
            val api = api()
            val vm = allocationsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()
            vm.selectTargetKind("FEE")
            vm.setAmount(draftKey("FEE", null), "3,50")

            vm.allocateTerminal(transactionId = 7, kind = "FEE")
            advanceUntilIdle()

            val request = api.created.single()
            assertEquals("FEE", request.targetKind)
            assertEquals("3.50", request.amount)
            assertEquals(7L, request.bankTransactionId)
            assertNull(request.invoiceId, "a fee settles no invoice")
            assertNull(request.contractInstalmentId, "a fee settles no contract instalment")
            assertNull(request.counterBankAccountId, "a fee is terminal — it has no counter-leg")
            assertNull(request.counterKassenbuchHeaderId, "a fee is terminal — it has no counter-leg")
        }

    @Test
    fun `an undocumented expense with a blank booking text is not posted`() =
        runTest(dispatcher) {
            val api = api()
            val vm = allocationsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()
            vm.selectTargetKind("EXPENSE_NO_DOC")
            vm.setAmount(draftKey("EXPENSE_NO_DOC", null), "12.00")
            vm.setBookingText("   ")

            vm.allocateTerminal(transactionId = 7, kind = "EXPENSE_NO_DOC")
            advanceUntilIdle()

            assertTrue(api.created.isEmpty(), "the server refuses this with a 400 — so must the client")
        }

    @Test
    fun `an undocumented expense posts its booking text`() =
        runTest(dispatcher) {
            val api = api()
            val vm = allocationsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()
            vm.selectTargetKind("EXPENSE_NO_DOC")
            vm.setAmount(draftKey("EXPENSE_NO_DOC", null), "12.00")
            vm.setBookingText("Parkgebühr Lieferung")
            vm.setAccountNo("6855")

            vm.allocateTerminal(transactionId = 7, kind = "EXPENSE_NO_DOC")
            advanceUntilIdle()

            val request = api.created.single()
            assertEquals("EXPENSE_NO_DOC", request.targetKind)
            assertEquals("Parkgebühr Lieferung", request.bookingText)
            assertEquals("6855", request.accountNo)
            assertNull(request.invoiceId)
        }

    @Test
    fun `selecting the instalment kind loads the open instalments`() =
        runTest(dispatcher) {
            val api = api()
            val vm = allocationsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()

            vm.selectTargetKind("CONTRACT_INSTALMENT")
            advanceUntilIdle()

            assertEquals(1, api.openInstalmentCalls.size)
            assertEquals(1, vm.state.openInstalments.size)
        }

    @Test
    fun `selecting the instalment kind twice does not re-fetch`() =
        runTest(dispatcher) {
            val api = api()
            val vm = allocationsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()

            vm.selectTargetKind("CONTRACT_INSTALMENT")
            advanceUntilIdle()
            // Away and back: selecting the already-active chip returns early on its own, so only a
            // round trip through another kind can prove the list itself is what stops the re-read.
            vm.selectTargetKind("FEE")
            vm.selectTargetKind("CONTRACT_INSTALMENT")
            advanceUntilIdle()

            assertEquals(1, api.openInstalmentCalls.size, "the loaded list is kept for the expanded row")
        }

    @Test
    fun `assigning to an instalment posts its id`() =
        runTest(dispatcher) {
            val api = api()
            val vm = allocationsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()
            vm.selectTargetKind("CONTRACT_INSTALMENT")
            advanceUntilIdle()
            vm.setAmount(instalmentKey, "90,00")

            vm.allocateInstalment(transactionId = 7, instalmentId = 5)
            advanceUntilIdle()

            val request = api.created.single()
            assertEquals("BANK_TRANSACTION", request.sourceKind)
            assertEquals("CONTRACT_INSTALMENT", request.targetKind)
            assertEquals(7L, request.bankTransactionId)
            assertEquals(5L, request.contractInstalmentId)
            assertEquals("90.00", request.amount)
            assertNull(request.invoiceId, "an instalment settles no invoice")
            assertFalse(vm.state.isSaving)
        }

    @Test
    fun `collapsing a row clears the instalment list`() =
        runTest(dispatcher) {
            val api = api()
            val vm = allocationsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()
            vm.selectTargetKind("CONTRACT_INSTALMENT")
            advanceUntilIdle()
            assertEquals(1, vm.state.openInstalments.size)

            vm.toggleExpanded(7)
            advanceUntilIdle()

            assertTrue(vm.state.openInstalments.isEmpty())
        }

    @Test
    fun `assigning the selection sends every picked invoice as one batch`() =
        runTest(dispatcher) {
            val api = api()
            val vm = allocationsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()
            vm.setAmount(invoiceKey, "60,50")

            vm.allocateSelected(transactionId = 7)
            advanceUntilIdle()

            val batch = api.createdBatches.single()
            assertEquals(1, batch.size)
            assertEquals(3L, batch.single().invoiceId)
            assertEquals("60.50", batch.single().amount)
            assertTrue(api.created.isEmpty(), "a selection goes out as a batch, never as single writes")
            assertTrue(vm.state.amountDrafts.isEmpty(), "the booked drafts are cleared")
            assertFalse(vm.state.isSaving)
        }

    @Test
    fun `an empty selection or one above the open amount sends nothing`() =
        runTest(dispatcher) {
            val api = api()
            val vm = allocationsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.toggleExpanded(7)
            advanceUntilIdle()

            vm.allocateSelected(transactionId = 7)
            vm.setAmount(invoiceKey, "999,00")
            vm.allocateSelected(transactionId = 7)
            advanceUntilIdle()

            assertTrue(api.createdBatches.isEmpty())
        }
}
