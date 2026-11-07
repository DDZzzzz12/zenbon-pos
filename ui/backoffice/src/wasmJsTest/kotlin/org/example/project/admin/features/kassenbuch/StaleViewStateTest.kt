package org.example.project.admin.features.kassenbuch

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.dashboard.FakeKassenbuchApiClient
import org.example.project.admin.features.dashboard.FakeZBonApiClient
import org.example.project.admin.features.kassenbuch.presentation.KassenbuchViewModel
import org.example.project.core.data.remote.KassenbuchApiClient
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val LADENKASSE_ID = 21L
private const val TRESOR_ID = 22L
private const val LADENKASSE_HEADER = 7501L

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
    currentBalance = "0.00",
    currency = "EUR",
)

private fun summary(headerId: Long) =
    KassenbuchDto.KassenbuchGroupedSummaryDto(
        headerId = headerId,
        cashBookId = TRESOR_ID,
        belegNr = "2026-07-00$headerId",
        entryType = KassenbuchEntryType.CASH_EXPENSE,
        belegtext = "Testbuchung $headerId",
        belegDatum = "20.07.2026",
        waehrung = "EUR",
        totalAmount = "23.80",
        lineItems = emptyList(),
        currentBalance = "23.80",
        employeeNumber = "EMP001",
        createdAt = "2026-07-20T10:00:00",
    )

private fun detail(headerId: Long) =
    KassenbuchDto.KassenbuchGroupedDetailDto(
        headerId = headerId,
        belegNr = "2026-07-0075",
        entryType = KassenbuchEntryType.CASH_EXPENSE,
        belegtext = "Testbuchung",
        belegDatum = "20.07.2026",
        waehrung = "EUR",
        totalAmount = "23.80",
        currentBalance = "23.80",
        employeeNumber = "EMP001",
        isLocked = false,
        createdAt = "2026-07-20T10:00:00",
        bu = null,
        gegenkonto = null,
        kost1 = null,
        kost2 = null,
        nachricht = null,
        tseTransactionId = null,
        tseSignature = null,
        tseTransactionNumber = null,
        tseSignedAt = null,
        lineItems = emptyList(),
        hasLinkedDocuments = false,
        linkedDocumentCount = 0,
    )

/**
 * Two ways this screen used to keep showing an answer to a question the operator had moved on from.
 *
 * Both are invalidation defects, and both are invisible to a single-call test: each needs a
 * *sequence* — select then switch, search then retype — which is exactly the shape a manual
 * walkthrough finds and a per-method unit test does not.
 */
class StaleViewStateTest {
    private fun viewModel(api: FakeKassenbuchApiClient) =
        KassenbuchViewModel(
            kassenbuchApiClient = api,
            cashBookApiClient =
                FakeCashBookApiClient(
                    books =
                        listOf(
                            book(LADENKASSE_ID, "Ladenkasse", isFiscal = true),
                            book(TRESOR_ID, "Tresor", isFiscal = false),
                        ),
                ),
            zBonApiClient = FakeZBonApiClient(latestEndTime = null),
            fiscalConfigurationApiClient = FakeFiscalConfigurationApiClient(),
        )

    private fun api() =
        FakeKassenbuchApiClient(
            entries = { emptyList() },
            balance = { "0.00" },
            detail = { headerId -> detail(headerId) },
        )

    /**
     * Switching cash book closes the detail panel.
     *
     * The panel is beside the table, and the table is now the Tresor's. Leaving the Ladenkasse's
     * booking up next to it describes an entry that is not in the visible ledger — the operator reads
     * a Beleg and a balance belonging to a book they are no longer looking at.
     */
    @Test
    fun switchingCashBookClosesTheDetailOfTheBookLeftBehind() =
        runTest {
            val vm = viewModel(api())
            vm.loadBooks()
            vm.selectBook(LADENKASSE_ID)
            vm.selectEntry(LADENKASSE_HEADER)
            assertEquals(LADENKASSE_HEADER, assertNotNull(vm.state.selectedEntry).headerId)

            vm.selectBook(TRESOR_ID)

            assertNull(
                vm.state.selectedEntry,
                "the Ladenkasse entry is not in the Tresor's table; the panel must not keep describing it",
            )
        }

    /**
     * Re-selecting the tab that is already open changes nothing — including the open detail.
     *
     * This is what keeps the dashboard deep link working: it lands on "Alle Kassen" and calls
     * `selectEntry` directly, and any later no-op tab click must not throw that selection away.
     */
    @Test
    fun reselectingTheSameTabKeepsTheDetailOpen() =
        runTest {
            val vm = viewModel(api())
            vm.loadBooks()
            vm.selectBook(TRESOR_ID)
            vm.selectEntry(LADENKASSE_HEADER)

            vm.selectBook(TRESOR_ID)

            assertNotNull(vm.state.selectedEntry, "the tab did not change, so nothing became stale")
        }

    /**
     * Retyping the search term drops the previous term's global verdict.
     *
     * "In keinem Zeitraum gefunden" is an assertion about the term that was searched. Carried over to
     * a new term it becomes a false one — and the operator has no reason to suspect it, because the
     * field in front of them holds the term the screen appears to be answering about.
     *
     * Back to `null`, never to an empty list: `null` is "not in global mode", an empty list is
     * "searched everywhere, found nothing", and collapsing the two would keep the false verdict.
     */
    @Test
    fun changingTheSearchTermDropsThePreviousTermsGlobalVerdict() =
        runTest {
            val vm = viewModel(api())
            vm.loadBooks()
            vm.setFilterText("zzzz")
            vm.searchAllPeriods()
            assertEquals(
                emptyList<KassenbuchDto.KassenbuchGroupedSummaryDto>(),
                vm.state.globalResults,
                "searched every period for 'zzzz' and found nothing — not the same as not searching",
            )

            vm.setFilterText("Testbuchung")

            assertNull(
                vm.state.globalResults,
                "global search is offered per term; 'zzzz' found nothing says nothing about 'Testbuchung'",
            )
        }

    /**
     * The truncation warning is part of the same verdict and goes with it.
     *
     * "There are more hits than shown" is true of the term that was searched, and there is no
     * continuation token to make it mean anything for the next one.
     */
    @Test
    fun changingTheSearchTermAlsoDropsTheTruncationWarning() =
        runTest {
            val capped = List(KassenbuchApiClient.SEARCH_RESULT_LIMIT) { summary(it.toLong()) }
            val vm =
                viewModel(
                    FakeKassenbuchApiClient(
                        entries = { capped },
                        balance = { "0.00" },
                        detail = { headerId -> detail(headerId) },
                    ),
                )
            vm.loadBooks()
            vm.setFilterText("Buchung")
            vm.searchAllPeriods()
            assertEquals(true, vm.state.globalResultsTruncated, "the fake returns exactly the server's hard cap")

            vm.setFilterText("Buchung 2")

            assertEquals(false, vm.state.globalResultsTruncated)
        }
}
