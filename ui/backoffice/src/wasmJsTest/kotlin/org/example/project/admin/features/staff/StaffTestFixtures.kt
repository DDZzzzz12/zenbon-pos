package org.example.project.admin.features.staff

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.enums.employee.ContractedHoursPeriod

private val T = LocalDateTime(2026, 7, 12, 9, 0)

fun summary(
    id: String,
    username: String,
    role: String,
    active: Boolean = true,
    displayName: String? = null,
) = EmployeeDto.EmployeeSummaryResponseDto(
    id = id,
    username = username,
    employeeNumber = "E$id",
    role = role,
    employmentStatus = if (active) "ACTIVE" else "INACTIVE",
    isActive = active,
    hasEmployeeInfo = displayName != null,
    displayName = displayName,
)

fun employeeResponse(
    id: String,
    username: String,
    role: String,
    active: Boolean = true,
    permissionGroupCode: String? = null,
) = EmployeeDto.EmployeeResponseDto(
    id = id,
    username = username,
    employeeNumber = "E$id",
    lastName = null,
    employmentStatus = if (active) "ACTIVE" else "INACTIVE",
    role = role,
    permissionGroupCode = permissionGroupCode,
    isActive = active,
    createdAt = T,
    updatedAt = T,
    lastLogin = null,
    languagePreference = "de",
    notes = null,
    pin = null,
    hasEmployeeInfo = false,
    trainingMode = false,
    personnelNumber = null,
    defaultCostCenterId = null,
    inRotationPool = false,
)

fun employeeInfoResponse(
    employeeId: String,
    first: String = "Ada",
    last: String = "Lovelace",
) = EmployeeInfoDto.EmployeeInfoResponseDto(
    id = 1,
    employeeId = employeeId,
    firstName = first,
    lastName = last,
    fullName = "$first $last",
    email = "ada@example.com",
    phoneNumber = "+49 30 123456",
    street = "Hauptstr. 1",
    city = "Berlin",
    state = "BE",
    zipCode = "10115",
    country = "DE",
    dateOfBirth = "1815-12-10",
    hireDate = "2026-01-15",
    jobTitle = "Serviceleitung",
    hourlyRate = "18.50",
    salary = "3200.00",
    contractedHours = "38.5",
    contractedHoursPeriod = ContractedHoursPeriod.WEEKLY,
    createdAt = "2026-01-15T09:00:00",
    updatedAt = "2026-01-15T09:00:00",
)

fun permissionGroup(
    code: String,
    isSystem: Boolean = false,
    capabilities: Set<String> = setOf("SELF_READ"),
    memberCount: Int = 0,
) = org.example.project.core.domain.dto.employee.PermissionGroupDto.PermissionGroupResponseDto(
    code = code,
    name = code.lowercase(),
    description = null,
    isSystem = isSystem,
    isDeletable = !isSystem,
    capabilities = capabilities,
    memberCount = memberCount,
)
