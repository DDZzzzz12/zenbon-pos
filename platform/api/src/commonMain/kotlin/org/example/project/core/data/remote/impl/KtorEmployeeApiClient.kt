/**
 * Ktor-based implementation of EmployeeApiClient.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.EmployeeApiClient
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorEmployeeApiClient(
    private val httpCall: HttpCall,
) : EmployeeApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/employees"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun setLanguageForEmployee(languageCode: String) {
        httpCall.httpClient.apiCall<Unit> {
            patch(url("$BASE_ENDPOINT/language")) {
                parameter("languageCode", languageCode)
            }
        }
    }

    override suspend fun getActiveEmployees(): List<EmployeeDto.EmployeeInfoDto> = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/info/active")) }

    override suspend fun getCurrentEmployee(): EmployeeDto.EmployeeResponseDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/me")) }

    override suspend fun getEmployeeSummaries(
        limit: Int?,
        offset: Int?,
        active: Boolean?,
    ): List<EmployeeDto.EmployeeSummaryResponseDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/summary")) {
                limit?.let { parameter("limit", it) }
                offset?.let { parameter("offset", it) }
                active?.let { parameter("active", it) }
            }
        }

    override suspend fun getEmployeeById(id: String): EmployeeDto.EmployeeResponseDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$id")) }

    override suspend fun createEmployee(request: EmployeeDto.CreateEmployeeRequestDto): EmployeeDto.EmployeeResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateEmployee(
        id: String,
        request: EmployeeDto.UpdateEmployeeRequestDto,
    ): EmployeeDto.EmployeeResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun assignPermissionGroup(
        employeeId: String,
        groupCode: String,
    ): EmployeeDto.PermissionGroupAssignmentDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$employeeId/permission-group")) {
                contentType(ContentType.Application.Json)
                setBody(EmployeeDto.AssignPermissionGroupRequestDto(groupCode))
            }
        }

    override suspend fun deleteEmployee(id: String) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$BASE_ENDPOINT/$id")) }
    }

    override suspend fun deactivateEmployee(id: String) {
        httpCall.httpClient.apiCall<Unit> { patch(url("$BASE_ENDPOINT/$id/deactivate")) }
    }

    override suspend fun changePassword(
        id: String,
        request: EmployeeDto.PasswordChangeRequestDto,
    ) {
        httpCall.httpClient.apiCall<Unit> {
            patch(url("$BASE_ENDPOINT/$id/password")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
    }

    override suspend fun getMyEmployeeInfo(): EmployeeInfoDto.EmployeeInfoResponseDto = httpCall.httpClient.apiCall { get(url("api/employee/info/me")) }

    override suspend fun updateMyEmployeeInfo(request: EmployeeInfoDto.UpdateEmployeeInfoRequestDto): EmployeeInfoDto.EmployeeInfoResponseDto =
        httpCall.httpClient.apiCall {
            put(url("api/employee/info/me")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getEmployeeInfoByEmployeeId(employeeId: String): EmployeeInfoDto.EmployeeInfoResponseDto =
        httpCall.httpClient.apiCall { get(url("api/employee/info/by-employee/$employeeId")) }

    override suspend fun updateEmployeeInfoByEmployeeId(
        employeeId: String,
        request: EmployeeInfoDto.UpdateEmployeeInfoRequestDto,
    ): EmployeeInfoDto.EmployeeInfoResponseDto =
        httpCall.httpClient.apiCall {
            put(url("api/employee/info/by-employee/$employeeId")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun createEmployeeInfo(request: EmployeeInfoDto.CreateEmployeeInfoRequestDto): EmployeeInfoDto.EmployeeInfoResponseDto =
        httpCall.httpClient.apiCall {
            post(url("api/employee/info")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun changeMyPin(newPin: String) {
        httpCall.httpClient.apiCall<Unit> {
            patch(url("$BASE_ENDPOINT/me/pin")) {
                parameter("pin", newPin)
            }
        }
    }

    override suspend fun setRotationPool(
        id: String,
        inRotationPool: Boolean,
    ): EmployeeDto.EmployeeResponseDto =
        httpCall.httpClient.apiCall {
            patch(url("$BASE_ENDPOINT/$id/rotation-pool")) {
                contentType(ContentType.Application.Json)
                setBody(EmployeeDto.RotationPoolUpdateRequestDto(inRotationPool = inRotationPool))
            }
        }

    override suspend fun authenticate(
        username: String,
        password: String,
    ): EmployeeDto.EmployeeResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/authenticate")) {
                contentType(ContentType.Application.Json)
                setBody(EmployeeDto.AuthenticationRequestDto(username, password))
            }
        }

    override suspend fun resetPassword(
        id: String,
        newPassword: String,
    ) {
        httpCall.httpClient.apiCall<Unit> {
            post(url("$BASE_ENDPOINT/$id/reset-password")) {
                contentType(ContentType.Application.Json)
                setBody(EmployeeDto.AdminResetPasswordRequestDto(newPassword))
            }
        }
    }

    override suspend fun resetPin(
        id: String,
        newPin: String,
    ) {
        httpCall.httpClient.apiCall<Unit> {
            post(url("$BASE_ENDPOINT/$id/reset-pin")) {
                contentType(ContentType.Application.Json)
                setBody(EmployeeDto.AdminResetPinRequestDto(newPin))
            }
        }
    }
}
