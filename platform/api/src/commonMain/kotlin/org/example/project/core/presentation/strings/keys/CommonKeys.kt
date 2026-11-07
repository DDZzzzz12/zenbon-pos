package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object CommonKeys {
    // From existing CommonStrings
    val cancel = StringKey("common.cancel")

    /**
     * Placeholder of a typed date field: the display pattern of the UI language (`TT.MM.JJJJ`), never a
     * sample date that goes stale. The wire value stays ISO; this is only what the operator types.
     */
    val dateFormatHint = StringKey("common.date_format_hint")

    /** A date field holds text that is not a date; carries [dateFormatHint]'s pattern as `{0}`. */
    val dateInvalid = StringKey("common.date_invalid")

    /** Accessible name of the calendar button inside a date field. */
    val datePick = StringKey("common.date_pick")

    /** Field error of a required field left empty when Save was pressed (validate-on-press forms). */
    val fieldRequired = StringKey("common.field_required")
    val close = StringKey("common.close")
    val save = StringKey("common.save")
    val delete = StringKey("common.delete")
    val edit = StringKey("common.edit")
    val refresh = StringKey("common.refresh")
    val update = StringKey("common.update")
    val confirm = StringKey("common.confirm")
    val create = StringKey("common.create")
    val exitAppTitle = StringKey("common.exit_app_title")
    val exitAppMessage = StringKey("common.exit_app_message")
    val exitAppConfirm = StringKey("common.exit_app_confirm")

    // Common strings reused across multiple features
    val back = StringKey("common.back")
    val scrollLeft = StringKey("common.scroll_left")
    val scrollRight = StringKey("common.scroll_right")
    val loading = StringKey("common.loading")
    val error = StringKey("common.error")
    val retry = StringKey("common.retry")
    val noData = StringKey("common.no_data")
    val all = StringKey("common.all")
    val actions = StringKey("common.actions")
    val add = StringKey("common.add")
    val remove = StringKey("common.remove")
    val total = StringKey("common.total")
    val name = StringKey("common.name")
    val status = StringKey("common.status")
    val date = StringKey("common.date")
    val time = StringKey("common.time")
    val amount = StringKey("common.amount")
    val quantity = StringKey("common.quantity")
    val notes = StringKey("common.notes")
    val discardChanges = StringKey("common.discard_changes")
    val discardChangesMessage = StringKey("common.discard_changes_message")
    val discard = StringKey("common.discard")
    val keepEditing = StringKey("common.keep_editing")

    // Error fallbacks (UiError mapping defaults — Task 12)
    val networkErrorTitle = StringKey("common.network_error_title")
    val networkErrorMessage = StringKey("common.network_error_message")
    val unknownErrorTitle = StringKey("common.unknown_error_title")
    val unknownErrorMessage = StringKey("common.unknown_error_message")
    val accessDeniedTitle = StringKey("common.access_denied_title")
    val accessDeniedMessage = StringKey("common.access_denied_message")

    // PosErrorDialog UI (Task 14)
    val errorDialogValidationErrorsLabel = StringKey("common.error_dialog_validation_errors_label")
    val errorDialogStatusLabel = StringKey("common.error_dialog_status_label")
    val errorDialogCodeLabel = StringKey("common.error_dialog_code_label")
    val errorDialogTraceIdLabel = StringKey("common.error_dialog_trace_id_label")
    val errorDialogCopyTraceId = StringKey("common.error_dialog_copy_trace_id")
    val errorDialogCopiedToClipboard = StringKey("common.error_dialog_copied_to_clipboard")
    val errorDialogRetry = StringKey("common.error_dialog_retry")
    val errorDialogClose = StringKey("common.error_dialog_close")
    val errorDialogTechnicalDetails = StringKey("common.error_dialog_technical_details")
    val errorDialogTimestampLabel = StringKey("common.error_dialog_timestamp_label")

    // Short weekday labels (Monday-first), reusable for compact axes/tables
    val weekdayShortMon = StringKey("common.weekday_short_mon")
    val weekdayShortTue = StringKey("common.weekday_short_tue")
    val weekdayShortWed = StringKey("common.weekday_short_wed")
    val weekdayShortThu = StringKey("common.weekday_short_thu")
    val weekdayShortFri = StringKey("common.weekday_short_fri")
    val weekdayShortSat = StringKey("common.weekday_short_sat")
    val weekdayShortSun = StringKey("common.weekday_short_sun")
    val ok = StringKey("common.ok")
    val loadingPdf = StringKey("common.loading_pdf")
    val printing = StringKey("common.printing")
    val systemPrint = StringKey("common.system_print")

    // Browser station: server upgraded underneath a running tab
    val updateAvailable = StringKey("common.update_available")
    val reloadNow = StringKey("common.reload_now")
    val openInAnotherTab = StringKey("common.open_in_another_tab")
}
