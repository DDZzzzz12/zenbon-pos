/**
 * Service interface for EmployeeInfo business operations.
 *
 * Migrated to throws-style in Stage B Batch 10. Methods return their value
 * directly and throw [org.example.project.infrastructure.errors.PosException]
 * on error.
 */
package org.example.project.features.employee.domain.service

import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import java.util.UUID

interface EmployeeInfoService {
    suspend fun createEmployeeInfo(request: EmployeeInfoDto.CreateEmployeeInfoRequestDto): EmployeeInfoDto.EmployeeInfoResponseDto

    suspend fun getEmployeeInfoById(employeeInfoId: Int): EmployeeInfoDto.EmployeeInfoResponseDto

    suspend fun getEmployeeInfoByEmployeeId(employeeId: UUID): EmployeeInfoDto.EmployeeInfoResponseDto

    suspend fun updateEmployeeInfo(
        employeeInfoId: Int,
        updateData: EmployeeInfoDto.UpdateEmployeeInfoRequestDto,
    ): EmployeeInfoDto.EmployeeInfoResponseDto

    /**
     * Updates the HR profile of [employeeId]; [actorId] is the signed-in employee, recorded on the
     * `UPDATE_EMPLOYEE` audit row (field names only, including which fields were cleared).
     */
    suspend fun updateEmployeeInfoByEmployeeId(
        employeeId: UUID,
        updateData: EmployeeInfoDto.UpdateEmployeeInfoRequestDto,
        actorId: String,
    ): EmployeeInfoDto.EmployeeInfoResponseDto

    /**
     * Self-service update of [employeeId]'s own HR profile (`PUT /api/employee/info/me`). Refuses
     * (403 `SELF_MODIFICATION_FORBIDDEN`) a request that sets or clears any employment term: hire
     * date, job title, hourly rate, salary, contracted hours or their period.
     */
    suspend fun updateOwnEmployeeInfo(
        employeeId: UUID,
        updateData: EmployeeInfoDto.UpdateEmployeeInfoRequestDto,
    ): EmployeeInfoDto.EmployeeInfoResponseDto

    suspend fun deleteEmployeeInfo(employeeInfoId: Int)

    suspend fun deleteEmployeeInfoByEmployeeId(employeeId: UUID)

    suspend fun getAllEmployeeInfo(
        limit: Int = 50,
        offset: Int = 0,
    ): List<EmployeeInfoDto.EmployeeInfoResponseDto>

    suspend fun searchEmployeeInfoByName(
        searchTerm: String,
        limit: Int = 50,
        offset: Int = 0,
        includeInactive: Boolean = false,
    ): List<EmployeeInfoDto.EmployeeInfoResponseDto>

    suspend fun getEmployeeInfoSummary(
        limit: Int = 50,
        offset: Int = 0,
    ): List<EmployeeInfoDto.EmployeeInfoSummaryDto>

    suspend fun employeeInfoExists(employeeId: UUID): Boolean

    suspend fun getEmployeeInfoCount(): Long

    /**
     * Validates employee information data for business rules. Throws PosException on
     * validation failure.
     */
    suspend fun validateEmployeeInfo(request: EmployeeInfoDto.CreateEmployeeInfoRequestDto)
}
