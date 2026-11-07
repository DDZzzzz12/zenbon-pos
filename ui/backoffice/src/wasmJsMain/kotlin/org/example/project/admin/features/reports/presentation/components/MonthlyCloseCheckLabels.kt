package org.example.project.admin.features.reports.presentation.components

import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.MonthlyCloseKeys

/**
 * Maps a server check id onto the localized label every monthly-close section renders.
 *
 * The server sends only the id — a stable contract string — so the display text is chosen here,
 * where a language is known. Every id the checks package emits must have a branch: the tolerant
 * `else` keeps an unknown id from throwing, but it renders the raw id to the operator, so it is a
 * fallback for a newer server, not a substitute for a branch. `MonthlyCloseCheckLabelsTest` pins
 * the known set.
 */
internal fun checkLabelKey(id: String): StringKey =
    when (id) {
        "zbon_chain_integrity" -> MonthlyCloseKeys.checkZBonChainIntegrity
        "no_bills_outside_zbon" -> MonthlyCloseKeys.checkBillsOutsideZBon
        "vat_consistent" -> MonthlyCloseKeys.checkVatConsistent
        "no_open_bills" -> MonthlyCloseKeys.checkNoOpenBills
        "all_tse_signed" -> MonthlyCloseKeys.checkAllTseSigned
        "all_cash_documented" -> MonthlyCloseKeys.checkAllCashDocumented
        "datev_exported" -> MonthlyCloseKeys.checkDatevExported
        "no_possible_double_sign" -> MonthlyCloseKeys.checkNoPossibleDoubleSign
        "tse_tanr_continuity" -> MonthlyCloseKeys.checkTseTanrContinuity
        "no_simulated_signatures" -> MonthlyCloseKeys.checkNoSimulatedSignatures
        else -> StringKey(id)
    }
