package org.example.project.admin.features.documents

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.documents.presentation.DocumentUploadViewModel
import org.example.project.admin.features.documents.presentation.UnlockStep
import org.example.project.admin.features.documents.presentation.UploadOutcome
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.PosApiException
import org.example.project.core.utils.file.FilePickResult
import org.example.project.core.utils.file.PickedFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun pickedFile(name: String = "beleg.pdf") = PickedFile(fileName = name, mimeType = "application/pdf", bytes = byteArrayOf(1, 2, 3))

/**
 * A picker that answers with a scripted sequence of outcomes, repeating the last one once the
 * script runs out. It is the whole point of the ViewModel taking its picker as a parameter: the
 * browser `<input type=file>` cannot be opened from a test, an injected lambda can.
 */
private fun picker(vararg outcomes: FilePickResult): suspend (String) -> FilePickResult {
    var index = 0
    return { outcomes[minOf(index++, outcomes.lastIndex)] }
}

private fun picking(name: String = "beleg.pdf") = picker(FilePickResult.Picked(pickedFile(name)))

/**
 * The upload section's contract, which is narrower than it looks: it sends a file and reports an id.
 *
 * The two cases that matter to every caller are the **reset** (adminApp shares one
 * `ViewModelStoreOwner` app-wide, so a finished upload outlives the form that made it) and the
 * **absence of metadata** (accepting an invoice number is how "uploads a file" quietly becomes
 * "knows what an invoice is").
 *
 * Every case drives the real entry point, [DocumentUploadViewModel.pickAndUpload], because the
 * picker is injected — including the two outcomes that used to be untestable, a dismissed dialog and
 * an unreadable file.
 */
class DocumentUploadViewModelTest {
    @Test
    fun aSuccessfulUploadReportsTheServersDocumentId() =
        runTest {
            val api = FakeDocumentManagementApiClient(documentId = 42L)
            val vm = DocumentUploadViewModel(api, picking())
            var reported: Long? = null

            vm.pickAndUpload(fileType = "EIGENBELEG", accept = "application/pdf") { reported = it }

            assertEquals(42L, reported)
            assertEquals(42L, vm.state.documentId)
            assertTrue(vm.state.hasDocument)
            assertFalse(vm.state.isUploading)
            assertEquals("beleg.pdf", vm.state.fileName)
        }

    @Test
    fun theAcceptFilterIsHandedToTheInjectedPicker() =
        runTest {
            val api = FakeDocumentManagementApiClient()
            val asked = mutableListOf<String>()
            val vm =
                DocumentUploadViewModel(api) { accept ->
                    asked += accept
                    FilePickResult.Picked(pickedFile())
                }

            vm.pickAndUpload(fileType = "EIGENBELEG", accept = "application/pdf,image/*") { }

            assertEquals(listOf("application/pdf,image/*"), asked)
        }

    @Test
    fun onlyTheFileNameAndTypeAreSent() =
        runTest {
            val api = FakeDocumentManagementApiClient()
            val vm = DocumentUploadViewModel(api, picking("rechnung.pdf"))

            vm.pickAndUpload(fileType = "INVOICE", accept = "application/pdf") { }

            val upload = assertNotNull(api.lastUpload)
            assertEquals("rechnung.pdf", upload.fileName)
            assertEquals("INVOICE", upload.fileType)
            assertTrue(
                upload.metadataArguments.all { it == null },
                "every invoice and bank-statement argument must go out null — the section does not " +
                    "know what an invoice is, and taking one field would be how it started to",
            )
        }

    @Test
    fun dismissingTheDialogUploadsNothingAndDisturbsNothing() =
        runTest {
            val api = FakeDocumentManagementApiClient(documentId = 11L)
            val vm =
                DocumentUploadViewModel(
                    api,
                    picker(FilePickResult.Picked(pickedFile("erster.pdf")), FilePickResult.Cancelled),
                )
            var reported: Long? = null
            vm.pickAndUpload(fileType = "EIGENBELEG", accept = "application/pdf") { reported = it }

            vm.pickAndUpload(fileType = "EIGENBELEG", accept = "application/pdf") { reported = it }

            assertEquals(1, api.uploadCalls, "a cancel must not reach the server")
            assertEquals(11L, reported, "the cancel must not report an id of its own")
            assertEquals(11L, vm.state.documentId, "the Beleg picked before the cancel still stands")
            assertEquals("erster.pdf", vm.state.fileName)
            assertNull(vm.state.error, "dismissing a dialog is not a failure and must stay silent")
        }

    @Test
    fun anUnreadableFileIsAnErrorAndStillUploadsNothing() =
        runTest {
            val api = FakeDocumentManagementApiClient()
            val vm = DocumentUploadViewModel(api, picker(FilePickResult.ReadFailed))
            var reported: Long? = null

            vm.pickAndUpload(fileType = "EIGENBELEG", accept = "application/pdf") { reported = it }

            assertEquals(0, api.uploadCalls, "there are no bytes to send")
            assertNull(reported)
            assertNull(vm.state.documentId)
            assertFalse(vm.state.isUploading)
            assertNotNull(vm.state.error, "unlike a cancel, an unreadable file is rare and must be surfaced")
        }

    @Test
    fun resetDropsAFinishedUploadSoItCannotLeakIntoTheNextForm() =
        runTest {
            val api = FakeDocumentManagementApiClient(documentId = 7L)
            val vm = DocumentUploadViewModel(api, picking())
            vm.pickAndUpload(fileType = "EIGENBELEG", accept = "application/pdf") { }
            assertEquals(7L, vm.state.documentId)

            vm.reset()

            assertNull(vm.state.documentId, "one ViewModelStoreOwner app-wide: the id must not survive")
            assertNull(vm.state.fileName)
            assertNull(vm.state.error)
            assertFalse(vm.state.hasDocument)
        }

    /**
     * The parse outcome must survive onto the state, or the Belege card has nothing to render.
     *
     * The server has always determined this on upload; until the response stopped being a pre-parse
     * snapshot, it was simply never told to anyone.
     */
    @Test
    fun theParseOutcomeReachesTheState() =
        runTest {
            val api =
                FakeDocumentManagementApiClient(documentId = 5L).apply {
                    uploadProcessingStatus = ProcessingStatus.COMPLETED
                    uploadInvoiceMetadata =
                        DocumentDto.InvoiceMetadataDto(vendorName = "Metro AG", invoiceNumber = "MET-1")
                }
            val vm = DocumentUploadViewModel(api, picking())

            vm.pickAndUpload(fileType = "INVOICE", accept = "application/xml") { }

            val extracted = assertIs<UploadOutcome.Extracted>(vm.state.outcome)
            assertEquals("MET-1", extracted.invoice.invoiceNumber)
            assertNull(vm.state.error, "a read e-invoice is not an error")
        }

    /**
     * A duplicate is a success with nothing to do, not a failure to show in red.
     *
     * The server refuses byte-identical content with a 409 before any document exists. Surfacing its
     * raw validation error would leave the operator unsure whether the file had been filed at all.
     */
    @Test
    fun aDuplicateIsReportedAsAnOutcomeRatherThanAnError() =
        runTest {
            val api =
                FakeDocumentManagementApiClient(
                    failWith =
                        PosApiException(
                            code = PosErrorCode.VALIDATION_FAILED,
                            httpStatus = 409,
                            message = "Document with identical content already exists",
                            traceId = null,
                        ),
                )
            val vm = DocumentUploadViewModel(api, picking())

            vm.pickAndUpload(fileType = "INVOICE", accept = "application/xml") { }

            assertEquals(UploadOutcome.Duplicate, vm.state.outcome)
            assertNull(vm.state.error, "an already-archived file is not an error to escalate")
            assertFalse(vm.state.isUploading)
        }

    /** Any other server failure stays an error — only the duplicate 409 is reinterpreted. */
    @Test
    fun anOrdinaryServerFailureIsStillAnError() =
        runTest {
            val api =
                FakeDocumentManagementApiClient(
                    failWith =
                        PosApiException(
                            code = PosErrorCode.INTERNAL_ERROR,
                            httpStatus = 500,
                            message = "boom",
                            traceId = null,
                        ),
                )
            val vm = DocumentUploadViewModel(api, picking())

            vm.pickAndUpload(fileType = "INVOICE", accept = "application/xml") { }

            assertNotNull(vm.state.error)
            assertNull(vm.state.outcome)
        }

    @Test
    fun aFailedUploadReportsNoIdAndLeavesTheSectionEmpty() =
        runTest {
            val api = FakeDocumentManagementApiClient(failWith = NetworkApiException(IllegalStateException("offline")))
            val vm = DocumentUploadViewModel(api, picking())
            var reported: Long? = null

            vm.pickAndUpload(fileType = "EIGENBELEG", accept = "application/pdf") { reported = it }

            assertNull(reported, "a failed upload must never report an id")
            assertNull(vm.state.documentId)
            assertFalse(vm.state.hasDocument)
            assertFalse(vm.state.isUploading)
            assertNotNull(vm.state.error)
            assertEquals("beleg.pdf", vm.state.fileName, "the failed file is named so the error can say which one")
        }

    @Test
    fun replacingAFileReplacesTheReportedDocument() =
        runTest {
            val api = FakeDocumentManagementApiClient(documentId = 3L)
            val vm =
                DocumentUploadViewModel(
                    api,
                    picker(
                        FilePickResult.Picked(pickedFile("erster.pdf")),
                        FilePickResult.Picked(pickedFile("zweiter.pdf")),
                    ),
                )
            val reported = mutableListOf<Long>()

            vm.pickAndUpload(fileType = "EIGENBELEG", accept = "application/pdf") { reported += it }
            vm.pickAndUpload(fileType = "EIGENBELEG", accept = "application/pdf") { reported += it }

            assertEquals(2, api.uploadCalls)
            assertEquals(listOf(3L, 3L), reported, "each upload reports its own id to the parent")
            assertEquals("zweiter.pdf", vm.state.fileName)
        }

    private fun lockedPdf(name: String = "locked.pdf") = PickedFile(name, "application/pdf", "%PDF-1.7\ntrailer << /Encrypt 5 0 R >>".encodeToByteArray())

    private fun pickingLocked() = picker(FilePickResult.Picked(lockedPdf()))

    @Test
    fun aLockedPdfOpensThePromptInsteadOfUploading() =
        runTest {
            val api = FakeDocumentManagementApiClient()
            val vm = DocumentUploadViewModel(api, pickingLocked())

            vm.pickAndUpload(fileType = "INVOICE", accept = "application/pdf") { }

            assertEquals(0, api.uploadCalls)
            assertEquals(UnlockStep.Ask, vm.state.unlockPrompt?.step)
            assertEquals("locked.pdf", vm.state.unlockPrompt?.fileName)
        }

    @Test
    fun aPlainPdfUploadsStraightAway() =
        runTest {
            val api = FakeDocumentManagementApiClient()
            val plain = PickedFile("plain.pdf", "application/pdf", "%PDF-1.7 plain".encodeToByteArray())
            val vm = DocumentUploadViewModel(api, picker(FilePickResult.Picked(plain)))

            vm.pickAndUpload(fileType = "INVOICE", accept = "application/pdf") { }

            assertEquals(1, api.uploadCalls)
            assertNull(vm.state.unlockPrompt)
        }

    @Test
    fun submittingAPasswordSendsItAndFinishesTheUpload() =
        runTest {
            val api = FakeDocumentManagementApiClient(documentId = 9L)
            val vm = DocumentUploadViewModel(api, pickingLocked())
            var reported: Long? = null
            vm.pickAndUpload(fileType = "INVOICE", accept = "application/pdf") { reported = it }

            vm.submitUnlockPassword("s3cret")

            assertEquals("s3cret", api.lastUnlockPassword)
            assertEquals(9L, reported)
            assertNull(vm.state.unlockPrompt)
            assertEquals(9L, vm.state.documentId)
        }

    @Test
    fun aRefusedPasswordKeepsThePromptOpenWithTheRefusalAndAllowsARetry() =
        runTest {
            val api = FakeDocumentManagementApiClient(documentId = 9L)
            api.uploadFailures += PosApiException(PosErrorCode.PDF_PASSWORD_INCORRECT, 422, "wrong", null)
            val vm = DocumentUploadViewModel(api, pickingLocked())
            vm.pickAndUpload(fileType = "INVOICE", accept = "application/pdf") { }

            vm.submitUnlockPassword("bad")

            assertNotNull(vm.state.unlockPrompt?.rejection, "the dialog must say why it is still open")
            assertNull(vm.state.error, "a refused password is the dialog's business, not an inline upload error")
            assertFalse(vm.state.isUploading)

            vm.submitUnlockPassword("good")

            assertEquals(2, api.uploadCalls)
            assertNull(vm.state.unlockPrompt)
            assertEquals(9L, vm.state.documentId)
        }

    @Test
    fun decliningNeedsAConfirmationAndThenUploadsWithoutAPassword() =
        runTest {
            val api = FakeDocumentManagementApiClient()
            val vm = DocumentUploadViewModel(api, pickingLocked())
            vm.pickAndUpload(fileType = "INVOICE", accept = "application/pdf") { }

            vm.declineUnlock()

            assertEquals(UnlockStep.ConfirmDecline, vm.state.unlockPrompt?.step)
            assertEquals(0, api.uploadCalls, "nothing is sent until the warning is confirmed")

            vm.confirmStoreLocked()

            assertEquals(1, api.uploadCalls)
            assertNull(api.lastUnlockPassword, "declining must not send the password field at all")
            assertNull(vm.state.unlockPrompt)
        }

    @Test
    fun backReturnsFromTheWarningToThePasswordStep() =
        runTest {
            val vm = DocumentUploadViewModel(FakeDocumentManagementApiClient(), pickingLocked())
            vm.pickAndUpload(fileType = "INVOICE", accept = "application/pdf") { }
            vm.declineUnlock()

            vm.backToUnlockPrompt()

            assertEquals(UnlockStep.Ask, vm.state.unlockPrompt?.step)
        }

    @Test
    fun cancellingTheDialogUploadsNothingAndClearsTheFile() =
        runTest {
            val api = FakeDocumentManagementApiClient()
            val vm = DocumentUploadViewModel(api, pickingLocked())
            vm.pickAndUpload(fileType = "INVOICE", accept = "application/pdf") { }

            vm.cancelUnlock()

            assertEquals(0, api.uploadCalls)
            assertNull(vm.state.unlockPrompt)
            assertNull(vm.state.fileName)
        }
}
