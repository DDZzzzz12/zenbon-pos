package org.example.project.admin.features.dataexport.presentation

import kotlinx.datetime.LocalDate

/**
 * The open ad-hoc export dialog. [type] is the wire value the endpoint takes:
 * KASSENBUCH | AP_LEDGER | BUCHUNGSSTAPEL | BUCHUNGSSTAPEL_MIT_BELEGEN | ALL.
 *
 * [excludeKassenbuchLinked] only applies to the AP ledger; the dialog hides the toggle otherwise.
 */
data class AdHocExportDialogState(
    val type: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val isRunning: Boolean = false,
    val excludeKassenbuchLinked: Boolean = true,
) {
    /** An inverted range is refused client-side; the server would reject it anyway. */
    val isValid: Boolean get() = endDate >= startDate
}
