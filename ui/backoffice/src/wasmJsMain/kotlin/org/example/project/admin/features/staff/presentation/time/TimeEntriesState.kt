package org.example.project.admin.features.staff.presentation.time

import org.example.project.core.domain.dto.timetracking.TimeEntryDto
import org.example.project.core.presentation.error.UiError

enum class EntryMode { CREATE, EDIT }

sealed interface TimeEntriesState {
    data object Loading : TimeEntriesState

    data class Error(
        val error: UiError,
    ) : TimeEntriesState

    data class Data(
        val entries: List<TimeEntryDto.TimeEntrySummaryDto>,
        val overview: TimeEntryDto.MonthlyOverviewDto?,
        val year: Int,
        val month: Int,
    ) : TimeEntriesState
}

/** Draft for the create/edit time-entry dialog. */
data class EntryEditor(
    val mode: EntryMode,
    val entryId: String? = null, // edit only
    val employeeId: String,
    val date: String = "", // create only (edit keeps the entry's date server-side)
    val startTime: String = "",
    val endTime: String = "",
    val breakMinutes: String = "0", // create only
    val comment: String = "",
    val saving: Boolean = false,
    val error: UiError? = null,
)
