/**
 * Employee entity to DTO mapping functions.
 *
 * Provides conversion methods for Employee entities to their corresponding DTOs
 * for API responses and data transfer operations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.employee.mapper

import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.features.employee.domain.models.Employee

/**
 * Converts Employee entity to EmployeeResponseDto.
 *
 * [permissionGroupCode] has **no default** on purpose: it is the authority for what this employee
 * may reach, and a defaulted null would let a call site quietly report "belongs to no group" —
 * which reads as "can reach nothing" — simply by forgetting to pass it. Resolve it with
 * `PermissionGroupRepository.groupCodesOf`, batched for a list.
 */
fun Employee.toEmployeeResponse(permissionGroupCode: String?): EmployeeDto.EmployeeResponseDto =
    EmployeeDto.EmployeeResponseDto(
        id = this.id.value.toString(),
        username = this.username,
        employeeNumber = this.employeeNumber,
        lastName = this.employeeInfo?.lastName,
        employmentStatus = this.employmentStatus.name,
        role = this.role.name,
        // Always empty since Stage 2a retired per-employee grants. The DTO field stays on the wire
        // so an un-upgraded client still deserialises; capabilities now come from the permission
        // group and reach the client through /api/auth/me (track C2).
        permissionGroupCode = permissionGroupCode,
        isActive = this.isActive,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt,
        lastLogin = this.lastLogin,
        languagePreference = this.languagePreference,
        notes = this.notes,
        pin = null, // Never expose PIN in responses for security
        hasEmployeeInfo = this.employeeInfo != null,
        trainingMode = this.trainingMode,
        personnelNumber = this.personnelNumber,
        defaultCostCenterId = this.defaultCostCenterId?.value?.toString(),
        inRotationPool = this.inRotationPool,
    )

/**
 * Converts an Employee to the short [EmployeeDto.EmployeeInfoDto] (number, id, a name).
 *
 * `lastName` is the personnel record's last name only when [hrLastName] is set; by default it is the
 * username, so a new call site cannot hand personnel data to a caller who may not read it (UI review
 * follow-up 7, the `EmployeeHrNames.visibleTo` rule). A caller that sets it says why it may.
 */
fun Employee.toInfoDto(
    isConnected: Boolean = false,
    hrLastName: Boolean = false,
): EmployeeDto.EmployeeInfoDto =
    EmployeeDto.EmployeeInfoDto(
        employeeNumber = this.employeeNumber,
        employeeId = this.id.value.toString(),
        lastName = (if (hrLastName) this.employeeInfo?.lastName else null) ?: username,
        isConnected = isConnected,
    )

/**
 * Converts Employee entity to EmployeeSummaryResponseDto.
 *
 * [displayName] is the HR first + last name, resolved by the caller in one batch (and only for a
 * caller who may read HR data) — this mapper does no lookup of its own, so it cannot become N+1.
 */
fun Employee.toSummaryResponse(displayName: String? = null): EmployeeDto.EmployeeSummaryResponseDto =
    EmployeeDto.EmployeeSummaryResponseDto(
        id = this.id.value.toString(),
        username = this.username,
        employeeNumber = this.employeeNumber,
        role = this.role.name,
        employmentStatus = this.employmentStatus.name,
        isActive = this.isActive,
        hasEmployeeInfo = this.employeeInfo != null,
        trainingMode = this.trainingMode,
        inRotationPool = this.inRotationPool,
        displayName = displayName,
    )
