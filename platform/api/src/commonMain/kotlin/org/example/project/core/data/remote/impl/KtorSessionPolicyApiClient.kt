/**
 * Ktor-based implementation of SessionPolicyApiClient.
 *
 * Talks to the admin-only session endpoints on pos-server:
 * - GET  /api/auth/sessions/policy
 * - PUT  /api/auth/sessions/policy
 * - POST /api/auth/sessions/revoke-all
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.auth.data.SessionPolicyDto
import org.example.project.core.data.remote.SessionPolicyApiClient
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorSessionPolicyApiClient(
    private val httpCall: HttpCall,
) : SessionPolicyApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/auth/sessions"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getPolicy(): SessionPolicyDto.Policy = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/policy")) }

    override suspend fun updatePolicy(req: SessionPolicyDto.UpdatePolicyRequest): SessionPolicyDto.Policy =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/policy")) {
                contentType(ContentType.Application.Json)
                setBody(req)
            }
        }

    override suspend fun revokeAll(): Unit = httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/revoke-all")) }
}
