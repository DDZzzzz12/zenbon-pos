package org.example.project.features.terminal

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalClientKindPersistenceTest {
    @Test
    fun `pairing from a BROWSER client stores and reports client kind`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
            val token = TestAuthHelper.loginAsAdmin(client)
            val id = client.provisionTerminal(token)

            val paired = client.pair(id, token, """"deviceId":"pos-preview-t1","clientKind":"BROWSER"""")

            assertEquals("BROWSER", paired.clientKind())
            val listed = client.get("/api/v1/terminals") { bearerAuth(token) }.bodyAsText()
            // Only one terminal exists in this app instance, so any BROWSER hit is its summary row.
            assertTrue(
                Regex(""""clientKind":\s*"BROWSER"""").containsMatchIn(listed),
                "terminal list must report clientKind BROWSER, was: $listed",
            )
            Unit
        }

    @Test
    fun `pairing without clientKind defaults to NATIVE`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val id = client.provisionTerminal(token)

            val paired = client.pair(id, token, """"deviceId":"android-1"""")

            assertEquals("NATIVE", paired.clientKind())
            Unit
        }

    @Test
    fun `a browser device id is stored as BROWSER even when the client claims NATIVE`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
            val token = TestAuthHelper.loginAsAdmin(client)
            val id = client.provisionTerminal(token)

            val paired = client.pair(id, token, """"deviceId":"pos-preview-t2","clientKind":"NATIVE"""")

            assertEquals("BROWSER", paired.clientKind())
            Unit
        }
}

private fun String.clientKind(): String = Regex(""""clientKind":\s*"(\w+)"""").find(this)!!.groupValues[1]

private suspend fun HttpClient.provisionTerminal(token: String): String =
    post("/api/v1/terminals") {
        bearerAuth(token)
        setBody(TextContent("""{"name":"Bar","role":"CASHIER"}""", ContentType.Application.Json))
    }.bodyAsText()
        .let { Regex(""""id":\s*(\d+)""").find(it)!!.groupValues[1] }

/** Mints a code and redeems it with the given raw JSON members; returns the pair response body. */
private suspend fun HttpClient.pair(
    terminalId: String,
    token: String,
    members: String,
): String {
    val code =
        post("/api/v1/terminals/$terminalId/pairing-code") { bearerAuth(token) }
            .bodyAsText()
            .let { Regex(""""code":\s*"(\d{6})"""").find(it)!!.groupValues[1] }
    val response =
        post("/api/v1/terminals/pair") {
            setBody(TextContent("""{"code":"$code",$members}""", ContentType.Application.Json))
        }
    val body = response.bodyAsText()
    assertEquals(200, response.status.value, "pair failed: $body")
    return body
}
