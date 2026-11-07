/**
 * Mapper functions for EmployeeInfo entity conversions.
 *
 * Provides mapping between EmployeeInfo entities and DTOs for API communication.
 * Handles data transformation, validation, and type conversions.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.employee.mapper

import kotlinx.datetime.LocalDate
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.features.employee.domain.models.EmployeeInfo
import java.math.BigDecimal

/**
 * Converts an EmployeeInfo entity to response DTO.
 *
 * @param employeeInfo The EmployeeInfo entity to convert
 * @return EmployeeInfoResponseDto with all employee information
 */
fun EmployeeInfo.toResponseDto(): EmployeeInfoDto.EmployeeInfoResponseDto =
    EmployeeInfoDto.EmployeeInfoResponseDto(
        id = id.value,
        employeeId = employee.id.value.toString(),
        firstName = firstName,
        lastName = lastName,
        fullName = fullName,
        email = email,
        phoneNumber = phoneNumber,
        street = street,
        city = city,
        state = state,
        zipCode = zipCode,
        country = country,
        dateOfBirth = dateOfBirth?.toString(),
        hireDate = hireDate?.toString(),
        jobTitle = jobTitle,
        hourlyRate = hourlyRate?.toString(),
        salary = salary?.toString(),
        contractedHours = contractedHours?.toString(),
        contractedHoursPeriod = contractedHoursPeriod,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

/**
 * Converts an EmployeeInfo entity to summary DTO.
 *
 * @param employeeInfo The EmployeeInfo entity to convert
 * @return EmployeeInfoSummaryDto with essential information
 */
fun EmployeeInfo.toSummaryDto(): EmployeeInfoDto.EmployeeInfoSummaryDto =
    EmployeeInfoDto.EmployeeInfoSummaryDto(
        id = id.value,
        employeeId = employee.id.value.toString(),
        fullName = fullName,
        email = email,
        jobTitle = jobTitle,
        hireDate = hireDate?.toString(),
    )

/**
 * Helper function to safely parse LocalDate from string.
 *
 * @param dateString The date string to parse (YYYY-MM-DD format)
 * @return LocalDate object or null if parsing fails
 */
fun String?.toLocalDateOrNull(): LocalDate? =
    try {
        if (this.isNullOrBlank()) null else LocalDate.parse(this)
    } catch (e: Exception) {
        null
    }

/**
 * Helper function to safely parse BigDecimal from string.
 *
 * @param numberString The number string to parse
 * @return BigDecimal object or null if parsing fails
 */
fun String?.toBigDecimalOrNull(): BigDecimal? =
    try {
        if (this.isNullOrBlank()) null else BigDecimal(this)
    } catch (e: Exception) {
        null
    }
