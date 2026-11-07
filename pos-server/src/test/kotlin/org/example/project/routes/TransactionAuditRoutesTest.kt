package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.example.project.infrastructure.plugins.TRACE_ID_MDC_KEY
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.errorHandling.ExceptionConverter
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("Transaction Audit Routes")
class TransactionAuditRoutesTest {
    @Nested
    @DisplayName("GET /api/audit/transactions")
    inner class GetTransactionLogs {
        @Test
        fun `returns 200 with default params`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/audit/transactions") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `supports filter params`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/audit/transactions?operation=CREATE&entityType=ORDER&limit=10") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/audit/transactions")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        /**
         * The trace id must survive the whole chain — column, DAO entity, `toDto()` mapper, JSON.
         *
         * `TransactionAuditLogDto.traceId` defaults to null, so a mapper that never reads the column
         * COMPILES and serves every row with a null id. Nothing but an assertion over the response
         * body notices; reading the mapper does not. Deliberately end-to-end rather than a direct
         * `toDto()` call, because the route is where an operator actually looks the id up.
         */
        @Test
        @DisplayName("the trace id survives the mapper and reaches the response body")
        fun `serves the trace id it stored`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val traceId = "3d41f0aa-6d2c-45b7-9a02-b7c1f4d8e5a9"
                MDC.put(TRACE_ID_MDC_KEY, traceId)
                try {
                    ExceptionConverter.catchingThrows(
                        context = "route-test",
                        employeeId = "tester",
                        operation = "CREATE_BILL",
                        entityType = "BILL",
                        block = { Unit },
                    )
                } finally {
                    MDC.remove(TRACE_ID_MDC_KEY)
                }

                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/audit/transactions") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(
                    body.contains(traceId),
                    "the stored trace id must be served; a mapper that drops it still compiles. Body: $body",
                )
            }
    }

    @Nested
    @DisplayName("GET /api/audit/transactions/paginated")
    inner class GetPaginatedLogs {
        @Test
        fun `returns 200 with default params`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/audit/transactions/paginated") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `supports filter params`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/audit/transactions/paginated?operation=DELETE&entityType=PRODUCT&page=0&size=10") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `accepts search, amount range, and auth-type params`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get(
                        "/api/audit/transactions/paginated?search=order&amountMin=1&amountMax=100&authorizationType=DIRECT&page=0&size=10",
                    ) { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/audit/transactions/paginated")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }
}
