package org.example.project.testutils

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import org.example.project.auth.data.LoginResponse

object TestAuthHelper {
    suspend fun loginAs(
        client: io.ktor.client.HttpClient,
        username: String,
        password: String = TestData.DEFAULT_PASSWORD,
        // Stamped into the issued JWT's `deviceId` claim (AuthRoutes reads X-Device-ID at login).
        // Needed by the A4 terminal-capability ceiling tests, which resolve the TRUSTED identity
        // from the JWT, not from a per-request X-Device-ID header.
        deviceId: String? = null,
    ): String {
        val body = """{"identifier":"$username","secret":"$password"}"""
        val response =
            client.post("/api/auth/login") {
                if (deviceId != null) header("X-Device-ID", deviceId)
                setBody(TextContent(body, ContentType.Application.Json))
            }
        val loginResponse = testJson.decodeFromString<LoginResponse>(response.bodyAsText())
        return loginResponse.token.accessToken
    }

    suspend fun loginAsAdmin(client: io.ktor.client.HttpClient): String = loginAs(client, "admin")

    suspend fun loginAsManager(client: io.ktor.client.HttpClient): String = loginAs(client, "manager")

    suspend fun loginAsCashier(client: io.ktor.client.HttpClient): String = loginAs(client, "cashier")

    suspend fun loginAsTerminal(client: io.ktor.client.HttpClient): String = loginAs(client, "terminal")
}
