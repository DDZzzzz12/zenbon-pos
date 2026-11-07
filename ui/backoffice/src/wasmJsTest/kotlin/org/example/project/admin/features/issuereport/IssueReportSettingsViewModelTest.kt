package org.example.project.admin.features.issuereport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.core.data.LocalStorageKeyValueStore
import org.example.project.admin.features.issuereport.presentation.IssueReportSettingsViewModel
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.dto.issuereport.IssueReportDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.IssueReportKeys
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IssueReportSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun store(held: Set<Capability>): TokenStore =
        TokenStore(LocalStorageKeyValueStore()).apply {
            role = "ADMIN"
            capabilities = held.map { it.name }.toSet()
        }

    private val admin = setOf(Capability.ISSUE_REPORT_CREATE, Capability.ISSUE_REPORT_MANAGE)

    private val secret = "github_pat_11ABCDEFGH_issue_secret"

    @Test
    fun `load fills the editable fields from the server`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            val vm = IssueReportSettingsViewModel(api, store(admin))

            vm.load()
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals("DDZzzzz12", state.owner)
            assertEquals("POS-KMP", state.repo)
            assertEquals("https://api.github.com", state.apiBaseUrl)
            assertFalse(state.isPublic)
            assertTrue(state.canManage)
            assertFalse(state.isDirty)
        }

    @Test
    fun `saving settings sends the edited target and updates state`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            val vm = IssueReportSettingsViewModel(api, store(admin))
            vm.load()
            advanceUntilIdle()

            vm.onRepo("zenbon-pos")
            vm.onPublic(true)
            assertTrue(vm.state.value.canSaveSettings)
            vm.saveSettings()
            advanceUntilIdle()

            assertEquals(
                IssueReportDto.UpdateSettingsRequestDto("DDZzzzz12", "zenbon-pos", "https://api.github.com", isPublic = true),
                api.settingsSent.single(),
            )
            val state = vm.state.value
            assertEquals("zenbon-pos", state.settings?.repo)
            assertEquals(true, state.settings?.isPublic)
            assertFalse(state.isDirty)
            assertTrue(state.successMessage != null)
        }

    @Test
    fun `setting the token clears the typed text and never holds the token afterwards`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            val vm = IssueReportSettingsViewModel(api, store(admin))
            vm.load()
            advanceUntilIdle()

            vm.onTokenDraft(secret)
            vm.saveToken()
            // Cleared synchronously, before the request has even completed.
            assertEquals("", vm.state.value.tokenDraft)
            advanceUntilIdle()

            assertEquals(listOf(secret), api.tokensSent)
            assertEquals(
                IssueReportDto.TokenState.SET,
                vm.state.value.settings
                    ?.tokenState,
            )
            assertEquals("", vm.state.value.tokenDraft)
            assertFalse(
                vm.state.value
                    .toString()
                    .contains(secret),
            )
        }

    @Test
    fun `a failed token write does not keep the token in state either`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            val vm = IssueReportSettingsViewModel(api, store(admin))
            vm.load()
            advanceUntilIdle()

            api.failWith =
                PosApiException(
                    code = PosErrorCode.UPDATE_TOKEN_KEY_MISSING,
                    httpStatus = 409,
                    message = "no UPDATE_TOKEN_KEY is provisioned on this host",
                    traceId = null,
                )
            vm.onTokenDraft(secret)
            vm.saveToken()
            advanceUntilIdle()

            assertTrue(vm.state.value.tokenKeyMissing)
            assertNull(vm.state.value.error, "the section explains the missing key itself")
            assertFalse(
                vm.state.value
                    .toString()
                    .contains(secret),
            )
        }

    @Test
    fun `clearing the token updates the token state`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            api.settings = api.settings.copy(tokenState = IssueReportDto.TokenState.SET, tokenPreview = "••••cret")
            val vm = IssueReportSettingsViewModel(api, store(admin))
            vm.load()
            advanceUntilIdle()

            vm.clearToken()
            advanceUntilIdle()

            assertTrue("clearToken" in api.calls)
            assertEquals(
                IssueReportDto.TokenState.NONE,
                vm.state.value.settings
                    ?.tokenState,
            )
            assertNull(
                vm.state.value.settings
                    ?.tokenPreview,
            )
        }

    @Test
    fun `the update-token choice is saved together with the target`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            api.settings = api.settings.copy(updateTokenState = IssueReportDto.TokenState.SET)
            val vm = IssueReportSettingsViewModel(api, store(admin))
            vm.load()
            advanceUntilIdle()

            assertTrue(vm.state.value.canToggleUpdateToken)
            vm.onRepo("tickets")
            vm.onUseUpdateToken(true)
            assertTrue(vm.state.value.isDirty)
            vm.saveSettings()
            advanceUntilIdle()

            assertEquals(
                IssueReportDto.UpdateSettingsRequestDto(
                    "DDZzzzz12",
                    "tickets",
                    "https://api.github.com",
                    isPublic = false,
                    useUpdateToken = true,
                ),
                api.settingsSent.single(),
            )
            assertEquals(
                true,
                vm.state.value.settings
                    ?.useUpdateToken,
            )
            assertTrue(vm.state.value.useUpdateToken)
            assertFalse(vm.state.value.isDirty)
        }

    @Test
    fun `the update-token switch cannot be turned on unless the update token is SET`() =
        runTest(dispatcher) {
            for (unusable in listOf(IssueReportDto.TokenState.NONE, IssueReportDto.TokenState.UNREADABLE)) {
                val api = FakeIssueReportApiClient()
                api.settings = api.settings.copy(updateTokenState = unusable)
                val vm = IssueReportSettingsViewModel(api, store(admin))
                vm.load()
                advanceUntilIdle()

                assertFalse(vm.state.value.canToggleUpdateToken, "$unusable must disable the switch")
                vm.onUseUpdateToken(true)
                assertFalse(vm.state.value.useUpdateToken, "$unusable: the VM must refuse turning it on")
                assertFalse(vm.state.value.canSaveSettings)
            }
        }

    @Test
    fun `a stored choice whose update token went away can still be switched off`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            api.settings = api.settings.copy(useUpdateToken = true, updateTokenState = IssueReportDto.TokenState.NONE)
            val vm = IssueReportSettingsViewModel(api, store(admin))
            vm.load()
            advanceUntilIdle()

            assertTrue(vm.state.value.useUpdateToken)
            assertTrue(vm.state.value.canToggleUpdateToken)
            vm.onUseUpdateToken(false)
            vm.saveSettings()
            advanceUntilIdle()
            assertEquals(false, api.settingsSent.single().useUpdateToken)
        }

    @Test
    fun `while the update token is chosen the own-token input is disabled`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            api.settings = api.settings.copy(updateTokenState = IssueReportDto.TokenState.SET)
            val vm = IssueReportSettingsViewModel(api, store(admin))
            vm.load()
            advanceUntilIdle()

            vm.onTokenDraft(secret)
            assertTrue(vm.state.value.ownTokenEditable)
            vm.onUseUpdateToken(true)
            assertFalse(vm.state.value.ownTokenEditable)
            assertEquals("", vm.state.value.tokenDraft, "a half-typed own token is dropped when the update token is chosen")
            vm.onTokenDraft(secret)
            assertFalse(vm.state.value.canSaveToken)
            vm.saveToken()
            advanceUntilIdle()
            assertTrue(api.tokensSent.isEmpty())

            vm.onUseUpdateToken(false)
            assertTrue(vm.state.value.ownTokenEditable)
            assertTrue(vm.state.value.canSaveToken)
        }

    @Test
    fun `a host-mismatch refusal is kept inline as a typed localized error and raises no toast`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            api.settings = api.settings.copy(updateTokenState = IssueReportDto.TokenState.SET)
            val vm = IssueReportSettingsViewModel(api, store(admin))
            vm.load()
            advanceUntilIdle()

            val detail = "the update token may only be sent to the update source's API address (https://api.github.com)"
            api.failWith =
                PosApiException(code = PosErrorCode.ISSUE_REPORT_UPDATE_TOKEN_HOST_MISMATCH, httpStatus = 400, message = detail, traceId = null)
            vm.onApiBaseUrl("https://github.evil.example/api/v3")
            vm.onUseUpdateToken(true)
            vm.saveSettings()
            advanceUntilIdle()

            val inline = vm.state.value.settingsSaveError
            assertTrue(inline is UiError.FromServer, "the refusal must be kept for the inline note: $inline")
            assertEquals(PosErrorCode.ISSUE_REPORT_UPDATE_TOKEN_HOST_MISMATCH, inline.code)
            // Localized client-side: the server's English sentence is never what the section renders.
            assertEquals(IssueReportKeys.errorUpdateTokenHostMismatch, inline.messageKey)
            assertNull(vm.state.value.error, "no toast: a toast would cut the sentence off")
            assertFalse(vm.state.value.isBusy)
            // The draft survives, so the operator can correct the address and save again.
            assertEquals("https://github.evil.example/api/v3", vm.state.value.apiBaseUrl)
            assertTrue(vm.state.value.useUpdateToken)

            // The next successful save clears the note.
            api.failWith = null
            vm.onApiBaseUrl("https://api.github.com")
            vm.saveSettings()
            advanceUntilIdle()
            assertNull(vm.state.value.settingsSaveError)
            assertEquals(2, api.settingsSent.size)
        }

    @Test
    fun `retry after a failed save repeats the save, never a reload`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            val vm = IssueReportSettingsViewModel(api, store(admin))
            vm.load()
            advanceUntilIdle()

            api.failWith = PosApiException(code = PosErrorCode.VALIDATION_FAILED, httpStatus = 400, message = "nope", traceId = null)
            vm.onRepo("tickets")
            vm.saveSettings()
            advanceUntilIdle()
            assertTrue(vm.state.value.error is UiError.FromServer)
            assertTrue(vm.state.value.retryable)

            api.failWith = null
            val loadsBefore = api.calls.count { it == "settings" }
            vm.retry()
            advanceUntilIdle()

            assertEquals(loadsBefore, api.calls.count { it == "settings" }, "retry must not reload: ${api.calls}")
            assertEquals(2, api.settingsSent.size, "retry must send the save again")
            assertEquals("tickets", api.settingsSent.last().repo)
            val saved = vm.state.value.settings
            assertEquals("tickets", saved?.repo)
        }

    @Test
    fun `a failed token write offers no retry, since the token is already gone from state`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            val vm = IssueReportSettingsViewModel(api, store(admin))
            vm.load()
            advanceUntilIdle()

            api.failWith = PosApiException(code = PosErrorCode.VALIDATION_FAILED, httpStatus = 400, message = "nope", traceId = null)
            vm.onTokenDraft(secret)
            vm.saveToken()
            advanceUntilIdle()

            assertTrue(vm.state.value.error is UiError.FromServer)
            assertFalse(vm.state.value.retryable)
            vm.retry()
            advanceUntilIdle()
            assertEquals(1, api.tokensSent.size)
        }

    @Test
    fun `without ISSUE_REPORT_MANAGE nothing reaches the network`() =
        runTest(dispatcher) {
            val api = FakeIssueReportApiClient()
            val vm = IssueReportSettingsViewModel(api, store(setOf(Capability.ISSUE_REPORT_CREATE)))

            vm.load()
            vm.onRepo("elsewhere")
            vm.saveSettings()
            vm.onTokenDraft(secret)
            vm.saveToken()
            vm.clearToken()
            advanceUntilIdle()

            assertTrue(api.calls.isEmpty(), "calls: ${api.calls}")
            assertFalse(vm.state.value.canManage)
        }
}
