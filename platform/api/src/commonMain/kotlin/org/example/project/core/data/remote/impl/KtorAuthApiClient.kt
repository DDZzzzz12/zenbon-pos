package org.example.project.core.data.remote.impl

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.auth.data.LoginRequest
import org.example.project.auth.data.LoginResponse
import org.example.project.auth.data.ValidationResponse
import org.example.project.core.data.remote.AuthApiClient
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall
import org.example.project.core.network.forgetCachedBearer

/**
 * Ktor implementation of [AuthApiClient].
 *
 * Throws-style: uses [apiCall], which raises
 * [org.example.project.core.network.error.PosApiException] (server returned a ProblemDetail)
 * or [org.example.project.core.network.error.NetworkApiException] (transport failure) on
 * non-2xx / IO errors.
 *
 * Paths are built from [HttpCall.getBaseUrl], which always ends in `/` — composeApp resolves
 * it to the configured server origin, adminApp to `"/"` because the panel is served from
 * pos-server itself.
 */
class KtorAuthApiClient(
    private val httpCall: HttpCall,
    private val httpClient: HttpClient,
) : AuthApiClient {
    companion object {
        private const val LOGIN_ENDPOINT = "api/auth/login"
        private const val LOGOUT_ENDPOINT = "api/auth/logout"
        private const val CURRENT_USER_ENDPOINT = "api/auth/me"
        private const val VALIDATE_TOKEN_ENDPOINT = "api/auth/validate"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun login(request: LoginRequest): LoginResponse {
        // Drop any cached bearer token before logging in, so a stale session's token is
        // never sent with the login request.
        httpClient.forgetCachedBearer()

        return httpClient.apiCall {
            post(url(LOGIN_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
    }

    override suspend fun logout() {
        // Same reason as login: the next session must not inherit this one's cached token.
        httpClient.forgetCachedBearer()

        try {
            httpClient.apiCall<Unit> {
                post(url(LOGOUT_ENDPOINT))
            }
        } finally {
            // The POST reloads the bearer into Ktor's cache; the session is over either way.
            httpClient.forgetCachedBearer()
        }
    }

    override suspend fun getCurrentUser(): EmployeeInfoDto.EmployeeInfo = httpClient.apiCall { get(url(CURRENT_USER_ENDPOINT)) }

    override suspend fun validateToken(): ValidationResponse = httpClient.apiCall { get(url(VALIDATE_TOKEN_ENDPOINT)) }
}
