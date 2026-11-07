package org.example.project.features.employee

import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.features.employee.presentation.pickerLabel
import kotlin.test.Test
import kotlin.test.assertEquals

private fun row(
    number: String,
    username: String = "u$number",
    displayName: String? = null,
) = EmployeeDto.EmployeeSummaryResponseDto(
    id = "id-$number-$username",
    username = username,
    employeeNumber = number,
    role = "CASHIER",
    employmentStatus = "ACTIVE",
    isActive = true,
    hasEmployeeInfo = displayName != null,
    displayName = displayName,
)

class EmployeePickerRosterTest {
    @Test
    fun label_showsNumberAndHrNameElseUsername() {
        assertEquals("K001 · Klara Kern", row("K001", displayName = "Klara Kern").pickerLabel())
        assertEquals("K002 · uK002", row("K002").pickerLabel())
    }
}
