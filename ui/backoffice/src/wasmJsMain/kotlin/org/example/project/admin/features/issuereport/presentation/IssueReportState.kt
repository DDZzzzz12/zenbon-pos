package org.example.project.admin.features.issuereport.presentation

import org.example.project.core.domain.dto.issuereport.IssueReportDto
import org.example.project.core.presentation.error.UiError

/**
 * Everything the "Report a problem" dialog renders.
 *
 * ### Publicity is learned, not always read
 *
 * `GET /settings` needs `ISSUE_REPORT_MANAGE`, which only an administrator holds, so a manager
 * filing a report cannot read whether the target repository is public. [publicTarget] is therefore
 * filled from whichever answer arrives first — the settings read for an administrator, the preview
 * (`PreviewDto.isPublic`) for everyone — and stays null while unknown. Null is not "private":
 * [IssueReportViewModel.loadPreview] probes before it ever asks an unknown target for logs.
 *
 * ### Two error fields on purpose
 *
 * [error] feeds `PosErrorWatcher`, which clears it as soon as it has shown it. [failure] is the
 * last send/preview refusal, kept so the dialog can keep saying "not sent, your text is kept" next
 * to the draft until the operator edits or retries.
 */
data class IssueReportState(
    val title: String = "",
    val description: String = "",
    val includeLogs: Boolean = false,
    /** The navigation destination the dialog was opened from; null when unknown. */
    val screen: String? = null,
    val settings: IssueReportDto.SettingsDto? = null,
    val publicTarget: Boolean? = null,
    val preview: IssueReportDto.PreviewDto? = null,
    /**
     * Send is possible for the shown [preview]. False for a short guard period after a preview
     * appears (see [IssueReportViewModel.SEND_ARM_DELAY_MS]), so a double-click on "Preview" cannot
     * land on "Send report", which sits in the same place.
     */
    val sendArmed: Boolean = false,
    val created: IssueReportDto.CreatedDto? = null,
    val isBusy: Boolean = false,
    /** The principal holds `ISSUE_REPORT_CREATE`; resolved on every [IssueReportViewModel.open]. */
    val canReport: Boolean = false,
    val error: UiError? = null,
    val failure: UiError? = null,
) {
    val isPublicTarget: Boolean get() = publicTarget == true

    val hasRequiredText: Boolean get() = title.isNotBlank() && description.isNotBlank()

    /** A half-written report that has not been sent; a created report or an empty form closes without asking. */
    val isDirty: Boolean get() = created == null && (title.isNotBlank() || description.isNotBlank())

    val canSend: Boolean get() = hasRequiredText && !isBusy

    /** The preview stage's "Send report" may be pressed. */
    val canSubmitPreview: Boolean get() = canSend && canReport && preview != null && sendArmed
}
