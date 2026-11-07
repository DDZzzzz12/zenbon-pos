package org.example.project.admin.features.invoices

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.finance.FakeBookkeepingStartApiClient
import org.example.project.admin.features.invoices.presentation.InvoicesViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Buchungsbeginn banner on Rechnungen.
 *
 * Rechnungen sits above Zuordnung in the sidebar, so it is the likelier first screen, and it is the
 * one where an unset cut-off does the damage: every historical invoice would show as unpaid at its
 * full amount. The banner therefore has to appear here too — which only became safe once the
 * acknowledgement was made durable, because a one-shot flag would have been consumed by whichever
 * of the two screens loaded first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InvoicesBuchungsbeginnTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an unacknowledged Buchungsbeginn raises the banner`() =
        runTest(dispatcher) {
            val bookkeeping = FakeBookkeepingStartApiClient().apply { needsAcknowledgement = true }
            val vm = InvoicesViewModel(FakeInvoiceListApiClient(), bookkeeping)

            vm.load()
            advanceUntilIdle()

            assertTrue(vm.bookkeeping.state.showBanner)
            assertEquals("2026-08-01", vm.bookkeeping.state.startDate)
        }

    @Test
    fun `an acknowledged Buchungsbeginn raises no banner`() =
        runTest(dispatcher) {
            val bookkeeping = FakeBookkeepingStartApiClient()
            val vm = InvoicesViewModel(FakeInvoiceListApiClient(), bookkeeping)

            vm.load()
            advanceUntilIdle()

            assertFalse(vm.bookkeeping.state.showBanner)
        }

    @Test
    fun `dismissing acknowledges once and re-reads nothing`() =
        runTest(dispatcher) {
            val bookkeeping = FakeBookkeepingStartApiClient().apply { needsAcknowledgement = true }
            val invoices = FakeInvoiceListApiClient()
            val vm = InvoicesViewModel(invoices, bookkeeping)
            vm.load()
            advanceUntilIdle()
            val readsSoFar = bookkeeping.getCalls
            val listReads = invoices.listCalls.size

            vm.bookkeeping.acknowledge()
            advanceUntilIdle()

            assertFalse(vm.bookkeeping.state.showBanner)
            assertEquals(1, bookkeeping.acknowledgeCalls, "dismissal must reach the server exactly once")
            assertTrue(bookkeeping.updated.isEmpty(), "dismissal must not change the date")
            assertEquals(readsSoFar, bookkeeping.getCalls, "dismissal must not re-read the setting")
            assertEquals(
                listReads,
                invoices.listCalls.size,
                "acknowledging moves no date, so the list cannot have changed",
            )
        }

    @Test
    fun `a dismissed banner stays down on the next load`() =
        runTest(dispatcher) {
            val bookkeeping = FakeBookkeepingStartApiClient().apply { needsAcknowledgement = true }
            val vm = InvoicesViewModel(FakeInvoiceListApiClient(), bookkeeping)
            vm.load()
            advanceUntilIdle()

            vm.bookkeeping.acknowledge()
            advanceUntilIdle()
            vm.load()
            advanceUntilIdle()

            assertFalse(vm.bookkeeping.state.showBanner, "the acknowledgement is stored, not local")
        }

    @Test
    fun `saving a date writes it once and re-reads the list`() =
        runTest(dispatcher) {
            val bookkeeping = FakeBookkeepingStartApiClient().apply { needsAcknowledgement = true }
            val invoices = FakeInvoiceListApiClient()
            val vm = InvoicesViewModel(invoices, bookkeeping)
            vm.load()
            advanceUntilIdle()
            val listReads = invoices.listCalls.size

            vm.bookkeeping.startEditing()
            vm.saveBookkeepingStart("2025-01-01")
            advanceUntilIdle()

            assertEquals(listOf("2025-01-01"), bookkeeping.updated)
            assertEquals("2025-01-01", vm.bookkeeping.state.startDate)
            assertFalse(vm.bookkeeping.state.isEditing)
            assertFalse(vm.bookkeeping.state.showBanner)
            assertEquals(0, bookkeeping.acknowledgeCalls, "the PUT acknowledges server-side")
            assertEquals(
                listReads + 1,
                invoices.listCalls.size,
                "the cut-off decides which invoices count as open, so the list must be re-read",
            )
        }

    @Test
    fun `a refused date never leaves the client`() =
        runTest(dispatcher) {
            val bookkeeping = FakeBookkeepingStartApiClient().apply { needsAcknowledgement = true }
            val vm = InvoicesViewModel(FakeInvoiceListApiClient(), bookkeeping)
            vm.load()
            advanceUntilIdle()

            vm.saveBookkeepingStart("01.09.2026")
            advanceUntilIdle()

            assertTrue(bookkeeping.updated.isEmpty(), "the server answers 400 — do not ask it")
        }
}
