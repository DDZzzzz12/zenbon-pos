package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("Temporary Session Routes")
class TemporarySessionRoutesTest {
    @Nested
    @DisplayName("POST /api/auth/temporary-session")
    inner class CreateSession {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val body =
                    """
                    {
                        "managerUsername": "manager",
                        "managerPin": "1234",
                        "deviceId": "POS-001"
                    }
                    """.trimIndent()
                val response =
                    client.post("/api/auth/temporary-session") {
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid request body`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/auth/temporary-session") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns error for invalid manager credentials`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createCashier()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val body =
                    """
                    {
                        "managerUsername": "nonexistent",
                        "managerPin": "0000",
                        "deviceId": "POS-001"
                    }
                    """.trimIndent()
                val response =
                    client.post("/api/auth/temporary-session") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                // Should fail with forbidden or not found for invalid credentials
                val errorStatuses = setOf(HttpStatusCode.Forbidden, HttpStatusCode.NotFound, HttpStatusCode.Unauthorized)
                assert(response.status in errorStatuses) {
                    "Expected an error status but got ${response.status}"
                }
            }
    }

    @Nested
    @DisplayName("GET /api/auth/temporary-session/active")
    inner class GetActiveSession {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.get("/api/auth/temporary-session/active") {
                        header("X-Device-ID", "POS-001")
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 when X-Device-ID header missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/auth/temporary-session/active") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 204 when no active session`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/auth/temporary-session/active") {
                        bearerAuth(token)
                        header("X-Device-ID", "POS-001")
                    }
                assertEquals(HttpStatusCode.NoContent, response.status)
            }
    }
}
