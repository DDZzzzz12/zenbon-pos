package org.example.project.core.network.error

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.example.project.core.error.PosErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PosApiExceptionDecoderTest {
    @Test
    fun `decodes full ProblemDetail body`() =
        runTest {
            val body =
                """
                {
                  "type": "https://pos.example.com/errors/employee-not-found",
                  "title": "Employee not found",
                  "status": 404,
                  "code": "EMPLOYEE_NOT_FOUND",
                  "detail": "employee 42 not found",
                  "instance": "/api/employees/42",
                  "traceId": "trace-abc"
                }
                """.trimIndent()
            val resp = mockResponse(HttpStatusCode.NotFound, body, contentType = "application/problem+json")

            val ex = PosApiExceptionDecoder.decode(resp)

            assertEquals(PosErrorCode.EMPLOYEE_NOT_FOUND, ex.code)
            assertEquals(404, ex.httpStatus)
            assertEquals("employee 42 not found", ex.message)
            assertEquals("trace-abc", ex.traceId)
            assertTrue(ex.fieldErrors.isEmpty())
        }

    @Test
    fun `falls back to INTERNAL_ERROR for plain-text body and reads traceId from header`() =
        runTest {
            val resp =
                mockResponse(
                    status = HttpStatusCode.BadGateway,
                    body = "upstream gateway error",
                    contentType = "text/plain",
                    extraHeaders = mapOf("X-Request-ID" to "trace-from-header"),
                )

            val ex = PosApiExceptionDecoder.decode(resp)

            assertEquals(PosErrorCode.INTERNAL_ERROR, ex.code)
            assertEquals(502, ex.httpStatus)
            assertEquals("upstream gateway error", ex.message)
            assertEquals("trace-from-header", ex.traceId)
        }

    @Test
    fun `unknown code field defaults to INTERNAL_ERROR`() =
        runTest {
            val body =
                """
                {
                  "title": "Weird error",
                  "status": 500,
                  "code": "NOT_A_REAL_CODE",
                  "detail": "boom",
                  "traceId": "trace-x"
                }
                """.trimIndent()
            val resp = mockResponse(HttpStatusCode.InternalServerError, body, contentType = "application/problem+json")

            val ex = PosApiExceptionDecoder.decode(resp)

            assertEquals(PosErrorCode.INTERNAL_ERROR, ex.code)
            assertEquals(500, ex.httpStatus)
            assertEquals("boom", ex.message)
            assertEquals("trace-x", ex.traceId)
        }

    @Test
    fun `validation error populates fieldErrors`() =
        runTest {
            val body =
                """
                {
                  "title": "Validation failed",
                  "status": 400,
                  "code": "VALIDATION_FAILED",
                  "detail": "request body invalid",
                  "traceId": null,
                  "errors": [
                    {"field": "username", "code": "BLANK"},
                    {"field": "pin", "code": "TOO_SHORT"}
                  ]
                }
                """.trimIndent()
            val resp = mockResponse(HttpStatusCode.BadRequest, body, contentType = "application/problem+json")

            val ex = PosApiExceptionDecoder.decode(resp)

            assertEquals(PosErrorCode.VALIDATION_FAILED, ex.code)
            assertEquals(400, ex.httpStatus)
            assertEquals(2, ex.fieldErrors.size)
            assertEquals("username", ex.fieldErrors[0].field)
            assertEquals("BLANK", ex.fieldErrors[0].code)
            assertEquals("pin", ex.fieldErrors[1].field)
            assertNull(ex.traceId)
        }

    private suspend fun mockResponse(
        status: HttpStatusCode,
        body: String,
        contentType: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val headers =
            headersOf(
                *(mapOf("Content-Type" to contentType) + extraHeaders)
                    .map {
                        it.key to listOf(it.value)
                    }.toTypedArray(),
            )
        val client =
            HttpClient(
                MockEngine { _ ->
                    respond(content = body, status = status, headers = headers)
                },
            )
        return client.get("http://test/")
    }
}
