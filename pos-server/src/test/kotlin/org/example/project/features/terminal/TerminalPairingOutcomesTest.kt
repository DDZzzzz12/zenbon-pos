package org.example.project.features.terminal

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.error.ProblemDetail
import org.example.project.features.terminal.database.tables.TerminalPairingCodeTable
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What POST /api/v1/terminals/pair really answers (E2E-2026-09-18: its KDoc — the OpenAPI source —
 * documented 404/409, the route answers 400). The §14a check "an expired code is refused with a
 * message naming expiry" could not be run live, so it is pinned here.
 */
class TerminalPairingOutcomesTest {
    private suspend fun HttpClient.mintCode(token: String): String {
        val id =
            post("/api/v1/terminals") {
                bearerAuth(token)
                setBody(TextContent("""{"name":"Pair","role":"CASHIER"}""", ContentType.Application.Json))
            }.bodyAsText()
                .let { Regex(""""id":\s*(\d+)""").find(it)!!.groupValues[1] }
        return post("/api/v1/terminals/$id/pairing-code") { bearerAuth(token) }
            .bodyAsText()
            .let { Regex(""""code":\s*"(\d{6})"""").find(it)!!.groupValues[1] }
    }

    private suspend fun HttpClient.pair(
        code: String,
        deviceId: String,
    ): HttpResponse =
        post("/api/v1/terminals/pair") {
            setBody(TextContent("""{"code":"$code","deviceId":"$deviceId"}""", ContentType.Application.Json))
        }

    @Test
    fun `an expired code is refused with a message naming expiry`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val code = client.mintCode(token)
            transaction {
                TerminalPairingCodeTable.update({ TerminalPairingCodeTable.code eq code }) {
                    it[expiresAt] = LocalDateTime.parse("2020-01-01T00:00:00")
                }
            }

            val response = client.pair(code, "dev-PAIR-EXPIRED")

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            val problem = testJson.decodeFromString(ProblemDetail.serializer(), response.bodyAsText())
            assertEquals("PAIRING_CODE_EXPIRED", problem.code)
            assertTrue(problem.detail!!.contains("expired"), problem.detail)
        }

    @Test
    fun `a code that was already used is PAIRING_CODE_INVALID`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val code = client.mintCode(token)
            assertEquals(HttpStatusCode.OK, client.pair(code, "dev-PAIR-FIRST").status)

            val response = client.pair(code, "dev-PAIR-SECOND")

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            assertEquals("PAIRING_CODE_INVALID", testJson.decodeFromString(ProblemDetail.serializer(), response.bodyAsText()).code)
        }
}
