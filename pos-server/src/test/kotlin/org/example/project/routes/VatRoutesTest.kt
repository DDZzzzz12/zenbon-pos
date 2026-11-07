package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.assertStatus
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("VAT Routes")
class VatRoutesTest {
    @Nested
    @DisplayName("GET /api/vat")
    inner class GetAllVatRates {
        @Test
        fun `returns 200 with vat rates`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createDefaultVatRates()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/vat") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 200 with empty list`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/vat") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/vat")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/vat")
    inner class CreateVatRate {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/vat") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid request`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/vat") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/vat/{id}")
    inner class UpdateVatRate {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/vat/A") {
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
                    client.put("/api/vat/A") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/vat/{id}")
    inner class DeleteVatRate {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/vat/A")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 404 for non-existent vat rate`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                assertStatus(HttpStatusCode.NotFound) {
                    client.delete("/api/vat/Z") { bearerAuth(token) }
                }
            }
    }
}
