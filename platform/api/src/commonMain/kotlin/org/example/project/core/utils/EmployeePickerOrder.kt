package org.example.project.core.utils

import org.example.project.core.domain.dto.employee.EmployeeDto

/**
 * The employee number split into its letter prefix and numeric tail, so `K2` sorts before `K10`
 * and `2` before `10`. A purely numeric number has an empty prefix and therefore sorts before any
 * prefixed one; a number without a numeric tail sorts by its text alone.
 */
private data class EmployeeNumberKey(
    val prefix: String,
    val tail: Long,
) : Comparable<EmployeeNumberKey> {
    override fun compareTo(other: EmployeeNumberKey): Int = compareValuesBy(this, other, { it.prefix }, { it.tail })
}

private fun employeeNumberKey(employeeNumber: String): EmployeeNumberKey {
    val trimmed = employeeNumber.trim()
    val digits = trimmed.takeLastWhile { it.isDigit() }
    return EmployeeNumberKey(
        prefix = trimmed.dropLast(digits.length).lowercase(),
        tail = digits.toLongOrNull() ?: -1L,
    )
}

/**
 * Employees in picker order — the ONE roster order of every employee picker: by employee number,
 * naturally (`2` < `10`, `K2` < `K10`, plain numbers before prefixed ones), ties by username.
 */
fun sortEmployeesForPicker(employees: List<EmployeeDto.EmployeeSummaryResponseDto>): List<EmployeeDto.EmployeeSummaryResponseDto> =
    employees.sortedWith(
        compareBy<EmployeeDto.EmployeeSummaryResponseDto> { employeeNumberKey(it.employeeNumber) }
            .thenBy { it.username },
    )
