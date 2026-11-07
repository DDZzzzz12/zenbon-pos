package org.example.project.testutils.fakes

import org.example.project.auth.data.LoginRequest
import org.example.project.auth.data.LoginResponse
import org.example.project.auth.data.ValidationResponse
import org.example.project.core.data.remote.AuthApiClient
import org.example.project.core.domain.dto.employee.EmployeeInfoDto

/**
 * Stage C migrated: throws-style fake. Drive failures by setting the relevant
 * `*Result` to a `kotlin.Result.failure(...)` (typically with a
 * [org.example.project.core.network.error.PosApiException] /
 * [org.example.project.core.network.error.NetworkApiException]).
 */
class FakeAuthApiClient : AuthApiClient {
    var loginResult: Result<LoginResponse> = Result.failure(IllegalStateException("not configured"))
    var logoutResult: Result<Unit> = Result.success(Unit)
    var currentUserResult: Result<EmployeeInfoDto.EmployeeInfo> =
        Result.failure(IllegalStateException("not configured"))
    var validateResult: Result<ValidationResponse> = Result.success(ValidationResponse(valid = true))

    var loginCallCount = 0
    var logoutCallCount = 0

    override suspend fun login(request: LoginRequest): LoginResponse {
        loginCallCount++
        return loginResult.getOrThrow()
    }

    override suspend fun logout() {
        logoutCallCount++
        logoutResult.getOrThrow()
    }

    override suspend fun getCurrentUser(): EmployeeInfoDto.EmployeeInfo = currentUserResult.getOrThrow()

    override suspend fun validateToken(): ValidationResponse = validateResult.getOrThrow()
}
