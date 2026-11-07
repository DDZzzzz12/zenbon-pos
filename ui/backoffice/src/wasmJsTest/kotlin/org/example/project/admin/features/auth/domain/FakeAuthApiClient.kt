package org.example.project.admin.features.auth.domain

import org.example.project.auth.data.LoginRequest
import org.example.project.auth.data.LoginResponse
import org.example.project.auth.data.ValidationResponse
import org.example.project.core.data.remote.AuthApiClient
import org.example.project.core.domain.dto.employee.EmployeeInfoDto

/** Hand-written fake: only [logout] is exercised; its behaviour is supplied by the test. */
class FakeAuthApiClient(
    private val onLogout: suspend () -> Unit,
) : AuthApiClient {
    var logoutCalls = 0
        private set

    override suspend fun logout() {
        logoutCalls++
        onLogout()
    }

    override suspend fun login(request: LoginRequest): LoginResponse = error("not used")

    override suspend fun getCurrentUser(): EmployeeInfoDto.EmployeeInfo = error("not used")

    override suspend fun validateToken(): ValidationResponse = error("not used")
}
