package org.example.project.infrastructure.errors

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.example.project.core.error.PosErrorCode
import org.example.project.core.error.ProblemDetail
import org.example.project.infrastructure.plugins.MAX_TRACE_ID_LENGTH
import org.example.project.infrastructure.plugins.TRACE_ID_HEADER
import org.example.project.infrastructure.plugins.configureCallId
import org.example.project.infrastructure.plugins.configureCallLogging
import org.example.project.infrastructure.plugins.configureStatusPages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val PROBLEM_JSON = ContentType("application", "problem+json")

private suspend fun HttpResponse.assertProblemDetail(
    expectedStatus: HttpStatusCode,
    expectedCode: PosErrorCode,
): ProblemDetail {
    assertEquals(expectedStatus, status)
    assertEquals(PROBLEM_JSON, contentType()?.withoutParameters())
    val pd = body<ProblemDetail>()
    assertEquals(expectedCode.name, pd.code)
    assertTrue(!pd.traceId.isNullOrBlank())
    return pd
}

class ProblemDetailTest {
    private fun problemClient(block: suspend (HttpClient) -> Unit) =
        testApplication {
            application {
                configureCallId()
                configureCallLogging()
                configureStatusPages()
                routing {
                    get("/throw-pos") {
                        throw PosException(
                            PosErrorCode.EMPLOYEE_NOT_FOUND,
                            HttpStatusCode.NotFound,
                            "x",
                        )
                    }
                    get("/throw-runtime") { throw RuntimeException("boom") }
                }
            }
            val client =
                createClient {
                    install(ContentNegotiation) {
                        json()
                        json(contentType = PROBLEM_JSON)
                    }
                    expectSuccess = false
                }
            block(client)
        }

    @Test
    fun `PosException renders problem+json with correct code and trace id`() =
        problemClient { client ->
            val resp = client.get("/throw-pos")
            val pd = resp.assertProblemDetail(HttpStatusCode.NotFound, PosErrorCode.EMPLOYEE_NOT_FOUND)
            assertEquals("x", pd.detail)
            assertEquals("/throw-pos", pd.instance)
            assertEquals(404, pd.status)
            assertNotNull(resp.headers[TRACE_ID_HEADER])
        }

    @Test
    fun `incoming X-Request-ID is echoed and used as traceId`() =
        problemClient { client ->
            val resp =
                client.get("/throw-pos") {
                    headers { append(TRACE_ID_HEADER, "abc-123") }
                }
            val pd = resp.assertProblemDetail(HttpStatusCode.NotFound, PosErrorCode.EMPLOYEE_NOT_FOUND)
            assertEquals("abc-123", pd.traceId)
            assertEquals("abc-123", resp.headers[TRACE_ID_HEADER])
        }

    @Test
    fun `empty incoming X-Request-ID is replaced with generated UUID`() =
        problemClient { client ->
            val resp =
                client.get("/throw-pos") {
                    headers { append(TRACE_ID_HEADER, "") }
                }
            val pd = resp.assertProblemDetail(HttpStatusCode.NotFound, PosErrorCode.EMPLOYEE_NOT_FOUND)
            assertNotNull(pd.traceId)
            assertTrue(pd.traceId!!.isNotBlank())
            assertEquals(pd.traceId, resp.headers[TRACE_ID_HEADER])
        }

    /**
     * A client-controlled header must not be able to destroy an audit row.
     *
     * `verify` bounded only blankness, so a 65-character `X-Request-ID` was accepted, echoed and
     * published to MDC — from where it was captured and failed the INSERT into
     * `transaction_audit_log.trace_id`, a varchar(64). Every audit write sits inside a
     * swallow-and-log catch, so the row was lost with only a log line; in `cancelEntry` the insert
     * runs inside the ambient dbQuery, so it also left that transaction failed with the cause
     * already swallowed.
     *
     * Rejecting rather than truncating is the point: the generated fallback is echoed, logged AND
     * stored as one value, where a `take(64)` at the capture site would leave the three disagreeing.
     */
    @Test
    fun `an over-long incoming X-Request-ID is replaced with a generated UUID that fits the column`() =
        problemClient { client ->
            val tooLong = "x".repeat(MAX_TRACE_ID_LENGTH + 1)
            val resp =
                client.get("/throw-pos") {
                    headers { append(TRACE_ID_HEADER, tooLong) }
                }

            val pd = resp.assertProblemDetail(HttpStatusCode.NotFound, PosErrorCode.EMPLOYEE_NOT_FOUND)
            assertNotNull(pd.traceId)
            assertTrue(pd.traceId != tooLong, "an over-long client id must not be accepted")
            assertTrue(
                pd.traceId!!.length <= MAX_TRACE_ID_LENGTH,
                "whatever is accepted must fit transaction_audit_log.trace_id; got ${pd.traceId!!.length} chars",
            )
            // Header, log MDC and audit row must all carry the SAME value — that is the column's purpose.
            assertEquals(pd.traceId, resp.headers[TRACE_ID_HEADER])
        }

    @Test
    fun `unhandled Throwable maps to INTERNAL_ERROR with 500`() =
        problemClient { client ->
            val resp = client.get("/throw-runtime")
            resp.assertProblemDetail(HttpStatusCode.InternalServerError, PosErrorCode.INTERNAL_ERROR)
        }

    @Test
    fun `JWT-protected route without token returns AUTH_INVALID_CREDENTIALS`() =
        testApplication {
            application {
                configureCallId()
                configureCallLogging()
                install(Authentication) {
                    jwt("auth-jwt") {
                        realm = "test"
                        validate { JWTPrincipal(it.payload) }
                    }
                }
                configureStatusPages()
                routing {
                    authenticate("auth-jwt") {
                        get("/secure") { call.respond("ok") }
                    }
                }
            }
            val client =
                createClient {
                    install(ContentNegotiation) {
                        json()
                        json(contentType = PROBLEM_JSON)
                    }
                    expectSuccess = false
                }
            val resp = client.get("/secure")
            resp.assertProblemDetail(HttpStatusCode.Unauthorized, PosErrorCode.AUTH_INVALID_CREDENTIALS)
        }
}
