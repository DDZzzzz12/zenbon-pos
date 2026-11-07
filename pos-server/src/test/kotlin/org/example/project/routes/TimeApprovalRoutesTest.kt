package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@Disabled(
    "Feature de-integrated 2026-05-07: Routing.kt no longer calls timeApprovalRoutes(), and the " +
        "matching RoutePermissions rules are commented out, so every endpoint under test is unmounted. " +
        "Requests return 403 (RouteAuthorizationPlugin default-denies unknown /api paths in the onCall " +
        "phase, before routing), which masks the underlying 404 and made these read as an authorization " +
        "regression for months. The assertions themselves are still correct and will pass as-is once the " +
        "routes are re-mounted — re-enable this class in the same change that restores the feature.",
)
@DisplayName("Time Approval Routes")
class TimeApprovalRoutesTest {
    @Nested
    @DisplayName("POST /api/timetracking/approval/submit")
    inner class SubmitMonth {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/timetracking/approval/submit") {
                        setBody(TextContent("{}", ContentType.Application.Json))
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
                    client.post("/api/timetracking/approval/submit") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/timetracking/approval/{id}/approve")
    inner class ApproveMonth {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/timetracking/approval/1/approve") {
                        setBody(TextContent("{}", ContentType.Application.Json))
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
                    client.post("/api/timetracking/approval/1/approve") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/timetracking/approval/{id}/reject")
    inner class RejectMonth {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/timetracking/approval/1/reject") {
                        setBody(TextContent("{}", ContentType.Application.Json))
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
                    client.post("/api/timetracking/approval/1/reject") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/timetracking/approval/pending")
    inner class GetPendingApprovals {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/timetracking/approval/pending")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 200 with valid auth`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/timetracking/approval/pending") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/timetracking/approval/my-status")
    inner class GetMyApprovalStatus {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/timetracking/approval/my-status?year=2026&month=3")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 when year missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/timetracking/approval/my-status?month=3") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 when month missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/timetracking/approval/my-status?year=2026") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }
}
