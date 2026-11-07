package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.serialization.json.*
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("Printer Location Routes")
class PrinterLocationRoutesTest {
    @Nested
    @DisplayName("POST /api/printer-location")
    inner class CreatePrinterLocation {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/printer-location") {
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
                    client.post("/api/printer-location") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/printer-location")
    inner class GetAllPrinterLocations {
        @Test
        fun `returns 200 with auth`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/printer-location") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/printer-location")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/printer-location/{id}")
    inner class UpdatePrinterLocation {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/printer-location/1") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid id format`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/printer-location/not-a-number") {
                        bearerAuth(token)
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/printer-location/{id}")
    inner class DeletePrinterLocation {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/printer-location/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid id format`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.delete("/api/printer-location/not-a-number") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("PATCH /api/printer-location/{id}/activate")
    inner class ActivatePrinterLocation {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.patch("/api/printer-location/1/activate")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid id format`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.patch("/api/printer-location/not-a-number/activate") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }
}
