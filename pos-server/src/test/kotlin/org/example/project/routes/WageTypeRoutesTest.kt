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
    "Feature de-integrated 2026-05-07: Routing.kt no longer calls wageTypeRoutes(), and the matching " +
        "RoutePermissions rules are commented out, so every endpoint under test is unmounted. Requests " +
        "return 403 (RouteAuthorizationPlugin default-denies unknown /api paths in the onCall phase, " +
        "before routing), which masks the underlying 404 and made these read as an authorization " +
        "regression for months. The assertions themselves are still correct and will pass as-is once the " +
        "routes are re-mounted — re-enable this class in the same change that restores the feature.",
)
@DisplayName("Wage Type Routes")
class WageTypeRoutesTest {
    @Nested
    @DisplayName("GET /api/wagetypes")
    inner class GetAll {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/wagetypes")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 200 with valid auth`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/wagetypes") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/wagetypes/{id}")
    inner class GetById {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/wagetypes/some-id")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/wagetypes")
    inner class Create {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/wagetypes") {
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
                    client.post("/api/wagetypes") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/wagetypes/{id}")
    inner class Update {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/wagetypes/some-id") {
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
                    client.put("/api/wagetypes/some-id") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/wagetypes/{id}")
    inner class Delete {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/wagetypes/some-id")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }
}
