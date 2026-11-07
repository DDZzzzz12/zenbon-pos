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
import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureField
import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureMode
import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureViewModel
import org.example.project.admin.features.documents.presentation.capture.VatRowForm
import org.example.project.admin.features.invoices.FakeInvoiceListApiClient
import org.example.project.admin.features.supplier.FakeSupplierApiClient
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ClearableBookingField
import org.example.project.core.domain.enums.document.ProcessingStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InvoiceCaptureViewModelSaveTest {
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

    /**
     * A stored invoice always carries a VAT breakdown — a blank Steuersatz is a required-field error
     * (`vatRowErrors`), so an aggregate without one would seed a form that can never be saved and
     * every routing assertion below would pass for the wrong reason.
     */
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

    private fun fillValidForm() {
        vm.updateForm {
            it.copy(
                invoiceNumber = "RE-1",
                issueDate = "2026-03-01",
                grossTotal = "238,00",
                sellerName = "Lieferant GmbH",
                vatRows = listOf(VatRowForm(rate = "19")),
            )
        }
    }

    @Test
    fun `an invalid form dispatches nothing and names the offending fields`() =
        runTest(dispatcher) {
            vm.load(1L, canManage = true)
            advanceUntilIdle()
            vm.updateForm { it.copy(invoiceNumber = "RE-1") }

            vm.save()
            advanceUntilIdle()

            assertEquals(0, api.createDocumentInvoiceCallCount, "a rejected form must not reach the server")
            assertEquals(
                AdminStringKeys.InvoiceCaptureErrorRequired,
                vm.state.fieldErrors[InvoiceCaptureField.SELLER_NAME],
            )
            assertTrue(vm.state.validationShown)
        }

    @Test
    fun `a create save posts once, canonicalizes money and switches to edit mode`() =
        runTest(dispatcher) {
            vm.load(1L, canManage = true)
            advanceUntilIdle()
            fillValidForm()
            var sent: DocumentDto.InvoiceContentRequestDto? = null
            api.createDocumentInvoiceResponse = { _, request ->
                sent = request
                manualInvoice()
            }

            vm.save()
            advanceUntilIdle()

            assertEquals(1, api.createDocumentInvoiceCallCount)
            assertEquals(0, api.updateInvoiceContentCallCount)
            assertEquals(0, api.updateInvoiceBookingCallCount, "no booking typed, no booking call")
            assertEquals("238.00", sent?.taxInclusiveTotal, "money is canonical, never a Double")
            assertEquals("200.00", sent?.vatBreakdown?.single()?.taxableAmount)
            assertEquals(InvoiceCaptureMode.Edit(42L, "MANUAL"), vm.state.mode)
            assertTrue(!vm.state.isDirty, "a successful save re-pins the pristine snapshot")
        }

    @Test
    fun `a create save with booking typed posts then books`() =
        runTest(dispatcher) {
            vm.load(1L, canManage = true)
            advanceUntilIdle()
            fillValidForm()
            vm.updateForm { it.copy(bpAccountNo = "70001", bookingText = "Wareneinkauf") }
            api.createDocumentInvoiceResponse = { _, _ -> manualInvoice() }
            var bookedInvoiceId: Long? = null
            api.updateInvoiceBookingResponse = { id, _ ->
                bookedInvoiceId = id
                manualInvoice()
            }

            vm.save()
            advanceUntilIdle()

            assertEquals(1, api.createDocumentInvoiceCallCount)
            assertEquals(1, api.updateInvoiceBookingCallCount)
            assertEquals(42L, bookedInvoiceId, "booking is keyed on the NEW invoice id, not the document id")
        }

    @Test
    fun `an edit save routes content and booking independently by dirty scope`() =
        runTest(dispatcher) {
            api.getDocumentInvoiceResponse = { manualInvoice() }
            api.updateInvoiceContentResponse = { _, _ -> manualInvoice() }
            api.updateInvoiceBookingResponse = { _, _ -> manualInvoice() }
            vm.load(1L, canManage = true)
            advanceUntilIdle()

            vm.updateForm { it.copy(bookingText = "Wareneinkauf") }
            vm.save()
            advanceUntilIdle()
            assertEquals(0, api.updateInvoiceContentCallCount, "booking-only edit must not rewrite content")
            assertEquals(1, api.updateInvoiceBookingCallCount)

            vm.updateForm { it.copy(sellerName = "Anderer Lieferant") }
            vm.save()
            advanceUntilIdle()
            assertEquals(1, api.updateInvoiceContentCallCount)
            assertEquals(1, api.updateInvoiceBookingCallCount, "booking is no longer dirty after the first save")
        }

    @Test
    fun `emptying a booking field names it in clearFields rather than leaving the old value`() =
        runTest(dispatcher) {
            api.getDocumentInvoiceResponse = {
                manualInvoice().copy(
                    booking = DocumentDto.InvoiceBookingDto(bpAccountNo = "70001", bookingText = "Alt"),
                )
            }
            var sent: DocumentDto.UpdateInvoiceBookingRequestDto? = null
            api.updateInvoiceBookingResponse = { _, request ->
                sent = request
                manualInvoice()
            }
            vm.load(1L, canManage = true)
            advanceUntilIdle()

            vm.updateForm { it.copy(bookingText = "") }
            vm.save()
            advanceUntilIdle()

            assertNull(sent?.bookingText, "null means leave unchanged, so a cleared field must be named")
            assertTrue(ClearableBookingField.BOOKING_TEXT in sent?.clearFields.orEmpty())
        }

    /**
     * T9-19: a cancelled invoice is frozen. Whatever is typed, a save writes nothing — the form is
     * disabled on screen and the server would refuse with `INVOICE_CANCELLED_READ_ONLY` anyway.
     */
    @Test
    fun `a cancelled invoice is read-only and a save writes nothing`() =
        runTest(dispatcher) {
            api.getDocumentInvoiceResponse = {
                manualInvoice().copy(
                    storno =
                        DocumentDto.InvoiceStornoDto(
                            originalInvoiceId = 42L,
                            correctionInvoiceId = 43L,
                            reason = "Doppelt erfasst",
                            cancelledAt = "2026-09-26T10:00",
                        ),
                )
            }
            api.updateInvoiceContentResponse = { _, _ -> manualInvoice() }
            api.updateInvoiceBookingResponse = { _, _ -> manualInvoice() }
            vm.load(1L, canManage = true)
            advanceUntilIdle()

            assertTrue(vm.state.stornoReadOnly)
            vm.updateForm { it.copy(invoiceNumber = "RE-9", bookingText = "Wareneinkauf") }
            vm.save()
            advanceUntilIdle()

            assertEquals(0, api.updateInvoiceContentCallCount)
            assertEquals(0, api.updateInvoiceBookingCallCount)
        }

    /** R6-1: the cancelled original is frozen the same way — *Als geprüft markieren* does nothing. */
    @Test
    fun `confirming a cancelled original is a no-op and runs no validation`() =
        runTest(dispatcher) {
            api.getDocumentInvoiceResponse = {
                manualInvoice().copy(
                    invoiceNumber = null,
                    storno =
                        DocumentDto.InvoiceStornoDto(
                            originalInvoiceId = 42L,
                            correctionInvoiceId = 43L,
                            reason = "Doppelt erfasst",
                            cancelledAt = "2026-09-26T10:00",
                        ),
                )
            }
            vm.load(1L, canManage = true)
            advanceUntilIdle()
            val statusWritesAfterLoad = api.statusWrites.toList()

            assertTrue(!vm.state.canConfirmReviewed)
            vm.confirmReviewed()
            advanceUntilIdle()

            assertEquals(statusWritesAfterLoad, api.statusWrites, "the document is not moved to REVIEWED")
            assertTrue(!vm.state.validationShown, "the blank Rechnungsnummer must not be flagged")
            assertTrue(vm.state.fieldErrors.isEmpty())
            assertEquals(0, api.updateInvoiceContentCallCount)
            assertEquals(0, api.updateInvoiceBookingCallCount)
            assertNull(vm.state.successMessage)
        }

    @Test
    fun `a live invoice may still be confirmed`() =
        runTest(dispatcher) {
            api.getDocumentInvoiceResponse = { manualInvoice() }
            vm.load(1L, canManage = true)
            advanceUntilIdle()

            assertTrue(vm.state.canConfirmReviewed)
        }

    @Test
    fun `save and continue calls back only after the save succeeded`() =
        runTest(dispatcher) {
            vm.load(1L, canManage = true)
            advanceUntilIdle()
            fillValidForm()
            api.createDocumentInvoiceResponse = { _, _ -> manualInvoice() }
            var continued = 0

            vm.saveThen { continued++ }
            advanceUntilIdle()

            assertEquals(1, api.createDocumentInvoiceCallCount)
            assertEquals(1, continued, "Speichern und zuordnen carries on once the invoice is written")
        }

    @Test
    fun `save and continue stays on the form when validation refuses the save`() =
        runTest(dispatcher) {
            vm.load(1L, canManage = true)
            advanceUntilIdle()
            vm.updateForm { it.copy(invoiceNumber = "RE-1") }
            var continued = 0

            vm.saveThen { continued++ }
            advanceUntilIdle()

            assertEquals(0, continued, "an invalid form must not navigate away")
            assertEquals(0, api.createDocumentInvoiceCallCount)
        }
}
