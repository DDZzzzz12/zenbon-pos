package org.example.project.admin.features.documents.capture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.documents.FakeDocumentManagementApiClient
import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureMode
import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureViewModel
import org.example.project.admin.features.invoices.FakeInvoiceListApiClient
import org.example.project.admin.features.supplier.FakeSupplierApiClient
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InvoiceCaptureViewModelLoadTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: FakeDocumentManagementApiClient
    private lateinit var vm: InvoiceCaptureViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = FakeDocumentManagementApiClient()
        api.getDocumentByIdResponse = { document(it, ProcessingStatus.NONE) }
        api.getDocumentFileResponse = { ByteArray(0) }
        api.updateDocumentStatusResponse = { id, _ -> document(id, ProcessingStatus.IN_PROGRESS) }
        vm = InvoiceCaptureViewModel(api, FakeInvoiceListApiClient(), FakeSupplierApiClient())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun document(
        id: Long,
        status: ProcessingStatus,
    ) = DocumentDto.DocumentResponseDto(
        id = id,
        filename = "scan.pdf",
        fileType = "INVOICE",
        fileSize = 1024L,
        uploadedAt = "2026-03-01T09:00",
        uploadedBy = "admin",
        downloadUrl = "/api/documents/$id/file",
        formattedFileSize = "1 KB",
        monthFolder = "2026-03",
        processingStatus = status,
    )

    private fun parsedInvoice() =
        DocumentDto.InvoiceDto(
            id = 42L,
            sourceDocumentId = 1L,
            extractionSource = "EINVOICE_XML",
            currency = "EUR",
            invoiceNumber = "RE-XML-1",
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
    fun `a 404 from getDocumentInvoice opens an empty create form and is not an error`() =
        runTest(dispatcher) {
            // The fake's default is exactly the server's behaviour for an uncaptured document.
            vm.load(documentId = 1L, canManage = true)
            advanceUntilIdle()

            assertEquals(InvoiceCaptureMode.Create, vm.state.mode)
            assertNull(vm.state.error, "an uncaptured document is the normal case, not a failure")
            assertEquals("", vm.state.form.invoiceNumber)
            assertEquals(1, vm.state.form.vatRows.size, "a create form starts with one VAT row")
            assertTrue(!vm.state.isLoading)
            assertTrue(!vm.state.isDirty, "a freshly opened form must not look dirty")
        }

    @Test
    fun `an existing invoice opens in edit mode seeded from the aggregate`() =
        runTest(dispatcher) {
            api.getDocumentInvoiceResponse = { parsedInvoice() }
            vm.load(documentId = 1L, canManage = true)
            advanceUntilIdle()

            assertEquals(InvoiceCaptureMode.Edit(42L, "EINVOICE_XML"), vm.state.mode)
            assertEquals("RE-XML-1", vm.state.form.invoiceNumber)
            assertEquals("2026-03-01", vm.state.form.issueDate, "the time part is dropped for the date field")
            assertEquals("238.00", vm.state.form.grossTotal)
            val loadedForm = vm.state.form
            assertEquals("19.00", loadedForm.vatRows.single().rate)
            assertTrue(vm.state.supplierReadOnly, "an e-invoice's supplier fields are not editable")
            assertNull(vm.state.error)
        }

    @Test
    fun `any error that is not a 404 still surfaces`() =
        runTest(dispatcher) {
            api.getDocumentInvoiceResponse = {
                throw PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)
            }
            vm.load(documentId = 1L, canManage = true)
            advanceUntilIdle()

            assertTrue(vm.state.error != null, "a real failure must not be swallowed by the 404 branch")
            assertTrue(!vm.state.isLoading)
        }

    @Test
    fun `opening a document that needs entry parks it at IN_PROGRESS, and only once`() =
        runTest(dispatcher) {
            vm.load(documentId = 1L, canManage = true)
            advanceUntilIdle()
            assertEquals(listOf(ProcessingStatus.IN_PROGRESS), api.statusWrites)

            api.statusWrites.clear()
            api.getDocumentByIdResponse = { document(it, ProcessingStatus.COMPLETED) }
            vm.load(documentId = 1L, canManage = true)
            advanceUntilIdle()
            assertEquals(emptyList(), api.statusWrites, "a parsed document is for checking, not entry")

            api.getDocumentByIdResponse = { document(it, ProcessingStatus.NONE) }
            vm.load(documentId = 1L, canManage = false)
            advanceUntilIdle()
            assertEquals(emptyList(), api.statusWrites, "a read-only viewer must not move the workflow")
        }
}
