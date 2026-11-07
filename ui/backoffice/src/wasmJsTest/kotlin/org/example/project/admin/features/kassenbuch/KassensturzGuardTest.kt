package org.example.project.admin.features.kassenbuch

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.dashboard.FakeKassenbuchApiClient
import org.example.project.admin.features.dashboard.FakeZBonApiClient
import org.example.project.admin.features.kassenbuch.presentation.KassenbuchViewModel
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val FISCAL_BOOK_ID = 1L
private const val TRESOR_ID = 2L

private fun book(
    id: Long,
    name: String,
    isFiscal: Boolean,
) = CashBookDto.ResponseDto(
    id = id,
    name = name,
    isFiscal = isFiscal,
    datevAccount = "1600",
    closeInterval = CashBookCloseInterval.MANUAL,
    isActive = true,
    sortOrder = 0,
    currentBalance = "128.50",
    currency = "EUR",
)

/**
 * The client half of the fiscal guard (CLAUDE.md rule 8).
 *
 * `CashBookCloseServiceImpl.requireNonFiscal` rejects a close of the fiscal Ladenkasse with a 422 —
 * it is closed by the Z-Bon. These cases pin that the client never *asks*: a guard that only hides
 * the button still leaves every other caller free to produce an error the operator has to interpret,
 * which is exactly the half-implemented guard the rule forbids.
 */
class KassensturzGuardTest {
    private fun viewModel(cashBooks: FakeCashBookApiClient) =
        KassenbuchViewModel(
            kassenbuchApiClient = FakeKassenbuchApiClient(entries = { emptyList() }, balance = { "128.50" }),
            cashBookApiClient = cashBooks,
            zBonApiClient = FakeZBonApiClient(latestEndTime = "2026-08-09T23:12:00"),
            fiscalConfigurationApiClient = FakeFiscalConfigurationApiClient(),
        )

    private fun bothBooks() =
        FakeCashBookApiClient(
            books = listOf(book(FISCAL_BOOK_ID, "Ladenkasse", isFiscal = true), book(TRESOR_ID, "Tresor", isFiscal = false)),
        )

    @Test
    fun theFiscalBookIsNeverPreviewedForAKassensturz() =
        runTest {
            val api = bothBooks()
            val vm = viewModel(api)
            vm.loadBooks()
            vm.selectBook(FISCAL_BOOK_ID)

            vm.openKassensturz()

            assertEquals(0, api.closePreviewCalls, "the fiscal Ladenkasse must never be previewed for a close")
            assertNull(vm.state.kassensturz)
        }

    @Test
    fun theAllBooksTabHasNoBookToClose() =
        runTest {
            val api = bothBooks()
            val vm = viewModel(api)
            vm.loadBooks()

            vm.openKassensturz()

            assertEquals(0, api.closePreviewCalls)
            assertNull(vm.state.kassensturz)
        }

    @Test
    fun aNonFiscalBookOpensTheDialogOnTheServersPreview() =
        runTest {
            val api = bothBooks()
            val vm = viewModel(api)
            vm.loadBooks()
            vm.selectBook(TRESOR_ID)

            vm.openKassensturz()

            val sturz = assertNotNull(vm.state.kassensturz)
            assertEquals(TRESOR_ID, sturz.preview.cashBookId)
            assertEquals("128.50", sturz.preview.bookBalance)
            assertEquals("", sturz.countedInput)
        }

    @Test
    fun theFiscalTabShowsTheZBonStatusInstead() =
        runTest {
            val vm = viewModel(bothBooks())
            vm.loadBooks()

            vm.selectBook(TRESOR_ID)
            assertNull(vm.state.latestZBonEndTime, "a non-fiscal tab has no Z-Bon status to show")

            vm.selectBook(FISCAL_BOOK_ID)
            assertEquals("2026-08-09T23:12:00", vm.state.latestZBonEndTime)
        }

    @Test
    fun theCountedBalanceIsSentAsAnExactDecimalString() =
        runTest {
            val api = bothBooks()
            val vm = viewModel(api)
            vm.loadBooks()
            vm.selectBook(TRESOR_ID)
            vm.openKassensturz()

            vm.setCountedBalance("128,50")
            vm.setKassensturzNotes("  ")
            vm.confirmKassensturz()

            val request = assertNotNull(api.lastCloseRequest)
            assertEquals("128.50", request.countedBalance)
            assertNull(request.notes, "a whitespace-only note is absent, not empty")
            assertNull(vm.state.kassensturz, "a booked Kassensturz closes its dialog")
        }

    @Test
    fun anEmptiedSafeIsBookable() =
        runTest {
            val api = bothBooks()
            val vm = viewModel(api)
            vm.loadBooks()
            vm.selectBook(TRESOR_ID)
            vm.openKassensturz()

            vm.setCountedBalance("0")
            vm.confirmKassensturz()

            assertEquals("0", assertNotNull(api.lastCloseRequest).countedBalance)
        }

    @Test
    fun anUnusableCountIsNeverSent() =
        runTest {
            val api = bothBooks()
            val vm = viewModel(api)
            vm.loadBooks()
            vm.selectBook(TRESOR_ID)
            vm.openKassensturz()

            vm.confirmKassensturz()
            assertNull(api.lastCloseRequest, "a blank field is not a count of zero")

            vm.setCountedBalance("-5")
            vm.confirmKassensturz()
            assertNull(api.lastCloseRequest, "a negative count is refused before the request")

            assertNotNull(vm.state.kassensturz, "the dialog stays open so the operator can correct it")
        }
}
