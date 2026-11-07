package org.example.project.features.terminal

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import org.example.project.core.error.ProblemDetail
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * QA 2026-10-06: `POST /api/v1/terminals` with a role that names no TerminalRole silently provisioned a
 * fiscal CASHIER till (`TerminalRole.fromCode` defaults), while the update route refused the same value.
 * An absent role keeps its CASHIER default — older clients send none.
 */
class TerminalCreateRoleTest {
    private suspend fun HttpClient.create(
        token: String,
        body: String,
    ): HttpResponse =
        post("/api/v1/terminals") {
            bearerAuth(token)
            setBody(TextContent(body, ContentType.Application.Json))
        }

    @Test
    fun `an unknown role is refused with a role field error and creates nothing`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)

            val response = client.create(token, """{"name":"Typo","role":"KITCHEN_DISPLAY"}""")

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            val problem = testJson.decodeFromString(ProblemDetail.serializer(), response.bodyAsText())
            assertEquals("VALIDATION_FAILED", problem.code)
            assertEquals(listOf("role" to "UNKNOWN"), problem.errors?.map { it.field to it.code })
            val list = client.get("/api/v1/terminals") { bearerAuth(token) }.bodyAsText()
            assertFalse(list.contains("Typo"), list)
        }

    @Test
    fun `a blank role is refused too`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)

            val response = client.create(token, """{"name":"Blank","role":" "}""")

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        }

    @Test
    fun `an absent role still provisions a fiscal CASHIER, as older clients expect`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)

            val response = client.create(token, """{"name":"Old client"}""")

            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.Created, response.status, body)
            assertTrue(body.contains(""""role":"CASHIER""""), body)
            assertTrue(body.contains(""""fiscal":true"""), body)
        }

    @Test
    fun `a known role is taken case-insensitively, as before`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)

            val response = client.create(token, """{"name":"Küche","role":"kitchen"}""")

            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.Created, response.status, body)
            assertTrue(body.contains(""""role":"KITCHEN""""), body)
            assertTrue(body.contains(""""fiscal":false"""), body)
        }
}
