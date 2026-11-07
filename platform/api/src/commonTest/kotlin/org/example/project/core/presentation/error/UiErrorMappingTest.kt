package org.example.project.core.presentation.error

import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.strings.keys.BackOfficeErrorKeys
import org.example.project.core.presentation.strings.keys.BillReceiptKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.IssueReportKeys
import org.example.project.core.presentation.strings.keys.KassenbuchKeys
import org.example.project.core.presentation.strings.keys.MainScreenKeys
import org.example.project.core.presentation.strings.keys.OrderErrorKeys
import org.example.project.core.presentation.strings.keys.PrinterSettingsKeys
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.presentation.strings.keys.RulesKeys
import org.example.project.core.presentation.strings.keys.SalesInvoiceTillKeys
import org.example.project.core.presentation.strings.keys.TerminalPairingKeys
import org.example.project.core.presentation.strings.keys.TipPayoutKeys
import org.example.project.core.presentation.strings.keys.TseSettingsKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiErrorMappingTest {
    @Test
    fun tse_disabled_maps_to_the_dedicated_tse_disabled_message() {
        val uiError = PosApiException(PosErrorCode.TSE_DISABLED, 409, "TSE is disabled", null).toUiError()

        assertEquals(PosErrorCode.TSE_DISABLED, uiError.code)
        assertEquals(MainScreenKeys.tseDisabled, uiError.titleKey)
        assertEquals(TseSettingsKeys.tseDisabledActionBlocked, uiError.messageKey)
        assertEquals(409, uiError.httpStatus)
    }

    @Test
    fun browser_station_disabled_maps_to_the_browser_stations_disabled_message() {
        val uiError = PosApiException(PosErrorCode.BROWSER_STATION_DISABLED, 403, "disabled", null).toUiError()

        assertEquals(CommonKeys.accessDeniedTitle, uiError.titleKey)
        assertEquals(TerminalPairingKeys.browserStationDisabled, uiError.messageKey)
    }

    @Test
    fun printing_disabled_maps_to_the_dedicated_printing_disabled_message() {
        val uiError = PosApiException(PosErrorCode.PRINTING_DISABLED, 409, "Printing is not enabled", null).toUiError()

        assertEquals(PosErrorCode.PRINTING_DISABLED, uiError.code)
        assertEquals(PrinterSettingsKeys.printingDisabledTitle, uiError.titleKey)
        assertEquals(PrinterSettingsKeys.printingDisabledMessage, uiError.messageKey)
        assertEquals(409, uiError.httpStatus)
    }

    @Test
    fun an_incomplete_tse_tar_maps_to_its_own_message_and_keeps_the_serial_in_the_raw_detail() {
        // Checklist 28.4: the DSFinV-K export refuses a TAR that does not cover the period.
        val detail = "TSE TAR does not cover the exported period: TSE serial ABAB: 2 of 2 signed transaction(s) missing"
        val uiError = PosApiException(PosErrorCode.TSE_EXPORT_INCOMPLETE, 409, detail, null).toUiError()

        assertEquals(TseSettingsKeys.tarIncompleteTitle, uiError.titleKey)
        assertEquals(TseSettingsKeys.tarIncompleteMessage, uiError.messageKey)
        assertEquals(detail, uiError.rawDetail)
    }

    @Test
    fun a_bill_already_voided_elsewhere_maps_to_its_own_message_not_the_unknown_error() {
        // A Storno or Zahlartwechsel of a bill another device stornoed or rebooked meanwhile.
        val uiError = PosApiException(PosErrorCode.BILL_ALREADY_DELETED, 409, "Bill 6 is already deleted", null).toUiError()

        assertEquals(BillReceiptKeys.billAlreadyVoidedTitle, uiError.titleKey)
        assertEquals(BillReceiptKeys.billAlreadyVoidedMessage, uiError.messageKey)
        assertEquals(409, uiError.httpStatus)
    }

    @Test
    fun an_owner_tip_that_cannot_be_taken_off_the_invoice_maps_to_its_own_message() {
        val detail = "Bill 6 cannot be invoiced: its owner tip of 2.00 cannot be taken out of its VAT details exactly"
        val uiError = PosApiException(PosErrorCode.SALES_INVOICE_OWNER_TIP_NOT_SEPARABLE, 409, detail, null).toUiError()

        assertEquals(SalesInvoiceTillKeys.ownerTipNotSeparableTitle, uiError.titleKey)
        assertEquals(SalesInvoiceTillKeys.ownerTipNotSeparableMessage, uiError.messageKey)
        assertEquals(detail, uiError.rawDetail)
    }

    @Test
    fun promotions_that_do_not_reconcile_with_the_bill_map_to_their_own_message() {
        val detail = "Bill 6 cannot be invoiced: its promotions cannot be shown as allowances that agree with its VAT details exactly"
        val uiError = PosApiException(PosErrorCode.SALES_INVOICE_BILL_NOT_RECONCILABLE, 409, detail, null).toUiError()

        assertEquals(SalesInvoiceTillKeys.billNotReconcilableTitle, uiError.titleKey)
        assertEquals(SalesInvoiceTillKeys.billNotReconcilableMessage, uiError.messageKey)
        assertEquals(detail, uiError.rawDetail)
    }

    @Test
    fun a_tip_paid_on_account_maps_to_its_own_message() {
        val uiError = PosApiException(PosErrorCode.SALES_INVOICE_TIP_ON_ACCOUNT, 409, "Bill 6 cannot be invoiced", null).toUiError()

        assertEquals(SalesInvoiceTillKeys.tipOnAccountTitle, uiError.titleKey)
        assertEquals(SalesInvoiceTillKeys.tipOnAccountMessage, uiError.messageKey)
    }

    @Test
    fun an_already_invoiced_bill_maps_to_its_own_message() {
        val uiError = PosApiException(PosErrorCode.SALES_INVOICE_BILL_ALREADY_INVOICED, 409, "Bill 6 already has invoice 3", null).toUiError()

        assertEquals(SalesInvoiceTillKeys.alreadyInvoicedTitle, uiError.titleKey)
        assertEquals(SalesInvoiceTillKeys.alreadyInvoicedMessage, uiError.messageKey)
    }

    @Test
    fun open_tables_blocking_the_z_bon_map_to_their_own_message() {
        val uiError =
            PosApiException(PosErrorCode.ZBON_TABLES_OPEN, 409, "Table 3 is occupied by open items - cannot create Z-report", null)
                .toUiError()

        assertEquals(ReportsKeys.zBonTablesOpenTitle, uiError.titleKey)
        assertEquals(ReportsKeys.zBonTablesOpenMessage, uiError.messageKey)
    }

    /** NEW-25: the overlap refusal used to be a 500 "unexpected error"; now a 409 with its own message. */
    @Test
    fun an_overlapping_z_bon_period_maps_to_its_own_message() {
        val uiError =
            PosApiException(PosErrorCode.ZBON_PERIOD_OVERLAP, 409, "Z-report already exists for overlapping period", null)
                .toUiError()

        assertEquals(ReportsKeys.zBonPeriodOverlapTitle, uiError.titleKey)
        assertEquals(ReportsKeys.zBonPeriodOverlapMessage, uiError.messageKey)
    }

    @Test
    fun a_close_with_nothing_open_maps_to_its_own_message() {
        val uiError =
            PosApiException(PosErrorCode.ZBON_NOTHING_TO_CLOSE, 400, "No open business to close for Z-report", null).toUiError()

        assertEquals(ReportsKeys.zBonNothingToCloseTitle, uiError.titleKey)
        assertEquals(ReportsKeys.zBonNothingToCloseMessage, uiError.messageKey)
    }

    @Test
    fun closing_signer_codes_map_to_their_own_keys() {
        val cases =
            mapOf(
                PosErrorCode.ZBON_SIGNER_NOT_DESIGNATED to ReportsKeys.zBonSignerNotDesignatedMessage,
                PosErrorCode.ZBON_SIGNER_UNAVAILABLE to ReportsKeys.zBonSignerUnavailableMessage,
                PosErrorCode.CASH_BOOK_SIGNER_INELIGIBLE to ReportsKeys.cashBookSignerIneligibleMessage,
            )
        cases.forEach { (code, key) ->
            val uiError = PosApiException(code, 409, "detail", null).toUiError()
            assertEquals(ReportsKeys.zBonSignerTitle, uiError.titleKey, "code=$code")
            assertEquals(key, uiError.messageKey, "code=$code")
        }
    }

    @Test
    fun tip_payout_refusals_map_to_their_own_messages() {
        val exceeds = PosApiException(PosErrorCode.ZBON_TIP_PAYOUT_EXCEEDS_REMAINING, 409, "exceeds", null).toUiError()
        assertEquals(TipPayoutKeys.exceedsRemainingTitle, exceeds.titleKey)
        assertEquals(TipPayoutKeys.exceedsRemainingMessage, exceeds.messageKey)

        val notAvailable = PosApiException(PosErrorCode.ZBON_TIP_PAYOUT_NOT_AVAILABLE, 409, "none", null).toUiError()
        assertEquals(TipPayoutKeys.notAvailableTitle, notAvailable.titleKey)
        assertEquals(TipPayoutKeys.notAvailableMessage, notAvailable.messageKey)

        val reversed = PosApiException(PosErrorCode.ZBON_TIP_PAYOUT_ALREADY_REVERSED, 409, "twice", null).toUiError()
        assertEquals(TipPayoutKeys.alreadyReversedTitle, reversed.titleKey)
        val notPayout = PosApiException(PosErrorCode.ZBON_TIP_PAYOUT_NOT_A_PAYOUT, 409, "no", null).toUiError()
        assertEquals(TipPayoutKeys.notAPayoutMessage, notPayout.messageKey)
    }

    @Test
    fun the_kassenminus_refusal_maps_to_its_own_message_and_keeps_the_available_amount_in_the_raw_detail() {
        val error =
            PosApiException(
                PosErrorCode.KASSENBUCH_INSUFFICIENT_CASH,
                409,
                "Cash book 'Ladenkasse' holds 12.00 — cannot pay out 40.00",
                null,
            ).toUiError()

        assertEquals(KassenbuchKeys.insufficientCashTitle, error.titleKey)
        assertEquals(KassenbuchKeys.insufficientCashMessage, error.messageKey)
        assertTrue(error.rawDetail.orEmpty().contains("12.00"))
    }

    @Test
    fun a_capability_denial_maps_to_a_localized_message_instead_of_the_english_server_detail() {
        // T9-21: the Bankkonten toast read "Required capability: CREDITOR_WRITE".
        val uiError =
            PosApiException(PosErrorCode.AUTH_FORBIDDEN, 403, "Required capability: CREDITOR_WRITE", null).toUiError()

        assertEquals(CommonKeys.accessDeniedTitle, uiError.titleKey)
        assertEquals(CommonKeys.accessDeniedMessage, uiError.messageKey)
        assertNull(uiError.serverDetailFallback())
        assertEquals("Required capability: CREDITOR_WRITE", uiError.rawDetail)
    }

    @Test
    fun a_training_mode_refusal_maps_to_a_localized_message_instead_of_the_english_server_detail() {
        // E-5 (emulator pass 2026-09-27): the order station toasted the raw server detail.
        val detail =
            "Cannot create order: rotated owner K002 of table T4 has training mode (true), employee training mode is (false)"
        val uiError = PosApiException(PosErrorCode.TRAINING_MODE_MISMATCH, 409, detail, null).toUiError()

        assertEquals(OrderErrorKeys.trainingModeMismatchTitle, uiError.titleKey)
        assertEquals(OrderErrorKeys.trainingModeMismatch, uiError.messageKey)
        assertNull(uiError.serverDetailFallback())
        assertEquals(detail, uiError.rawDetail)
    }

    @Test
    fun the_other_order_path_refusals_map_to_localized_messages() {
        val expected =
            mapOf(
                PosErrorCode.TABLE_VIRTUAL_NOT_ORDERABLE to (RulesKeys.stationTableDeniedTitle to OrderErrorKeys.virtualTableNotOrderable),
                PosErrorCode.SESSION_ALREADY_CLOSED to
                    (OrderErrorKeys.sessionAlreadyClosedTitle to OrderErrorKeys.sessionAlreadyClosed),
                PosErrorCode.CUSTOM_PRICE_NOT_ALLOWED to (CommonKeys.accessDeniedTitle to OrderErrorKeys.customPriceNotAllowed),
                PosErrorCode.DISCOUNT_NOT_ALLOWED to (CommonKeys.accessDeniedTitle to OrderErrorKeys.discountNotAllowed),
            )
        expected.forEach { (code, keys) ->
            val uiError = PosApiException(code, 409, "raw English detail", null).toUiError()
            assertEquals(keys.first, uiError.titleKey, "title for $code")
            assertEquals(keys.second, uiError.messageKey, "message for $code")
            assertNull(uiError.serverDetailFallback(), "fallback for $code")
        }
    }

    /**
     * e2e backlog "raw English server strings in the back office": the refusals the staff,
     * terminal, TSE and DATEV screens meet routinely toasted the server's English detail. Each now
     * resolves to a localized message; the detail stays in `rawDetail` for the error report.
     */
    @Test
    fun routine_back_office_refusals_map_to_localized_messages() {
        val tseUnreachable = BackOfficeErrorKeys.tseUnreachableTitle to BackOfficeErrorKeys.tseUnreachable
        val expected =
            mapOf(
                PosErrorCode.EMPLOYEE_USERNAME_TAKEN to (BackOfficeErrorKeys.usernameTakenTitle to BackOfficeErrorKeys.usernameTaken),
                PosErrorCode.LAST_ADMIN to (BackOfficeErrorKeys.lastAdminTitle to BackOfficeErrorKeys.lastAdmin),
                PosErrorCode.SYSTEM_RECORD_PROTECTED to
                    (BackOfficeErrorKeys.systemRecordTitle to BackOfficeErrorKeys.systemRecordProtected),
                PosErrorCode.SELF_MODIFICATION_FORBIDDEN to (CommonKeys.accessDeniedTitle to BackOfficeErrorKeys.selfModification),
                PosErrorCode.EMPLOYEE_NOT_FOUND to (BackOfficeErrorKeys.notFoundTitle to BackOfficeErrorKeys.employeeNotFound),
                PosErrorCode.TERMINAL_NOT_FOUND to (BackOfficeErrorKeys.notFoundTitle to BackOfficeErrorKeys.terminalNotFound),
                PosErrorCode.TSE_UNREACHABLE to tseUnreachable,
                PosErrorCode.TSE_CONNECTION_FAILED to tseUnreachable,
                PosErrorCode.TSE_COMMUNICATION_TIMEOUT to tseUnreachable,
                PosErrorCode.DATEV_EXPORT_TYPE_NOT_SCHEDULABLE to
                    (BackOfficeErrorKeys.datevNotSchedulableTitle to BackOfficeErrorKeys.datevNotSchedulable),
                PosErrorCode.RESTORED_PATHS_NEED_REVIEW to
                    (BackOfficeErrorKeys.restoredPathsNeedReviewTitle to BackOfficeErrorKeys.restoredPathsNeedReview),
                // admin21 NOTE-d21 / NOTE-c21: the product form showed the raw English detail.
                PosErrorCode.PRICE_INVALID to
                    (BackOfficeErrorKeys.priceInvalidTitle to BackOfficeErrorKeys.priceInvalid),
                PosErrorCode.CHOICE_GROUP_OPTION_REQUIRED to
                    (BackOfficeErrorKeys.choiceGroupOptionRequiredTitle to BackOfficeErrorKeys.choiceGroupOptionRequired),
                // An unlock was requested for an uploaded PDF: the dialog re-prompts with this sentence.
                PosErrorCode.PDF_PASSWORD_REQUIRED to
                    (BackOfficeErrorKeys.pdfPasswordRequiredTitle to BackOfficeErrorKeys.pdfPasswordRequired),
                PosErrorCode.PDF_PASSWORD_INCORRECT to
                    (BackOfficeErrorKeys.pdfPasswordIncorrectTitle to BackOfficeErrorKeys.pdfPasswordIncorrect),
            )
        expected.forEach { (code, keys) ->
            val detail = "raw English detail for $code"
            val uiError = PosApiException(code, 409, detail, null).toUiError()
            assertEquals(keys.first, uiError.titleKey, "title for $code")
            assertEquals(keys.second, uiError.messageKey, "message for $code")
            assertNull(uiError.serverDetailFallback(), "fallback for $code")
            assertEquals(detail, uiError.rawDetail, "raw detail kept for $code")
        }
    }

    /**
     * The codes deliberately left unmapped: their server detail is the actionable sentence (or one
     * code covers remedies a single translation could not name), so the fallback must still show it.
     */
    @Test
    fun codes_whose_detail_is_the_message_still_fall_back_to_the_server_detail() {
        listOf(
            PosErrorCode.VALIDATION_FAILED,
            PosErrorCode.INVALID_REQUEST,
            PosErrorCode.DATEV_ACCOUNT_MAPPING_REQUIRED,
            PosErrorCode.TSE_NOT_CONFIGURED,
        ).forEach { code ->
            val uiError = PosApiException(code, 400, "specific detail", null).toUiError()
            assertEquals("specific detail", uiError.serverDetailFallback(), "fallback for $code")
        }
    }

    /** Each problem-report refusal needs a different remedy, so each gets its own sentence. */
    @Test
    fun issue_report_refusals_each_map_to_their_own_message() {
        mapOf(
            PosErrorCode.ISSUE_REPORT_NOT_CONFIGURED to IssueReportKeys.errorNotConfigured,
            PosErrorCode.ISSUE_REPORT_TOKEN_REJECTED to IssueReportKeys.errorTokenRejected,
            PosErrorCode.ISSUE_REPORT_RATE_LIMITED to IssueReportKeys.errorRateLimited,
            PosErrorCode.ISSUE_REPORT_GITHUB_UNREACHABLE to IssueReportKeys.errorGithubUnreachable,
        ).forEach { (code, messageKey) ->
            val uiError = PosApiException(code, 502, "server detail", null).toUiError()
            assertEquals(IssueReportKeys.errorTitle, uiError.titleKey, "title for $code")
            assertEquals(messageKey, uiError.messageKey, "message for $code")
            assertEquals("server detail", uiError.rawDetail)
        }
    }

    /**
     * The update token's refusals point at different remedies than the generic "not configured":
     * a save for the wrong host (fix the API address, or use a dedicated token) and a send whose
     * update token is missing (Settings → Updates). Neither may read as the server's English detail.
     */
    @Test
    fun issue_report_update_token_refusals_map_to_their_own_localized_messages() {
        val hostMismatch =
            PosApiException(PosErrorCode.ISSUE_REPORT_UPDATE_TOKEN_HOST_MISMATCH, 400, "english detail", null).toUiError()
        assertEquals(IssueReportKeys.settingsSectionTitle, hostMismatch.titleKey)
        assertEquals(IssueReportKeys.errorUpdateTokenHostMismatch, hostMismatch.messageKey)
        assertEquals(400, hostMismatch.httpStatus)

        val unavailable =
            PosApiException(PosErrorCode.ISSUE_REPORT_UPDATE_TOKEN_UNAVAILABLE, 409, "english detail", null).toUiError()
        assertEquals(IssueReportKeys.errorTitle, unavailable.titleKey)
        assertEquals(IssueReportKeys.errorUpdateTokenUnavailable, unavailable.messageKey)
        assertEquals("english detail", unavailable.rawDetail)
    }
}
