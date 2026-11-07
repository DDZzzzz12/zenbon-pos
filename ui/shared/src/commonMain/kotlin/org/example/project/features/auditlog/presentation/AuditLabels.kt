package org.example.project.features.auditlog.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.AuditLogKeys
import org.example.project.core.presentation.strings.str

/**
 * Display labels for the audit log.
 *
 * Both tabs store machine values (`CREATE`, `CANCEL_ORDER_ITEMS`, `OrderItem`, ...). The screen shows a
 * localized label and keeps the raw value as secondary text, so the trail can still be matched against
 * an export or a support request. A value this build has no label for is never an error: it is shown
 * humanized ([humanizeAuditValue]) and the raw value stays visible next to it.
 *
 * Filter dropdowns show these labels but send the raw values unchanged.
 */

/**
 * `LogOperation.DELETE` is deliberately what a *deactivation* (soft delete) is logged as: a new
 * constant would break older clients, and an unknown name reads back as `ERROR`, which the change feed
 * filters out. So the wire value stays `DELETE` and only the label and tone change: a `DELETE` whose
 * message starts with "Deactivated" (what the services write) or "Deaktiviert" is a deactivation.
 */
private val DEACTIVATION_MESSAGE_PREFIXES = listOf("Deactivated", "Deaktiviert")

private fun isDeactivation(
    operation: String,
    message: String?,
): Boolean {
    if (operation != "DELETE") return false
    val text = message?.trimStart() ?: return false
    return DEACTIVATION_MESSAGE_PREFIXES.any { text.startsWith(it, ignoreCase = true) }
}

private val ENTRY_OPERATION_KEYS: Map<String, StringKey> =
    mapOf(
        "CREATE" to AuditLogKeys.entryOpCreate,
        "UPDATE" to AuditLogKeys.entryOpUpdate,
        "DELETE" to AuditLogKeys.entryOpDelete,
        "ERROR" to AuditLogKeys.entryOpError,
    )

private val TRANSACTION_OPERATION_KEYS: Map<String, StringKey> =
    mapOf(
        "CREATE_ORDER" to AuditLogKeys.opCreateOrder,
        "UPDATE_ORDER" to AuditLogKeys.opUpdateOrder,
        "CANCEL_ORDER" to AuditLogKeys.opCancelOrder,
        "DELETE_ORDER" to AuditLogKeys.opDeleteOrder,
        "FINALIZE_ORDER" to AuditLogKeys.opFinalizeOrder,
        "REOPEN_ORDER" to AuditLogKeys.opReopenOrder,
        "ADD_ORDER_ITEM" to AuditLogKeys.opAddOrderItem,
        "UPDATE_ORDER_ITEM" to AuditLogKeys.opUpdateOrderItem,
        "CANCEL_ORDER_ITEM" to AuditLogKeys.opCancelOrderItem,
        "DELETE_ORDER_ITEM" to AuditLogKeys.opDeleteOrderItem,
        "TRANSFER_ORDER_ITEM" to AuditLogKeys.opTransferOrderItem,
        "CANCEL_ORDER_ITEMS" to AuditLogKeys.opCancelOrderItems,
        "TRANSFER_ORDER_ITEMS" to AuditLogKeys.opTransferOrderItems,
        "CREATE_BILL" to AuditLogKeys.opCreateBill,
        "SPLIT_BILL" to AuditLogKeys.opSplitBill,
        "MERGE_BILL" to AuditLogKeys.opMergeBill,
        "VOID_BILL" to AuditLogKeys.opVoidBill,
        "CLOSE_BILL" to AuditLogKeys.opCloseBill,
        "DELETE_BILL" to AuditLogKeys.opDeleteBill,
        "REBOOK_BILL" to AuditLogKeys.opRebookBill,
        "PROCESS_PAYMENT" to AuditLogKeys.opProcessPayment,
        "REFUND_PAYMENT" to AuditLogKeys.opRefundPayment,
        "VOID_PAYMENT" to AuditLogKeys.opVoidPayment,
        "PARTIAL_PAYMENT" to AuditLogKeys.opPartialPayment,
        "APPLY_DISCOUNT" to AuditLogKeys.opApplyDiscount,
        "REMOVE_DISCOUNT" to AuditLogKeys.opRemoveDiscount,
        "OVERRIDE_PRICE" to AuditLogKeys.opOverridePrice,
        "APPLY_VOUCHER" to AuditLogKeys.opApplyVoucher,
        "TRANSFER_TABLE" to AuditLogKeys.opTransferTable,
        "MERGE_TABLE" to AuditLogKeys.opMergeTable,
        "SPLIT_TABLE" to AuditLogKeys.opSplitTable,
        "DELETE_TABLE_SESSION" to AuditLogKeys.opDeleteTableSession,
        "OPEN_CASH_REGISTER" to AuditLogKeys.opOpenCashRegister,
        "CLOSE_CASH_REGISTER" to AuditLogKeys.opCloseCashRegister,
        "CASH_IN" to AuditLogKeys.opCashIn,
        "CASH_OUT" to AuditLogKeys.opCashOut,
        "CREATE_PRODUCT" to AuditLogKeys.opCreateProduct,
        "UPDATE_PRODUCT" to AuditLogKeys.opUpdateProduct,
        "DELETE_PRODUCT" to AuditLogKeys.opDeleteProduct,
        "UPDATE_STOCK" to AuditLogKeys.opUpdateStock,
        "CREATE_EMPLOYEE" to AuditLogKeys.opCreateEmployee,
        "UPDATE_EMPLOYEE" to AuditLogKeys.opUpdateEmployee,
        "DELETE_EMPLOYEE" to AuditLogKeys.opDeleteEmployee,
        "DEACTIVATE_EMPLOYEE" to AuditLogKeys.opDeactivateEmployee,
        "CHANGE_PERMISSIONS" to AuditLogKeys.opChangePermissions,
        "ASSIGN_PERMISSION_GROUP" to AuditLogKeys.opAssignPermissionGroup,
        "CREATE_ZBON" to AuditLogKeys.opCreateZbon,
        "CREATE_KASSENBUCH_ENTRY" to AuditLogKeys.opCreateKassenbuchEntry,
        "CREATE_CASH_BOOK_ENTRY" to AuditLogKeys.opCreateCashBookEntry,
        "CORRECT_CASH_BOOK_ENTRY" to AuditLogKeys.opCorrectCashBookEntry,
        "BACKDATE_CASH_BOOK_ENTRY" to AuditLogKeys.opBackdateCashBookEntry,
        "LINK_CASH_BOOK_DOCUMENT" to AuditLogKeys.opLinkCashBookDocument,
        "UNLINK_CASH_BOOK_DOCUMENT" to AuditLogKeys.opUnlinkCashBookDocument,
        "REPLACE_CASH_BOOK_DOCUMENT" to AuditLogKeys.opReplaceCashBookDocument,
        "SET_CASH_BOOK_SIGNING_TERMINAL" to AuditLogKeys.opSetCashBookSigningTerminal,
        "CREATE_CASH_TRANSFER" to AuditLogKeys.opCreateCashTransfer,
        "REVERSE_CASH_TRANSFER" to AuditLogKeys.opReverseCashTransfer,
        "MERGE_BUSINESS_PARTNER" to AuditLogKeys.opMergeBusinessPartner,
        "VOID_RETURNED_DEBIT_ALLOCATION" to AuditLogKeys.opVoidReturnedDebitAllocation,
        "CANCEL_SALES_INVOICE" to AuditLogKeys.opCancelSalesInvoice,
        "CLOCK_IN" to AuditLogKeys.opClockIn,
        "CLOCK_OUT" to AuditLogKeys.opClockOut,
        "START_BREAK" to AuditLogKeys.opStartBreak,
        "END_BREAK" to AuditLogKeys.opEndBreak,
        "CREATE_MANUAL_TIME_ENTRY" to AuditLogKeys.opCreateManualTimeEntry,
        "DELETE_TIME_ENTRY" to AuditLogKeys.opDeleteTimeEntry,
        "DEACTIVATE_TERMINAL" to AuditLogKeys.opDeactivateTerminal,
        "REACTIVATE_TERMINAL" to AuditLogKeys.opReactivateTerminal,
        "REGISTER_TSE_ARCHIVE" to AuditLogKeys.opRegisterTseArchive,
        "DEREGISTER_TSE_CLIENT" to AuditLogKeys.opDeregisterTseClient,
        "TSE_EXPLANATION_CREATED" to AuditLogKeys.opTseExplanationCreated,
        "TSE_QUEUE_SIGNED" to AuditLogKeys.opTseQueueSigned,
        "CREATE_ISSUE_REPORT" to AuditLogKeys.opCreateIssueReport,
        "CREATE_REPORT" to AuditLogKeys.opCreateReport,
        "UPDATE_BROWSER_STATIONS" to AuditLogKeys.opUpdateBrowserStations,
        "UPDATE_WEB_ACCESS" to AuditLogKeys.opUpdateWebAccess,
        "PAY_OUT_EMPLOYEE_TIP" to AuditLogKeys.opPayOutEmployeeTip,
        "REVERSE_EMPLOYEE_TIP_PAYOUT" to AuditLogKeys.opReverseEmployeeTipPayout,
        "REVERSE_EMPLOYEE_TIP_SETTLEMENT" to AuditLogKeys.opReverseEmployeeTipSettlement,
        "SETTLE_EMPLOYEE_TIP_BANK_PAYROLL" to AuditLogKeys.opSettleEmployeeTipBankPayroll,
    )

private val ENTITY_TYPE_KEYS: Map<String, StringKey> =
    mapOf(
        "ORDER" to AuditLogKeys.entityOrder,
        "ORDER_ITEM" to AuditLogKeys.entityOrderItem,
        "BILL" to AuditLogKeys.entityBill,
        "PAYMENT" to AuditLogKeys.entityPayment,
        "TABLE" to AuditLogKeys.entityTable,
        "TABLE_SESSION" to AuditLogKeys.entityTableSession,
        "PRODUCT" to AuditLogKeys.entityProduct,
        "EMPLOYEE" to AuditLogKeys.entityEmployee,
        "EMPLOYEE_TIP_PAYOUT" to AuditLogKeys.entityEmployeeTipPayout,
        "CASH_REGISTER" to AuditLogKeys.entityCashRegister,
        "CASH_BOOK" to AuditLogKeys.entityCashBook,
        "DISCOUNT" to AuditLogKeys.entityDiscount,
        "VOUCHER" to AuditLogKeys.entityVoucher,
        "ZBON" to AuditLogKeys.entityZbon,
        "Z_REPORT" to AuditLogKeys.entityZbon,
        "KASSENBUCH" to AuditLogKeys.entityKassenbuch,
        "CANCELLATION" to AuditLogKeys.entityCancellation,
        "TIME_ENTRY" to AuditLogKeys.entityTimeEntry,
        "TIME_TRACKING" to AuditLogKeys.entityTimeTracking,
        "CATEGORY" to AuditLogKeys.entityCategory,
        "PRODUCT_CATEGORY" to AuditLogKeys.entityProductCategory,
        "PRODUCT_EXTRA" to AuditLogKeys.entityProductExtra,
        "PRODUCTEXTRAS" to AuditLogKeys.entityProductextras,
        "PRODUCT_EXTRAS_ASSIGNMENT" to AuditLogKeys.entityProductExtrasAssignment,
        "CHOICE_GROUP" to AuditLogKeys.entityChoiceGroup,
        "PRODUCT_CHOICE_GROUP" to AuditLogKeys.entityProductChoiceGroup,
        "PAYMENT_TYPE" to AuditLogKeys.entityPaymentType,
        "PAYMENTTYPE" to AuditLogKeys.entityPaymenttype,
        "RTABLE" to AuditLogKeys.entityRtable,
        "ROOM_ELEMENT" to AuditLogKeys.entityRoomElement,
        "ROOMELEMENT" to AuditLogKeys.entityRoomelement,
        "SHOP_INFO" to AuditLogKeys.entityShopInfo,
        "SHOP" to AuditLogKeys.entityShop,
        "SHOP_CONFIGURATION" to AuditLogKeys.entityShopConfiguration,
        "OPENING_HOURS" to AuditLogKeys.entityOpeningHours,
        "VAT" to AuditLogKeys.entityVat,
        "SETUP" to AuditLogKeys.entitySetup,
        "TERMINAL" to AuditLogKeys.entityTerminal,
        "PRINTER_LOCATION" to AuditLogKeys.entityPrinterLocation,
        "PRINTERLOCATION" to AuditLogKeys.entityPrinterlocation,
        "UNKNOWN" to AuditLogKeys.entityUnknown,
        "ISSUE_REPORT" to AuditLogKeys.entityIssueReport,
        "SALES_INVOICE" to AuditLogKeys.entitySalesInvoice,
        "SOFTWARE_UPDATE" to AuditLogKeys.entitySoftwareUpdate,
        "TSE_ARCHIVE" to AuditLogKeys.entityTseArchive,
        "TSE_EXPLANATION" to AuditLogKeys.entityTseExplanation,
        "PAYMENT_ALLOCATION" to AuditLogKeys.entityPaymentAllocation,
    )

/**
 * Label key for an entry-log operation. A soft delete (see [isDeactivation]) reads as "Deactivated",
 * not "Deleted". `null` for an unknown operation.
 */
internal fun entryOperationKey(
    operation: String,
    message: String?,
): StringKey? {
    if (isDeactivation(operation, message)) return AuditLogKeys.entryOpDeactivated
    return ENTRY_OPERATION_KEYS[operation]
}

/** Colour class of an entry-log operation; a deactivation is [AuditTone.Neutral], not destructive red. */
internal fun entryOperationTone(
    operation: String,
    message: String?,
): AuditTone =
    when {
        isDeactivation(operation, message) -> AuditTone.Neutral
        operation == "CREATE" -> AuditTone.Create
        operation == "UPDATE" -> AuditTone.Update
        operation == "DELETE" -> AuditTone.Delete
        operation == "ERROR" -> AuditTone.Error
        else -> AuditTone.Neutral
    }

/** Label key for a transaction-audit operation, or `null` when this build has no label for it. */
internal fun transactionOperationKey(operation: String): StringKey? = TRANSACTION_OPERATION_KEYS[normalizeAuditValue(operation)]

/**
 * Label key for an entity type, from either tab: the transaction audit's `EntityType` and the entry
 * log's `ChangeEntityType`, plus the free-text spellings the server writes (`OrderItem`, `Z-report`,
 * `payment_allocation`). `null` when unknown.
 */
internal fun entityTypeKey(type: String): StringKey? = ENTITY_TYPE_KEYS[normalizeAuditValue(type)]

/** `OrderItem` -> `ORDER_ITEM`, `Z-report` -> `Z_REPORT`, `payment_allocation` -> `PAYMENT_ALLOCATION`. */
internal fun normalizeAuditValue(raw: String): String {
    val out = StringBuilder()
    raw.trim().forEachIndexed { index, c ->
        when {
            c == '-' || c == ' ' -> out.append('_')
            c.isUpperCase() && index > 0 && raw.trim()[index - 1].isLowerCase() -> out.append('_').append(c)
            else -> out.append(c)
        }
    }
    return out.toString().uppercase()
}

/** `SOME_NEW_VALUE` -> `Some new value`; used only when no label exists. Never throws. */
internal fun humanizeAuditValue(raw: String): String {
    val words = normalizeAuditValue(raw).split('_').filter { it.isNotEmpty() }.joinToString(" ") { it.lowercase() }
    return words.replaceFirstChar { it.uppercase() }
}

@Composable
internal fun entryOperationLabel(
    operation: String,
    message: String?,
): String = entryOperationKey(operation, message)?.let { str(it) } ?: humanizeAuditValue(operation)

@Composable
internal fun transactionOperationLabel(operation: String): String = transactionOperationKey(operation)?.let { str(it) } ?: humanizeAuditValue(operation)

@Composable
internal fun entityTypeLabel(type: String): String = entityTypeKey(type)?.let { str(it) } ?: humanizeAuditValue(type)

@Composable
internal fun auditToneColor(tone: AuditTone): Color =
    when (tone) {
        AuditTone.Create -> MaterialTheme.colorScheme.primary
        AuditTone.Update -> MaterialTheme.colorScheme.tertiary
        AuditTone.Delete, AuditTone.Error -> MaterialTheme.colorScheme.error
        AuditTone.Neutral -> MaterialTheme.colorScheme.onSurface
    }
