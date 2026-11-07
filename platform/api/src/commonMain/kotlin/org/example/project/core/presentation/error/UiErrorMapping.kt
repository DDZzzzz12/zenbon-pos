package org.example.project.core.presentation.error

import kotlinx.coroutines.CancellationException
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.strings.keys.BackOfficeErrorKeys
import org.example.project.core.presentation.strings.keys.BillReceiptKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.IssueReportKeys
import org.example.project.core.presentation.strings.keys.KassenbuchKeys
import org.example.project.core.presentation.strings.keys.LoginKeys
import org.example.project.core.presentation.strings.keys.MainScreenKeys
import org.example.project.core.presentation.strings.keys.OrderErrorKeys
import org.example.project.core.presentation.strings.keys.PrinterSettingsKeys
import org.example.project.core.presentation.strings.keys.PromotionKeys
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.presentation.strings.keys.RulesKeys
import org.example.project.core.presentation.strings.keys.SalesInvoiceTillKeys
import org.example.project.core.presentation.strings.keys.ShopInfoKeys
import org.example.project.core.presentation.strings.keys.TemporarySessionKeys
import org.example.project.core.presentation.strings.keys.TerminalPairingKeys
import org.example.project.core.presentation.strings.keys.TipPayoutKeys
import org.example.project.core.presentation.strings.keys.TseSettingsKeys

/**
 * Maps a [PosApiException] to a [UiError.FromServer]. Per-feature ports
 * (Stage C) extend the `when` below to map their feature-specific
 * [PosErrorCode]s to dedicated localization keys. Codes without a dedicated
 * mapping fall through to the unknown-error keys; users still see a useful
 * (if generic) toast and a [UiError.FromServer] in state for diagnostics.
 */
fun PosApiException.toUiError(): UiError.FromServer {
    val (titleKey, messageKey) =
        when (code) {
            // A station hits this on every free-table open once the pool is empty (spec
            // 2026-09-14) — the generic unknown-error title read as a crash rather than the
            // actionable denial it is. `stationTableDeniedTitle` is accurate for a person too:
            // it names the table as refused, not who was refused it.
            PosErrorCode.ROTATION_POOL_EMPTY ->
                RulesKeys.stationTableDeniedTitle to RulesKeys.rotationPoolEmptyToast
            // An order station never owns a table session (spec 2026-09-14). The till denies these
            // opens itself; a stale round-robin flag can still reach the server, and the operator must
            // then read the same sentence the till would have shown.
            PosErrorCode.STATION_TABLE_REQUIRES_ROTATION ->
                RulesKeys.stationTableDeniedTitle to RulesKeys.stationTableRequiresRotation
            PosErrorCode.STATION_TABLE_VIRTUAL ->
                RulesKeys.stationTableDeniedTitle to RulesKeys.stationCannotOpenVirtualTable
            // Owner decision 2026-10-03 (emu21 NOTE-v): a station never queues a Storno approval.
            // The station hides the Storno until a manager signs in, so this is a stale-UI race.
            PosErrorCode.STATION_STORNO_REQUIRES_MANAGER ->
                TemporarySessionKeys.stationStornoNeedsManagerTitle to TemporarySessionKeys.stationStornoNeedsManagerMessage
            // DSFinV-K round-5 KABS-2: shop master data must be snapshotted per Kassenabschluss, so
            // the server refuses an edit while the business day is open. Without this mapping the
            // operator saw only the generic unknown-error text for a perfectly actionable 409.
            PosErrorCode.SHOP_INFO_CLOSING_REQUIRED ->
                CommonKeys.unknownErrorTitle to ShopInfoKeys.closingRequiredError
            // A rejected sign-in is the single most common error an operator sees. Unmapped, it
            // fell through to the generic unknown-error text, and `serverDetailFallback()` then
            // showed the server's own untranslated "Invalid credentials" instead.
            PosErrorCode.AUTH_INVALID_CREDENTIALS ->
                LoginKeys.title to LoginKeys.errorInvalidCredentials
            // Right credentials, but no permission group: the server refuses the sign-in (403).
            PosErrorCode.NO_PERMISSION_GROUP ->
                LoginKeys.title to LoginKeys.errorNoPermissionGroup
            // The till disables a non-combinable promotion once another one is on the bill, but a
            // stale promotion list can still reach the server guard. Unmapped, an entirely
            // actionable 409 read as the generic unknown-error text.
            PosErrorCode.PROMOTION_NOT_COMBINABLE ->
                CommonKeys.unknownErrorTitle to PromotionKeys.notCombinableError
            // A fresh install starts with the TSE DISABLED, and the server refuses this in two
            // places until it is put into operation: terminal TSE registration/sync (409), and
            // TSE transaction lookups performed while the TSE is disabled (503). Unmapped, that
            // fixable state read as the generic unknown-error text.
            PosErrorCode.TSE_DISABLED ->
                MainScreenKeys.tseDisabled to TseSettingsKeys.tseDisabledActionBlocked
            // Printing switched off is the same shape of defect as TSE_DISABLED: the server refuses
            // a reference print and a bill print until the operator enables printing. Both guards
            // used to be INTERNAL_ERROR + 503, which `PosErrorWatcher` escalates to a modal
            // "unexpected error" — a crash dialog for a setting the operator can flip themselves.
            PosErrorCode.PRINTING_DISABLED ->
                PrinterSettingsKeys.printingDisabledTitle to PrinterSettingsKeys.printingDisabledMessage
            // An open table blocks the daily close: an everyday, actionable refusal — not a crash
            // (E2E-2026-09-18 #19: it was a 400 VALIDATION_FAILED shown as "Unerwarteter Fehler").
            PosErrorCode.ZBON_TABLES_OPEN ->
                ReportsKeys.zBonTablesOpenTitle to ReportsKeys.zBonTablesOpenMessage
            // An existing closing ends after the new period would start (NEW-25): it used to be a
            // bare IllegalStateException and answered 500 "unexpected error".
            PosErrorCode.ZBON_PERIOD_OVERLAP ->
                ReportsKeys.zBonPeriodOverlapTitle to ReportsKeys.zBonPeriodOverlapMessage
            PosErrorCode.ZBON_NOTHING_TO_CLOSE ->
                ReportsKeys.zBonNothingToCloseTitle to ReportsKeys.zBonNothingToCloseMessage
            // Tip payouts: the till pre-checks both, so these arrive only from a stale screen or a
            // concurrent payout from another till — a business refusal, never a crash.
            PosErrorCode.ZBON_TIP_PAYOUT_EXCEEDS_REMAINING ->
                TipPayoutKeys.exceedsRemainingTitle to TipPayoutKeys.exceedsRemainingMessage
            PosErrorCode.ZBON_TIP_PAYOUT_NOT_AVAILABLE ->
                TipPayoutKeys.notAvailableTitle to TipPayoutKeys.notAvailableMessage
            PosErrorCode.ZBON_TIP_PAYOUT_ALREADY_REVERSED ->
                TipPayoutKeys.alreadyReversedTitle to TipPayoutKeys.alreadyReversedMessage
            PosErrorCode.ZBON_TIP_PAYOUT_NOT_A_PAYOUT ->
                TipPayoutKeys.notAPayoutTitle to TipPayoutKeys.notAPayoutMessage
            // Spec 2026-10-09: a closing's cash movements need a signing till. Actionable, never a crash.
            PosErrorCode.ZBON_SIGNER_NOT_DESIGNATED ->
                ReportsKeys.zBonSignerTitle to ReportsKeys.zBonSignerNotDesignatedMessage
            PosErrorCode.ZBON_SIGNER_UNAVAILABLE ->
                ReportsKeys.zBonSignerTitle to ReportsKeys.zBonSignerUnavailableMessage
            PosErrorCode.CASH_BOOK_SIGNER_INELIGIBLE ->
                ReportsKeys.zBonSignerTitle to ReportsKeys.cashBookSignerIneligibleMessage
            // F59 Kassenminus guard: the forms pre-check it, so this arrives from a stale balance or a
            // concurrent withdrawal. The server detail (kept as rawDetail) names the available amount.
            PosErrorCode.KASSENBUCH_INSUFFICIENT_CASH ->
                KassenbuchKeys.insufficientCashTitle to KassenbuchKeys.insufficientCashMessage
            // The server checks the Admin PIN on unlock (E2E round 4, R4-1). A refusal is the
            // operator's typo, not a crash; say so, and that it costs a device attempt.
            PosErrorCode.TSE_INVALID_PIN ->
                TseSettingsKeys.adminUnlockTitle to TseSettingsKeys.adminPinRejected
            // Setup on a commissioned TSE writes nothing (E2E round 4, R4-3); the operator must learn
            // that the credentials they just typed were not applied.
            PosErrorCode.TSE_ALREADY_INITIALIZED ->
                TseSettingsKeys.setupDialogTitle to TseSettingsKeys.alreadyCommissioned
            // The DSFinV-K export refuses a TSE TAR that does not hold the period's signed
            // transactions (checklist 28.4). Actionable, not a crash: the server detail names the
            // uncovered device serial and stays in `rawDetail`.
            PosErrorCode.TSE_EXPORT_INCOMPLETE ->
                TseSettingsKeys.tarIncompleteTitle to TseSettingsKeys.tarIncompleteMessage
            // A 403 from the route capability gate (and the few service-level refusals that share
            // its code). Unmapped, `serverDetailFallback()` showed the server's English detail —
            // "Required capability: CREDITOR_WRITE" — to an operator (T9-21). The raw detail stays
            // in `rawDetail` for the client-error report.
            PosErrorCode.AUTH_FORBIDDEN ->
                CommonKeys.accessDeniedTitle to CommonKeys.accessDeniedMessage
            // A manager step-up reached its hard cap: not a crash and not a lapsed login — the
            // manager must enter the PIN again.
            PosErrorCode.AUTH_STEP_UP_CAP_REACHED ->
                TemporarySessionKeys.approvalExpiredTitle to TemporarySessionKeys.approvalExpiredMessage
            // Refusals of the order-writing path (create order, cancel/transfer items). Unmapped, the
            // station toast read the server's English detail — "Cannot create order: rotated owner
            // K002 … training mode (true)" (emulator pass 2026-09-27, E-5). The raw detail stays in
            // `rawDetail` for the client-error report.
            PosErrorCode.TRAINING_MODE_MISMATCH ->
                OrderErrorKeys.trainingModeMismatchTitle to OrderErrorKeys.trainingModeMismatch
            PosErrorCode.TABLE_VIRTUAL_NOT_ORDERABLE ->
                RulesKeys.stationTableDeniedTitle to OrderErrorKeys.virtualTableNotOrderable
            PosErrorCode.SESSION_ALREADY_CLOSED ->
                OrderErrorKeys.sessionAlreadyClosedTitle to OrderErrorKeys.sessionAlreadyClosed
            PosErrorCode.CUSTOM_PRICE_NOT_ALLOWED ->
                CommonKeys.accessDeniedTitle to OrderErrorKeys.customPriceNotAllowed
            PosErrorCode.DISCOUNT_NOT_ALLOWED ->
                CommonKeys.accessDeniedTitle to OrderErrorKeys.discountNotAllowed
            // Back-office refusals operators meet routinely (staff, terminals, TSE, DATEV export).
            // Unmapped, each showed the server's English detail. Deliberately NOT mapped:
            // VALIDATION_FAILED / INVALID_REQUEST (the detail IS the actionable sentence),
            // RESOURCE_NOT_FOUND (too many unrelated meanings), DATEV_ACCOUNT_MAPPING_REQUIRED (one
            // code, two different remedies — see its KDoc) and TSE_NOT_CONFIGURED (the detail
            // carries the SDK install instruction).
            PosErrorCode.EMPLOYEE_USERNAME_TAKEN ->
                BackOfficeErrorKeys.usernameTakenTitle to BackOfficeErrorKeys.usernameTaken
            PosErrorCode.SYSTEM_RECORD_PROTECTED ->
                BackOfficeErrorKeys.systemRecordTitle to BackOfficeErrorKeys.systemRecordProtected
            PosErrorCode.LAST_ADMIN ->
                BackOfficeErrorKeys.lastAdminTitle to BackOfficeErrorKeys.lastAdmin
            PosErrorCode.SELF_MODIFICATION_FORBIDDEN ->
                CommonKeys.accessDeniedTitle to BackOfficeErrorKeys.selfModification
            PosErrorCode.EMPLOYEE_NOT_FOUND ->
                BackOfficeErrorKeys.notFoundTitle to BackOfficeErrorKeys.employeeNotFound
            PosErrorCode.TERMINAL_NOT_FOUND ->
                BackOfficeErrorKeys.notFoundTitle to BackOfficeErrorKeys.terminalNotFound
            // TSE setup with no terminal to register (none chosen, caller is no terminal): the same
            // sentence the setup dialog shows in place of its terminal picker.
            PosErrorCode.TSE_SETUP_TERMINAL_REQUIRED ->
                TseSettingsKeys.setupDialogTitle to TseSettingsKeys.setupNoTerminal
            // A configuration save without a TimeAdmin PIN: names the field and where to enter it,
            // instead of the generic "unexpected error" dialog (QA 2026-10-06, SIMULATION save).
            PosErrorCode.TSE_TIME_ADMIN_PIN_REQUIRED ->
                TseSettingsKeys.configurationSection to TseSettingsKeys.timeAdminPinRequiredForSave
            PosErrorCode.TSE_UNREACHABLE,
            PosErrorCode.TSE_CONNECTION_FAILED,
            PosErrorCode.TSE_COMMUNICATION_TIMEOUT,
            ->
                BackOfficeErrorKeys.tseUnreachableTitle to BackOfficeErrorKeys.tseUnreachable
            // A product/extra price the server cannot read (admin21 NOTE-d21: the raw English
            // "Price must be a valid decimal number" reached the product form).
            PosErrorCode.PRICE_INVALID ->
                BackOfficeErrorKeys.priceInvalidTitle to BackOfficeErrorKeys.priceInvalid
            // Deleting/deactivating an extra a required choice group cannot do without (NOTE-c21).
            PosErrorCode.CHOICE_GROUP_OPTION_REQUIRED ->
                BackOfficeErrorKeys.choiceGroupOptionRequiredTitle to BackOfficeErrorKeys.choiceGroupOptionRequired
            // Unlock-on-upload for a password-protected PDF: the upload dialog shows these inline.
            PosErrorCode.PDF_PASSWORD_REQUIRED ->
                BackOfficeErrorKeys.pdfPasswordRequiredTitle to BackOfficeErrorKeys.pdfPasswordRequired
            PosErrorCode.PDF_PASSWORD_INCORRECT ->
                BackOfficeErrorKeys.pdfPasswordIncorrectTitle to BackOfficeErrorKeys.pdfPasswordIncorrect
            PosErrorCode.DATEV_EXPORT_TYPE_NOT_SCHEDULABLE ->
                BackOfficeErrorKeys.datevNotSchedulableTitle to BackOfficeErrorKeys.datevNotSchedulable
            // A restore onto another installation carried that shop's folder paths; writes there are
            // paused until an admin reviews them. The server's English detail names the path, the
            // operator needs to know where to go.
            PosErrorCode.RESTORED_PATHS_NEED_REVIEW ->
                BackOfficeErrorKeys.restoredPathsNeedReviewTitle to BackOfficeErrorKeys.restoredPathsNeedReview
            // The till hides "Rechnung erstellen" and "Bewirtungsbeleg" for a Storno, a cancelled
            // original or a clearing; a stale bill on screen can still reach the server's
            // guard, and the operator must read why rather than the server's English detail.
            PosErrorCode.BILL_NOT_A_STANDING_SALE ->
                SalesInvoiceTillKeys.notStandingSaleTitle to SalesInvoiceTillKeys.notStandingSaleMessage
            // An Ausgangsrechnung leaves an owner tip off; a bill whose VAT rows cannot give it back
            // to the cent (a pre-apportionment bill, a promotion) is refused rather than misinvoiced.
            PosErrorCode.SALES_INVOICE_OWNER_TIP_NOT_SEPARABLE ->
                SalesInvoiceTillKeys.ownerTipNotSeparableTitle to SalesInvoiceTillKeys.ownerTipNotSeparableMessage
            // A promotion is shown as an allowance on the Ausgangsrechnung; a bill whose stored
            // promotions do not reproduce its VAT rows to the cent is refused rather than misinvoiced.
            PosErrorCode.SALES_INVOICE_BILL_NOT_RECONCILABLE ->
                SalesInvoiceTillKeys.billNotReconcilableTitle to SalesInvoiceTillKeys.billNotReconcilableMessage
            // The till hides *Rechnung erstellen* for it too; a stale bill on screen can still reach the server.
            PosErrorCode.SALES_INVOICE_TIP_ON_ACCOUNT ->
                SalesInvoiceTillKeys.tipOnAccountTitle to SalesInvoiceTillKeys.tipOnAccountMessage
            // A second "Rechnung erstellen" on a bill that got its invoice a moment ago (the dialog
            // still showed the button) or on another device. Showed "Unerwarteter Fehler" (emu20 NOTE-q).
            PosErrorCode.SALES_INVOICE_BILL_ALREADY_INVOICED ->
                SalesInvoiceTillKeys.alreadyInvoicedTitle to SalesInvoiceTillKeys.alreadyInvoicedMessage
            // A Storno or Zahlartwechsel of a bill another device stornoed or rebooked meanwhile:
            // an everyday concurrent edit, not a crash. The Berichte panel re-reads the bill and its list.
            PosErrorCode.BILL_ALREADY_DELETED ->
                BillReceiptKeys.billAlreadyVoidedTitle to BillReceiptKeys.billAlreadyVoidedMessage
            PosErrorCode.BILL_REBOOK_UNSUPPORTED ->
                BillReceiptKeys.rebookUnsupportedTitle to BillReceiptKeys.rebookUnsupportedMessage
            PosErrorCode.BILL_REBOOK_NO_CHANGE ->
                BillReceiptKeys.rebookNoChangeTitle to BillReceiptKeys.rebookNoChangeMessage
            PosErrorCode.BILL_REBOOK_PROMOTION_CHANGED ->
                BillReceiptKeys.rebookPromotionChangedTitle to BillReceiptKeys.rebookPromotionChangedMessage
            PosErrorCode.BILL_REBOOK_TIP_DECREASE_FORBIDDEN ->
                BillReceiptKeys.rebookTipDecreaseTitle to BillReceiptKeys.rebookTipDecreaseMessage
            // The till hides Zahlartwechsel and Storno for a bill with an issued invoice; a stale
            // bill on screen can still reach the server's guard. Worded generically: the same code
            // refuses edits to an issued invoice elsewhere.
            PosErrorCode.SALES_INVOICE_IMMUTABLE ->
                BillReceiptKeys.invoiceIssuedTitle to BillReceiptKeys.invoiceIssuedMessage
            // The shop has not enabled browser stations for this role (pair, station exchange, login,
            // refresh). The shop's choice, not a bad credential or a crash: say so in plain words.
            PosErrorCode.BROWSER_STATION_DISABLED ->
                CommonKeys.accessDeniedTitle to TerminalPairingKeys.browserStationDisabled
            // Problem reports filed as GitHub issues. Each needs a different remedy (configure a
            // token, replace it, wait, check the connection), and unmapped all four read as the
            // server's English detail. The detail stays in `rawDetail` for the client-error report.
            PosErrorCode.ISSUE_REPORT_NOT_CONFIGURED ->
                IssueReportKeys.errorTitle to IssueReportKeys.errorNotConfigured
            PosErrorCode.ISSUE_REPORT_TOKEN_REJECTED ->
                IssueReportKeys.errorTitle to IssueReportKeys.errorTokenRejected
            PosErrorCode.ISSUE_REPORT_RATE_LIMITED ->
                IssueReportKeys.errorTitle to IssueReportKeys.errorRateLimited
            PosErrorCode.ISSUE_REPORT_GITHUB_UNREACHABLE ->
                IssueReportKeys.errorTitle to IssueReportKeys.errorGithubUnreachable
            // The update token's two refusals point at different screens than the rest: the save
            // is a settings error (shown inline in the section), the send names Settings → Updates.
            PosErrorCode.ISSUE_REPORT_UPDATE_TOKEN_HOST_MISMATCH ->
                IssueReportKeys.settingsSectionTitle to IssueReportKeys.errorUpdateTokenHostMismatch
            PosErrorCode.ISSUE_REPORT_UPDATE_TOKEN_UNAVAILABLE ->
                IssueReportKeys.errorTitle to IssueReportKeys.errorUpdateTokenUnavailable
            else ->
                CommonKeys.unknownErrorTitle to CommonKeys.unknownErrorMessage
        }
    return UiError.FromServer(
        code = code,
        traceId = traceId,
        titleKey = titleKey,
        messageKey = messageKey,
        httpStatus = httpStatus,
        fieldErrors = fieldErrors,
        rawDetail = message,
    )
}

fun Throwable.toUiError(retry: (() -> Unit)? = null): UiError =
    when (this) {
        is PosApiException -> toUiError()
        is NetworkApiException -> UiError.Network(retry = retry)
        is CancellationException -> throw this
        else -> UiError.Unknown(cause = this)
    }
