package org.example.project.features.financialdocuments

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.core.network.error.NetworkApiException
import org.example.project.features.financialdocuments.presentation.state.FinancialDocumentsEventBus
import org.example.project.features.financialdocuments.presentation.viewmodel.KassenbuchViewModel
import org.example.project.testutils.fakes.FakeCashBookApiClient
import org.example.project.testutils.fakes.FakeDocumentManagementApiClient
import org.example.project.testutils.fakes.FakeKassenbuchApiClient
import org.example.project.testutils.fakes.FakeZBonApiClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A4 — the manual cash transfer (Geldtransit) form's ViewModel behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KassenbuchTransferViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var cashBooks: FakeCashBookApiClient

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        cashBooks =
            FakeCashBookApiClient().apply {
                listBooksResult = listOf(till, safe)
                createTransferResult = transferResponse
            }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): KassenbuchViewModel =
        KassenbuchViewModel(
            kassenbuchApiClient = FakeKassenbuchApiClient(),
            cashBookApiClient = cashBooks,
            zBonApiClient = FakeZBonApiClient(),
            documentManagementApiClient = FakeDocumentManagementApiClient(),
            eventBus = FinancialDocumentsEventBus(),
        )

    @Test
    fun `opening the transfer dialog loads the book list`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()

            viewModel.showTransferDialog()
            advanceUntilIdle()

            assertTrue(viewModel.kassenbuchState.value.showTransferDialog)
            assertEquals(2, viewModel.kassenbuchState.value.cashBooks.size)
        }

    @Test
    fun `a successful transfer closes the dialog and sends the request unchanged`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            viewModel.showTransferDialog()
            advanceUntilIdle()

            viewModel.createTransfer(request)
            advanceUntilIdle()

            assertFalse(viewModel.kassenbuchState.value.showTransferDialog)
            assertEquals(1, cashBooks.createTransferCallCount)
            assertEquals(
                request,
                cashBooks.lastTransferRequest,
                "the amount and both book ids must reach the server unaltered",
            )
        }

    @Test
    fun `a rejected transfer keeps the dialog open and surfaces the error`() =
        runTest(testDispatcher) {
            cashBooks.createTransferThrows = NetworkApiException(RuntimeException("boom"))
            val viewModel = newViewModel()
            viewModel.showTransferDialog()
            advanceUntilIdle()

            viewModel.createTransfer(request)
            advanceUntilIdle()

            assertTrue(
                viewModel.kassenbuchState.value.showTransferDialog,
                "the operator must not have to re-key an amount they already typed",
            )
            assertFalse(viewModel.kassenbuchState.value.isTransferring)
            assertNotNull(viewModel.kassenbuchState.value.error)
        }

    @Test
    fun `a failed book load surfaces an error but still opens the dialog`() =
        runTest(testDispatcher) {
            cashBooks.listBooksThrows = NetworkApiException(RuntimeException("boom"))
            val viewModel = newViewModel()

            viewModel.showTransferDialog()
            advanceUntilIdle()

            assertTrue(viewModel.kassenbuchState.value.showTransferDialog)
            assertTrue(
                viewModel.kassenbuchState.value.cashBooks
                    .isEmpty(),
            )
            assertNotNull(viewModel.kassenbuchState.value.error)
        }

    private companion object {
        val till =
            CashBookDto.ResponseDto(
                id = 1L,
                name = "Ladenkasse",
                isFiscal = true,
                datevAccount = "1000",
                closeInterval = CashBookCloseInterval.MANUAL,
                isActive = true,
                sortOrder = 0,
                currentBalance = "500.00",
                currency = "EUR",
            )

        val safe = till.copy(id = 2L, name = "Tresor", isFiscal = false, currentBalance = "0.00")

        val request =
            CashBookDto.CreateTransferRequestDto(
                fromBookId = 1L,
                toBookId = 2L,
                amount = "120.00",
                belegDatum = "11.08.2026",
                note = null,
            )

        val transferResponse =
            CashBookDto.TransferResponseDto(
                transferId = "T-1",
                fromLegHeaderId = 10L,
                toLegHeaderId = 11L,
                fromBelegNr = "2026-08-0010",
                toBelegNr = "2026-08-0011",
                amount = "120.00",
            )
    }
}
