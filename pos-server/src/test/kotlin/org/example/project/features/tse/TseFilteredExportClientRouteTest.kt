package org.example.project.features.tse

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * F69: the filtered exports take an optional `clientId`. Absent (today's clients) or blank means
 * all clients; a value must be a known client, else 400 — checked before the TSE is touched.
 *
 * The accepted cases do reach the export, which on this host has no TSE and fails with a
 * TSE-specific status; what matters is that it is not the 400 of the client check.
 */
@DisplayName("POST /api/tse/export/{time,transaction}-filtered — clientId")
class TseFilteredExportClientRouteTest {
    private val timeBody = """{"startDay":"2026-09-01","endDay":"2026-09-01"%s}"""
    private val txBody = """{"startTransaction":1,"endTransaction":5%s}"""

    private suspend fun HttpClient.export(
        token: String,
        path: String,
        body: String,
    ): HttpResponse =
        post("/api/tse/export/$path") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun withClient(clientId: String?): String = clientId?.let { ""","clientId":"$it"""" } ?: ""

    @Test
    fun `an unknown client is a 400 on both filtered exports`() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createFiscalRegisteredTerminal(deviceId = "dev-1", clientId = "TER-001")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            for ((path, body) in listOf("time-filtered" to timeBody, "transaction-filtered" to txBody)) {
                val response = client.export(token, path, body.format(withClient("TER-999")))
                assertEquals(HttpStatusCode.BadRequest, response.status, path)
                val text = response.bodyAsText()
                assertTrue("VALIDATION_FAILED" in text && "TER-999" in text, "$path: $text")
            }
        }

    @Test
    fun `a terminal's client, a blank one and an absent one all pass the client check`() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createFiscalRegisteredTerminal(deviceId = "dev-1", clientId = "TER-001")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            for ((path, body) in listOf("time-filtered" to timeBody, "transaction-filtered" to txBody)) {
                for (clientId in listOf("TER-001", "  ", null)) {
                    val response = client.export(token, path, body.format(withClient(clientId)))
                    val text = response.bodyAsText()
                    assertNotEquals(HttpStatusCode.BadRequest, response.status, "$path clientId=$clientId: $text")
                    assertFalse("Unknown TSE client" in text, "$path clientId=$clientId: $text")
                }
            }
        }
}
