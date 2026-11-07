package org.example.project.admin.features.allocations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.allocations.presentation.AllocationsViewModel
import org.example.project.admin.features.allocations.presentation.draftKey
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.domain.dto.allocation.SuggestionDto
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The AR side of the Zuordnung editor: a credit payment settled against a STANDALONE invoice, and
 * the CREDIT ladder's suggestions accepted the same way the AP ones are.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SalesInvoiceAllocationEditorTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun salesInvoice(
        id: Long,
        number: String = "RG-2027-0000$id",
        open: String = "119.0000",
    ) = AllocationDto.OpenSalesInvoiceDto(
        salesInvoiceId = id,
        invoiceNumber = number,
        debtorId = null,
        debtorName = "Musterfirma GmbH",
        issueDate = "2026-08-16T10:00:00",
        openAmount = open,
        currency = "EUR",
    )

    /** Expands the one queue row and selects the AR kind — where every test below starts. */
    private fun TestScope.expanded(
        vm: AllocationsViewModel,
        api: FakeAllocationApiClient,
    ) {
        api.rowsByFilter = mapOf(AllocationDto.QueueFilter.NICHT_ZUGEORDNET to listOf(queueRow(id = 1L, amount = "119.0000", open = "119.0000")))
        vm.load()
        advanceUntilIdle()
        vm.toggleExpanded(1L)
        advanceUntilIdle()
        vm.selectTargetKind("SALES_INVOICE")
        advanceUntilIdle()
    }

    @Test
    fun `selecting SALES_INVOICE loads the AR candidates`() =
        runTest(dispatcher) {
            val api = FakeAllocationApiClient().apply { salesInvoices = listOf(salesInvoice(id = 7L)) }
            val vm = allocationsViewModel(api)
            expanded(vm, api)

            assertEquals(listOf<Long?>(null), api.openSalesInvoiceCalls)
            assertEquals(listOf(7L), vm.state.openSalesInvoices.map { it.salesInvoiceId })
        }

    @Test
    fun `allocateSalesInvoice posts a SALES_INVOICE allocation with the canonical amount`() =
        runTest(dispatcher) {
            val api = FakeAllocationApiClient().apply { salesInvoices = listOf(salesInvoice(id = 7L)) }
            val vm = allocationsViewModel(api)
            expanded(vm, api)
            val key = draftKey("SALES_INVOICE", 7L)
            vm.setAmount(key, "119.00")

            vm.allocateSalesInvoice(transactionId = 1L, salesInvoiceId = 7L)
            advanceUntilIdle()

            assertEquals(1, api.created.size)
            val request = api.created.single()
            assertEquals("SALES_INVOICE", request.targetKind)
            assertEquals(7L, request.salesInvoiceId)
            assertEquals("119.00", request.amount)
        }

    @Test
    fun `an amount above the open residual refuses the button`() =
        runTest(dispatcher) {
            val api = FakeAllocationApiClient().apply { salesInvoices = listOf(salesInvoice(id = 7L)) }
            val vm = allocationsViewModel(api)
            expanded(vm, api)
            val key = draftKey("SALES_INVOICE", 7L)
            vm.setAmount(key, "120.00")

            assertNull(vm.draftFor(key, "119.0000"), "the button must be disabled past the residual")
        }

    @Test
    fun `accepting a SALES_INVOICE suggestion posts salesInvoiceId with the matched signal`() =
        runTest(dispatcher) {
            val api =
                FakeAllocationApiClient().apply {
                    salesInvoices = listOf(salesInvoice(id = 7L))
                    suggestionsByTransaction =
                        mapOf(
                            1L to
                                listOf(
                                    SuggestionDto.ItemDto(
                                        targetKind = "SALES_INVOICE",
                                        signal = "STRUCTURED_REFERENCE",
                                        strength = "EXACT",
                                        amount = "119.00",
                                        currency = "EUR",
                                        label = "RG-2027-00007",
                                        salesInvoiceIds = listOf(7L),
                                    ),
                                ),
                        )
                }
            val vm = allocationsViewModel(api)
            expanded(vm, api)

            assertEquals(1, vm.state.suggestions.size, "expanding the row loads the canned suggestion")
            vm.acceptSuggestion(vm.state.suggestions.single())
            advanceUntilIdle()

            assertEquals(1, api.createdBatches.size)
            val request = api.createdBatches.single().single()
            assertEquals("SALES_INVOICE", request.targetKind)
            assertEquals(7L, request.salesInvoiceId)
            assertEquals("SUGGESTION_ACCEPTED", request.matchedBy)
            assertEquals("STRUCTURED_REFERENCE", request.matchedSignal)
            assertNull(request.invoiceId, "the AP id must stay unset for an AR suggestion")
        }

    @Test
    fun `accepting a subset SALES_INVOICE suggestion reads each open amount`() =
        runTest(dispatcher) {
            val api =
                FakeAllocationApiClient().apply {
                    salesInvoices = listOf(salesInvoice(id = 7L, open = "100.0000"), salesInvoice(id = 8L, open = "200.0000"))
                    suggestionsByTransaction =
                        mapOf(
                            1L to
                                listOf(
                                    SuggestionDto.ItemDto(
                                        targetKind = "SALES_INVOICE",
                                        signal = "SUBSET_SUM",
                                        strength = "MEDIUM",
                                        amount = "300.00",
                                        currency = "EUR",
                                        label = "RG-2027-00007, RG-2027-00008",
                                        salesInvoiceIds = listOf(7L, 8L),
                                    ),
                                ),
                        )
                }
            val vm = allocationsViewModel(api)
            expanded(vm, api)

            vm.acceptSuggestion(vm.state.suggestions.single())
            advanceUntilIdle()

            val batch = api.createdBatches.single()
            assertEquals(listOf("100.0000", "200.0000"), batch.map { it.amount })
            assertEquals(listOf(7L, 8L), batch.map { it.salesInvoiceId })
        }
}
