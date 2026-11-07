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
import kotlin.test.assertTrue

@DisplayName("Z-Bon Routes")
class ZBonRoutesTest {
    @Nested
    @DisplayName("GET /api/z-bon")
    inner class GetAllZBons {
        @Test
        fun `returns 200 with empty list`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/z-bon") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/z-bon")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/z-bon/{id}")
    inner class GetZBonById {
        @Test
        fun `returns 400 for invalid id`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/z-bon/invalid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/z-bon/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/z-bon/history")
    inner class GetZBonHistory {
        @Test
        fun `returns 400 when dates missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/z-bon/history") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 200 with valid dates`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/z-bon/history?startDate=2026-01-01&endDate=2026-12-31") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/z-bon/history?startDate=2026-01-01&endDate=2026-12-31")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/z-bon/latest/datetime")
    inner class GetLatestZBonDatetime {
        @Test
        fun `returns 200 with default datetime when no reports exist`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/z-bon/latest/datetime") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/z-bon/latest/datetime")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/z-bon/recent")
    inner class GetRecentZBons {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/z-bon/recent")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 403 for cashier role`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response = client.get("/api/z-bon/recent") { bearerAuth(token) }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 200 with valid auth`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/z-bon/recent?limit=5") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 200 with default limit`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/z-bon/recent") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/z-bon/closing-readiness")
    inner class ClosingReadiness {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/z-bon/closing-readiness").status)
            }

        @Test
        fun `returns 403 for cashier role`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response = client.get("/api/z-bon/closing-readiness") { bearerAuth(token) }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 200 with the readiness body for ZBON_CLOSE`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/z-bon/closing-readiness") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("dailyTransferConfigured"))
                assertTrue(body.contains("\"currency\":\"EUR\""))
            }
    }

    @Nested
    @DisplayName("POST /api/z-bon")
    inner class CreateZBon {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/z-bon") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/z-bon/{id}/pdf")
    inner class GetZBonPdf {
        @Test
        fun `returns 400 for invalid id`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/z-bon/invalid/pdf") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/z-bon/1/pdf")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/z-bon/{id}/print")
    inner class PrintZBon {
        @Test
        fun `returns 400 for invalid id`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.post("/api/z-bon/invalid/print") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/z-bon/1/print")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }
}
