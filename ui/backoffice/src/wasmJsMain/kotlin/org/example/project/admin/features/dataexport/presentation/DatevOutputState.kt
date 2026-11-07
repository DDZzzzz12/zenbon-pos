package org.example.project.admin.features.dataexport.presentation

import kotlinx.datetime.LocalDate
import org.example.project.core.domain.dto.datev.UnassignedSummary
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.presentation.error.UiError

/**
 * The DATEV output surface: which period's export log is listed, and the ad-hoc dialog if one is
 * open. There is no close state to hold — this is a trigger and a receipt list.
 */
data class DatevOutputState(
    val rangeStart: LocalDate,
    val rangeEnd: LocalDate,
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val log: List<DatevExportLogDto> = emptyList(),
    val adHocDialog: AdHocExportDialogState? = null,
    /**
     * When the Ausgangsrechnungen (DATEV Debitoren) preflight finds invoices with no resolvable
     * account, their numbers land here and the ZIP is NOT downloaded. Null = no block to show.
     */
    val arBlockedInvoiceNumbers: List<String>? = null,
    /**
     * The notices the last ad-hoc export reported (e.g. Buchungsstapel rows whose Beleg date lies
     * outside the period, or a batch spanning two Wirtschaftsjahre). The file still downloaded; these
     * are what the operator has to settle with the accountant. Empty = nothing to show.
     */
    val exportWarnings: List<String> = emptyList(),
    /**
     * Per export type of the last ad-hoc run (`BUCHUNGSSTAPEL`, `KASSENBUCH`, `AR_LEDGER`, …): the
     * lines it wrote without a contra account because their mapping cell's „Konto automatisch
     * zuordnen" switch is off. The file still downloaded; the Steuerberater must import with
     * „Buchungen trotz Fehler importieren" and complete the accounts. Empty = nothing to show.
     */
    val unassignedContra: List<Pair<String, UnassignedSummary>> = emptyList(),
)
