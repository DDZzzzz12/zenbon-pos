package org.example.project.features.reports.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.presentation.state.DateRange
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.utils.LanguageManager
import org.example.project.features.reports.presentation.viewmodel.ZBonViewModel
import org.example.project.testutils.fakes.FakeCashBookApiClient
import org.example.project.testutils.fakes.FakeKassenbuchApiClient
import org.example.project.testutils.fakes.FakeZBonApiClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ZBonViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var fakeZBonApiClient: FakeZBonApiClient
    private lateinit var fakeKassenbuchApiClient: FakeKassenbuchApiClient
    private lateinit var viewModel: ZBonViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        TranslationRegistry.initialize(defaultTranslations())
        fakeZBonApiClient = FakeZBonApiClient()
        fakeKassenbuchApiClient = FakeKassenbuchApiClient()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val sampleZBonResponse =
        ZBonDto.ZBonResponseDto(
            id = 1L,
            zbonNummer = 1L,
            shopInfoId = 1L,
            shopName = "Test Shop",
            employeeNumber = "EMP001",
            startTime = "2026-03-07T08:00:00",
            endTime = "2026-03-07T22:00:00",
            cancelledAmount = "0.00",
            totalRevenue = "5000.00",
            openingBalance = "100.00",
            expenses = "50.00",
            closingBalance = "5050.00",
            createdAt = "2026-03-07T22:00:00",
        )

    private fun createViewModel(): ZBonViewModel =
        ZBonViewModel(
            zBonApiClient = fakeZBonApiClient,
            kassenbuchApiClient = fakeKassenbuchApiClient,
            languageManager = LanguageManager(),
            cashBookApiClient = FakeCashBookApiClient(),
        )

    @Nested
    inner class InitialState {
        @Test
        fun `initial state has default values`() =
            runTest(testDispatcher) {
                viewModel = createViewModel()
                advanceUntilIdle()

                val state = viewModel.zBonState.value
                assertTrue(state.zbonHistory.isEmpty())
                assertEquals(DateRange.THIS_WEEK, state.selectedDateRange)
                assertNull(state.customDateRange)
                assertEquals(false, state.isLoading)
                assertNull(state.selectedZBon)
                assertNull(state.error)
                assertNull(state.successMessage)
            }
    }

    @Nested
    inner class LoadZBonHistory {
        @Test
        fun `success populates zbonHistory`() =
            runTest(testDispatcher) {
                val summaries =
                    listOf(
                        ZBonDto.ZBonSummeryDto(
                            id = 1L,
                            zbonNummer = 1L,
                            startTime = "2026-03-01T08:00:00",
                            endTime = "2026-03-01T22:00:00",
                            totalRevenue = "1500.00",
                        ),
                        ZBonDto.ZBonSummeryDto(
                            id = 2L,
                            zbonNummer = 2L,
                            startTime = "2026-03-02T08:00:00",
                            endTime = "2026-03-02T22:00:00",
                            totalRevenue = "2000.00",
                        ),
                    )
                fakeZBonApiClient.getZBonHistoryResult = summaries
                viewModel = createViewModel()

                viewModel.loadZBonHistory()
                advanceUntilIdle()

                val state = viewModel.zBonState.value
                assertEquals(2, state.zbonHistory.size)
                assertEquals(1L, state.zbonHistory[0].id)
                assertEquals(2L, state.zbonHistory[1].id)
                assertEquals(false, state.isLoading)
                assertNull(state.error)
            }

        @Test
        fun `error sets error state`() =
            runTest(testDispatcher) {
                fakeZBonApiClient.getZBonHistoryThrows = NetworkApiException(RuntimeException("History load failed"))
                viewModel = createViewModel()

                viewModel.loadZBonHistory()
                advanceUntilIdle()

                val state = viewModel.zBonState.value
                assertTrue(state.zbonHistory.isEmpty())
                assertEquals(false, state.isLoading)
                assertNotNull(state.error)
            }

        @Test
        fun `calls API with date range parameters`() =
            runTest(testDispatcher) {
                fakeZBonApiClient.getZBonHistoryResult = emptyList()
                viewModel = createViewModel()

                viewModel.loadZBonHistory()
                advanceUntilIdle()

                assertEquals(1, fakeZBonApiClient.getZBonHistoryCallCount)
                assertNotNull(fakeZBonApiClient.lastGetZBonHistoryStartDate)
                assertNotNull(fakeZBonApiClient.lastGetZBonHistoryEndDate)
            }
    }

    @Nested
    inner class SelectDateRange {
        @Test
        fun `updates selectedDateRange and reloads history`() =
            runTest(testDispatcher) {
                fakeZBonApiClient.getZBonHistoryResult = emptyList()
                viewModel = createViewModel()

                viewModel.selectDateRange(DateRange.THIS_MONTH)
                advanceUntilIdle()

                val state = viewModel.zBonState.value
                assertEquals(DateRange.THIS_MONTH, state.selectedDateRange)
                assertEquals(1, fakeZBonApiClient.getZBonHistoryCallCount)
            }

        @Test
        fun `setCustomDateRange updates state and reloads`() =
            runTest(testDispatcher) {
                fakeZBonApiClient.getZBonHistoryResult = emptyList()
                viewModel = createViewModel()

                viewModel.setCustomDateRange("2026-01-01", "2026-01-31")
                advanceUntilIdle()

                val state = viewModel.zBonState.value
                assertEquals(DateRange.CUSTOM, state.selectedDateRange)
                assertEquals(Pair("2026-01-01", "2026-01-31"), state.customDateRange)
                assertEquals("2026-01-01", fakeZBonApiClient.lastGetZBonHistoryStartDate)
                assertEquals("2026-01-31", fakeZBonApiClient.lastGetZBonHistoryEndDate)
            }
    }

    @Nested
    inner class CreateZBon {
        @Test
        fun `success sets selectedZBon`() =
            runTest(testDispatcher) {
                fakeZBonApiClient.createZBonResult = sampleZBonResponse
                viewModel = createViewModel()

                viewModel.createZBon()
                advanceUntilIdle()

                val state = viewModel.zBonState.value
                assertNotNull(state.selectedZBon)
                assertEquals(1L, state.selectedZBon?.id)
                assertEquals(1, fakeZBonApiClient.createZBonCallCount)
            }

        @Test
        fun `success invokes onSuccess callback`() =
            runTest(testDispatcher) {
                fakeZBonApiClient.createZBonResult = sampleZBonResponse
                viewModel = createViewModel()

                var callbackInvoked = false
                viewModel.createZBon(onSuccess = { callbackInvoked = true })
                advanceUntilIdle()

                assertTrue(callbackInvoked)
            }

        @Test
        fun `error sets error state`() =
            runTest(testDispatcher) {
                fakeZBonApiClient.createZBonThrows = NetworkApiException(RuntimeException("Creation failed"))
                viewModel = createViewModel()

                viewModel.createZBon()
                advanceUntilIdle()

                val state = viewModel.zBonState.value
                assertNotNull(state.error)
                assertNull(state.selectedZBon)
            }
    }

    @Nested
    inner class ClearError {
        @Test
        fun `clearError sets error to null`() =
            runTest(testDispatcher) {
                fakeZBonApiClient.createZBonThrows = NetworkApiException(RuntimeException("Some error"))
                viewModel = createViewModel()

                // Trigger an error first
                viewModel.createZBon()
                advanceUntilIdle()
                assertNotNull(viewModel.zBonState.value.error)

                // Clear the error
                viewModel.clearError()

                assertNull(viewModel.zBonState.value.error)
            }
    }

    @Nested
    inner class OnZBonSelected {
        private val sampleZBonResponse =
            ZBonDto.ZBonResponseDto(
                id = 42L,
                zbonNummer = 5L,
                shopInfoId = 1L,
                shopName = "Test Shop",
                employeeNumber = "EMP001",
                startTime = "2026-03-07T08:00:00",
                endTime = "2026-03-07T22:00:00",
                cancelledAmount = "0.00",
                totalRevenue = "5000.00",
                openingBalance = "100.00",
                expenses = "50.00",
                closingBalance = "5050.00",
                createdAt = "2026-03-07T22:00:00",
            )

        @Test
        fun `success loads and sets selectedZBon`() =
            runTest(testDispatcher) {
                fakeZBonApiClient.getByIdResult = sampleZBonResponse
                viewModel = createViewModel()

                val summary =
                    ZBonDto.ZBonSummeryDto(
                        id = 42L,
                        zbonNummer = 5L,
                        startTime = "2026-03-07T08:00:00",
                        endTime = "2026-03-07T22:00:00",
                        totalRevenue = "5000.00",
                    )
                viewModel.onZBonSelected(summary)
                advanceUntilIdle()

                val state = viewModel.zBonState.value
                assertNotNull(state.selectedZBon)
                assertEquals(42L, state.selectedZBon?.id)
                assertEquals(42L, fakeZBonApiClient.lastGetByIdZbonId)
            }

        @Test
        fun `error sets error state`() =
            runTest(testDispatcher) {
                fakeZBonApiClient.getByIdThrows = NetworkApiException(RuntimeException("Z-Bon not found"))
                viewModel = createViewModel()

                val summary =
                    ZBonDto.ZBonSummeryDto(
                        id = 99L,
                        zbonNummer = 10L,
                        startTime = "2026-03-07T08:00:00",
                        endTime = "2026-03-07T22:00:00",
                        totalRevenue = "0.00",
                    )
                viewModel.onZBonSelected(summary)
                advanceUntilIdle()

                assertNotNull(viewModel.zBonState.value.error)
                assertNull(viewModel.zBonState.value.selectedZBon)
            }
    }

    @Nested
    inner class PrintZBon {
        @Test
        fun `success sets success message`() =
            runTest(testDispatcher) {
                fakeZBonApiClient.printZBonThrows = null
                viewModel = createViewModel()

                viewModel.printZBon(1L)
                advanceUntilIdle()

                val state = viewModel.zBonState.value
                assertEquals(getString(ReportsKeys.zBonPrinted, Language.DEFAULT), state.successMessage)
                assertEquals(1L, fakeZBonApiClient.lastPrintZBonId)
                assertEquals(1, fakeZBonApiClient.printZBonCallCount)
            }

        @Test
        fun `error sets error state`() =
            runTest(testDispatcher) {
                fakeZBonApiClient.printZBonThrows = NetworkApiException(RuntimeException("Printer offline"))
                viewModel = createViewModel()

                viewModel.printZBon(1L)
                advanceUntilIdle()

                assertNotNull(viewModel.zBonState.value.error)
                assertNull(viewModel.zBonState.value.successMessage)
            }
    }

    @Nested
    inner class ClearSelectedZBon {
        @Test
        fun `clearSelectedZBon sets selectedZBon to null`() =
            runTest(testDispatcher) {
                val zbonResponse =
                    ZBonDto.ZBonResponseDto(
                        id = 1L,
                        zbonNummer = 1L,
                        shopInfoId = 1L,
                        shopName = "Test Shop",
                        employeeNumber = "EMP001",
                        startTime = "2026-03-07T08:00:00",
                        endTime = "2026-03-07T22:00:00",
                        cancelledAmount = "0.00",
                        totalRevenue = "5000.00",
                        openingBalance = "100.00",
                        expenses = "50.00",
                        closingBalance = "5050.00",
                        createdAt = "2026-03-07T22:00:00",
                    )
                fakeZBonApiClient.createZBonResult = zbonResponse
                viewModel = createViewModel()

                viewModel.createZBon()
                advanceUntilIdle()
                assertNotNull(viewModel.zBonState.value.selectedZBon)

                viewModel.clearSelectedZBon()

                assertNull(viewModel.zBonState.value.selectedZBon)
            }
    }

    @Nested
    inner class ClearSuccessMessage {
        @Test
        fun `clearSuccessMessage sets successMessage to null`() =
            runTest(testDispatcher) {
                fakeZBonApiClient.printZBonThrows = null
                viewModel = createViewModel()

                viewModel.printZBon(1L)
                advanceUntilIdle()
                assertNotNull(viewModel.zBonState.value.successMessage)

                viewModel.clearSuccessMessage()

                assertNull(viewModel.zBonState.value.successMessage)
            }
    }

    /**
     * E2E-2026-09-18 #8 — the drawer count travels WITH the closing request. The server books the
     * Kassendifferenz only once the closing's preconditions pass; the till never calls /cash-count
     * itself any more, so a refused closing can no longer leave a difference booked.
     */
    @Nested
    inner class DrawerCount {
        @Test
        fun `no count means no counted amount on the closing`() =
            runTest(testDispatcher) {
                fakeZBonApiClient.createZBonResult = sampleZBonResponse
                viewModel = createViewModel()

                viewModel.createZBonWithCount(countedAmount = null)
                advanceUntilIdle()

                assertEquals(listOf<String?>(null), fakeZBonApiClient.createdWithCounts)
                assertEquals(0, fakeKassenbuchApiClient.cashCountCallCount)
            }

        @Test
        fun `a count travels with the closing request, unchanged`() =
            runTest(testDispatcher) {
                fakeZBonApiClient.createZBonResult = sampleZBonResponse
                viewModel = createViewModel()

                viewModel.createZBonWithCount(countedAmount = "130.00")
                advanceUntilIdle()

                assertEquals(listOf<String?>("130.00"), fakeZBonApiClient.createdWithCounts)
                assertEquals(0, fakeKassenbuchApiClient.cashCountCallCount, "the till no longer books the count itself")
            }

        @Test
        fun `a refused closing surfaces its error`() =
            runTest(testDispatcher) {
                fakeZBonApiClient.createZBonThrows = NetworkApiException(RuntimeException("boom"))
                viewModel = createViewModel()

                viewModel.createZBonWithCount(countedAmount = "130.00")
                advanceUntilIdle()

                assertEquals(0, fakeKassenbuchApiClient.cashCountCallCount)
                assertNotNull(viewModel.zBonState.value.error)
            }

        /**
         * The drawer holds the day's cash takings before the closing books them, so the prefill
         * must be the server's expected drawer content, never the bare book balance — a count
         * accepted against the book alone signs the takings as a phantom surplus.
         */
        @Test
        fun `the prefill is the expected drawer content, not the bare book balance`() =
            runTest(testDispatcher) {
                fakeKassenbuchApiClient.getCurrentBalanceRawResult = "100.00"
                fakeKassenbuchApiClient.getCashCountExpectationResult =
                    KassenbuchDto.CashCountExpectationDto(
                        bookBalance = "100.00",
                        unbookedCashTakings = "30.00",
                        expectedCash = "130.00",
                    )
                viewModel = createViewModel()

                viewModel.loadExpectedCashBalance()
                advanceUntilIdle()

                assertEquals("130.00", viewModel.zBonState.value.expectedCashBalance)
                assertEquals(0, fakeKassenbuchApiClient.getCurrentBalanceRawCallCount, "the bare book balance is not the prefill")
            }

        @Test
        fun `a failed prefill leaves the balance null and never blocks closing`() =
            runTest(testDispatcher) {
                fakeKassenbuchApiClient.getCashCountExpectationThrows = NetworkApiException(RuntimeException("boom"))
                viewModel = createViewModel()

                viewModel.loadExpectedCashBalance()
                advanceUntilIdle()

                assertNull(viewModel.zBonState.value.expectedCashBalance)
                assertNull(viewModel.zBonState.value.error, "a prefill failure is not an error the operator must clear")
            }
    }
}
