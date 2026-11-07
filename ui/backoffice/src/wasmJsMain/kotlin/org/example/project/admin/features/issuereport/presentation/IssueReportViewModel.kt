package org.example.project.admin.features.issuereport.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.admin.core.auth.canConfigureIssueReports
import org.example.project.admin.core.auth.canReportProblems
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.dto.issuereport.IssueReportDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.IssueReportKeys
import org.example.project.features.issuereport.data.remote.IssueReportApiClient

/**
 * The "Report a problem" dialog: form → preview → send.
 *
 * ### Guards on the path, not only on the buttons
 *
 * [loadPreview] and [send] refuse without `ISSUE_REPORT_CREATE` and while the title or description
 * is blank, before touching the network; the server refuses both again (CLAUDE.md rule 8).
 *
 * ### No log excerpt for a public repository, ever
 *
 * [onIncludeLogs] ignores "on" for a known-public target. For a target whose publicity is not yet
 * known (a reporter without `ISSUE_REPORT_MANAGE` cannot read the settings), [loadPreview] first
 * previews *without* logs to learn it, and only asks for logs once the target is known private —
 * so no request ever asks a public repository for logs, and the operator never meets the server's
 * 400 for doing so.
 *
 * ### Send posts the preview, nothing else
 *
 * [send] is only possible from a loaded preview and names it by `previewId`, so the server posts the
 * snapshot the operator read — not a rebuild with newer log lines. Every edit drops the preview (and
 * with it the id). A preview the server no longer holds (expired, already used, target changed)
 * comes back as 409 `VALIDATION_FAILED`; the dialog returns to the form with a localized prompt to
 * preview again.
 *
 * ### Send is armed late
 *
 * "Preview" (form) and "Send report" (preview) occupy the same footer position, so a double-click on
 * Preview would land on Send and file a report nobody read. A freshly shown preview therefore starts
 * with `sendArmed = false`, and [send] refuses — without a network call — until [SEND_ARM_DELAY_MS]
 * have passed for *that* preview. The delay runs here rather than in the dialog so the guard holds on
 * the path, not only on the button.
 *
 * Nothing the operator typed is logged: title and description are free text that may hold personal
 * data, which is exactly what the public-target warning is about.
 */
class IssueReportViewModel(
    private val api: IssueReportApiClient,
    private val tokenStore: TokenStore,
) : ViewModel() {
    private val log = Logger.withTag("IssueReportVM")

    private val _state = MutableStateFlow(IssueReportState())

    /** The pending arming of the shown preview's send button; cancelled whenever that preview goes. */
    private var armJob: Job? = null
    val state: StateFlow<IssueReportState> = _state.asStateFlow()

    /**
     * Starts a fresh draft for [screen]. Called each time the dialog opens — the VM outlives the
     * dialog and the session, so a retained draft would otherwise leak into the next report.
     */
    fun open(screen: String?) {
        val canReport = tokenStore.canReportProblems
        _state.value = IssueReportState(screen = screen, canReport = canReport)
        if (!canReport || !tokenStore.canConfigureIssueReports) return
        viewModelScope.launch {
            runCatchingApi { api.settings() }
                .onSuccess { settings ->
                    log.d { "settings loaded: public=${settings.isPublic}" }
                    _state.update { it.copy(settings = settings, publicTarget = settings.isPublic) }
                }.onError { error ->
                    log.w { "settings load failed: ${error.code}" }
                    _state.update { it.copy(error = error) }
                }
        }
    }

    fun onTitle(value: String) = editDraft { it.copy(title = value) }

    fun onDescription(value: String) = editDraft { it.copy(description = value) }

    fun onIncludeLogs(enabled: Boolean) {
        if (enabled && _state.value.isPublicTarget) {
            log.d { "log excerpt refused: the target repository is public" }
            return
        }
        editDraft { it.copy(includeLogs = enabled) }
    }

    /** Leaves the preview stage; the draft stays as it is. */
    fun backToForm() {
        disarm()
        _state.update { it.copy(preview = null, sendArmed = false) }
    }

    fun loadPreview() {
        if (!mayProceed()) return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, failure = null) }
            runCatchingApi { previewRespectingPublicity() }
                .onSuccess { preview ->
                    log.d { "preview loaded for ${preview.repository} (public=${preview.isPublic})" }
                    _state.update { it.copy(isBusy = false, preview = preview, sendArmed = false) }
                    armSendLater(preview.previewId)
                }.onError { error -> fail("preview", error) }
        }
    }

    fun send() {
        if (!mayProceed()) return
        val previewId = _state.value.preview?.previewId
        if (previewId == null) {
            log.d { "refused: send needs a current preview" }
            return
        }
        if (!_state.value.sendArmed) {
            log.d { "refused: send is not armed yet for this preview" }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, failure = null) }
            runCatchingApi { api.submit(request().copy(previewId = previewId)) }
                .onSuccess { created ->
                    log.i { "report filed as issue #${created.issueNumber}" }
                    _state.update {
                        it.copy(
                            title = "",
                            description = "",
                            includeLogs = false,
                            preview = null,
                            created = created,
                            sendArmed = false,
                            isBusy = false,
                        )
                    }
                }.onError { error -> if (isStalePreview(error)) previewAgain(error) else fail("send", error) }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** Drops the draft when the dialog closes; [open] starts the next one clean. */
    fun dismiss() {
        disarm()
        _state.value = IssueReportState()
    }

    /**
     * The preview for the current draft. When logs are wanted but the target's publicity is
     * unknown, a log-free preview is fetched first; it answers the question, and for a public
     * target it already is the right preview.
     */
    private suspend fun previewRespectingPublicity(): IssueReportDto.PreviewDto {
        val current = _state.value
        if (current.includeLogs && current.publicTarget == null) {
            val probe = api.preview(request().copy(includeLogs = false))
            learnPublicity(probe.isPublic)
            if (probe.isPublic) return probe
        }
        val preview = api.preview(request())
        learnPublicity(preview.isPublic)
        return preview
    }

    private fun learnPublicity(isPublic: Boolean) {
        _state.update {
            it.copy(publicTarget = isPublic, includeLogs = it.includeLogs && !isPublic)
        }
    }

    private fun request(): IssueReportDto.ReportRequestDto {
        val current = _state.value
        return IssueReportDto.ReportRequestDto(
            title = current.title.trim(),
            description = current.description.trim(),
            source = IssueReportDto.Source.ADMIN,
            screen = current.screen,
            includeLogs = current.includeLogs && !current.isPublicTarget,
        )
    }

    private fun mayProceed(): Boolean {
        val current = _state.value
        if (!tokenStore.canReportProblems) {
            log.w { "refused: this principal does not hold ISSUE_REPORT_CREATE" }
            _state.update { it.copy(canReport = false) }
            return false
        }
        if (!current.hasRequiredText) {
            log.d { "refused: title or description is blank" }
            return false
        }
        return !current.isBusy
    }

    /** Arms send for [previewId] after the guard period, unless that preview has been replaced or dropped. */
    private fun armSendLater(previewId: String) {
        disarm()
        armJob =
            viewModelScope.launch {
                delay(SEND_ARM_DELAY_MS)
                _state.update { if (it.preview?.previewId == previewId) it.copy(sendArmed = true) else it }
            }
    }

    private fun disarm() {
        armJob?.cancel()
        armJob = null
    }

    /** The server's "preview again" refusal: a 409 VALIDATION_FAILED on send. */
    private fun isStalePreview(error: UiError): Boolean =
        error is UiError.FromServer && error.code == PosErrorCode.VALIDATION_FAILED && error.httpStatus == HTTP_CONFLICT

    private fun previewAgain(error: UiError) {
        log.w { "send refused: the preview is no longer held by the server" }
        val localized =
            (error as UiError.FromServer).copy(
                titleKey = IssueReportKeys.errorTitle,
                messageKey = IssueReportKeys.errorPreviewExpired,
                messageArgs = emptyList(),
            )
        disarm()
        _state.update { it.copy(isBusy = false, preview = null, sendArmed = false, error = localized, failure = localized) }
    }

    private fun fail(
        step: String,
        error: UiError,
    ) {
        log.w { "$step failed: ${error.code ?: error::class.simpleName}" }
        _state.update { it.copy(isBusy = false, error = error, failure = error) }
    }

    /** Any edit invalidates a shown preview's claim to be "exactly what will be posted". */
    private fun editDraft(edit: (IssueReportState) -> IssueReportState) {
        disarm()
        _state.update { edit(it).copy(preview = null, sendArmed = false, created = null, failure = null) }
    }

    companion object {
        /** How long a freshly shown preview keeps "Send report" inactive. */
        const val SEND_ARM_DELAY_MS = 1_500L

        private const val HTTP_CONFLICT = 409
    }
}
