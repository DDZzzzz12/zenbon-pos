package org.example.project.core.data.remote

import org.example.project.auth.data.LoginRequest
import org.example.project.auth.data.LoginResponse
import org.example.project.auth.data.ValidationResponse
import org.example.project.core.domain.dto.employee.EmployeeInfoDto

/**
 * HTTP client for the pos-server authentication endpoints, shared by composeApp and adminApp.
 *
 * Throws-style: raises [org.example.project.core.network.error.PosApiException] (server
 * returned a ProblemDetail) or [org.example.project.core.network.error.NetworkApiException]
 * (transport failure). composeApp call sites wrap in `runCatchingApi { … }.onError { }`;
 * adminApp catches the two exceptions directly and maps them to its own error enums.
 */
interface AuthApiClient {
    suspend fun login(request: LoginRequest): LoginResponse

    suspend fun logout()

    suspend fun getCurrentUser(): EmployeeInfoDto.EmployeeInfo

    /**
     * Validate the current session against `api/auth/validate`.
     *
     * A lightweight authenticated GET: reaching it at all means the access token was
     * accepted, and an expired one is transparently refreshed first by the HTTP client's
     * Auth plugin. Returns the server's [ValidationResponse]; a rejected session throws
     * [org.example.project.core.network.error.PosApiException] rather than returning
     * `valid = false`, so callers that must not propagate a failure (an app-startup gate,
     * for instance) catch it themselves.
     *
     * Use this to check whether a token is still good, to force a refresh, or to ensure
     * freshness before a critical operation such as opening an SSE stream.
     */
    suspend fun validateToken(): ValidationResponse
}
