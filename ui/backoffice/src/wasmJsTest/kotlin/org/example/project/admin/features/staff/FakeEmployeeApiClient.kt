package org.example.project.admin.features.staff

import org.example.project.core.data.remote.EmployeeApiClient
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.PosApiException

/** Hand-written fake implementing the shared interface; records mutations. */
class FakeEmployeeApiClient(
    var summaries: List<EmployeeDto.EmployeeSummaryResponseDto> = emptyList(),
    var current: EmployeeDto.EmployeeResponseDto = employeeResponse("me", "manager", "MANAGER"),
    private val onError: (() -> Nothing)? = null,
) : EmployeeApiClient {
    var created: EmployeeDto.CreateEmployeeRequestDto? = null
    var updatedId: String? = null
    var updated: EmployeeDto.UpdateEmployeeRequestDto? = null
    var deactivatedId: String? = null
    var authenticatedWith: Pair<String, String>? = null
    var authShouldFail: Boolean = false
    var resetPasswordId: String? = null
    var resetPasswordValue: String? = null
    var resetPinId: String? = null
    var resetPinValue: String? = null

    /** Returned by getEmployeeInfoByEmployeeId; null makes it throw a 404 PosApiException. */
    var info: EmployeeInfoDto.EmployeeInfoResponseDto? = null
    var createdInfo: EmployeeInfoDto.CreateEmployeeInfoRequestDto? = null
    var updatedInfoId: String? = null
    var updatedInfo: EmployeeInfoDto.UpdateEmployeeInfoRequestDto? = null

    /** When true, updateEmployee (the cost-centre leg) throws — used to exercise partial-save. */
    var employeeUpdateShouldFail: Boolean = false

    /** When true, the EmployeeInfo write leg throws. */
    var infoWriteShouldFail: Boolean = false

    /** Permission-group code returned by getEmployeeById, keyed by employee id. */
    var groupCodeById: Map<String, String> = emptyMap()

    /** Records the last assignPermissionGroup call. */
    var assignedTo: Pair<String, String>? = null

    /** When true, assignPermissionGroup throws. */
    var assignShouldFail: Boolean = false

    /** When true, getEmployeeById throws — used to exercise a failed detail load. */
    var detailShouldFail: Boolean = false

    /** When true, deactivateEmployee throws. */
    var deactivateShouldFail: Boolean = false

    override suspend fun getEmployeeSummaries(
        limit: Int?,
        offset: Int?,
        active: Boolean?,
    ): List<EmployeeDto.EmployeeSummaryResponseDto> {
        onError?.invoke()
        return summaries
    }

    override suspend fun getEmployeeById(id: String): EmployeeDto.EmployeeResponseDto {
        if (detailShouldFail) throw NetworkApiException(RuntimeException("detail down"))
        return employeeResponse(
            id,
            summaries.firstOrNull { it.id == id }?.username ?: "user",
            summaries.firstOrNull { it.id == id }?.role ?: "CASHIER",
            permissionGroupCode = groupCodeById[id],
        )
    }

    override suspend fun getCurrentEmployee(): EmployeeDto.EmployeeResponseDto = current

    override suspend fun assignPermissionGroup(
        employeeId: String,
        groupCode: String,
    ): EmployeeDto.PermissionGroupAssignmentDto {
        if (assignShouldFail) throw NetworkApiException(RuntimeException("assign down"))
        assignedTo = employeeId to groupCode
        groupCodeById = groupCodeById + (employeeId to groupCode)
        return EmployeeDto.PermissionGroupAssignmentDto(employeeId, groupCode, groupCode.lowercase())
    }

    override suspend fun createEmployee(request: EmployeeDto.CreateEmployeeRequestDto): EmployeeDto.EmployeeResponseDto {
        created = request
        return employeeResponse("new", request.username, request.role)
    }

    override suspend fun updateEmployee(
        id: String,
        request: EmployeeDto.UpdateEmployeeRequestDto,
    ): EmployeeDto.EmployeeResponseDto {
        if (employeeUpdateShouldFail) throw NetworkApiException(RuntimeException("employee write down"))
        updatedId = id
        updated = request
        return employeeResponse(id, "user", request.role ?: "CASHIER")
    }

    override suspend fun deactivateEmployee(id: String) {
        if (deactivateShouldFail) throw NetworkApiException(RuntimeException("deactivate down"))
        deactivatedId = id
    }

    // ---- unused-in-phase-1 members (return trivial values) ----
    override suspend fun setLanguageForEmployee(languageCode: String) {}

    override suspend fun getActiveEmployees(): List<EmployeeDto.EmployeeInfoDto> = emptyList()

    override suspend fun deleteEmployee(id: String) {}

    override suspend fun changePassword(
        id: String,
        request: EmployeeDto.PasswordChangeRequestDto,
    ) {}

    override suspend fun getMyEmployeeInfo(): EmployeeInfoDto.EmployeeInfoResponseDto = error("unused")

    override suspend fun updateMyEmployeeInfo(request: EmployeeInfoDto.UpdateEmployeeInfoRequestDto): EmployeeInfoDto.EmployeeInfoResponseDto = error("unused")

    override suspend fun getEmployeeInfoByEmployeeId(employeeId: String): EmployeeInfoDto.EmployeeInfoResponseDto =
        info ?: throw PosApiException(
            code = PosErrorCode.EMPLOYEE_NOT_FOUND,
            httpStatus = 404,
            message = "no employee info",
            traceId = null,
        )

    override suspend fun updateEmployeeInfoByEmployeeId(
        employeeId: String,
        request: EmployeeInfoDto.UpdateEmployeeInfoRequestDto,
    ): EmployeeInfoDto.EmployeeInfoResponseDto {
        if (infoWriteShouldFail) throw NetworkApiException(RuntimeException("info write down"))
        updatedInfoId = employeeId
        updatedInfo = request
        return employeeInfoResponse(employeeId)
    }

    override suspend fun createEmployeeInfo(request: EmployeeInfoDto.CreateEmployeeInfoRequestDto): EmployeeInfoDto.EmployeeInfoResponseDto {
        if (infoWriteShouldFail) throw NetworkApiException(RuntimeException("info write down"))
        createdInfo = request
        return employeeInfoResponse(request.employeeId, first = request.firstName, last = request.lastName)
    }

    override suspend fun changeMyPin(newPin: String) {}

    override suspend fun setRotationPool(
        id: String,
        inRotationPool: Boolean,
    ): EmployeeDto.EmployeeResponseDto = employeeResponse(id, "user", "CASHIER")

    override suspend fun authenticate(
        username: String,
        password: String,
    ): EmployeeDto.EmployeeResponseDto {
        authenticatedWith = username to password
        if (authShouldFail) throw PosApiException(PosErrorCode.AUTH_INVALID_CREDENTIALS, 401, "bad", null)
        return current
    }

    override suspend fun resetPassword(
        id: String,
        newPassword: String,
    ) {
        resetPasswordId = id
        resetPasswordValue = newPassword
    }

    override suspend fun resetPin(
        id: String,
        newPin: String,
    ) {
        resetPinId = id
        resetPinValue = newPin
    }
}
