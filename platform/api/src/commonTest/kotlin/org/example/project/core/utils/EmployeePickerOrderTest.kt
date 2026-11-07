package org.example.project.core.utils

import org.example.project.core.domain.dto.employee.EmployeeDto
import kotlin.test.Test
import kotlin.test.assertEquals

class EmployeePickerOrderTest {
    private fun employee(
        number: String,
        username: String,
    ) = EmployeeDto.EmployeeSummaryResponseDto(
        id = "$number-$username",
        username = username,
        employeeNumber = number,
        role = "CASHIER",
        employmentStatus = "ACTIVE",
        isActive = true,
        hasEmployeeInfo = false,
    )

    @Test
    fun numericOrderNotLexicographic() {
        val sorted = sortEmployeesForPicker(listOf(employee("10", "a"), employee("2", "b"), employee("1", "c")))
        assertEquals(listOf("1", "2", "10"), sorted.map { it.employeeNumber })
    }

    @Test
    fun nonNumericNumbersGoLast() {
        val sorted = sortEmployeesForPicker(listOf(employee("X-1", "a"), employee("5", "b")))
        assertEquals(listOf("5", "X-1"), sorted.map { it.employeeNumber })
    }

    @Test
    fun prefixedNumbersSortNaturally() {
        val sorted =
            sortEmployeesForPicker(
                listOf(employee("K10", "a"), employee("K2", "b"), employee("A1", "c"), employee("K01", "d")),
            )
        assertEquals(listOf("A1", "K01", "K2", "K10"), sorted.map { it.employeeNumber })
    }

    @Test
    fun plainNumbersPrecedePrefixedOnes() {
        val sorted = sortEmployeesForPicker(listOf(employee("K1", "a"), employee("12", "b"), employee("MGR", "c")))
        assertEquals(listOf("12", "K1", "MGR"), sorted.map { it.employeeNumber })
    }

    @Test
    fun tiesBreakByUsername() {
        val sorted = sortEmployeesForPicker(listOf(employee("3", "zed"), employee("3", "amy")))
        assertEquals(listOf("amy", "zed"), sorted.map { it.username })
    }
}
