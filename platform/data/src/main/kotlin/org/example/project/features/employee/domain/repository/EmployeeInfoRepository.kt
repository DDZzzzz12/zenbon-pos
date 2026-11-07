package org.example.project.features.employee.domain.repository

import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.features.employee.domain.models.EmployeeInfo
import java.util.UUID

/**
 * Repository interface for EmployeeInfo operations.
 *
 * Handles CRUD operations for employee personal and employment information,
 * maintaining separation from core employee authentication data.
 * Returns plain entities; exceptions propagate naturally to the service layer.
 */
interface EmployeeInfoRepository {
    /**
     * Creates new employee information record.
     *
     * @param request The employee information data to create
     * @return Created EmployeeInfo entity
     */
    fun createEmployeeInfo(request: EmployeeInfoDto.CreateEmployeeInfoRequestDto): EmployeeInfo

    /**
     * Retrieves employee information by employee info ID.
     *
     * @param employeeInfoId The employee info record ID
     * @return EmployeeInfo or null if not found
     */
    fun getEmployeeInfoById(employeeInfoId: Int): EmployeeInfo?

    /**
     * Retrieves employee information by employee ID.
     *
     * @param employeeId The employee UUID
     * @return EmployeeInfo or null if not found
     */
    fun getEmployeeInfoByEmployeeId(employeeId: UUID): EmployeeInfo?

    /**
     * Updates existing employee information.
     *
     * @param employeeInfoId The employee info record ID to update
     * @param updateData The updated employee information data
     * @return Updated EmployeeInfo or null if not found
     */
    fun updateEmployeeInfo(
        employeeInfoId: Int,
        updateData: EmployeeInfoDto.UpdateEmployeeInfoRequestDto,
    ): EmployeeInfo?

    /**
     * Updates employee information by employee ID.
     *
     * @param employeeId The employee UUID
     * @param updateData The updated employee information data
     * @return Updated EmployeeInfo or null if not found
     */
    fun updateEmployeeInfoByEmployeeId(
        employeeId: UUID,
        updateData: EmployeeInfoDto.UpdateEmployeeInfoRequestDto,
    ): EmployeeInfo?

    /**
     * Deletes employee information record.
     *
     * @param employeeInfoId The employee info record ID to delete
     * @throws NoSuchElementException if not found
     */
    fun deleteEmployeeInfo(employeeInfoId: Int)

    /**
     * Deletes employee information by employee ID.
     *
     * @param employeeId The employee UUID
     * @throws NoSuchElementException if not found
     */
    fun deleteEmployeeInfoByEmployeeId(employeeId: UUID)

    /**
     * Retrieves all employee information records with pagination.
     *
     * @param limit Maximum number of records to return
     * @param offset Number of records to skip
     * @return List of EmployeeInfo records
     */
    fun getAllEmployeeInfo(
        limit: Int = 50,
        offset: Int = 0,
    ): List<EmployeeInfo>

    /**
     * Searches employee information by name.
     *
     * @param searchTerm Search term to match against first name or last name
     * @param limit Maximum number of records to return
     * @param offset Number of records to skip
     * @return List of matching EmployeeInfo records
     */
    fun searchEmployeeInfoByName(
        searchTerm: String,
        limit: Int = 50,
        offset: Int = 0,
        includeInactive: Boolean = false,
    ): List<EmployeeInfo>

    /**
     * Checks if employee information exists for a given employee ID.
     *
     * @param employeeId The employee UUID to check
     * @return true if employee info exists, false otherwise
     */
    fun employeeInfoExists(employeeId: UUID): Boolean

    /**
     * Gets employee information count for statistics.
     *
     * @return Total number of employee info records
     */
    fun getEmployeeInfoCount(): Long
}
