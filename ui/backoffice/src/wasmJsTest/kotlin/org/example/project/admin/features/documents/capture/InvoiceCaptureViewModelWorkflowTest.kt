package org.example.project.admin.features.documents.capture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.FakeDocumentManagementApiClient
import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureViewModel
import org.example.project.admin.features.invoices.FakeInvoiceListApiClient
import org.example.project.admin.features.supplier.FakeSupplierApiClient
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.error.UiError
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InvoiceCaptureViewModelWorkflowTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: FakeDocumentManagementApiClient
    private lateinit var vm: InvoiceCaptureViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = FakeDocumentManagementApiClient()
        api.getDocumentByIdResponse = { document(it) }
        api.getDocumentFileResponse = { ByteArray(0) }
        api.updateDocumentStatusResponse = { id, _ -> document(id) }
        vm = InvoiceCaptureViewModel(api, FakeInvoiceListApiClient(), FakeSupplierApiClient())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun document(id: Long) =
        DocumentDto.DocumentResponseDto(
            id = id,
            filename = "scan.pdf",
            fileType = "INVOICE",
            fileSize = 1024L,
            uploadedAt = "2026-03-01T09:00",
            uploadedBy = "admin",
            downloadUrl = "/api/documents/$id/file",
            formattedFileSize = "1 KB",
            monthFolder = "2026-03",
            processingStatus = ProcessingStatus.NONE,
        )

    private fun manualInvoice(id: Long = 42L) =
        DocumentDto.InvoiceDto(
            id = id,
            sourceDocumentId = 1L,
            extractionSource = "MANUAL",
            currency = "EUR",
            invoiceNumber = "RE-1",
            issueDate = "2026-03-01T00:00",
            sellerName = "Lieferant GmbH",
            taxInclusiveTotal = "238.00",
            vatBreakdown =
                listOf(
                    DocumentDto.VatBreakdownDto(
                        vatCategoryCode = "S",
                        vatRate = "19.00",
                        taxableAmount = "200.00",
                        vatAmount = "38.00",
                    ),
                ),
        )

    @Test
    fun `a 409 read-only rejection is surfaced with its own message and re-reads the truth`() =
        runTest(dispatcher) {
            api.getDocumentInvoiceResponse = { manualInvoice().copy(extractionSource = "MANUAL") }
            api.updateInvoiceContentResponse = { _, _ ->
                throw PosApiException(
                    code = PosErrorCode.INVOICE_SUPPLIER_CONTENT_READ_ONLY,
                    httpStatus = 409,
                    message = "supplier content is read-only",
                    traceId = null,
                )
            }
            vm.load(1L, canManage = true)
            advanceUntilIdle()
            vm.updateForm { it.copy(sellerName = "Von Hand Getippt GmbH") }

            vm.save()
            advanceUntilIdle()

            val error = vm.state.error
            assertTrue(error is UiError.FromServer)
            assertEquals(PosErrorCode.INVOICE_SUPPLIER_CONTENT_READ_ONLY, error.code)
            assertEquals(
                AdminStringKeys.InvoiceCaptureReadOnlyConflict,
                error.messageKey,
                "the generic server detail is useless to an operator here",
            )
            assertTrue(!vm.state.isSaving)
        }

    @Test
    fun `confirming a checked invoice saves first, then marks it REVIEWED`() =
        runTest(dispatcher) {
            api.getDocumentInvoiceResponse = { manualInvoice() }
            api.updateInvoiceContentResponse = { _, _ -> manualInvoice() }
            api.updateDocumentStatusResponse = { id, _ -> document(id) }
            vm.load(1L, canManage = true)
            advanceUntilIdle()
            api.statusWrites.clear()

            vm.updateForm { it.copy(sellerName = "Geprüfter Lieferant") }
            vm.confirmReviewed()
            advanceUntilIdle()

            assertEquals(1, api.updateInvoiceContentCallCount, "confirm saves before it confirms")
            assertEquals(listOf(ProcessingStatus.REVIEWED), api.statusWrites)
            assertEquals(
                AdminStringKeys.InvoiceCaptureReviewed,
                vm.state.successMessage,
                "one gesture, one toast, naming the outcome the operator asked for",
            )
        }

    /**
     * The bug this pins: an operator who reads a form and presses *Als geprüft markieren* without
     * editing anything leaves no dirty scope, so [InvoiceCaptureViewModel.performSave] issues no
     * request at all — and the save confirmation that used to fire from the save chain was reporting
     * a write that never happened.
     */
    @Test
    fun `confirming an untouched invoice issues no write and never claims it saved`() =
        runTest(dispatcher) {
            api.getDocumentInvoiceResponse = { manualInvoice() }
            vm.load(1L, canManage = true)
            advanceUntilIdle()
            api.statusWrites.clear()

            vm.confirmReviewed()
            advanceUntilIdle()

            assertEquals(0, api.updateInvoiceContentCallCount, "nothing was dirty")
            assertEquals(0, api.updateInvoiceBookingCallCount, "nothing was dirty")
            assertEquals(listOf(ProcessingStatus.REVIEWED), api.statusWrites)
            assertEquals(AdminStringKeys.InvoiceCaptureReviewed, vm.state.successMessage)
        }

    @Test
    fun `a plain save confirms the save`() =
        runTest(dispatcher) {
            api.getDocumentInvoiceResponse = { manualInvoice() }
            api.updateInvoiceContentResponse = { _, _ -> manualInvoice() }
            vm.load(1L, canManage = true)
            advanceUntilIdle()
            api.statusWrites.clear()

            vm.updateForm { it.copy(sellerName = "Neuer Lieferant") }
            vm.save()
            advanceUntilIdle()

            assertEquals(1, api.updateInvoiceContentCallCount)
            assertEquals(AdminStringKeys.InvoiceCaptureSaved, vm.state.successMessage)
            assertEquals(emptyList(), api.statusWrites, "a save is not a review")
        }

    @Test
    fun `confirming an invalid form neither saves nor marks it REVIEWED`() =
        runTest(dispatcher) {
            vm.load(1L, canManage = true)
            advanceUntilIdle()
            api.statusWrites.clear()

            vm.confirmReviewed()
            advanceUntilIdle()

            assertEquals(0, api.createDocumentInvoiceCallCount)
            assertEquals(emptyList(), api.statusWrites, "REVIEWED must never outrun the data")
            assertTrue(vm.state.fieldErrors.isNotEmpty())
        }

    @Test
    fun `a failed save leaves the status alone`() =
        runTest(dispatcher) {
            api.getDocumentInvoiceResponse = { manualInvoice() }
            api.updateInvoiceContentResponse = { _, _ ->
                throw PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)
            }
            vm.load(1L, canManage = true)
            advanceUntilIdle()
            api.statusWrites.clear()

            vm.updateForm { it.copy(sellerName = "Neuer Name") }
            vm.confirmReviewed()
            advanceUntilIdle()

            assertEquals(emptyList(), api.statusWrites)
            assertTrue(vm.state.error != null)
        }
}
