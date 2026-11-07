package org.example.project.core.domain.dto.monthlyclose

/**
 * The stable codes a monthly-close checklist item carries in
 * [MonthlyCloseDto.ChecklistItemDto.detailCode], with the meaning of each code's positional
 * [MonthlyCloseDto.ChecklistItemDto.detailArgs].
 *
 * The server used to send only an English sentence in `detail`, which the admin screen printed as
 * it came ("Missing full-period export for: KASSENBUCH"). The client now renders the sentence from
 * the code in the operator's language; `detail` stays on the wire for older clients.
 *
 * Adding a code means adding its EN/DE/ZH text on the client — `MonthlyCloseDetailTextTest` walks
 * [ALL] and fails on a code without a translation.
 */
object MonthlyCloseDetailCodes {
    /** args: `[overlapCount]` */
    const val ZBON_OVERLAPS = "zbon_overlaps"

    /** args: `[gapCount]` */
    const val BILLS_OUTSIDE_ZBON = "bills_outside_zbon"

    /** The VAT totals agree; only the total was compared, not each rate. args: none. */
    const val VAT_TOTAL_ONLY = "vat_total_only"

    /** args: `[delta, billsVat, zBonVat]` — canonical decimal strings, money in the response currency. */
    const val VAT_DELTA = "vat_delta"

    /** args: `[openBillCount]` */
    const val OPEN_BILLS = "open_bills"

    /** args: `[unsignedBillCount]` */
    const val UNSIGNED_BILLS = "unsigned_bills"

    /** args: `[undocumentedEntryCount]` */
    const val UNDOCUMENTED_CASH_MOVEMENTS = "undocumented_cash_movements"

    /** args: the missing export types, each one of [EXPORT_TYPES]. */
    const val DATEV_MISSING_PERIOD = "datev_missing_period"

    /** args: `[weekCount, exportType…]` — the export types each one of [EXPORT_TYPES]. */
    const val DATEV_MISSING_WEEKS = "datev_missing_weeks"

    /** args: `[vorgangCount]` */
    const val POSSIBLE_DOUBLE_SIGN = "possible_double_sign"

    /** args: `[vorgangCount]` */
    const val SIMULATED_SIGNATURES = "simulated_signatures"

    /** args: `[rangeCount]` */
    const val UNEXPLAINED_TSE_RANGES = "unexplained_tse_ranges"

    /** Every code the server may send. */
    val ALL: List<String> =
        listOf(
            ZBON_OVERLAPS,
            BILLS_OUTSIDE_ZBON,
            VAT_TOTAL_ONLY,
            VAT_DELTA,
            OPEN_BILLS,
            UNSIGNED_BILLS,
            UNDOCUMENTED_CASH_MOVEMENTS,
            DATEV_MISSING_PERIOD,
            DATEV_MISSING_WEEKS,
            POSSIBLE_DOUBLE_SIGN,
            SIMULATED_SIGNATURES,
            UNEXPLAINED_TSE_RANGES,
        )

    /** The DATEV export types a `datev_missing_*` code can name. */
    const val EXPORT_KASSENBUCH = "KASSENBUCH"
    const val EXPORT_AP_LEDGER = "AP_LEDGER"

    val EXPORT_TYPES: List<String> = listOf(EXPORT_KASSENBUCH, EXPORT_AP_LEDGER)
}
