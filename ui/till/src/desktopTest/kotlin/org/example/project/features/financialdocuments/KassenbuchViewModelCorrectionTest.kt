package org.example.project.features.financialdocuments

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KassenbuchViewModelCorrectionTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        fake: FakeKassenbuchApiClient,
        documentApi: FakeDocumentManagementApiClient = FakeDocumentManagementApiClient(),
    ): KassenbuchViewModel =
        KassenbuchViewModel(
            kassenbuchApiClient = fake,
            cashBookApiClient = FakeCashBookApiClient(),
            zBonApiClient = FakeZBonApiClient(),
            documentManagementApiClient = documentApi,
            eventBus = FinancialDocumentsEventBus(),
        )

    private fun groupedDetail(
        headerId: Long,
        correctsHeaderId: Long? = null,
    ): KassenbuchDto.KassenbuchGroupedDetailDto =
        KassenbuchDto.KassenbuchGroupedDetailDto(
            headerId = headerId,
            belegNr = "KB-$headerId",
            entryType = KassenbuchEntryType.CASH_SALE,
            belegtext = "Test",
            belegDatum = "01.01.2026",
            waehrung = "EUR",
            totalAmount = "90.0000",
            currentBalance = "90.0000",
            employeeNumber = "EMP001",
            isLocked = false,
            createdAt = "2026-01-01T10:00:00",
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
            correctsHeaderId = correctsHeaderId,
            correctionRole = CorrectionRole.RE_ENTRY,
        )

    private fun correctionRequest(reason: String): KassenbuchDto.CorrectEntryRequestDto =
        KassenbuchDto.CorrectEntryRequestDto(
            reason = reason,
            correctedEntry =
                KassenbuchDto.CreateKassenbuchEntryRequestDto(
                    entryType = KassenbuchEntryType.CASH_SALE,
                    lines =
                        listOf(
                            KassenbuchDto.EntryLineDto(vatRate = "19", amount = "90.0"),
                        ),
                    waehrung = "EUR",
                    belegtext = "Test",
                    documentId = null,
                ),
        )

    @Test
    fun `submitCorrection success closes dialog and refreshes`() =
        runTest(testDispatcher) {
            val fake =
                FakeKassenbuchApiClient().apply {
                    correctEntryResult = groupedDetail(headerId = 102L, correctsHeaderId = 45L)
                    getEntryHistoryThrows = null
                }
            val vm = newViewModel(fake)

            vm.submitCorrection(45L, correctionRequest("Wrong amount"))
            advanceUntilIdle()

            assertEquals(1, fake.correctEntryCallCount)
            assertEquals(45L, fake.lastCorrectHeaderId)
            assertFalse(vm.kassenbuchState.value.showCorrectionDialog)
            assertFalse(vm.kassenbuchState.value.isCorrecting)
        }

    @Test
    fun `submitCorrection error surfaces UiError and keeps dialog state`() =
        runTest(testDispatcher) {
            val fake =
                FakeKassenbuchApiClient().apply {
                    correctEntryThrows = NetworkApiException(RuntimeException("boom"))
                }
            val vm = newViewModel(fake)

            vm.submitCorrection(45L, correctionRequest("x"))
            advanceUntilIdle()

            assertTrue(vm.kassenbuchState.value.error != null)
            assertFalse(vm.kassenbuchState.value.isCorrecting)
        }

    @Test
    fun `submitCorrection with replacement uploads document and sets replacementDocumentId`() =
        runTest(testDispatcher) {
            val fake =
                FakeKassenbuchApiClient().apply {
                    correctEntryResult = groupedDetail(headerId = 103L, correctsHeaderId = 45L)
                    getEntryHistoryThrows = null
                }
            val documentApi =
                FakeDocumentManagementApiClient().apply {
                    uploadDocumentThrows = null
                    uploadDocumentResult =
                        DocumentDto.DocumentResponseDto(
                            id = 777L,
                            filename = "replacement.pdf",
                            fileType = "EIGENBELEG",
                            fileSize = 4L,
                            uploadedAt = "2026-01-01T10:00:00",
                            uploadedBy = "EMP001",
                            downloadUrl = "http://example/777",
                            formattedFileSize = "4 B",
                            monthFolder = "2026-01",
                        )
                }
            val vm = newViewModel(fake, documentApi)

            vm.submitCorrection(
                headerId = 45L,
                request = correctionRequest("Wrong document"),
                fileBytes = byteArrayOf(1, 2, 3, 4),
                fileName = "replacement.pdf",
            )
            advanceUntilIdle()

            assertEquals(1, documentApi.uploadDocumentCallCount)
            assertEquals(1, fake.correctEntryCallCount)
            assertEquals(777L, fake.lastCorrectRequest?.replacementDocumentId)
            assertFalse(vm.kassenbuchState.value.showCorrectionDialog)
            assertFalse(vm.kassenbuchState.value.isCorrecting)
        }
}
