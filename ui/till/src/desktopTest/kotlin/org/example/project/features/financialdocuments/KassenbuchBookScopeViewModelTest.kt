package org.example.project.features.financialdocuments

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.network.error.NetworkApiException
import org.example.project.features.financialdocuments.presentation.state.FinancialDocumentsEventBus
import org.example.project.features.financialdocuments.presentation.state.KassenbuchState
import org.example.project.features.financialdocuments.presentation.viewmodel.KassenbuchViewModel
import org.example.project.money.Decimal
import org.example.project.testutils.fakes.FakeCashBookApiClient
import org.example.project.testutils.fakes.FakeDocumentManagementApiClient
import org.example.project.testutils.fakes.FakeKassenbuchApiClient
import org.example.project.testutils.fakes.FakeZBonApiClient
import org.example.project.ui.components.formatMoneyForDisplay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OBS-1 — the till's Kassenbuch screen is scoped to ONE cash book at a time.
 *
 * It used to list every book's entries (the history call carried no `cashBookId`) while the
 * Kassenbestand card showed only the fiscal Ladenkasse (the server's `/balance` default), so
 * Einnahmen/Ausgaben included the Tresor and the three cards could not reconcile.
 *
 * The fake answers exactly as the server does: a history without `cashBookId` returns every book's
 * entries, a balance without `cashBookId` returns the fiscal book's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KassenbuchBookScopeViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var cashBooks: FakeCashBookApiClient
    private lateinit var kassenbuch: FakeKassenbuchApiClient

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        cashBooks = FakeCashBookApiClient().apply { listBooksResult = listOf(safe, till) }
        kassenbuch =
            FakeKassenbuchApiClient().apply {
                // Server semantics: no cashBookId = every book's entries / the fiscal book's balance.
                getEntryHistoryResult = tillEntries + safeEntries
                getEntryHistoryByBook = mapOf(TILL_ID to tillEntries, SAFE_ID to safeEntries)
                // The API client hands back the server's canonical decimal (admin16 NOTE-5); the VM
                // formats it, grouping included — "1000.00" must reach the card as "1.000,00".
                getCurrentBalanceResult = "500.00"
                getCurrentBalanceByBook = mapOf(TILL_ID to "500.00", SAFE_ID to "1000.00")
            }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(documents: FakeDocumentManagementApiClient = FakeDocumentManagementApiClient()): KassenbuchViewModel =
        KassenbuchViewModel(
            kassenbuchApiClient = kassenbuch,
            cashBookApiClient = cashBooks,
            zBonApiClient = FakeZBonApiClient(),
            documentManagementApiClient = documents,
            eventBus = FinancialDocumentsEventBus(),
        )

    /** F59: opening the create form loads what the Ladenkasse can pay out — the form's Kassenminus ceiling. */
    @Test
    fun `opening the create form loads the Ladenkasse's available cash`() =
        runTest(testDispatcher) {
            kassenbuch.availableCashByBook = mapOf(null to "123.45")
            val viewModel = newViewModel()

            viewModel.showCreateDialog()
            advanceUntilIdle()

            assertEquals("123.45", viewModel.kassenbuchState.value.availableCash)
            assertEquals(listOf<Long?>(null), kassenbuch.availableCashCalls, "the till's form books into the fiscal Ladenkasse")

            viewModel.hideCreateDialog()
            kassenbuch.availableCashThrows = NetworkApiException(RuntimeException("offline"))
            viewModel.showCreateDialog()
            advanceUntilIdle()
            assertEquals(null, viewModel.kassenbuchState.value.availableCash, "a failed lookup leaves no stale ceiling behind")
        }

    @Test
    fun `with two books the default view shows only the Ladenkasse and its cards reconcile`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()

            viewModel.loadKassenbuchData()
            advanceUntilIdle()

            val state = viewModel.kassenbuchState.value
            assertEquals(TILL_ID, state.selectedCashBookId, "the fiscal Ladenkasse is the till's default book")
            assertEquals(listOf(TILL_ID), state.entries.map { it.cashBookId }.distinct(), "no Tresor entry may leak into the list")
            assertEquals("+150,00", state.totalIncome)
            assertEquals("-50,00", state.totalExpenses)
            assertEquals("500,00", state.currentBalance)
            assertReconciles(state, bookBalance = till.currentBalance)
        }

    @Test
    fun `switching book recomputes the list and all three cards for that book`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            viewModel.loadKassenbuchData()
            advanceUntilIdle()

            viewModel.selectCashBook(SAFE_ID)
            advanceUntilIdle()

            val state = viewModel.kassenbuchState.value
            assertEquals(SAFE_ID, state.selectedCashBookId)
            assertEquals(SAFE_ID, kassenbuch.lastGetEntryHistoryCashBookId)
            assertEquals(SAFE_ID, kassenbuch.lastGetCurrentBalanceCashBookId)
            assertEquals(listOf(SAFE_ID), state.entries.map { it.cashBookId }.distinct())
            assertEquals("+1.000,00", state.totalIncome)
            // A book with no outgoing entry shows an unsigned zero, never "-0,00" (emulator pass 4, NOTE-3).
            assertEquals("0,00", state.totalExpenses)
            assertEquals("1.000,00", state.currentBalance)
            assertReconciles(state, bookBalance = safe.currentBalance)

            viewModel.selectCashBook(TILL_ID)
            advanceUntilIdle()

            val back = viewModel.kassenbuchState.value
            assertEquals("+150,00", back.totalIncome)
            assertEquals("500,00", back.currentBalance)
        }

    @Test
    fun `the selection survives a reload and the selector lists active books only`() =
        runTest(testDispatcher) {
            cashBooks.listBooksResult = listOf(safe, till, safe.copy(id = 3L, name = "Alt", isActive = false))
            val viewModel = newViewModel()
            viewModel.loadKassenbuchData()
            advanceUntilIdle()
            viewModel.selectCashBook(SAFE_ID)
            advanceUntilIdle()

            viewModel.loadKassenbuchData()
            advanceUntilIdle()

            val state = viewModel.kassenbuchState.value
            assertEquals(SAFE_ID, state.selectedCashBookId)
            assertEquals(listOf("Ladenkasse", "Tresor"), state.selectableCashBooks.map { it.name })
        }

    @Test
    fun `a selected book that was deactivated falls back to the Ladenkasse`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            viewModel.loadKassenbuchData()
            advanceUntilIdle()
            viewModel.selectCashBook(SAFE_ID)
            advanceUntilIdle()

            cashBooks.listBooksResult = listOf(safe.copy(isActive = false), till)
            viewModel.loadKassenbuchData()
            advanceUntilIdle()

            assertEquals(TILL_ID, viewModel.kassenbuchState.value.selectedCashBookId)
            assertEquals(TILL_ID, kassenbuch.lastGetEntryHistoryCashBookId)
        }

    @Test
    fun `an entry booked from the Tresor view switches the view to the Ladenkasse it was booked into`() =
        runTest(testDispatcher) {
            kassenbuch.createEntryThrows = null
            kassenbuch.createEntryResult =
                KassenbuchDto.CreateKassenbuchEntryGroupResponseDto(
                    belegNr = "2026-09-99",
                    totalGrossAmount = "10.00",
                    entries = emptyList(),
                    createdAt = "2026-09-27T11:00:00",
                )
            val documents =
                FakeDocumentManagementApiClient().apply {
                    uploadDocumentThrows = null
                    uploadDocumentResult =
                        DocumentDto.DocumentResponseDto(
                            id = 777L,
                            filename = "beleg.pdf",
                            fileType = "EIGENBELEG",
                            fileSize = 4L,
                            uploadedAt = "2026-09-27T11:00:00",
                            uploadedBy = "EMP001",
                            downloadUrl = "http://example/777",
                            formattedFileSize = "4 B",
                            monthFolder = "2026-09",
                        )
                }
            val viewModel = newViewModel(documents)
            viewModel.loadKassenbuchData()
            advanceUntilIdle()
            viewModel.selectCashBook(SAFE_ID)
            advanceUntilIdle()

            viewModel.createEntry(
                request =
                    KassenbuchDto.CreateKassenbuchEntryRequestDto(
                        entryType = KassenbuchEntryType.CASH_DEPOSIT,
                        lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "0", amount = "10.00")),
                        belegtext = "Einlage",
                    ),
                fileBytes = byteArrayOf(1, 2, 3, 4),
                fileName = "beleg.pdf",
                fileType = "EIGENBELEG",
            )
            advanceUntilIdle()

            assertEquals(1, kassenbuch.createEntryCallCount)
            assertEquals(null, kassenbuch.lastCreateEntryRequest?.cashBookId, "the till books into the fiscal default")
            assertEquals(TILL_ID, viewModel.kassenbuchState.value.selectedCashBookId)
            assertEquals(TILL_ID, kassenbuch.lastGetEntryHistoryCashBookId)
        }

    @Test
    fun `a single-book shop shows no selector and the same figures as before`() =
        runTest(testDispatcher) {
            cashBooks.listBooksResult = listOf(till)
            kassenbuch.getEntryHistoryResult = tillEntries
            val viewModel = newViewModel()

            viewModel.loadKassenbuchData()
            advanceUntilIdle()

            val state = viewModel.kassenbuchState.value
            assertEquals(1, state.selectableCashBooks.size, "CashBookSelector renders nothing below two books")
            assertEquals(tillEntries, state.entries)
            assertEquals("+150,00", state.totalIncome)
            assertEquals("-50,00", state.totalExpenses)
            assertEquals("500,00", state.currentBalance)
        }

    /**
     * The invariant the three cards claim for a period that ends now: the book's balance before the
     * first listed entry, plus Einnahmen, minus Ausgaben, equals the Kassenbestand. Computed in
     * [Decimal] from the entries actually shown — the opening is the oldest entry's running balance
     * minus that entry's signed amount.
     */
    private fun assertReconciles(
        state: KassenbuchState,
        bookBalance: String,
    ) {
        val oldest = state.entries.minBy { it.createdAt }
        val opening = Decimal.parse(oldest.currentBalance) - Decimal.parse(oldest.totalAmount)
        var income = Decimal.ZERO
        var expenses = Decimal.ZERO
        state.entries.forEach { entry ->
            val amount = Decimal.parse(entry.totalAmount).abs()
            if (entry.entryType.isIncoming) income += amount else expenses += amount
        }
        assertEquals(formatMoneyForDisplay(income.toPlainString(), "", forceSign = true), state.totalIncome)
        assertEquals(formatMoneyForDisplay((-expenses).toPlainString(), "", forceSign = true), state.totalExpenses)
        assertTrue(
            (opening + income - expenses).compareTo(Decimal.parse(bookBalance)) == 0,
            "opening $opening + in $income - out $expenses must equal the Kassenbestand $bookBalance",
        )
    }

    private companion object {
        const val TILL_ID = 1L
        const val SAFE_ID = 2L

        val till =
            CashBookDto.ResponseDto(
                id = TILL_ID,
                name = "Ladenkasse",
                isFiscal = true,
                datevAccount = "1000",
                closeInterval = CashBookCloseInterval.MANUAL,
                isActive = true,
                sortOrder = 0,
                currentBalance = "500.00",
                currency = "EUR",
            )

        val safe = till.copy(id = SAFE_ID, name = "Tresor", isFiscal = false, sortOrder = 1, currentBalance = "1000.00")

        fun entry(
            headerId: Long,
            bookId: Long,
            type: KassenbuchEntryType,
            amount: String,
            runningBalance: String,
            createdAt: String,
        ) = KassenbuchDto.KassenbuchGroupedSummaryDto(
            headerId = headerId,
            cashBookId = bookId,
            belegNr = "2026-09-$headerId",
            entryType = type,
            belegtext = type.name,
            belegDatum = "27.09.2026",
            waehrung = "EUR",
            totalAmount = amount,
            lineItems = emptyList(),
            currentBalance = runningBalance,
            employeeNumber = "1",
            createdAt = createdAt,
        )

        // Ladenkasse: opening 400.00, +150.00, -50.00 -> 500.00
        val tillEntries =
            listOf(
                entry(11L, TILL_ID, KassenbuchEntryType.CASH_EXPENSE, "-50.00", "500.00", "2026-09-27T10:00:00"),
                entry(10L, TILL_ID, KassenbuchEntryType.CASH_DEPOSIT, "150.00", "550.00", "2026-09-27T09:00:00"),
            )

        // Tresor: opening 0.00, +1000.00 -> 1000.00
        val safeEntries =
            listOf(
                entry(20L, SAFE_ID, KassenbuchEntryType.CASH_TRANSFER_IN, "1000.00", "1000.00", "2026-09-27T09:30:00"),
            )
    }
}
