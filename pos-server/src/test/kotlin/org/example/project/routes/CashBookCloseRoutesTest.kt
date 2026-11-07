package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Proves the Kassensturz close routes are mounted (not 404) AND permission-registered in
 * [org.example.project.infrastructure.security.RoutePermissions] (not a blanket 403) — the same
 * two failure modes [CashBookRoutesTest]'s KDoc warns about for the transfer routes. A missing
 * `RoutePermissions` entry denies every request before routing even runs, which would make every
 * case below (even the ones expecting 401) come back 403 instead — that is what these tests would
 * catch if a route/permission entry were dropped.
 *
 * Happy-path 200/201s and the service-level guards (fiscal book, nothing-to-close, period
 * ordering) are covered by the direct service tests in
 * [org.example.project.features.kassenbuch.CashBookCloseTest].
 */
@DisplayName("Cash Book Close Routes")
class CashBookCloseRoutesTest {
    @Nested
    @DisplayName("GET /api/v1/kassenbuch/books")
    inner class ListBooks {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/kassenbuch/books")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 403 for cashier role`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response = client.get("/api/v1/kassenbuch/books") { bearerAuth(token) }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/v1/kassenbuch/books")
    inner class CreateBook {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/v1/kassenbuch/books") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 403 for cashier role`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response =
                    client.post("/api/v1/kassenbuch/books") {
                        bearerAuth(token)
                        setBody(
                            TextContent(
                                """{"name": "Tresor", "datevAccount": "1010"}""",
                                ContentType.Application.Json,
                            ),
                        )
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 400 for invalid request body`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/v1/kassenbuch/books") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/v1/kassenbuch/books/{bookId}")
    inner class UpdateBook {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/v1/kassenbuch/books/1") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 403 for cashier role`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response =
                    client.put("/api/v1/kassenbuch/books/1") {
                        bearerAuth(token)
                        setBody(
                            TextContent(
                                """{"name": "Tresor", "datevAccount": "1010", "closeInterval": "MANUAL", "isActive": true}""",
                                ContentType.Application.Json,
                            ),
                        )
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 400 for invalid request body`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/v1/kassenbuch/books/1") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/kassenbuch/books/{bookId}/close/preview")
    inner class Preview {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/kassenbuch/books/1/close/preview")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 403 for cashier role`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response =
                    client.get("/api/v1/kassenbuch/books/1/close/preview") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/v1/kassenbuch/books/{bookId}/close")
    inner class Close {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/v1/kassenbuch/books/1/close") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 403 for cashier role`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response =
                    client.post("/api/v1/kassenbuch/books/1/close") {
                        bearerAuth(token)
                        setBody(TextContent("""{"countedBalance": "0.00"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 400 for negative countedBalance`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/v1/kassenbuch/books/1/close") {
                        bearerAuth(token)
                        setBody(TextContent("""{"countedBalance": "-5.00"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for non-decimal countedBalance`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/v1/kassenbuch/books/1/close") {
                        bearerAuth(token)
                        setBody(TextContent("""{"countedBalance": "not-a-number"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/kassenbuch/books/{bookId}/closes")
    inner class CloseHistory {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/kassenbuch/books/1/closes")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 403 for cashier role`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response =
                    client.get("/api/v1/kassenbuch/books/1/closes") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }
    }
}
