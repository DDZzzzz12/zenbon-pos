package org.example.project.admin.features.allocations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.finance.FakeBookkeepingStartApiClient
import org.example.project.admin.features.finance.presentation.isoDateOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Buchungsbeginn banner on Zuordnung.
 *
 * It is raised by the server's `needsAcknowledgement`, which is **durable**: true on every read
 * until a manager confirms the date. Dismissing therefore has to write, which is the opposite of
 * what the earlier one-shot `wasDefaulted` flag needed — that one was consumed by whichever request
 * happened to create the default first, so a purely local dismissal was enough. Since Rechnungen
 * shows the same banner, a local dismissal would now last only until the other screen was opened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuchungsbeginnTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(bookkeeping: FakeBookkeepingStartApiClient) = allocationsViewModel(bookkeeping = bookkeeping)

    @Test
    fun `an unacknowledged Buchungsbeginn raises the banner`() =
        runTest(dispatcher) {
            val bookkeeping = FakeBookkeepingStartApiClient().apply { needsAcknowledgement = true }
            val vm = viewModel(bookkeeping)

            vm.load()
            advanceUntilIdle()

            assertTrue(vm.bookkeeping.state.showBanner)
            assertEquals("2026-08-01", vm.bookkeeping.state.startDate)
        }

    @Test
    fun `an acknowledged Buchungsbeginn raises no banner`() =
        runTest(dispatcher) {
            val bookkeeping = FakeBookkeepingStartApiClient()
            val vm = viewModel(bookkeeping)

            vm.load()
            advanceUntilIdle()

            assertFalse(vm.bookkeeping.state.showBanner)
            assertEquals("2026-08-01", vm.bookkeeping.state.startDate)
        }

    @Test
    fun `dismissing acknowledges once and re-reads nothing`() =
        runTest(dispatcher) {
            val bookkeeping = FakeBookkeepingStartApiClient().apply { needsAcknowledgement = true }
            val allocations = FakeAllocationApiClient()
            val vm = allocationsViewModel(allocations, bookkeeping)
            vm.load()
            advanceUntilIdle()
            val readsSoFar = bookkeeping.getCalls
            val queueReads = allocations.queueCalls.size

            vm.bookkeeping.acknowledge()
            advanceUntilIdle()

            assertFalse(vm.bookkeeping.state.showBanner)
            assertEquals(1, bookkeeping.acknowledgeCalls, "dismissal must reach the server exactly once")
            assertTrue(bookkeeping.updated.isEmpty(), "dismissal must not change the date")
            assertEquals(readsSoFar, bookkeeping.getCalls, "dismissal must not re-read the setting")
            assertEquals(
                queueReads,
                allocations.queueCalls.size,
                "acknowledging moves no date, so the queue cannot have changed",
            )
        }

    @Test
    fun `a dismissed banner stays down on the next load`() =
        runTest(dispatcher) {
            val bookkeeping = FakeBookkeepingStartApiClient().apply { needsAcknowledgement = true }
            val vm = viewModel(bookkeeping)
            vm.load()
            advanceUntilIdle()

            vm.bookkeeping.acknowledge()
            advanceUntilIdle()
            vm.load()
            advanceUntilIdle()

            assertFalse(vm.bookkeeping.state.showBanner, "the acknowledgement is stored, not local")
        }

    /**
     * T5-9: the banner used to be the only place the date showed or could be changed, so once it was
     * dismissed the Buchungsbeginn was out of reach in the UI. The quiet line takes over exactly then.
     */
    @Test
    fun `after dismissing the banner the date stays visible and can still be changed`() =
        runTest(dispatcher) {
            val bookkeeping = FakeBookkeepingStartApiClient().apply { needsAcknowledgement = true }
            val vm = viewModel(bookkeeping)
            vm.load()
            advanceUntilIdle()
            assertFalse(vm.bookkeeping.state.showCurrentDate, "the banner already shows the date")

            vm.bookkeeping.acknowledge()
            advanceUntilIdle()
            vm.load()
            advanceUntilIdle()

            assertTrue(vm.bookkeeping.state.showCurrentDate, "the date must stay reachable once the banner is gone")
            vm.bookkeeping.startEditing()
            vm.saveBookkeepingStart("2026-01-01")
            advanceUntilIdle()

            assertEquals(listOf("2026-01-01"), bookkeeping.updated)
            assertEquals("2026-01-01", vm.bookkeeping.state.startDate)
            assertTrue(vm.bookkeeping.state.showCurrentDate)
        }

    @Test
    fun `saving a date writes it once and re-reads the queue`() =
        runTest(dispatcher) {
            val bookkeeping = FakeBookkeepingStartApiClient().apply { needsAcknowledgement = true }
            val allocations = FakeAllocationApiClient()
            val vm = allocationsViewModel(allocations, bookkeeping)
            vm.load()
            advanceUntilIdle()
            val queueReads = allocations.queueCalls.size

            vm.bookkeeping.startEditing()
            vm.saveBookkeepingStart("2025-01-01")
            advanceUntilIdle()

            assertEquals(listOf("2025-01-01"), bookkeeping.updated)
            assertEquals("2025-01-01", vm.bookkeeping.state.startDate)
            assertFalse(vm.bookkeeping.state.isEditing)
            assertFalse(vm.bookkeeping.state.showBanner)
            assertEquals(0, bookkeeping.acknowledgeCalls, "the PUT acknowledges server-side")
            assertEquals(
                queueReads + 1,
                allocations.queueCalls.size,
                "the cut-off decides which rows exist, so the queue must be re-read",
            )
        }

    @Test
    fun `cancelling the dialog writes nothing`() =
        runTest(dispatcher) {
            val bookkeeping = FakeBookkeepingStartApiClient()
            val vm = viewModel(bookkeeping)
            vm.load()
            advanceUntilIdle()

            vm.bookkeeping.startEditing()
            assertTrue(vm.bookkeeping.state.isEditing)
            vm.bookkeeping.cancelEditing()

            assertFalse(vm.bookkeeping.state.isEditing)
            assertTrue(bookkeeping.updated.isEmpty())
            assertEquals(0, bookkeeping.acknowledgeCalls)
        }

    @Test
    fun `only an ISO date is offered to the server`() {
        assertEquals("2025-01-01", isoDateOrNull(" 2025-01-01 "))
        for (bad in listOf("", "   ", "01.09.2026", "2026-13-01", "2026-02-30", "2026-1-1", "nicht-ein-datum")) {
            assertNull(isoDateOrNull(bad), "'$bad' must not enable Speichern — the server answers 400")
        }
    }
}
