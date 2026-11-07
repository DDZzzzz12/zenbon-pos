package org.example.project.features.employee.presentation

import org.example.project.core.domain.dto.employee.EmployeeDto

// Roster ORDER lives in shared `core.utils.sortEmployeesForPicker` (one definition for every picker).

/** One picker row: the employee number stays visible, then the HR name (else the login name). */
fun EmployeeDto.EmployeeSummaryResponseDto.pickerLabel(): String = "$employeeNumber · ${shownName()}"
