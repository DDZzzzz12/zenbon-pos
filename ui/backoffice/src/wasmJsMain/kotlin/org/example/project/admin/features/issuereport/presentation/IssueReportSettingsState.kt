package org.example.project.admin.features.issuereport.presentation

import org.example.project.core.domain.dto.issuereport.IssueReportDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey

/**
 * The Settings → Problem reports section.
 *
 * [owner]/[repo]/[apiBaseUrl]/[isPublic] are the editable draft of the stored target in [settings].
 * [tokenDraft] is what the operator is typing into the password field; it lives here only until
 * they press save — [IssueReportSettingsViewModel.saveToken] empties it before the request goes
 * out, so the token is never held after the call, whatever its outcome.
 *
 * [useUpdateToken] is the draft of the explicit "file reports with the update token" choice, saved
 * together with the target. While it is on, the own-token input is disabled: that token is kept but
 * not used.
 */
data class IssueReportSettingsState(
    val settings: IssueReportDto.SettingsDto? = null,
    val owner: String = "",
    val repo: String = "",
    val apiBaseUrl: String = "",
    val isPublic: Boolean = false,
    val useUpdateToken: Boolean = false,
    val tokenDraft: String = "",
    val isLoading: Boolean = false,
    val isBusy: Boolean = false,
    val canManage: Boolean = false,
    /** The host has no `UPDATE_TOKEN_KEY`, so no token can be stored; sticky until the next success. */
    val tokenKeyMissing: Boolean = false,
    val successMessage: StringKey? = null,
    val error: UiError? = null,
    /**
     * The last refused settings save that the section renders inline under the target fields (today
     * only `ISSUE_REPORT_UPDATE_TOKEN_HOST_MISMATCH`). Unlike [error] it is not consumed by the error
     * watcher; it stays until the next save attempt.
     */
    val settingsSaveError: UiError? = null,
    /** Whether the failure in [error] can be repeated by `retry()`; false after a token write. */
    val retryable: Boolean = false,
) {
    val isDirty: Boolean
        get() =
            settings != null &&
                (
                    owner != settings.owner ||
                        repo != settings.repo ||
                        apiBaseUrl != settings.apiBaseUrl ||
                        isPublic != settings.isPublic ||
                        useUpdateToken != settings.useUpdateToken
                )

    /** Unsaved target edits or a typed-but-unstored token; what a leave-confirmation asks about. */
    val hasUnsavedEdits: Boolean get() = isDirty || tokenDraft.isNotBlank()

    val canSaveSettings: Boolean
        get() = canManage && !isBusy && isDirty && owner.isNotBlank() && repo.isNotBlank() && apiBaseUrl.isNotBlank()

    /** The update token is usable only when the server reports it stored and readable. */
    val updateTokenAvailable: Boolean
        get() = settings?.updateTokenState == IssueReportDto.TokenState.SET

    /**
     * Turning the choice ON needs a usable update token (the server refuses it otherwise); turning it
     * OFF is always possible, so a choice whose update token went away is never stuck on.
     */
    val canToggleUpdateToken: Boolean
        get() = canManage && !isBusy && settings != null && (updateTokenAvailable || useUpdateToken)

    /** The own token is not used while the update token is chosen, so its input is off then. */
    val ownTokenEditable: Boolean get() = canManage && !isBusy && !useUpdateToken

    val canSaveToken: Boolean get() = ownTokenEditable && tokenDraft.isNotBlank()

    val canClearToken: Boolean
        get() = canManage && !isBusy && settings != null && settings.tokenState != IssueReportDto.TokenState.NONE

    /** Never prints [tokenDraft]: a state dump in a log line or a test failure must not carry the token. */
    override fun toString(): String =
        "IssueReportSettingsState(settings=$settings, owner=$owner, repo=$repo, apiBaseUrl=$apiBaseUrl, " +
            "isPublic=$isPublic, useUpdateToken=$useUpdateToken, tokenDraft=${if (tokenDraft.isEmpty()) "" else "<redacted>"}, isLoading=$isLoading, " +
            "isBusy=$isBusy, canManage=$canManage, tokenKeyMissing=$tokenKeyMissing, " +
            "successMessage=$successMessage, error=$error, settingsSaveError=$settingsSaveError, retryable=$retryable)"
}
