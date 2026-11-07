package org.example.project.admin.features.staff.presentation.time

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.remote.TimeTrackingApiClient
import org.example.project.core.domain.dto.timetracking.TimeEntryDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.CommonKeys

class TimeEntriesViewModel(
    private val timeApi: TimeTrackingApiClient,
) : ViewModel() {
    private val log = Logger.withTag("timeentries")
    private var employeeId: String? = null

    var state by mutableStateOf<TimeEntriesState>(TimeEntriesState.Loading)
        private set
    var entryEditor by mutableStateOf<EntryEditor?>(null)
        private set

    fun load(
        employeeId: String,
        year: Int,
        month: Int,
    ) {
        this.employeeId = employeeId
        entryEditor = null
        state = TimeEntriesState.Loading
        viewModelScope.launch { fetch(employeeId, year, month) }
    }

    private suspend fun fetch(
        employeeId: String,
        year: Int,
        month: Int,
    ) {
        runCatchingApi {
            coroutineScope {
                val entries = async { timeApi.getEntriesByEmployeeId(employeeId, year, month) }
                val overview = async { runCatchingOverview(employeeId, year, month) }
                TimeEntriesState.Data(
                    entries = entries.await(),
                    overview = overview.await(),
                    year = year,
                    month = month,
                )
            }
        }.onSuccess { state = it }
            .onError { uiError ->
                log.e { "load time entries failed" }
                state = TimeEntriesState.Error(uiError)
            }
    }

    // overview is best-effort; a missing overview shouldn't blank the whole section
    private suspend fun runCatchingOverview(
        employeeId: String,
        year: Int,
        month: Int,
    ): TimeEntryDto.MonthlyOverviewDto? =
        try {
            timeApi.getMonthlyOverviewByEmployeeId(employeeId, year, month)
        } catch (
            e: CancellationException,
        ) {
            throw e
        } catch (e: Throwable) {
            log.w { "overview load failed" }
            null
        }

    private fun reload() {
        val id = employeeId ?: return
        val d = state as? TimeEntriesState.Data ?: return
        viewModelScope.launch { fetch(id, d.year, d.month) }
    }

    fun prevMonth() = shiftMonth(-1)

    fun nextMonth() = shiftMonth(+1)

    private fun shiftMonth(delta: Int) {
        val id = employeeId ?: return
        val d = state as? TimeEntriesState.Data ?: return
        var y = d.year
        var m = d.month + delta
        if (m < 1) {
            m = 12
            y -= 1
        } else if (m > 12) {
            m = 1
            y += 1
        }
        state = TimeEntriesState.Loading
        viewModelScope.launch { fetch(id, y, m) }
    }

    fun openCreate(today: String) {
        val id = employeeId ?: return
        entryEditor = EntryEditor(mode = EntryMode.CREATE, employeeId = id, date = today)
    }

    fun openEdit(summary: TimeEntryDto.TimeEntrySummaryDto) {
        val id = employeeId ?: return
        entryEditor =
            EntryEditor(
                mode = EntryMode.EDIT,
                entryId = summary.id,
                employeeId = id,
                date = summary.date,
                startTime = summary.clockIn,
                endTime =
                    summary.clockOut ?: "",
            )
    }

    fun onEntryField(block: (EntryEditor) -> EntryEditor) {
        entryEditor = entryEditor?.let(block)
    }

    fun closeEntry() {
        entryEditor = null
    }

    private val timeRegex = Regex("([01]\\d|2[0-3]):[0-5]\\d")

    private fun invalidEntryError(): UiError =
        UiError.Unknown(
            cause = null,
            titleKey = CommonKeys.unknownErrorTitle,
            messageKey = AdminStringKeys.TimeErrorInvalid,
        )

    fun saveEntry() {
        val e = entryEditor ?: return
        val invalid = invalidEntryError()
        if (!timeRegex.matches(e.startTime) || !timeRegex.matches(e.endTime)) {
            entryEditor = e.copy(error = invalid)
            return
        }
        if (e.mode == EntryMode.CREATE && e.date.isBlank()) {
            entryEditor = e.copy(error = invalid)
            return
        }
        val br =
            if (e.mode ==
                EntryMode.CREATE
            ) {
                (
                    e.breakMinutes.ifBlank { "0" }.toIntOrNull() ?: run {
                        entryEditor = e.copy(error = invalid)
                        return
                    }
                )
            } else {
                0
            }
        entryEditor = e.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatchingApi {
                if (e.mode == EntryMode.CREATE) {
                    timeApi.createManualEntry(
                        TimeEntryDto.ManualEntryRequestDto(
                            employeeId = e.employeeId,
                            date = e.date,
                            startTime = e.startTime,
                            endTime = e.endTime,
                            breakMinutes = br,
                            comment = e.comment.ifBlank { null },
                        ),
                    )
                } else {
                    timeApi.updateEntry(
                        e.entryId!!,
                        TimeEntryDto.UpdateEntryRequestDto(
                            startTime = e.startTime,
                            endTime = e.endTime,
                            comment = e.comment.ifBlank { null },
                        ),
                    )
                }
            }.onSuccess {
                log.d { "time entry ${if (e.mode == EntryMode.CREATE) "created" else "updated"} for ${e.employeeId}" }
                entryEditor = null
                reload()
            }.onError { uiError ->
                log.e { "save time entry failed" }
                entryEditor = entryEditor?.copy(saving = false, error = uiError)
            }
        }
    }

    fun deleteEntry(id: String) {
        viewModelScope.launch {
            try {
                timeApi.deleteEntry(id)
                log.d { "time entry $id deleted" }
                reload()
            } catch (
                ex: CancellationException,
            ) {
                throw ex
            } catch (ex: Throwable) {
                log.e(ex) { "delete time entry $id failed" }
            }
        }
    }
}
