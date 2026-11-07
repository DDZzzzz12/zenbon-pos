package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

/**
 * Problem reports filed as GitHub issues — the "Report a problem" dialog and its settings section.
 *
 * The `error*` keys (except [errorPreviewExpired]) are what `PosApiException.toUiError()` maps the `ISSUE_REPORT_*` codes to,
 * so a refused report reads as an actionable sentence rather than the server's English detail.
 */
object IssueReportKeys {
    // Dialog
    val menuEntry = StringKey("issue_report.menu_entry")
    val dialogTitle = StringKey("issue_report.dialog_title")
    val titleLabel = StringKey("issue_report.title_label")
    val descriptionLabel = StringKey("issue_report.description_label")
    val includeLogsLabel = StringKey("issue_report.include_logs_label")
    val includeLogsDisabledPublic = StringKey("issue_report.include_logs_disabled_public")
    val previewButton = StringKey("issue_report.preview_button")
    val previewTitle = StringKey("issue_report.preview_title")
    val previewRepository = StringKey("issue_report.preview_repository")
    val sendButton = StringKey("issue_report.send_button")
    val publicWarning = StringKey("issue_report.public_warning")
    val contextNote = StringKey("issue_report.context_note")
    val requiredHint = StringKey("issue_report.required_hint")
    val notSentDraftKept = StringKey("issue_report.not_sent_draft_kept")
    val sentToast = StringKey("issue_report.sent_toast")
    val openIssue = StringKey("issue_report.open_issue")

    // Settings section
    val settingsSectionTitle = StringKey("issue_report.settings_section_title")
    val settingsDescription = StringKey("issue_report.settings_description")
    val ownerLabel = StringKey("issue_report.owner_label")
    val repoLabel = StringKey("issue_report.repo_label")
    val apiBaseUrlLabel = StringKey("issue_report.api_base_url_label")
    val publicToggleLabel = StringKey("issue_report.public_toggle_label")
    val publicToggleHint = StringKey("issue_report.public_toggle_hint")
    val tokenTitle = StringKey("issue_report.token_title")
    val tokenLabel = StringKey("issue_report.token_label")
    val tokenStateNone = StringKey("issue_report.token_state_none")
    val tokenStateSet = StringKey("issue_report.token_state_set")
    val tokenStateUnreadable = StringKey("issue_report.token_state_unreadable")
    val tokenSetAt = StringKey("issue_report.token_set_at")
    val tokenHelp = StringKey("issue_report.token_help")
    val tokenKeyMissing = StringKey("issue_report.token_key_missing")
    val saveSettings = StringKey("issue_report.save_settings")
    val saveToken = StringKey("issue_report.save_token")
    val clearToken = StringKey("issue_report.clear_token")
    val settingsSaved = StringKey("issue_report.settings_saved")
    val useUpdateTokenLabel = StringKey("issue_report.use_update_token_label")
    val useUpdateTokenHint = StringKey("issue_report.use_update_token_hint")
    val useUpdateTokenUnavailableNone = StringKey("issue_report.use_update_token_unavailable_none")
    val useUpdateTokenUnavailableUnreadable = StringKey("issue_report.use_update_token_unavailable_unreadable")
    val useUpdateTokenActiveNote = StringKey("issue_report.use_update_token_active_note")

    // Server refusals (see UiErrorMapping)
    val errorTitle = StringKey("issue_report.error_title")
    val errorNotConfigured = StringKey("issue_report.error_not_configured")
    val errorTokenRejected = StringKey("issue_report.error_token_rejected")
    val errorRateLimited = StringKey("issue_report.error_rate_limited")
    val errorGithubUnreachable = StringKey("issue_report.error_github_unreachable")
    val errorUpdateTokenHostMismatch = StringKey("issue_report.error_update_token_host_mismatch")
    val errorUpdateTokenUnavailable = StringKey("issue_report.error_update_token_unavailable")

    /** Not a server mapping: the dialog's own wording for a send whose preview the server no longer holds. */
    val errorPreviewExpired = StringKey("issue_report.error_preview_expired")
}
