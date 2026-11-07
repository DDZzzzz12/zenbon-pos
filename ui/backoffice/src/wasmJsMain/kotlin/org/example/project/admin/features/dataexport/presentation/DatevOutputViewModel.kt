package org.example.project.admin.features.dataexport.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.data.remote.DatevExportApiClient
import org.example.project.core.data.remote.DatevExportLogApiClient
import org.example.project.core.data.remote.SalesInvoiceApiClient
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.utils.FileSaver
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Drives Einstellungen → Datenexport's DATEV output surface: the five ad-hoc export kinds, and the
 * log of what has already been handed over.
 *
 * The log comes from `GET /api/v1/datev/export-log` rather than off the monthly-close payload the
 * till used — the same rows, without computing an entire period close to list them. The catch-up
 * endpoints are deliberately unused: the till's catch-up dialog was never reachable (nothing ever
 * set its state), and the scheduled job calls `DatevExportCatchupService` directly.
 */
@OptIn(ExperimentalTime::class)
class DatevOutputViewModel(
    private val datevExportApiClient: DatevExportApiClient,
    private val datevExportLogApiClient: DatevExportLogApiClient,
    private val salesInvoiceApiClient: SalesInvoiceApiClient,
) : ViewModel() {
    private val log = Logger.withTag("datev-output")

    var state by mutableStateOf(DatevOutputState(rangeStart = defaultStart(), rangeEnd = defaultEnd()))
        private set

    fun clearError() {
        state = state.copy(error = null)
    }

    fun setRange(
        start: LocalDate,
        end: LocalDate,
    ) {
        state = state.copy(rangeStart = start, rangeEnd = end)
        load()
    }

    fun load() {
        val start = LocalDateTime(state.rangeStart, LocalTime(0, 0)).toString()
        // Half-open, matching every other period query in this codebase.
        val end = LocalDateTime(state.rangeEnd.plus(DatePeriod(days = 1)), LocalTime(0, 0)).toString()
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi {
                datevExportLogApiClient.findOverlapping(periodStart = start, periodEnd = end)
            }.onSuccess { entries ->
                log.d { "datev export log: ${entries.size} entries for $start..$end" }
                state = state.copy(isLoading = false, log = entries)
            }.onError(retry = { load() }) { uiError ->
                log.e { "datev export log load failed for $start..$end" }
                state = state.copy(isLoading = false, error = uiError)
            }
        }
    }

    /** Opens the ad-hoc dialog for [type], seeded with the period currently listed. */
    fun startExport(type: String) {
        state =
            state.copy(
                arBlockedInvoiceNumbers = null,
                exportWarnings = emptyList(),
                unassignedContra = emptyList(),
                adHocDialog =
                    AdHocExportDialogState(
                        type = type,
                        startDate = state.rangeStart,
                        endDate = state.rangeEnd,
                    ),
            )
    }

    /** Dismisses the notices of the last ad-hoc export. */
    fun dismissExportWarnings() {
        state = state.copy(exportWarnings = emptyList())
    }

    /** Dismisses the "exported without a contra account" notice of the last ad-hoc export. */
    fun dismissUnassignedContra() {
        state = state.copy(unassignedContra = emptyList())
    }

    /** Dismisses the Ausgangsrechnungen preflight block banner. */
    fun dismissArBlocked() {
        state = state.copy(arBlockedInvoiceNumbers = null)
    }

    fun updateAdHocStart(date: LocalDate) {
        val dialog = state.adHocDialog ?: return
        val end = if (dialog.endDate < date) date else dialog.endDate
        state = state.copy(adHocDialog = dialog.copy(startDate = date, endDate = end))
    }

    fun updateAdHocEnd(date: LocalDate) {
        val dialog = state.adHocDialog ?: return
        state = state.copy(adHocDialog = dialog.copy(endDate = date))
    }

    fun updateAdHocExcludeKassenbuch(exclude: Boolean) {
        val dialog = state.adHocDialog ?: return
        state = state.copy(adHocDialog = dialog.copy(excludeKassenbuchLinked = exclude))
    }

    fun dismissAdHocDialog() {
        state = state.copy(adHocDialog = null)
    }

    /**
     * Runs the dialog's export. `ALL` fans out to the three scheduled kinds in a fixed order and
     * stops at the first failure, so a half-written set is never presented as complete;
     * `BUCHUNGSSTAPEL_MIT_BELEGEN` is ad-hoc only and is therefore not part of the fan-out.
     */
    fun confirmAdHocExport() {
        val dialog = state.adHocDialog ?: return
        if (!dialog.isValid) return
        if (dialog.type == "AR") {
            confirmArExport(dialog)
            return
        }
        val start = dialog.startDate.toString()
        val end = dialog.endDate.toString()
        val types =
            if (dialog.type == "ALL") {
                listOf("KASSENBUCH", "AP_LEDGER", "BUCHUNGSSTAPEL")
            } else {
                listOf(dialog.type)
            }
        state = state.copy(adHocDialog = dialog.copy(isRunning = true))
        viewModelScope.launch {
            var failed = false
            for (type in types) {
                if (failed) break
                runCatchingApi {
                    datevExportApiClient.exportAdHoc(
                        type = type,
                        startDate = start,
                        endDate = end,
                        excludeKassenbuchLinked = dialog.excludeKassenbuchLinked,
                    )
                }.onSuccess { download ->
                    if (download.warnings.isNotEmpty()) {
                        log.w { "ad-hoc $type export for $start..$end reported ${download.warnings.size} notice(s)" }
                        state = state.copy(exportWarnings = state.exportWarnings + download.warnings)
                    }
                    download.unassignedContra?.let { summary ->
                        log.i { "ad-hoc $type export for $start..$end left ${summary.lineCount} line(s) without a contra account" }
                        state = state.copy(unassignedContra = state.unassignedContra + (type to summary))
                    }
                    val name = download.fileName ?: fallbackFileName(type, start, end)
                    FileSaver.saveFile(
                        data = download.bytes,
                        filename = name,
                        mimeType = download.contentType ?: mimeTypeFor(name),
                    )
                }.onError { uiError ->
                    log.e { "ad-hoc $type export failed for $start..$end" }
                    state = state.copy(error = uiError)
                    failed = true
                }
            }
            state = state.copy(adHocDialog = null)
            if (!failed) load()
        }
    }

    /**
     * The Ausgangsrechnungen (DATEV Debitoren) export. Runs the preflight FIRST: if any invoice has
     * no resolvable account, the numbers are surfaced and the ZIP is NOT downloaded (the export route
     * would 409 anyway) so the operator fixes them before retrying. Otherwise the ZIP downloads.
     */
    private fun confirmArExport(dialog: AdHocExportDialogState) {
        val start = dialog.startDate.toString()
        val end = dialog.endDate.toString()
        state = state.copy(adHocDialog = dialog.copy(isRunning = true), arBlockedInvoiceNumbers = null, unassignedContra = emptyList())
        viewModelScope.launch {
            runCatchingApi { salesInvoiceApiClient.datevExportStatistics(start, end) }
                .onSuccess { stats ->
                    if (stats.unresolved.isNotEmpty()) {
                        log.i { "AR export blocked: ${stats.unresolved.size} unresolved invoice(s) for $start..$end" }
                        state =
                            state.copy(
                                adHocDialog = null,
                                arBlockedInvoiceNumbers = stats.unresolved.map { it.invoiceNumber },
                            )
                    } else {
                        stats.unassignedContra?.let { summary ->
                            state = state.copy(unassignedContra = state.unassignedContra + ("AR_LEDGER" to summary))
                        }
                        downloadArExport(start, end)
                    }
                }.onError { uiError ->
                    log.e { "AR export preflight failed for $start..$end" }
                    state = state.copy(adHocDialog = null, error = uiError)
                }
        }
    }

    /** Downloads the AR ZIP once the preflight is clear. Separate so the happy path reads plainly. */
    private suspend fun downloadArExport(
        start: String,
        end: String,
    ) {
        runCatchingApi { salesInvoiceApiClient.datevExport(start, end) }
            .onSuccess { bytes ->
                FileSaver.saveFile(
                    data = bytes,
                    filename = "datev-ar-${start}_$end.zip",
                    mimeType = "application/zip",
                )
                state = state.copy(adHocDialog = null)
                load()
            }.onError { uiError ->
                log.e { "AR export download failed for $start..$end" }
                state = state.copy(adHocDialog = null, error = uiError)
            }
    }

    fun downloadFromLog(
        logId: Long,
        fileName: String,
    ) {
        viewModelScope.launch {
            runCatchingApi { datevExportLogApiClient.downloadExport(logId) }
                .onSuccess { bytes ->
                    FileSaver.saveFile(data = bytes, filename = fileName, mimeType = mimeTypeFor(fileName))
                }.onError { uiError ->
                    log.e { "download of export log $logId failed" }
                    state = state.copy(error = uiError)
                }
        }
    }

    private fun fallbackFileName(
        type: String,
        start: String,
        end: String,
    ): String =
        when (type) {
            "KASSENBUCH" -> "datev_xml_kassenbuch_${start}_$end.zip"
            "AP_LEDGER" -> "datev_xml_ap_${start}_$end.zip"
            "BUCHUNGSSTAPEL" -> "datev_buchungsstapel_${start}_$end.csv"
            "BUCHUNGSSTAPEL_MIT_BELEGEN" -> "datev_buchungsstapel_belege_${start}_$end.zip"
            else -> "datev_export_${start}_$end.zip"
        }

    private fun mimeTypeFor(filename: String): String =
        when (filename.substringAfterLast('.', "").lowercase()) {
            "csv" -> "text/csv"
            "zip" -> "application/zip"
            "xml" -> "application/xml"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }

    private companion object {
        /** The month just gone — the period an operator is almost always handing over. */
        fun defaultStart(): LocalDate {
            val today =
                Clock.System
                    .now()
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .date
            return LocalDate(today.year, today.month, 1).minus(DatePeriod(months = 1))
        }

        /** The last day of the month just gone — the day before the first of this month. */
        fun defaultEnd(): LocalDate {
            val today =
                Clock.System
                    .now()
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .date
            return LocalDate(today.year, today.month, 1).minus(DatePeriod(days = 1))
        }
    }
}
