package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

/**
 * Localized refusals the back office (staff, terminals, TSE, DATEV export) meets routinely, mapped
 * from their `PosErrorCode` in `UiErrorMapping`. Unmapped, `serverDetailFallback()` showed the
 * server's raw English detail — "Username 'x' already taken", "Cannot demote the last active
 * administrator" — to a German operator (e2e backlog, "raw English server strings"). The server
 * detail itself survives in `UiError.FromServer.rawDetail` for the client-error report.
 */
object BackOfficeErrorKeys {
    val usernameTakenTitle = StringKey("backoffice_error.username_taken_title")
    val usernameTaken = StringKey("backoffice_error.username_taken")
    val lastAdminTitle = StringKey("backoffice_error.last_admin_title")
    val lastAdmin = StringKey("backoffice_error.last_admin")
    val selfModification = StringKey("backoffice_error.self_modification")
    val notFoundTitle = StringKey("backoffice_error.not_found_title")
    val employeeNotFound = StringKey("backoffice_error.employee_not_found")
    val terminalNotFound = StringKey("backoffice_error.terminal_not_found")
    val tseUnreachableTitle = StringKey("backoffice_error.tse_unreachable_title")
    val tseUnreachable = StringKey("backoffice_error.tse_unreachable")
    val datevNotSchedulableTitle = StringKey("backoffice_error.datev_not_schedulable_title")
    val datevNotSchedulable = StringKey("backoffice_error.datev_not_schedulable")
    val restoredPathsNeedReviewTitle = StringKey("backoffice_error.restored_paths_need_review_title")
    val restoredPathsNeedReview = StringKey("backoffice_error.restored_paths_need_review")

    /** PRICE_INVALID: a product/extra price that is not a non-negative decimal number (admin21). */
    val priceInvalidTitle = StringKey("backoffice_error.price_invalid_title")
    val priceInvalid = StringKey("backoffice_error.price_invalid")

    /** CHOICE_GROUP_OPTION_REQUIRED: an extra a required choice group cannot do without (admin21 NOTE-c21). */
    val choiceGroupOptionRequiredTitle = StringKey("backoffice_error.choice_group_option_required_title")
    val choiceGroupOptionRequired = StringKey("backoffice_error.choice_group_option_required")

    /** PDF_PASSWORD_REQUIRED / PDF_PASSWORD_INCORRECT: the unlock-on-upload dialog's re-prompt sentences. */
    val pdfPasswordRequiredTitle = StringKey("backoffice_error.pdf_password_required_title")
    val pdfPasswordRequired = StringKey("backoffice_error.pdf_password_required")
    val pdfPasswordIncorrectTitle = StringKey("backoffice_error.pdf_password_incorrect_title")
    val pdfPasswordIncorrect = StringKey("backoffice_error.pdf_password_incorrect")

    /**
     * SYSTEM_RECORD_PROTECTED: an update/delete of a record the system depends on (PFAND/VOUCHER
     * category, the voucher article, the virtual VOUCHER table, the virtual location types).
     * [systemRecordHint] is the short badge the editing screens show in place of the edit/delete
     * controls of such a record.
     */
    val systemRecordTitle = StringKey("backoffice_error.system_record_title")
    val systemRecordProtected = StringKey("backoffice_error.system_record_protected")
    val systemRecordHint = StringKey("backoffice_error.system_record_hint")
}
