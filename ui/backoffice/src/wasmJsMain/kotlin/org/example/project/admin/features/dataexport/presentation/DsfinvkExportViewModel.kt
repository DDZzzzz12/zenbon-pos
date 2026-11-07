package org.example.project.admin.features.dataexport.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.data.remote.DsfinvkApiClient
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.utils.FileSaver
import org.example.project.features.settings.presentation.state.DsfinvkExportState
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Drives the DSFinV-K export on Compliance › Datenexport.
 *
 * **Why it exists (T5-5).** The export's only UI lived on the TSE screen, behind `TSE_ADMIN`, while
 * its routes (`DsfinvkRules`) require `DSFINVK_EXPORT` — and the Datenexport menu item was gated on
 * `DSFINVK_EXPORT` without containing the export. An auditor, who holds `DSFINVK_EXPORT` and not
 * `TSE_ADMIN` (the DSFinV-K export is what the group description promises), could not reach it.
 *
 * Deliberately NOT `TseSettingsViewModel`: that one loads the TSE status, info and admin session on
 * construction, all `TSE_ADMIN` routes, so hosting it here would greet exactly this principal with
 * 403s. This view model touches the three `/api/v1/dsfinvk` routes and nothing else, and renders
 * through the same shared-ui `PosDsfinvkExportSection` the TSE screen uses.
 */
@OptIn(ExperimentalTime::class)
class DsfinvkExportViewModel(
    private val dsfinvkApiClient: DsfinvkApiClient,
) : ViewModel() {
    private val log = Logger.withTag("dsfinvk-export")

    var state by mutableStateOf(initialState())
        private set

    var error by mutableStateOf<UiError?>(null)
        private set

    /** Where the last export was saved; non-null only until [clearSaved] acknowledges it. */
    var savedPath by mutableStateOf<String?>(null)
        private set

    fun clearError() {
        error = null
    }

    fun clearSaved() {
        savedPath = null
    }

    fun updateDateRange(
        startDate: String,
        endDate: String,
    ) {
        state = state.copy(startDate = startDate, endDate = endDate, validationResult = null, statistics = null)
    }

    fun toggleIncludeTseTar() {
        state = state.copy(includeTseTar = !state.includeTseTar)
    }

    fun validate() {
        val current = state
        if (!current.hasValidDateRange()) return
        state = state.copy(isLoadingValidation = true)
        viewModelScope.launch {
            runCatchingApi { dsfinvkApiClient.validate(current.startDate, current.endDate) }
                .onSuccess { result -> state = state.copy(validationResult = result, isLoadingValidation = false) }
                .onError { uiError ->
                    log.e { "dsfinvk validate failed for ${current.startDate}..${current.endDate}" }
                    error = uiError
                    state = state.copy(isLoadingValidation = false)
                }
        }
    }

    fun loadStatistics() {
        val current = state
        if (!current.hasValidDateRange()) return
        state = state.copy(isLoadingStatistics = true)
        viewModelScope.launch {
            runCatchingApi { dsfinvkApiClient.getStatistics(current.startDate, current.endDate) }
                .onSuccess { stats -> state = state.copy(statistics = stats, isLoadingStatistics = false) }
                .onError { uiError ->
                    log.e { "dsfinvk statistics failed for ${current.startDate}..${current.endDate}" }
                    error = uiError
                    state = state.copy(isLoadingStatistics = false)
                }
        }
    }

    fun export() {
        val current = state
        if (!current.canExport()) return
        state = state.copy(isExporting = true)
        val request =
            DsfinvkExportDto.ExportRequestDto(
                startDate = current.startDate,
                endDate = current.endDate,
                includeTseTar = current.includeTseTar,
            )
        viewModelScope.launch {
            runCatchingApi { dsfinvkApiClient.exportZip(request) }
                .onSuccess { bytes -> save(bytes, "dsfinvk_${current.startDate}_${current.endDate}.zip") }
                .onError { uiError ->
                    log.e { "dsfinvk export failed for ${current.startDate}..${current.endDate}" }
                    error = uiError
                    state = state.copy(isExporting = false)
                }
        }
    }

    private fun save(
        bytes: ByteArray,
        filename: String,
    ) {
        FileSaver.saveFile(
            data = bytes,
            filename = filename,
            mimeType = "application/zip",
            onSuccess = { path ->
                log.i { "dsfinvk export saved: $path" }
                savedPath = path
                state = state.copy(isExporting = false)
            },
            onError = { cause ->
                log.e { "dsfinvk export could not be saved: $cause" }
                state = state.copy(isExporting = false)
            },
        )
    }

    /** First of the current month to today — the same default the TSE screen's section opens with. */
    private fun initialState(): DsfinvkExportState {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val month = twoDigits(now.month.number)
        val day = twoDigits(now.day)
        return DsfinvkExportState(startDate = "${now.year}-$month-01", endDate = "${now.year}-$month-$day")
    }

    private fun twoDigits(value: Int): String = value.toString().padStart(2, '0')
}
