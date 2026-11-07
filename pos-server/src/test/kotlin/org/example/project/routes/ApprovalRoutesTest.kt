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

@DisplayName("Approval Routes")
class ApprovalRoutesTest {
    @Nested
    @DisplayName("GET /api/auth/approvals/pending")
    inner class GetPending {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/auth/approvals/pending")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 200 with auth`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/auth/approvals/pending") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/auth/approvals/{requestId}/approve")
    inner class ApproveRequest {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/auth/approvals/some-request-id/approve")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 404 for non-existent request`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/auth/approvals/non-existent-id/approve") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.NotFound, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/auth/approvals/{requestId}/deny")
    inner class DenyRequest {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/auth/approvals/some-request-id/deny") {
                        setBody(TextContent("""{"reason":"test"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 when reason missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/auth/approvals/some-request-id/deny") {
                        bearerAuth(token)
                        setBody(TextContent("""{}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 404 for non-existent request`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/auth/approvals/non-existent-id/deny") {
                        bearerAuth(token)
                        setBody(TextContent("""{"reason":"denied for testing"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.NotFound, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/auth/approvals/{requestId}/status")
    inner class GetRequestStatus {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/auth/approvals/some-request-id/status")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 404 for non-existent request`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/auth/approvals/non-existent-id/status") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.NotFound, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/auth/approvals/{requestId}")
    inner class CancelRequest {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/auth/approvals/some-request-id")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 404 for non-existent request`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.delete("/api/auth/approvals/non-existent-id") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.NotFound, response.status)
            }
    }
}
