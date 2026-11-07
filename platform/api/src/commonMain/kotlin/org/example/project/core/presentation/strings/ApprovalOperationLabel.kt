package org.example.project.core.presentation.strings

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.ApprovalKeys

/**
 * The operator-facing name of an approval request's operation — one definition for the approval
 * dialog and the notification list, which each carried their own copy of this `when`. An unknown
 * operation falls back to its raw code rather than disappearing.
 */
fun approvalOperationLabel(
    operation: String,
    language: Language,
): String =
    when (operation) {
        "CANCEL_ORDER_ITEMS" -> getString(ApprovalKeys.operationCancelOrderItems, language)
        "TRANSFER_ORDER_ITEMS" -> getString(ApprovalKeys.operationTransferItems, language)
        "BACKDATE_CASH_BOOK_ENTRY" -> getString(ApprovalKeys.operationBackdateCashBookEntry, language)
        else -> operation
    }
