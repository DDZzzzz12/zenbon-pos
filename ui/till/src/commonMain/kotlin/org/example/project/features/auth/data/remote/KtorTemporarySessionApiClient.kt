package org.example.project.features.auth.data.remote

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

/**
 * Ktor-based implementation of TemporarySessionApiClient.
 *
 * Throws-style: raises [org.example.project.core.network.error.PosApiException] /
 * [org.example.project.core.network.error.NetworkApiException] on failure.
 *
 * @property httpCall HTTP client abstraction for making API calls
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
class KtorTemporarySessionApiClient(
    private val httpCall: HttpCall,
) : TemporarySessionApiClient {
    companion object {
        // Temporary session API endpoints
        private const val BASE_ENDPOINT = "api/auth/temporary-session"
        private const val ACTIVE_ENDPOINT = "api/auth/temporary-session/active"

        private fun extendEndpoint(sessionId: String) = "$BASE_ENDPOINT/$sessionId/extend"

        private fun deleteEndpoint(sessionId: String) = "$BASE_ENDPOINT/$sessionId"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun createSession(request: AuthDto.TemporarySessionRequestDto): AuthDto.TemporarySessionResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getActiveSession(): AuthDto.TemporarySessionResponseDto? {
        // GET /active can return 204 No Content when no session exists.
        // We perform a raw call so we can distinguish 204 from a deserialized
        // body and avoid running through apiCall's body() decode for an empty
        // response.
        val resp: HttpResponse = httpCall.httpClient.apiCall { get(url(ACTIVE_ENDPOINT)) }
        return if (resp.status == HttpStatusCode.NoContent) null else resp.body()
    }

    override suspend fun extendSession(sessionId: String): AuthDto.TemporarySessionResponseDto =
        httpCall.httpClient.apiCall {
            put(url(extendEndpoint(sessionId)))
        }

    override suspend fun endSession(sessionId: String) {
        httpCall.httpClient.apiCall<Unit> {
            delete(url(deleteEndpoint(sessionId)))
        }
    }
}
