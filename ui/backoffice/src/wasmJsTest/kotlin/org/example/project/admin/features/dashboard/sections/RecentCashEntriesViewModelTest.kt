package org.example.project.admin.features.dashboard.sections

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.dashboard.FakeKassenbuchApiClient
import org.example.project.admin.features.dashboard.presentation.sections.RecentCashEntriesViewModel
import org.example.project.admin.features.kassenbuch.FakeCashBookApiClient
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.network.error.NetworkApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun entry(id: Long) =
    KassenbuchDto.KassenbuchGroupedSummaryDto(
        headerId = id,
        cashBookId = 1,
        belegNr = "B$id",
        entryType = KassenbuchEntryType.entries.first(),
        belegtext = "Tisch $id",
        belegDatum = "2026-08-28",
        waehrung = "EUR",
        totalAmount = "${10 * id}.0000",
        lineItems = emptyList(),
        currentBalance = "500.0000",
        employeeNumber = "E1",
        createdAt = "2026-08-28T0$id:00:00",
        currency = "EUR",
    )

private val books =
    listOf(
        CashBookDto.ResponseDto(
            id = 1,
            name = "Hauptkasse",
            isFiscal = true,
            datevAccount = "1000",
            closeInterval = CashBookCloseInterval.entries.first(),
            isActive = true,
            sortOrder = 0,
            currentBalance = "500.0000",
            currency = "EUR",
        ),
    )

class RecentCashEntriesViewModelTest {
    @Test
    fun bookNamesAreResolvedFromTheBookList() =
        runTest {
            val vm =
                RecentCashEntriesViewModel(
                    FakeKassenbuchApiClient(entries = { listOf(entry(1)) }, balance = { "0.00" }),
                    FakeCashBookApiClient(books = books),
                )
            vm.refresh()
            assertEquals(mapOf(1L to "Hauptkasse"), vm.state.bookNames)
        }

    @Test
    fun loadsFiveEntriesSortedByHeaderId() =
        runTest {
            val api =
                FakeKassenbuchApiClient(
                    entries = { (1L..5L).map(::entry).reversed() },
                    balance = { "0.00" },
                )
            val vm = RecentCashEntriesViewModel(api, FakeCashBookApiClient(books = books))
            vm.refresh()
            assertEquals(5, vm.state.entries.size)
            val firstEntry = vm.state.entries.first()
            assertEquals(1L, firstEntry.headerId)
            assertEquals(5, api.lastRecentEntriesLimit)
            assertFalse(vm.state.isEmpty)
        }

    @Test
    fun noEntries_isEmpty() =
        runTest {
            val vm = RecentCashEntriesViewModel(FakeKassenbuchApiClient(entries = { emptyList() }, balance = { "0.00" }), FakeCashBookApiClient())
            vm.refresh()
            assertFalse(vm.state.failed)
            assertTrue(vm.state.isEmpty)
        }

    @Test
    fun failedLoad_isFailed() =
        runTest {
            val vm =
                RecentCashEntriesViewModel(
                    FakeKassenbuchApiClient(
                        entries = { throw NetworkApiException(RuntimeException("down")) },
                        balance = { "0.00" },
                    ),
                    FakeCashBookApiClient(),
                )
            vm.refresh()
            assertTrue(vm.state.failed)
        }
}
