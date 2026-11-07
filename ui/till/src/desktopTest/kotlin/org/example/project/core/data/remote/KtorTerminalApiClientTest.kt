package org.example.project.core.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.example.project.core.data.remote.impl.KtorTerminalApiClient
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.network.LocalDbHttpCall
import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KtorTerminalApiClientTest {
    private class Captured {
        var method: String = ""
        var path: String = ""
        var body: String = ""
    }

    private fun buildClient(
        responseBody: String,
        status: HttpStatusCode,
        cap: Captured,
    ): TerminalApiClient {
        val engine =
            MockEngine { request ->
                cap.method = request.method.value
                cap.path = request.url.encodedPath
                cap.body = (request.body as? TextContent)?.text ?: ""
                respond(
                    content = responseBody,
                    status = status,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
        val httpClient =
            HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        val localDb = FakeLocalDatabaseManager.create(mapOf("api_base_url" to "http://localhost:8080/"))
        return KtorTerminalApiClient(LocalDbHttpCall(httpClient, localDb))
    }

    @Test
    fun `getAllTerminals sends GET to base endpoint and decodes list`() =
        runTest {
            val cap = Captured()
            val body =
                """
                [{"id":1,"terminalId":"TER-001","terminalBrand":"Android","terminalModel":"Tab",
                "isActive":true,"tseClientRegistered":false,"lastSeenAt":"2026-07-26T10:00:00Z",
                "role":"CASHIER","fiscal":true}]
                """.trimIndent()
            val client = buildClient(body, HttpStatusCode.OK, cap)

            val result = client.getAllTerminals()

            assertEquals("GET", cap.method)
            assertEquals("/api/v1/terminals", cap.path)
            assertEquals("TER-001", result.single().terminalId)
        }

    @Test
    fun `pairDevice posts code and deviceId to pair endpoint and decodes token`() =
        runTest {
            val cap = Captured()
            val body =
                """
                {"terminal":{"id":7,"deviceId":"dev-1","terminalId":"TER-007","terminalBrand":"Android",
                 "terminalModel":"Tab","terminalSerialNr":null,"terminalSwBrand":"POS","terminalSwVersion":"1.0",
                 "isActive":true,"tseClientRegistered":false,"registeredAt":"2026-07-26T10:00:00Z",
                 "lastSeenAt":"2026-07-26T10:00:00Z","role":"ORDER_STATION","fiscal":true},
                 "deviceToken":"tok-xyz"}
                """.trimIndent()
            val client = buildClient(body, HttpStatusCode.OK, cap)

            val result =
                client.pairDevice(
                    TerminalDto.PairDeviceRequestDto(code = "123456", deviceId = "dev-1", brand = "Android"),
                )

            assertEquals("POST", cap.method)
            assertEquals("/api/v1/terminals/pair", cap.path)
            assertEquals("tok-xyz", result.deviceToken)
            assertEquals("TER-007", result.terminal.terminalId)
            assertTrue(cap.body.contains("\"code\":\"123456\""))
        }

    @Test
    fun `createTerminal posts to base endpoint and decodes 201 response`() =
        runTest {
            val cap = Captured()
            val body =
                """
                {"id":9,"deviceId":"","terminalId":"TER-009","terminalBrand":null,"terminalModel":null,
                 "terminalSerialNr":null,"terminalSwBrand":"POS","terminalSwVersion":null,"isActive":true,
                 "tseClientRegistered":false,"registeredAt":"2026-07-26T10:00:00Z","lastSeenAt":"2026-07-26T10:00:00Z",
                 "role":"KITCHEN","fiscal":false}
                """.trimIndent()
            val client = buildClient(body, HttpStatusCode.Created, cap)

            val result =
                client.createTerminal(
                    TerminalDto.CreateTerminalRequestDto(name = "Kitchen 1", role = "KITCHEN", fiscal = false),
                )

            assertEquals("POST", cap.method)
            assertEquals("/api/v1/terminals", cap.path)
            assertEquals("TER-009", result.terminalId)
            assertEquals(false, result.fiscal)
        }

    @Test
    fun `generatePairingCode posts to id pairing-code endpoint and decodes 201 response`() =
        runTest {
            val cap = Captured()
            val body = """{"terminalDbId":9,"terminalId":"TER-009","code":"654321","expiresAt":"2026-07-26T10:05:00Z"}"""
            val client = buildClient(body, HttpStatusCode.Created, cap)

            val result = client.generatePairingCode(9L)

            assertEquals("POST", cap.method)
            assertEquals("/api/v1/terminals/9/pairing-code", cap.path)
            assertEquals("654321", result.code)
            assertEquals(9L, result.terminalDbId)
        }

    @Test
    fun `generatePairingCode sends the register-at-the-TSE choice in its body`() =
        runTest {
            val cap = Captured()
            val body = """{"terminalDbId":9,"terminalId":"TER-009","code":"654321","expiresAt":"2026-07-26T10:05:00Z"}"""
            val client = buildClient(body, HttpStatusCode.Created, cap)

            client.generatePairingCode(9L, registerTseClient = true)

            assertTrue(cap.body.contains("\"registerTseClient\":true"), cap.body)
        }

    @Test
    fun `revokeBinding posts to id revoke endpoint and decodes summary`() =
        runTest {
            val cap = Captured()
            val body =
                """
                {"id":9,"terminalId":"TER-009","terminalBrand":null,"terminalModel":null,
                "isActive":true,"tseClientRegistered":false,"lastSeenAt":"2026-07-26T10:00:00Z",
                "role":"CASHIER","fiscal":true}
                """.trimIndent()
            val client = buildClient(body, HttpStatusCode.OK, cap)

            val result = client.revokeBinding(9L)

            assertEquals("POST", cap.method)
            assertEquals("/api/v1/terminals/9/revoke", cap.path)
            assertEquals("TER-009", result.terminalId)
        }

    @Test
    fun `openStationSession posts to the session endpoint and decodes the masked set`() =
        runTest {
            val cap = Captured()
            val body =
                """
                {"accessToken":"station-jwt","expiresAt":"2030-01-01T00:00:00Z","terminalId":"TER-007",
                "role":"ORDER_STATION","capabilities":["ORDER_READ","ORDER_WRITE"]}
                """.trimIndent()
            val client = buildClient(body, HttpStatusCode.OK, cap)

            val result = client.openStationSession()

            assertEquals("POST", cap.method)
            assertEquals("/api/v1/terminals/session", cap.path)
            assertEquals("station-jwt", result.accessToken)
            assertEquals(setOf("ORDER_READ", "ORDER_WRITE"), result.capabilities)
        }
}
