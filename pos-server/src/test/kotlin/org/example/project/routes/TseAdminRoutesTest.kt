package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.ProblemDetail
import org.example.project.features.tse.domain.session.TseAdminSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@DisplayName("TSE Admin Routes — Auth Requirements")
class TseAdminRoutesTest {
    @Nested
    @DisplayName("GET /api/tse/config")
    inner class GetConfig {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/tse/config")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        /** T9-14: the stored TimeAdmin PIN must not come back in clear text. */
        @Test
        fun `never returns the stored TimeAdmin PIN`() =
            testApp(setupData = {
                TestData.createAdmin()
                // Stores timeAdminPin = "12345".
                TestData.setTseMode(TseMode.DISABLED)
            }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/tse/config") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                val config = testJson.decodeFromString<TseConfigDto.TseConfig>(body)
                assertEquals("", config.timeAdminPin, body)
                assertFalse(body.contains("12345"), body)
                assertEquals(TseMode.DISABLED, config.mode)
                assertEquals(true, config.timeAdminPinConfigured, "the client may know a PIN is stored, never the PIN")
            }

        /** QA 2026-10-06: lets the TSE screen refuse a save without a PIN before sending it. */
        @Test
        fun `reports that no TimeAdmin PIN is stored`() =
            testApp(setupData = {
                TestData.createAdmin()
                TestData.setTseMode(TseMode.DISABLED)
                runBlocking {
                    ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(timeAdminPin = "", mode = TseMode.DISABLED))
                }
            }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val body = client.get("/api/tse/config") { bearerAuth(token) }.bodyAsText()
                // The configuration cache is process-wide; put the usual fixture back for later tests.
                TestData.setTseMode(TseMode.DISABLED)
                val config = testJson.decodeFromString<TseConfigDto.TseConfig>(body)
                assertEquals(false, config.timeAdminPinConfigured, body)
            }
    }

    @Nested
    @DisplayName("PUT /api/tse/config")
    inner class UpdateConfig {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/tse/config") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        /**
         * Found on a real Swissbit TSE: a fresh shop with no TimeAdmin PIN answered the setup dialog's
         * config save with an untranslatable TSE_INVALID_PARAMETERS. It must name the field.
         */
        @Test
        fun `a missing TimeAdmin PIN is TSE_TIME_ADMIN_PIN_REQUIRED naming the field`() =
            testApp(setupData = {
                TestData.createAdmin()
                TestData.setTseMode(TseMode.DISABLED)
                runBlocking {
                    ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(timeAdminPin = "", mode = TseMode.DISABLED))
                }
            }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/tse/config") {
                        bearerAuth(token)
                        setBody(TextContent("""{"keepAliveEnabled":true}""", ContentType.Application.Json))
                    }
                val body = response.bodyAsText()
                // The configuration cache is process-wide; put the usual fixture back for later tests.
                TestData.setTseMode(TseMode.DISABLED)
                assertEquals(HttpStatusCode.BadRequest, response.status, body)
                val problem = testJson.decodeFromString(ProblemDetail.serializer(), body)
                assertEquals("TSE_TIME_ADMIN_PIN_REQUIRED", problem.code, body)
                assertEquals(listOf("timeAdminPin"), problem.errors.orEmpty().map { it.field }, body)
            }
    }

    @Nested
    @DisplayName("GET /api/tse/status")
    inner class GetStatus {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/tse/status")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/tse/info")
    inner class GetInfo {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/tse/info")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/tse/health")
    inner class HealthCheck {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/tse/health")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/tse/ping")
    inner class Ping {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/tse/ping")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/tse/selftest")
    inner class SelfTest {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/tse/selftest")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/tse/updatetime")
    inner class UpdateTime {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/tse/updatetime")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/tse/export/full")
    inner class ExportFull {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/tse/export/full")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/tse/export")
    inner class ExportAndDelete {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/tse/export")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        /** F68: the locked sitting is still a 423 — refused before the TSE is touched. */
        @Test
        fun `a locked Admin sitting surfaces 423`() =
            testApp(setupData = {
                TestData.createAdmin()
                TseAdminSession.clear()
            }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.delete("/api/tse/export") { bearerAuth(token) }
                assertEquals(HttpStatusCode.Locked, response.status, response.bodyAsText())
            }
    }

    @Nested
    @DisplayName("POST /api/tse/setup")
    inner class Setup {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/tse/setup")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        /**
         * Found on the real Swissbit TSE: the back office is no terminal, so setup answered a bare 404
         * and the TSE could never be commissioned from there. The operator now chooses the terminal.
         */
        @Test
        fun `the back office without a chosen terminal is told to choose one`() =
            setupRefusal(SETUP_BODY, HttpStatusCode.NotFound, "TSE_SETUP_TERMINAL_REQUIRED")

        @Test
        fun `an unknown chosen terminal is a 404 and nothing reaches the TSE`() =
            setupRefusal(SETUP_BODY.replace("}", ""","terminalId":"TER-999"}"""), HttpStatusCode.NotFound, "TERMINAL_NOT_FOUND")

        private fun setupRefusal(
            body: String,
            expectedStatus: HttpStatusCode,
            expectedCode: String,
        ) = testApp(setupData = {
            TestData.createAdmin()
            TestData.setTseMode(TseMode.DISABLED)
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.post("/api/tse/setup") {
                    bearerAuth(token)
                    setBody(TextContent(body, ContentType.Application.Json))
                }
            val responseBody = response.bodyAsText()
            assertEquals(expectedStatus, response.status, responseBody)
            assertEquals(expectedCode, testJson.decodeFromString(ProblemDetail.serializer(), responseBody).code, responseBody)
        }
    }

    @Nested
    @DisplayName("POST /api/tse/factory-reset")
    inner class FactoryReset {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/tse/factory-reset")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/tse/restart")
    inner class Restart {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/tse/restart")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/tse/decommission")
    inner class Decommission {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/tse/decommission")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/tse/clients")
    inner class ListClients {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/tse/clients")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/tse/transactions")
    inner class ListTransactions {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/tse/transactions")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    private companion object {
        const val SETUP_BODY = """{"adminPin":"80417","adminPuk":"804172","credentialSeed":"seed"}"""
    }
}
