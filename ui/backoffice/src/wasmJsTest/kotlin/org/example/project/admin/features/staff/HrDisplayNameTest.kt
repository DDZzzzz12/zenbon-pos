package org.example.project.admin.features.staff

import org.example.project.admin.features.staff.presentation.hr.hrDisplayName
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HrDisplayNameTest {
    private fun info(
        first: String,
        last: String,
    ) = EmployeeInfoDto.EmployeeInfoResponseDto(
        id = 1,
        employeeId = "e1",
        firstName = first,
        lastName = last,
        fullName = "$first $last",
        email = null,
        phoneNumber = null,
        street = null,
        city = null,
        state = null,
        zipCode = null,
        country = null,
        dateOfBirth = null,
        hireDate = null,
        jobTitle = null,
        hourlyRate = null,
        salary = null,
        contractedHours = null,
        contractedHoursPeriod = null,
        createdAt = "2026-01-01T00:00:00",
        updatedAt = "2026-01-01T00:00:00",
    )

    @Test
    fun joinsFirstAndLastName() {
        assertEquals("Anna Beispiel", info("Anna", "Beispiel").hrDisplayName())
    }

    @Test
    fun noRecordMeansNoName() {
        assertNull((null as EmployeeInfoDto.EmployeeInfoResponseDto?).hrDisplayName())
    }

    @Test
    fun blankNamesMeanNoName() {
        assertNull(info(" ", "").hrDisplayName())
    }
}
