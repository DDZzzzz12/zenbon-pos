package org.example.project.admin.features.issuereport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.core.data.LocalStorageKeyValueStore
import org.example.project.admin.features.issuereport.presentation.IssueReportViewModel
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.dto.issuereport.IssueReportDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.strings.keys.IssueReportKeys
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IssueReportViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Both keys are written every time, so a value left in localStorage by a previous case cannot leak. */
    private fun store(held: Set<Capability>): TokenStore =
        TokenStore(LocalStorageKeyValueStore()).apply {
            role = "ADMIN"
            capabilities = held.map { it.name }.toSet()
        }

    private val admin = setOf(Capability.ISSUE_REPORT_CREATE, Capability.ISSUE_REPORT_MANAGE)

    /** A manager may file a report but may not read where it goes (`GET /settings` is MANAGE-only). */
    private val reporter = setOf(Capability.ISSUE_REPORT_CREATE)

    private fun IssueReportViewModel.fill(
        title: String = "Receipt printer prints twice",
        description: String = "After closing a bill the receipt came out twice.",
    ) {
        onTitle(title)
        onDescription(description)
    }

    @Test
    fun `opening loads settings and disables logs for a public target`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            api.settings = api.settings.copy(isPublic = true, repo = "zenbon-pos")
            val vm = IssueReportViewModel(api, store(admin))

            vm.open("DOCUMENTS")
            advanceUntilIdle()
            vm.onIncludeLogs(true)

            val state = vm.state.value
            assertEquals("zenbon-pos", state.settings?.repo)
            assertEquals("DOCUMENTS", state.screen)
            assertTrue(state.isPublicTarget)
            assertFalse(state.includeLogs, "a public repository must never be offered a log excerpt")
        }

    @Test
    fun `opening without ISSUE_REPORT_MANAGE does not read the settings it would be refused`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            val vm = IssueReportViewModel(api, store(reporter))

            vm.open(null)
            advanceUntilIdle()

            assertFalse("settings" in api.calls, "GET /settings is MANAGE-only; a 403 toast on opening the dialog is the defect")
            assertNull(vm.state.value.error)
            assertTrue(vm.state.value.canReport)
        }

    @Test
    fun `preview stores the exact title body and repository from the server`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            val vm = IssueReportViewModel(api, store(admin))
            vm.open("SETTINGS")
            advanceUntilIdle()
            vm.fill()
            vm.onIncludeLogs(true)

            vm.loadPreview()
            advanceUntilIdle()

            val sent = api.reportsSent.single()
            assertEquals(IssueReportDto.Source.ADMIN, sent.source)
            assertEquals("SETTINGS", sent.screen)
            assertTrue(sent.includeLogs)
            val preview = assertNotNull(vm.state.value.preview)
            assertEquals("Receipt printer prints twice", preview.title)
            assertEquals("After closing a bill the receipt came out twice.\n\n---\nscreen: SETTINGS\nlogs: true", preview.body)
            assertEquals("DDZzzzz12/POS-KMP", preview.repository)
        }

    @Test
    fun `send success exposes the issue number and url and clears the draft`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            val vm = IssueReportViewModel(api, store(admin))
            vm.open(null)
            advanceUntilIdle()
            vm.fill()
            vm.loadPreview()
            advanceUntilIdle()

            vm.send()
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals(42L, state.created?.issueNumber)
            assertEquals("https://github.com/DDZzzzz12/POS-KMP/issues/42", state.created?.issueUrl)
            assertEquals("", state.title)
            assertEquals("", state.description)
            assertFalse(state.includeLogs)
            assertNull(state.preview)
            assertNull(state.error)
        }

    @Test
    fun `send failure keeps the draft and surfaces a typed error`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            val vm = IssueReportViewModel(api, store(admin))
            vm.open(null)
            advanceUntilIdle()
            vm.fill()
            vm.loadPreview()
            advanceUntilIdle()

            api.failWith =
                PosApiException(
                    code = PosErrorCode.ISSUE_REPORT_TOKEN_REJECTED,
                    httpStatus = 502,
                    message = "GitHub rejected the stored token",
                    traceId = null,
                )
            vm.send()
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals("Receipt printer prints twice", state.title)
            assertEquals("After closing a bill the receipt came out twice.", state.description)
            assertNotNull(state.preview, "the operator must be able to retry the send without re-previewing")
            assertNull(state.created)
            assertEquals(PosErrorCode.ISSUE_REPORT_TOKEN_REJECTED, state.error?.code)
            assertEquals(IssueReportKeys.errorTokenRejected, state.error?.messageKey)
            // The inline note survives the error watcher clearing `error`.
            vm.clearError()
            assertEquals(
                PosErrorCode.ISSUE_REPORT_TOKEN_REJECTED,
                vm.state.value.failure
                    ?.code,
            )
            assertFalse(vm.state.value.isBusy)
        }

    @Test
    fun `an unavailable update token keeps the draft and names Settings - Updates in the inline note`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            val vm = IssueReportViewModel(api, store(admin))
            vm.open(null)
            advanceUntilIdle()
            vm.fill()
            vm.loadPreview()
            advanceUntilIdle()

            api.failSubmitWith =
                PosApiException(
                    code = PosErrorCode.ISSUE_REPORT_UPDATE_TOKEN_UNAVAILABLE,
                    httpStatus = 409,
                    message = "problem reports use the update token, but no update token is stored (Settings → Updates)",
                    traceId = null,
                )
            vm.send()
            advanceUntilIdle()
            vm.clearError()

            val state = vm.state.value
            assertEquals("Receipt printer prints twice", state.title)
            assertNotNull(state.preview, "a 409 that is not a stale preview keeps the preview for a retry")
            // The dialog's "not sent, your text is kept" note renders this key — not the generic
            // "configure it under Settings → Problem reports" sentence.
            assertEquals(PosErrorCode.ISSUE_REPORT_UPDATE_TOKEN_UNAVAILABLE, state.failure?.code)
            assertEquals(IssueReportKeys.errorUpdateTokenUnavailable, state.failure?.messageKey)
        }

    @Test
    fun `send is blocked while the title or description is blank`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            val vm = IssueReportViewModel(api, store(admin))
            vm.open(null)
            advanceUntilIdle()

            vm.fill(title = "Something", description = "   ")
            assertFalse(vm.state.value.canSend)
            vm.loadPreview()
            vm.send()
            vm.fill(title = " ", description = "Something happened")
            assertFalse(vm.state.value.canSend)
            vm.loadPreview()
            vm.send()
            advanceUntilIdle()

            assertFalse("preview" in api.calls)
            assertFalse("submit" in api.calls)
        }

    @Test
    fun `includeLogs cannot be turned on for a public target`() =
        runTest(dispatcher) {
            // The reporter cannot read the settings, so publicity is first learned from a preview.
            // A log excerpt must still never be requested against a public repository: the server
            // would refuse it (400), and the operator would read that as a broken dialog.
            val api = FakeIssueReportApiClient()
            api.settings = api.settings.copy(isPublic = true, repo = "zenbon-pos")
            val vm = IssueReportViewModel(api, store(reporter))
            vm.open(null)
            advanceUntilIdle()
            vm.fill()
            vm.onIncludeLogs(true)

            vm.loadPreview()
            advanceUntilIdle()

            assertTrue(api.reportsSent.none { it.includeLogs }, "no request may ask a public repository for logs")
            assertTrue(vm.state.value.isPublicTarget)
            assertFalse(vm.state.value.includeLogs)
            assertNotNull(vm.state.value.preview)

            vm.onIncludeLogs(true)
            assertFalse(vm.state.value.includeLogs)

            vm.send()
            advanceUntilIdle()
            assertTrue(api.reportsSent.none { it.includeLogs })
        }

    @Test
    fun `a private target asked for logs by a reporter still gets them`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            val vm = IssueReportViewModel(api, store(reporter))
            vm.open(null)
            advanceUntilIdle()
            vm.fill()
            vm.onIncludeLogs(true)

            vm.loadPreview()
            advanceUntilIdle()

            assertTrue(vm.state.value.includeLogs)
            assertTrue(api.reportsSent.last().includeLogs)
            assertTrue(
                vm.state.value.preview
                    ?.body
                    ?.endsWith("logs: true") == true,
            )
        }

    @Test
    fun `without ISSUE_REPORT_CREATE nothing reaches the network`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            // Non-empty on purpose: an EMPTY set means "unknown" and falls back to the role.
            val vm = IssueReportViewModel(api, store(setOf(Capability.UPDATE_READ)))
            vm.open(null)
            advanceUntilIdle()
            vm.fill()

            vm.loadPreview()
            vm.send()
            advanceUntilIdle()

            assertTrue(api.calls.isEmpty(), "calls: ${api.calls}")
            assertFalse(vm.state.value.canReport)
        }

    @Test
    fun `reopening starts from an empty draft`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            val vm = IssueReportViewModel(api, store(admin))
            vm.open(null)
            advanceUntilIdle()
            vm.fill()
            vm.loadPreview()
            advanceUntilIdle()

            vm.dismiss()
            vm.open("STAFF")
            advanceUntilIdle()

            assertEquals("", vm.state.value.title)
            assertNull(vm.state.value.preview)
            assertEquals("STAFF", vm.state.value.screen)
        }

    @Test
    fun `submit carries the previewId of the last preview`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            val vm = IssueReportViewModel(api, store(admin))
            vm.open(null)
            advanceUntilIdle()
            vm.fill()
            vm.onIncludeLogs(true)
            vm.loadPreview()
            advanceUntilIdle()
            vm.backToForm()
            vm.loadPreview()
            advanceUntilIdle()
            val shown = assertNotNull(vm.state.value.preview)
            assertEquals("preview-2", shown.previewId)

            vm.send()
            advanceUntilIdle()

            val submitted = api.reportsSent.last()
            assertEquals("preview-2", submitted.previewId)
            assertTrue(submitted.includeLogs)
        }

    @Test
    fun `any edit drops the preview and its id so send is no longer possible`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            val vm = IssueReportViewModel(api, store(admin))
            vm.open(null)
            advanceUntilIdle()

            val edits: List<(IssueReportViewModel) -> Unit> =
                listOf(
                    { it.onTitle("Changed title") },
                    { it.onDescription("Changed description") },
                    { it.onIncludeLogs(true) },
                )
            for (edit in edits) {
                vm.fill()
                vm.onIncludeLogs(false)
                vm.loadPreview()
                advanceUntilIdle()
                assertNotNull(vm.state.value.preview)

                edit(vm)
                assertNull(vm.state.value.preview, "an edit must invalidate the preview")

                vm.send()
                advanceUntilIdle()
            }
            assertFalse("submit" in api.calls, "send without a current preview must not reach the network")
        }

    @Test
    fun `send is refused until the preview has been shown for the guard period`() =
        runTest(dispatcher) {
            // "Preview" and "Send report" share a footer position: a double-click on Preview must
            // not file the report unread.
            val api = FakeIssueReportApiClient()
            val vm = IssueReportViewModel(api, store(admin))
            vm.open(null)
            advanceUntilIdle()
            vm.fill()
            vm.loadPreview()
            runCurrent()
            assertNotNull(vm.state.value.preview)
            assertFalse(vm.state.value.sendArmed)
            assertFalse(vm.state.value.canSubmitPreview)

            vm.send()
            runCurrent()
            assertFalse("submit" in api.calls, "an unarmed send must not reach the network")

            advanceTimeBy(IssueReportViewModel.SEND_ARM_DELAY_MS - 1)
            runCurrent()
            assertFalse(vm.state.value.sendArmed)

            advanceTimeBy(2)
            runCurrent()
            assertTrue(vm.state.value.sendArmed)
            assertTrue(vm.state.value.canSubmitPreview)

            vm.send()
            advanceUntilIdle()
            assertTrue("submit" in api.calls)
            val created = assertNotNull(vm.state.value.created)
            assertEquals(42L, created.issueNumber)
        }

    @Test
    fun `a new preview is not armed by the previous one's timer`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            val vm = IssueReportViewModel(api, store(admin))
            vm.open(null)
            advanceUntilIdle()
            vm.fill()
            vm.loadPreview()
            runCurrent()

            advanceTimeBy(IssueReportViewModel.SEND_ARM_DELAY_MS - 100)
            vm.backToForm()
            assertFalse(vm.state.value.sendArmed)
            vm.loadPreview()
            runCurrent()
            advanceTimeBy(200)
            runCurrent()

            assertFalse(vm.state.value.sendArmed, "the second preview must wait its own full guard period")
            vm.send()
            runCurrent()
            assertFalse("submit" in api.calls)

            advanceTimeBy(IssueReportViewModel.SEND_ARM_DELAY_MS)
            runCurrent()
            assertTrue(vm.state.value.sendArmed)
        }

    @Test
    fun `an expired preview returns to the form with a localized prompt to preview again`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            val vm = IssueReportViewModel(api, store(admin))
            vm.open(null)
            advanceUntilIdle()
            vm.fill()
            vm.loadPreview()
            advanceUntilIdle()

            api.failSubmitWith =
                PosApiException(
                    code = PosErrorCode.VALIDATION_FAILED,
                    httpStatus = 409,
                    message = "the preview expired or was already used; preview again",
                    traceId = null,
                )
            vm.send()
            advanceUntilIdle()

            val state = vm.state.value
            assertNull(state.preview, "the stale preview must not be offered for another send")
            assertEquals("Receipt printer prints twice", state.title, "the draft is kept")
            assertEquals(IssueReportKeys.errorPreviewExpired, state.error?.messageKey)
            assertFalse(state.isBusy)
        }
}
