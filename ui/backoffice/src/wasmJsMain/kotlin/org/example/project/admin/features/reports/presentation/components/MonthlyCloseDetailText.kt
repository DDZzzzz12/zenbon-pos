package org.example.project.admin.features.reports.presentation.components

import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDetailCodes
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.MonthlyCloseKeys
import org.example.project.ui.components.formatMoneyForDisplay

/*
 * The detail line under a monthly-close check, rendered in the operator's language from the
 * server's `detailCode` + `detailArgs` (admin16 NOTE-1). The server used to send only an English
 * sentence, which the screen printed verbatim. That sentence (`detail`) is still the fallback for
 * a code this client does not know — a newer server — and for an older server that sends no code.
 */

/** The template for [code], or `null` for a code this client does not know. */
internal fun checklistDetailKey(code: String): StringKey? =
    when (code) {
        MonthlyCloseDetailCodes.ZBON_OVERLAPS -> MonthlyCloseKeys.detailZBonOverlaps
        MonthlyCloseDetailCodes.BILLS_OUTSIDE_ZBON -> MonthlyCloseKeys.detailBillsOutsideZBon
        MonthlyCloseDetailCodes.VAT_TOTAL_ONLY -> MonthlyCloseKeys.detailVatTotalOnly
        MonthlyCloseDetailCodes.VAT_DELTA -> MonthlyCloseKeys.detailVatDelta
        MonthlyCloseDetailCodes.OPEN_BILLS -> MonthlyCloseKeys.detailOpenBills
        MonthlyCloseDetailCodes.UNSIGNED_BILLS -> MonthlyCloseKeys.detailUnsignedBills
        MonthlyCloseDetailCodes.UNDOCUMENTED_CASH_MOVEMENTS -> MonthlyCloseKeys.detailUndocumentedCashMovements
        MonthlyCloseDetailCodes.DATEV_MISSING_PERIOD -> MonthlyCloseKeys.detailDatevMissingPeriod
        MonthlyCloseDetailCodes.DATEV_MISSING_WEEKS -> MonthlyCloseKeys.detailDatevMissingWeeks
        MonthlyCloseDetailCodes.POSSIBLE_DOUBLE_SIGN -> MonthlyCloseKeys.detailPossibleDoubleSign
        MonthlyCloseDetailCodes.SIMULATED_SIGNATURES -> MonthlyCloseKeys.detailSimulatedSignatures
        MonthlyCloseDetailCodes.UNEXPLAINED_TSE_RANGES -> MonthlyCloseKeys.detailUnexplainedTseRanges
        else -> null
    }

/**
 * The localized name of a DATEV export type — one a `datev_missing_*` code names, or the
 * `export_type` of a DATEV export-log row (the server's `DatevExportType`) — or `null` if unknown.
 * The export log used to print the constant itself ("KASSENBUCH", QA admin18 NOTE-22).
 */
internal fun exportTypeKey(exportType: String): StringKey? =
    when (exportType) {
        MonthlyCloseDetailCodes.EXPORT_KASSENBUCH -> MonthlyCloseKeys.exportTypeKassenbuch
        MonthlyCloseDetailCodes.EXPORT_AP_LEDGER -> MonthlyCloseKeys.exportTypeApLedger
        "AR_LEDGER" -> MonthlyCloseKeys.exportTypeArLedger
        "BUCHUNGSSTAPEL" -> MonthlyCloseKeys.exportTypeBuchungsstapel
        "BUCHUNGSSTAPEL_MIT_BELEGEN" -> MonthlyCloseKeys.exportTypeBuchungsstapelBelege
        else -> null
    }

/**
 * The detail line of [item] in [language]; blank when there is nothing to say. Money arguments are
 * formatted with [currencyCode], export types by their localized name.
 */
internal fun checklistDetailText(
    item: MonthlyCloseDto.ChecklistItemDto,
    language: Language,
    currencyCode: String,
): String {
    val code = item.detailCode ?: return item.detail
    val key = checklistDetailKey(code) ?: return item.detail
    val args = localizedArgs(code, item.detailArgs, language, currencyCode)
    return getString(key, language, *args.toTypedArray())
}

private fun localizedArgs(
    code: String,
    args: List<String>,
    language: Language,
    currencyCode: String,
): List<String> =
    when (code) {
        MonthlyCloseDetailCodes.VAT_DELTA -> args.map { formatMoneyForDisplay(it, currencyCode) }
        MonthlyCloseDetailCodes.DATEV_MISSING_PERIOD -> listOf(exportTypeList(args, language))
        MonthlyCloseDetailCodes.DATEV_MISSING_WEEKS ->
            listOfNotNull(args.firstOrNull(), exportTypeList(args.drop(1), language))
        else -> args
    }

private fun exportTypeList(
    exportTypes: List<String>,
    language: Language,
): String = exportTypes.joinToString(", ") { type -> exportTypeKey(type)?.let { getString(it, language) } ?: type }
