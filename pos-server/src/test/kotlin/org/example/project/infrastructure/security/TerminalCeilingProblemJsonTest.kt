package org.example.project.infrastructure.security

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.example.project.core.error.ProblemDetail
import org.example.project.features.terminal.database.tables.TerminalTable
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2E-2026-09-18 #15: the terminal-capability ceiling answered `{"error": …}` while every other
 * refusal is application/problem+json with a typed code and a traceId. Statuses are unchanged.
 */
class TerminalCeilingProblemJsonTest {
    private val deviceId = "ceiling-problem-device"

    private suspend fun postOrder(
        client: HttpClient,
        token: String,
    ): HttpResponse =
        client.post("/api/orders") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

    private suspend fun assertProblem(
        response: HttpResponse,
        status: HttpStatusCode,
        code: String,
    ) {
        val body = response.bodyAsText()
        assertEquals(status, response.status, body)
        assertEquals("application/problem+json", response.contentType()?.withoutParameters()?.toString(), body)
        val problem = testJson.decodeFromString(ProblemDetail.serializer(), body)
        assertEquals(code, problem.code, body)
        assertTrue(!problem.traceId.isNullOrBlank(), "a ceiling refusal carries a traceId like every other error")
    }

    private fun setupAdmin() {
        TestData.createShopInfo()
        TestData.createAdmin()
    }

    @Test
    fun `no paired terminal is DEVICE_NOT_PAIRED`() =
        testApp(setupData = { setupAdmin() }) { client ->
            val token = TestAuthHelper.loginAs(client, "admin") // no device
            assertProblem(postOrder(client, token), HttpStatusCode.Conflict, "DEVICE_NOT_PAIRED")
        }

    @Test
    fun `a deactivated terminal is TERMINAL_DEACTIVATED`() =
        testApp(setupData = {
            setupAdmin()
            TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TCP-1")
            transaction { Terminal.find { TerminalTable.deviceId eq deviceId }.single().isActive = false }
        }) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            assertProblem(postOrder(client, token), HttpStatusCode.Forbidden, "TERMINAL_DEACTIVATED")
        }

    @Test
    fun `a non-fiscal terminal is TERMINAL_NOT_FISCAL`() =
        testApp(setupData = {
            setupAdmin()
            TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TCP-2")
            transaction { Terminal.find { TerminalTable.deviceId eq deviceId }.single().fiscal = false }
        }) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            assertProblem(postOrder(client, token), HttpStatusCode.Forbidden, "TERMINAL_NOT_FISCAL")
        }
}
