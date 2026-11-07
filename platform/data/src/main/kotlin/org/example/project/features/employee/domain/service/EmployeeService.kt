package org.example.project.features.employee.domain.service

import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmploymentStatus
import java.util.UUID

/**
 * Employee service interface defining business logic operations.
 *
 * Migrated to throws-style in Stage B Batch 10. Methods return their value
 * directly and throw [org.example.project.infrastructure.errors.PosException]
 * on error, except where a nullable lookup is the intended contract.
 */
interface EmployeeService {
    /** Active employees excluding the requesting employee. */
    suspend fun getActiveEmployees(): List<EmployeeDto.EmployeeInfoDto>

    /**
     * Creates a new employee with validation and password hashing.
     *
     * [actorCapabilities] is what the creator holds: the new employee's role group may confer
     * nothing beyond it, so a manager cannot create an administrator.
     */
    suspend fun createEmployee(
        employeeId: String,
        employeeRequest: EmployeeDto.CreateEmployeeRequestDto,
        actorCapabilities: Set<Capability>,
    ): EmployeeDto.EmployeeResponseDto

    /** Retrieves an employee by their unique identifier. */
    suspend fun getEmployeeById(employeeId: UUID): EmployeeDto.EmployeeResponseDto

    /** Retrieves an employee by their username. Returns null if not found. */
    suspend fun getEmployeeByUsername(username: String): EmployeeDto.EmployeeResponseDto?

    /**
     * Retrieves employee summaries with role-based filtering for optimized list views.
     *
     * Filtering by requesting employee's role:
     * - Admin: every employee, admins included (read-only: the write guards on an admin target are
     *   unchanged and enforced by the update/deactivate/credential routes)
     * - Everyone else: the non-management rows (CASHIER and TERMINAL_DEVICE)
     *
     * `displayName` (the HR first + last name) is personnel data: it is filled only when
     * [actorCapabilities] holds `EMPLOYEE_READ`, the capability that reads an employee's HR record.
     */
    suspend fun getEmployeeSummaries(
        requestingEmployeeId: UUID,
        limit: Int = 50,
        offset: Int = 0,
        active: Boolean? = null,
        actorCapabilities: Set<Capability> = emptySet(),
    ): List<EmployeeDto.EmployeeSummaryResponseDto>

    /** Retrieves all employees with optional filtering and pagination. */
    suspend fun getAllEmployees(
        limit: Int = 50,
        offset: Int = 0,
        active: Boolean? = null,
    ): List<EmployeeDto.EmployeeResponseDto>

    /**
     * Updates an existing employee's information with validation.
     *
     * [actorCapabilities] is what the route-authorization plugin resolved for the caller. The route
     * lets anyone edit their OWN record, so fields that are staff management rather than self-service
     * (everything except `pin` and `languagePreference`; `role` has its own guard) are checked against
     * it here — a cashier must not switch themselves into training mode or change their own HR data.
     * An unchanged value passes.
     */
    suspend fun updateEmployee(
        employeeId: UUID,
        updateData: EmployeeDto.UpdateEmployeeRequestDto,
        userId: String,
        actorCapabilities: Set<Capability>,
    ): EmployeeDto.EmployeeResponseDto

    /** Changes the employment status of an employee with optional reason tracking. */
    suspend fun changeEmploymentStatus(
        employeeId: UUID,
        newStatus: EmploymentStatus,
        reason: String?,
    ): Boolean

    /** Deactivates an employee (soft delete), audited as [actorId]'s `DEACTIVATE_EMPLOYEE`. Throws if not found. */
    suspend fun deactivateEmployee(
        employeeId: UUID,
        actorId: String,
    )

    /** Permanently deletes an employee record. Returns the deleted employee's username. */
    suspend fun deleteEmployee(
        employeeId: UUID,
        userId: String,
    ): String

    /**
     * Authenticates an employee with username/employee number and password/PIN.
     * Returns null on failure (kept nullable so AuthRoutes can map to 401).
     */
    suspend fun authenticateEmployee(
        identifier: String,
        secret: String,
    ): EmployeeDto.EmployeeResponseDto?

    /** Records employee login timestamp. */
    suspend fun recordLogin(employeeId: UUID): Boolean

    /** Verifies that an employee exists. */
    suspend fun verifyEmployee(
        employeeId: UUID? = null,
        username: String? = null,
        employeeNumber: String? = null,
    ): Boolean

    /** Checks if at least one admin account exists in the system. */
    suspend fun hasAdminAccount(): Boolean

    /** Updates the language preference for an employee. */
    suspend fun updateEmployeeLanguage(
        employeeId: UUID,
        languageCode: String,
    )

    /** Changes an employee's password after verifying the current password. */
    suspend fun changePassword(
        employeeId: UUID,
        currentPassword: String,
        newPassword: String,
    )

    suspend fun changeMyPin(
        employeeId: UUID,
        newPin: String,
    )

    /** Sets whether an employee is included in the round-robin rotation pool. Throws if not found. */
    suspend fun setRotationPool(
        id: UUID,
        enabled: Boolean,
    ): EmployeeDto.EmployeeResponseDto

    /**
     * Assign an employee to an existing permission group. Throws if the employee or group is unknown.
     *
     * [actorId] is the caller, needed for the two guardrails that depend on who is asking: an actor
     * may not change their own group, and may not confer capabilities they do not themselves hold.
     */
    suspend fun assignPermissionGroup(
        employeeId: UUID,
        groupCode: String,
        actorId: UUID,
    ): EmployeeDto.PermissionGroupAssignmentDto

    /** Manager/admin reset of an employee's password — skips the current-password check and revokes sessions. */
    suspend fun resetPassword(
        employeeId: UUID,
        newPassword: String,
    )

    /** Manager/admin reset of an employee's PIN — revokes sessions. */
    suspend fun resetPin(
        employeeId: UUID,
        newPin: String,
    )
}
