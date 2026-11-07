/**
 * API client interface for employee operations.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.dto.employee.EmployeeInfoDto

interface EmployeeApiClient {
    suspend fun setLanguageForEmployee(languageCode: String)

    suspend fun getActiveEmployees(): List<EmployeeDto.EmployeeInfoDto>

    suspend fun getCurrentEmployee(): EmployeeDto.EmployeeResponseDto

    suspend fun getEmployeeSummaries(
        limit: Int? = null,
        offset: Int? = null,
        active: Boolean? = null,
    ): List<EmployeeDto.EmployeeSummaryResponseDto>

    suspend fun getEmployeeById(id: String): EmployeeDto.EmployeeResponseDto

    suspend fun createEmployee(request: EmployeeDto.CreateEmployeeRequestDto): EmployeeDto.EmployeeResponseDto

    suspend fun updateEmployee(
        id: String,
        request: EmployeeDto.UpdateEmployeeRequestDto,
    ): EmployeeDto.EmployeeResponseDto

    /**
     * Point an employee at an existing permission group.
     *
     * Requires PERMISSION_ADMIN — deliberately not EMPLOYEE_WRITE: a manager who may edit staff
     * records must not thereby be able to hand out capabilities. The server also refuses an actor
     * changing their OWN group, since undoing a self-demotion needs the capability just given away.
     */
    suspend fun assignPermissionGroup(
        employeeId: String,
        groupCode: String,
    ): EmployeeDto.PermissionGroupAssignmentDto

    suspend fun deleteEmployee(id: String)

    suspend fun deactivateEmployee(id: String)

    suspend fun changePassword(
        id: String,
        request: EmployeeDto.PasswordChangeRequestDto,
    )

    suspend fun getMyEmployeeInfo(): EmployeeInfoDto.EmployeeInfoResponseDto

    suspend fun updateMyEmployeeInfo(request: EmployeeInfoDto.UpdateEmployeeInfoRequestDto): EmployeeInfoDto.EmployeeInfoResponseDto

    suspend fun getEmployeeInfoByEmployeeId(employeeId: String): EmployeeInfoDto.EmployeeInfoResponseDto

    suspend fun updateEmployeeInfoByEmployeeId(
        employeeId: String,
        request: EmployeeInfoDto.UpdateEmployeeInfoRequestDto,
    ): EmployeeInfoDto.EmployeeInfoResponseDto

    suspend fun createEmployeeInfo(request: EmployeeInfoDto.CreateEmployeeInfoRequestDto): EmployeeInfoDto.EmployeeInfoResponseDto

    suspend fun changeMyPin(newPin: String)

    suspend fun setRotationPool(
        id: String,
        inRotationPool: Boolean,
    ): EmployeeDto.EmployeeResponseDto

    suspend fun authenticate(
        username: String,
        password: String,
    ): EmployeeDto.EmployeeResponseDto

    suspend fun resetPassword(
        id: String,
        newPassword: String,
    )

    suspend fun resetPin(
        id: String,
        newPin: String,
    )
}
