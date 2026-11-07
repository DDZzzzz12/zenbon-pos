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

/**
 * The invoice-keyed entry point: an invoice that arrived as a bare XRechnung XML, or was entered by
 * hand, has **no document**, so every document-keyed call the document path makes is impossible here.
 *
 * The document fake's stubs are deliberately left unconfigured — they throw `NotImplementedError`,
 * which `runCatchingApi` does not catch — so a stray document call fails loudly rather than being
 * silently swallowed by an error branch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InvoiceCaptureViewModelInvoiceLoadTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var documentApi: FakeDocumentManagementApiClient
    private lateinit var invoiceApi: FakeInvoiceListApiClient
    private lateinit var vm: InvoiceCaptureViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        documentApi = FakeDocumentManagementApiClient()
        invoiceApi = FakeInvoiceListApiClient()
        invoiceApi.invoice = documentLessInvoice()
        vm = InvoiceCaptureViewModel(documentApi, invoiceApi, FakeSupplierApiClient())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** `sourceDocumentId = null` is the whole point: this invoice has no scan behind it. */
    private fun documentLessInvoice(id: Long = 42L) =
        DocumentDto.InvoiceDto(
            id = id,
            sourceDocumentId = null,
            extractionSource = "MANUAL",
            currency = "EUR",
            invoiceNumber = "RE-OHNE-BELEG",
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
    fun `loadByInvoice seeds the same edit form the document path would`() =
        runTest(dispatcher) {
            vm.loadByInvoice(invoiceId = 42L, canManage = true)
            advanceUntilIdle()

            assertEquals(listOf(42L), invoiceApi.getInvoiceCalls, "the invoice is fetched by its own id")
            assertEquals(InvoiceCaptureMode.Edit(42L, "MANUAL"), vm.state.mode)
            assertEquals("RE-OHNE-BELEG", vm.state.form.invoiceNumber)
            assertEquals("2026-03-01", vm.state.form.issueDate, "the time part is dropped for the date field")
            assertEquals("238.00", vm.state.form.grossTotal)
            val loadedForm = vm.state.form
            assertEquals("19.00", loadedForm.vatRows.single().rate)
            assertNull(vm.state.error)
            assertTrue(!vm.state.isLoading)
            assertTrue(!vm.state.isDirty, "a freshly loaded form must not look dirty")
        }

    @Test
    fun `loadByInvoice issues no document call at all`() =
        runTest(dispatcher) {
            vm.loadByInvoice(invoiceId = 42L, canManage = true)
            advanceUntilIdle()

            assertEquals(0, documentApi.getDocumentByIdCallCount, "there is no document to fetch")
            assertEquals(0, documentApi.getDocumentFileCallCount, "there are no file bytes to fetch")
            assertEquals(emptyList(), documentApi.statusWrites, "there is no document to park at IN_PROGRESS")
        }

    @Test
    fun `the resulting state reports no document and therefore no preview`() =
        runTest(dispatcher) {
            vm.loadByInvoice(invoiceId = 42L, canManage = true)
            advanceUntilIdle()

            assertNull(vm.state.document, "the screen must be able to see that there is no document")
            assertNull(vm.state.fileBytes, "no bytes means the screen lays out without a preview")
        }

    @Test
    fun `saving from this path writes through the invoice-keyed endpoints`() =
        runTest(dispatcher) {
            var bookedInvoiceId: Long? = null
            documentApi.updateInvoiceBookingResponse = { id, _ ->
                bookedInvoiceId = id
                documentLessInvoice()
            }
            vm.loadByInvoice(invoiceId = 42L, canManage = true)
            advanceUntilIdle()

            vm.updateForm { it.copy(bookingText = "Wareneinkauf") }
            vm.save()
            advanceUntilIdle()

            assertEquals(1, documentApi.updateInvoiceBookingCallCount)
            assertEquals(42L, bookedInvoiceId, "the write is keyed on the invoice, not a document")
            assertEquals(0, documentApi.createDocumentInvoiceCallCount, "an existing invoice is never re-created")
            assertEquals(0, documentApi.getDocumentByIdCallCount, "saving must not reach for a document either")
            assertNull(vm.state.error)
        }

    /**
     * The action that would 404 if it stayed document-keyed: there is no `processing_status` to move
     * because there is no document, so confirmation has to stamp `invoice.reviewed_at` instead.
     * Hiding the action would leave such an invoice in *Zu prüfen* forever, which is the worse bug.
     */
    @Test
    fun `confirming a document-less invoice marks the invoice, never a document status`() =
        runTest(dispatcher) {
            vm.loadByInvoice(invoiceId = 42L, canManage = true)
            advanceUntilIdle()

            vm.confirmReviewed()
            advanceUntilIdle()

            assertEquals(listOf(42L), invoiceApi.reviewed, "confirmation goes through the invoice endpoint")
            assertEquals(emptyList(), documentApi.statusWrites, "there is no document status to write")
            assertNull(vm.state.error)
            assertEquals(AdminStringKeys.InvoiceCaptureReviewed, vm.state.successMessage)
        }

    @Test
    fun `a failure to read the invoice surfaces as an error instead of hanging the screen`() =
        runTest(dispatcher) {
            invoiceApi.failWith = PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)

            vm.loadByInvoice(invoiceId = 42L, canManage = true)
            advanceUntilIdle()

            assertTrue(vm.state.error != null)
            assertTrue(!vm.state.isLoading, "a failed load must not leave the spinner up forever")
        }

    /**
     * R6-2: a Storno correction is opened by its own id (the Beleg's Storno notice links to it), yet
     * it sits on the original's Beleg. The form must show that file, not claim there is none.
     */
    private fun stornoCorrection() =
        documentLessInvoice(id = 43L).copy(
            sourceDocumentId = 7L,
            extractionSource = "STORNO",
            invoiceNumber = null,
            invoiceTypeCode = "381",
            precedingInvoiceReference = "RE-1",
            storno =
                DocumentDto.InvoiceStornoDto(
                    originalInvoiceId = 42L,
                    correctionInvoiceId = 43L,
                    reason = "Doppelt erfasst",
                    cancelledAt = "2026-09-26T10:00",
                ),
        )

    @Test
    fun `a correction opened by its id shows the Beleg it sits on`() =
        runTest(dispatcher) {
            val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46)
            val fetched = mutableListOf<Long>()
            documentApi.getDocumentFileResponse = { id ->
                fetched += id
                pdf
            }
            invoiceApi.invoice = stornoCorrection()

            vm.loadByInvoice(invoiceId = 43L, canManage = true)
            advanceUntilIdle()

            assertEquals(7L, vm.state.belegDocumentId, "the screen must know there is a Beleg to show")
            assertEquals(listOf(7L), fetched, "the preview is the original's Beleg, fetched by its id")
            assertTrue(pdf.contentEquals(vm.state.fileBytes), "the Beleg's bytes reach the preview")
            assertNull(vm.state.document, "no document record: confirm/re-read must stay invoice-keyed")
            assertEquals(emptyList(), documentApi.statusWrites, "a correction's Beleg is never parked")
            assertNull(vm.state.error)
        }

    @Test
    fun `a Beleg file that fails to load leaves the form usable without an error dialog`() =
        runTest(dispatcher) {
            documentApi.getDocumentFileResponse = {
                throw PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)
            }
            invoiceApi.invoice = stornoCorrection()

            vm.loadByInvoice(invoiceId = 43L, canManage = true)
            advanceUntilIdle()

            assertEquals(7L, vm.state.belegDocumentId)
            assertNull(vm.state.fileBytes)
            assertNull(vm.state.error, "a missing preview is not a failed load")
            assertTrue(!vm.state.isLoading)
        }

    @Test
    fun `a document-less invoice still reports no Beleg`() =
        runTest(dispatcher) {
            vm.loadByInvoice(invoiceId = 42L, canManage = true)
            advanceUntilIdle()

            assertNull(vm.state.belegDocumentId)
        }

    /**
     * R6-1: *Als geprüft markieren* on a correction must neither validate (the empty Rechnungsnummer
     * of a Storno would turn red on a field nobody can edit) nor send anything.
     */
    @Test
    fun `confirming a correction is a no-op and runs no validation`() =
        runTest(dispatcher) {
            documentApi.getDocumentFileResponse = { ByteArray(0) }
            invoiceApi.invoice = stornoCorrection()
            vm.loadByInvoice(invoiceId = 43L, canManage = true)
            advanceUntilIdle()

            assertTrue(!vm.state.canConfirmReviewed, "the header action is disabled on a correction")
            vm.confirmReviewed()
            advanceUntilIdle()

            assertEquals(emptyList(), invoiceApi.reviewed, "nothing is marked reviewed")
            assertEquals(emptyList(), documentApi.statusWrites)
            assertEquals(0, documentApi.updateInvoiceContentCallCount)
            assertEquals(0, documentApi.updateInvoiceBookingCallCount)
            assertTrue(!vm.state.validationShown, "no validation verdict is painted onto the frozen form")
            assertTrue(vm.state.fieldErrors.isEmpty())
            assertTrue(!vm.state.isSaving)
            assertNull(vm.state.successMessage)
        }

    /**
     * The "Prüfen" card opens the form by invoice id. For an invoice read from a document the review must
     * reach the document as well, or the Beleg-Verlauf and the Erledigt count never see it.
     */
    @Test
    fun `confirming an invoice that has a source document marks both the invoice and the document`() =
        runTest(dispatcher) {
            invoiceApi.invoice = documentLessInvoice().copy(sourceDocumentId = 7L)
            documentApi.getDocumentFileResponse = { ByteArray(0) }
            var statusWrite: Pair<Long, DocumentDto.UpdateDocumentStatusRequestDto>? = null
            documentApi.updateDocumentStatusResponse = { id, request ->
                statusWrite = id to request
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
                    processingStatus = ProcessingStatus.REVIEWED,
                )
            }
            vm.loadByInvoice(invoiceId = 42L, canManage = true)
            advanceUntilIdle()

            vm.confirmReviewed()
            advanceUntilIdle()

            assertEquals(listOf(42L), invoiceApi.reviewed, "the invoice is stamped")
            assertEquals(7L, statusWrite?.first, "and its source document is moved")
            assertEquals(ProcessingStatus.REVIEWED, statusWrite?.second?.status)
            assertEquals(AdminStringKeys.InvoiceCaptureReviewed, vm.state.successMessage, "the message follows both writes")
            assertNull(vm.state.error)
        }
}
