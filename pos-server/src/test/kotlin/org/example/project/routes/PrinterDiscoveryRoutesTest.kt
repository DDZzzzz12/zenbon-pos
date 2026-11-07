package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("Printer Discovery Routes")
class PrinterDiscoveryRoutesTest {
    @Nested
    @DisplayName("GET /api/printer-discovery/discover")
    inner class DiscoverPrinters {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/printer-discovery/discover")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/printer-discovery/status")
    inner class GetPrinterStatus {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/printer-discovery/status?uri=ipp://localhost")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 when uri parameter missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/printer-discovery/status") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/printer-discovery/formats")
    inner class GetSupportedFormats {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/printer-discovery/formats?uri=ipp://localhost")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 when uri parameter missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/printer-discovery/formats") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/printer-discovery/print-pdf")
    inner class PrintPdf {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/printer-discovery/print-pdf")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }
}
