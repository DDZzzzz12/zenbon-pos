package org.example.project.admin.features.documents

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.DocumentDetailViewModel
import org.example.project.admin.features.documents.presentation.DocumentMetadataField
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.core.network.error.NetworkApiException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun invoiceDocument(id: Long) =
    DocumentDto.DocumentResponseDto(
        id = id,
        filename = "invoice-$id.pdf",
        fileType = "INVOICE",
        fileSize = 1024,
        uploadedAt = "2026-08-01T00:00:00Z",
        uploadedBy = "emp-1",
        downloadUrl = "/api/documents/$id/file",
        formattedFileSize = "1.0 KB",
        monthFolder = "2026-08",
    )

private fun invoiceMetadata(id: Long) =
    DocumentDto.DocumentMetadataDto(
        id = id,
        filename = "invoice-$id.pdf",
        fileType = "INVOICE",
        processingStatus = ProcessingStatus.COMPLETED,
        isActive = true,
        linkCount = 0,
        invoiceMetadata =
            DocumentDto.InvoiceMetadataDto(
                vendorName = "Acme GmbH",
                netAmount = "100.00",
                grossAmount = "119.00",
                taxAmount = "19.00",
            ),
    )

private fun bankStatementDocument(id: Long) =
    DocumentDto.DocumentResponseDto(
        id = id,
        filename = "statement-$id.pdf",
        fileType = "BANK_STATEMENT",
        fileSize = 1024,
        uploadedAt = "2026-08-01T00:00:00Z",
        uploadedBy = "emp-1",
        downloadUrl = "/api/documents/$id/file",
        formattedFileSize = "1.0 KB",
        monthFolder = "2026-08",
    )

private fun bankStatementMetadata(id: Long) =
    DocumentDto.DocumentMetadataDto(
        id = id,
        filename = "statement-$id.pdf",
        fileType = "BANK_STATEMENT",
        processingStatus = ProcessingStatus.COMPLETED,
        isActive = true,
        linkCount = 0,
        bankStatementMetadata =
            DocumentDto.BankStatementMetadataDto(
                bankName = "Sparkasse",
                openingBalance = "100.00",
                closingBalance = "250.00",
                currency = "EUR",
            ),
    )

/**
 * [DocumentDetailViewModel.save] must validate every money field to a canonical decimal string
 * BEFORE dispatching to the network — money never passes through a `Double`, and an unparseable
 * amount must never reach
 * [org.example.project.core.data.remote.DocumentManagementApiClient.updateBankStatementMetadata].
 *
 * The endpoint under test is the bank-statement one because Stage 4 removed adminApp's legacy
 * invoice form: an invoice's data belongs to the normalized model that the capture screen owns, so
 * the bank-statement editor is the only metadata write adminApp still performs. The guard itself —
 * `DocumentMetadataForm.hasValidMoneyFields()` — is shared by both, so testing it on the surviving
 * leg loses nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: FakeDocumentManagementApiClient
    private lateinit var vm: DocumentDetailViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = FakeDocumentManagementApiClient()
        vm = DocumentDetailViewModel(api)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun loadBankStatement(id: Long = 1L) {
        api.getDocumentByIdResponse = { bankStatementDocument(id) }
        api.getDocumentMetadataResponse = { bankStatementMetadata(id) }
        api.getDocumentFileResponse = { ByteArray(0) }
        vm.load(id)
    }

    private fun loadInvoice(id: Long = 1L) {
        api.getDocumentByIdResponse = { invoiceDocument(id) }
        api.getDocumentMetadataResponse = { invoiceMetadata(id) }
        api.getDocumentFileResponse = { ByteArray(0) }
        vm.load(id)
    }

    @Test
    fun `save with an unparseable money field is blocked before any network call`() =
        runTest(dispatcher) {
            // Bank statements, not invoices: the legacy invoice form was removed in Stage 4, so
            // the bank-statement editor is now the only metadata write adminApp performs. The money
            // guard being tested is hasValidMoneyFields(), which is shared by both.
            loadBankStatement()
            advanceUntilIdle()

            vm.updateForm { it.copy(closingBalance = "nicht eine Zahl") }
            vm.save(1L)
            advanceUntilIdle()

            assertEquals(0, api.updateBankStatementMetadataCallCount)
            // Validate on press (UI-E2-06): the amount is marked on its field, not raised as a dialog.
            assertEquals(AdminStringKeys.DocumentInvalidAmount, vm.state.fieldErrors[DocumentMetadataField.CLOSING_BALANCE])
            assertEquals(1, vm.state.validationAttempt)
            assertNull(vm.state.error)
            assertFalse(vm.state.isSaving)
        }

    @Test
    fun `save with a statement date that is no date is refused on the date field`() =
        runTest(dispatcher) {
            loadBankStatement()
            advanceUntilIdle()

            // What the date field hands over for "31.02.2026": the typed text, since it is no date.
            vm.updateForm { it.copy(statementDate = "31.02.2026") }
            vm.save(1L)
            advanceUntilIdle()

            assertEquals(0, api.updateBankStatementMetadataCallCount)
            assertEquals(setOf(DocumentMetadataField.STATEMENT_DATE), vm.state.fieldErrors.keys)

            vm.updateForm { it.copy(statementDate = "2026-02-28") }
            assertTrue(vm.state.fieldErrors.isEmpty(), "fixing the field clears its error")
        }

    @Test
    fun `save with a blank money field is allowed through`() =
        runTest(dispatcher) {
            // Blank is a legitimate "not recorded", distinct from an unparseable value.
            loadBankStatement()
            advanceUntilIdle()

            api.updateBankStatementMetadataResponse = { id, _ -> bankStatementDocument(id) }
            vm.updateForm { it.copy(closingBalance = "", bankName = "Neue Bank") }
            vm.save(1L)
            advanceUntilIdle()

            assertEquals(1, api.updateBankStatementMetadataCallCount)
            assertNull(vm.state.error)
            assertFalse(vm.state.isDirty)
        }

    @Test
    fun `save with a comma decimal money field canonicalizes before dispatch`() =
        runTest(dispatcher) {
            // A German keyboard produces "1234,56". It must reach the wire as "1234.56" -- never as
            // a Double, and never as the raw comma string the server would reject.
            loadBankStatement()
            advanceUntilIdle()

            var sent: String? = null
            api.updateBankStatementMetadataResponse = { id, request ->
                sent = request.closingBalance
                bankStatementDocument(id)
            }
            vm.updateForm { it.copy(closingBalance = "1234,56") }
            vm.save(1L)
            advanceUntilIdle()

            assertEquals(1, api.updateBankStatementMetadataCallCount)
            assertEquals("1234.56", sent)
        }

    @Test
    fun `a landed metadata write confirms itself`() =
        runTest(dispatcher) {
            loadBankStatement()
            advanceUntilIdle()

            api.updateBankStatementMetadataResponse = { id, _ -> bankStatementDocument(id) }
            vm.updateForm { it.copy(bankName = "Neue Bank") }
            vm.save(1L)
            advanceUntilIdle()

            assertEquals(AdminStringKeys.DocumentSaved, vm.state.successMessage)
        }

    /**
     * Since Stage 4/5 the invoice branch of [DocumentDetailViewModel.save] persists nothing — the
     * capture screen owns invoice data. It must therefore stay silent: a confirmation there would be
     * telling the operator their edit was stored when no request was ever sent.
     */
    @Test
    fun `the invoice no-op branch saves nothing and says nothing`() =
        runTest(dispatcher) {
            loadInvoice()
            advanceUntilIdle()

            vm.updateForm { it.copy(bankName = "ignoriert") }
            vm.save(1L)
            advanceUntilIdle()

            assertEquals(0, api.updateBankStatementMetadataCallCount)
            assertNull(vm.state.successMessage, "nothing was persisted, so there is nothing to confirm")
        }

    /**
     * Regression guard for I4: a transport failure during delete must not leave [DocumentDetailState]
     * wedged with `isDeleting=true`/`showDeleteDialog=true` forever — [DeleteDocumentDialog] disables
     * both Cancel and Delete and blocks click-outside-dismiss while `isDeleting` is `true`, so an
     * uncaught [NetworkApiException] there was a dead end with no way out short of a page reload.
     */
    @Test
    fun `confirmDelete recovers from a transport failure instead of wedging the dialog`() =
        runTest(dispatcher) {
            loadInvoice()
            advanceUntilIdle()

            api.deleteDocumentResponse = { throw NetworkApiException(RuntimeException("offline")) }
            vm.requestDelete()
            var onDeletedCalled = false
            vm.confirmDelete(1L, onDeleted = { onDeletedCalled = true })
            advanceUntilIdle()

            assertFalse(vm.state.isDeleting)
            assertFalse(vm.state.showDeleteDialog)
            assertNotNull(vm.state.error)
            assertFalse(onDeletedCalled)
        }

    /**
     * T9-19: a document an invoice hangs off cannot be deleted — the server refuses it, and the
     * client must not even open the confirmation (the header's delete button is disabled).
     */
    @Test
    fun `a document carrying an invoice cannot be deleted`() =
        runTest(dispatcher) {
            api.getDocumentByIdResponse = { invoiceDocument(1L).copy(invoiceId = 42L) }
            api.getDocumentMetadataResponse = { invoiceMetadata(1L) }
            api.getDocumentFileResponse = { ByteArray(0) }
            vm.load(1L)
            advanceUntilIdle()

            assertTrue(vm.state.deleteBlockedByInvoice)
            vm.requestDelete()
            assertFalse(vm.state.showDeleteDialog, "no confirmation for a delete the server would refuse")
        }

    @Test
    fun `a document without an invoice can still be deleted`() =
        runTest(dispatcher) {
            loadInvoice()
            advanceUntilIdle()

            assertFalse(vm.state.deleteBlockedByInvoice)
            vm.requestDelete()
            assertTrue(vm.state.showDeleteDialog)
        }

    private fun storno(documentInvoiceId: Long) =
        DocumentDto.InvoiceStornoDto(
            originalInvoiceId = documentInvoiceId,
            correctionInvoiceId = 43L,
            reason = "Doppelt erfasst",
            cancelledAt = "2026-09-26T10:00",
        )

    private fun correctionInvoice() = DocumentDto.InvoiceDto(id = 43L, extractionSource = "STORNO", currency = "EUR", storno = storno(42L))

    /**
     * T9-19: a Beleg with an invoice is never deleted — its action is a Storno. The dialog needs a
     * reason; the request carries it trimmed; the reloaded Beleg then shows the Storno and the action
     * is gone, so a second correction cannot be sent from here.
     */
    @Test
    fun `a Beleg with an invoice is cancelled by a correction, once`() =
        runTest(dispatcher) {
            var cancelled = false
            api.getDocumentByIdResponse = {
                invoiceDocument(1L).copy(invoiceId = 42L, invoiceStorno = if (cancelled) storno(42L) else null)
            }
            api.getDocumentMetadataResponse = { invoiceMetadata(1L) }
            api.getDocumentFileResponse = { ByteArray(0) }
            api.cancelInvoiceResponse = { _, _ ->
                cancelled = true
                correctionInvoice()
            }
            vm.load(1L)
            advanceUntilIdle()

            assertTrue(vm.state.canCancelInvoice)
            vm.requestCancelInvoice()
            assertTrue(vm.state.showCancelDialog)

            vm.confirmCancelInvoice(1L)
            advanceUntilIdle()
            assertTrue(api.cancelRequests.isEmpty(), "no request without a reason")

            vm.updateCancelReason("x".repeat(DocumentDto.CancelInvoiceRequestDto.MAX_REASON_LENGTH + 1))
            assertTrue(vm.state.cancelReasonTooLong)
            vm.confirmCancelInvoice(1L)
            advanceUntilIdle()
            assertTrue(api.cancelRequests.isEmpty(), "no request with a reason the server would refuse")

            vm.updateCancelReason("  Doppelt erfasst ")
            vm.confirmCancelInvoice(1L)
            advanceUntilIdle()

            assertEquals(listOf(42L to DocumentDto.CancelInvoiceRequestDto("Doppelt erfasst")), api.cancelRequests)
            assertFalse(vm.state.showCancelDialog)
            assertEquals(43L, vm.state.invoiceStorno?.correctionInvoiceId)
            assertEquals(AdminStringKeys.DocumentCancelled, vm.state.successMessage)
            assertFalse(vm.state.canCancelInvoice, "a cancelled invoice offers no second correction")
            vm.requestCancelInvoice()
            assertFalse(vm.state.showCancelDialog)
        }

    @Test
    fun `a Beleg without an invoice offers no Storno`() =
        runTest(dispatcher) {
            loadInvoice()
            advanceUntilIdle()

            assertFalse(vm.state.canCancelInvoice)
            vm.requestCancelInvoice()
            assertFalse(vm.state.showCancelDialog)
        }

    // ---- The PDF is shown from its URL (shared PdfViewer), so the screen never holds its bytes. ----

    @Test
    fun `a PDF is shown from its URL, so loading it downloads no file bytes`() =
        runTest(dispatcher) {
            api.getDocumentByIdResponse = { invoiceDocument(1L).copy(mediaType = "application/pdf") }
            api.getDocumentMetadataResponse = { invoiceMetadata(1L) }
            vm.load(1L)
            advanceUntilIdle()

            assertEquals(0, api.getDocumentFileCallCount, "the viewer streams the PDF itself")
            assertNull(vm.state.fileBytes)
            assertNotNull(vm.state.document)
            assertFalse(vm.state.isLoading)
            assertNull(vm.state.error)
        }

    @Test
    fun `a file without an in-page preview still loads its bytes for the download`() =
        runTest(dispatcher) {
            api.getDocumentByIdResponse = { bankStatementDocument(1L).copy(mediaType = "application/xml") }
            api.getDocumentMetadataResponse = { bankStatementMetadata(1L) }
            api.getDocumentFileResponse = { byteArrayOf(1, 2, 3) }
            vm.load(1L)
            advanceUntilIdle()

            assertEquals(1, api.getDocumentFileCallCount)
            assertEquals(listOf<Byte>(1, 2, 3), vm.state.fileBytes?.toList())
            assertFalse(vm.state.isLoading)
        }

    @Test
    fun `an action on a PDF fetches its bytes once, on demand, without keeping them`() =
        runTest(dispatcher) {
            loadInvoice()
            advanceUntilIdle()
            api.getDocumentFileResponse = { byteArrayOf(9) }

            var delivered: ByteArray? = null
            vm.withFileBytes(1L, onFailed = {}) { delivered = it }
            advanceUntilIdle()

            assertEquals(listOf<Byte>(9), delivered?.toList())
            assertEquals(1, api.getDocumentFileCallCount)
            assertNull(vm.state.fileBytes, "an action must not pin the whole file in the screen state")
        }

    @Test
    fun `an action on an already loaded file reuses its bytes`() =
        runTest(dispatcher) {
            api.getDocumentByIdResponse = { bankStatementDocument(1L).copy(mediaType = "application/xml") }
            api.getDocumentMetadataResponse = { bankStatementMetadata(1L) }
            api.getDocumentFileResponse = { byteArrayOf(4) }
            vm.load(1L)
            advanceUntilIdle()

            var delivered: ByteArray? = null
            vm.withFileBytes(1L, onFailed = {}) { delivered = it }
            advanceUntilIdle()

            assertEquals(listOf<Byte>(4), delivered?.toList())
            assertEquals(1, api.getDocumentFileCallCount, "no second download")
        }

    @Test
    fun `a failed on-demand fetch is reported and tells the caller`() =
        runTest(dispatcher) {
            loadInvoice()
            advanceUntilIdle()
            api.getDocumentFileResponse = { throw NetworkApiException(RuntimeException("offline")) }

            var failed = false
            var delivered = false
            vm.withFileBytes(1L, onFailed = { failed = true }) { delivered = true }
            advanceUntilIdle()

            assertTrue(failed)
            assertFalse(delivered)
            assertNotNull(vm.state.error)
        }

    @Test
    fun `every load starts a new epoch, so a retry reopens the PDF viewer`() =
        runTest(dispatcher) {
            val before = vm.state.loadEpoch
            loadInvoice()
            advanceUntilIdle()
            val first = vm.state.loadEpoch
            vm.load(1L)
            advanceUntilIdle()

            assertTrue(first > before)
            assertTrue(vm.state.loadEpoch > first, "a retry must hand the viewer a new source")
        }

    @Test
    fun `only a load starts a new epoch, so actions, edits and saves keep the open PDF`() =
        runTest(dispatcher) {
            loadBankStatement()
            advanceUntilIdle()
            api.updateBankStatementMetadataResponse = { id, _ -> bankStatementDocument(id) }
            val epoch = vm.state.loadEpoch

            vm.withFileBytes(1L, onFailed = {}) {}
            vm.updateForm { it.copy(bankName = "Volksbank") }
            vm.save(1L)
            vm.requestDelete()
            vm.dismissDelete()
            vm.clearError()
            vm.clearSuccessMessage()
            advanceUntilIdle()

            assertEquals(epoch, vm.state.loadEpoch, "a new epoch reopens the viewer at page 1 and refetches the file")
            assertEquals(1, api.getDocumentByIdCallCount, "nothing but load() reads the document again")
        }
}
