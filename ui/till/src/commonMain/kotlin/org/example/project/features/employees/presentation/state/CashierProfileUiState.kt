package org.example.project.features.employees.presentation.state

import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.dto.employee.WorkScheduleDto
import org.example.project.core.domain.dto.timetracking.TimeEntryDto
import org.example.project.core.presentation.error.UiError

data class CashierProfileUiState(
    val employee: EmployeeDto.EmployeeResponseDto? = null,
    val employeeInfo: EmployeeInfoDto.EmployeeInfoResponseDto? = null,
    val schedule: WorkScheduleDto.WeeklyScheduleResponseDto? = null,
    val overview: TimeEntryDto.MonthlyOverviewDto? = null,
    val entries: List<TimeEntryDto.TimeEntrySummaryDto> = emptyList(),
    val showEditProfileDialog: Boolean = false,
    val showEntries: Boolean = false,
    val credentialChangeMode: CredentialChangeMode? = null,
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val successMessage: String? = null,
)
