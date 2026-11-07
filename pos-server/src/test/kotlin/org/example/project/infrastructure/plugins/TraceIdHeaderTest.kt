package org.example.project.infrastructure.plugins

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every response carries exactly ONE `X-Request-ID`.
 *
 * Ktor's `CallIdConfig.header(name)` already registers a reply to that header; a second
 * `replyToHeader(name)` on top of it made every response carry the header twice. `headers[name]`
 * returns only the first value, so these tests read `getAll` instead.
 */
class TraceIdHeaderTest {
    private fun HttpResponse.traceIds(): List<String> = headers.getAll(TRACE_ID_HEADER).orEmpty()

    @Test
    fun `health carries a single generated X-Request-ID`() =
        testApp { client ->
            val response = client.get("/health")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, response.traceIds().size, "X-Request-ID values: ${response.traceIds()}")
        }

    @Test
    fun `health echoes a client X-Request-ID exactly once`() =
        testApp { client ->
            val response = client.get("/health") { header(TRACE_ID_HEADER, "trace-abc-123") }
            assertEquals(listOf("trace-abc-123"), response.traceIds())
        }

    @Test
    fun `api route carries a single X-Request-ID`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.get("/api/admin/permissions") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, response.traceIds().size, "X-Request-ID values: ${response.traceIds()}")
        }

    @Test
    fun `api error response carries a single X-Request-ID`() =
        testApp { client ->
            val response = client.get("/api/admin/permissions") { header(TRACE_ID_HEADER, "trace-401") }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(listOf("trace-401"), response.traceIds())
        }
}
