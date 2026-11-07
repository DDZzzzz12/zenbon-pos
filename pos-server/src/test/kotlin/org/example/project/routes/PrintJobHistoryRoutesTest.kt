package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("Print Job History Routes")
class PrintJobHistoryRoutesTest {
    @Nested
    @DisplayName("GET /api/print-job-history")
    inner class GetPrintJobHistory {
        @Test
        fun `returns 200 with valid date range`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/print-job-history?startDate=2026-01-01T00:00:00&endDate=2026-01-31T23:59:59") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/print-job-history?startDate=2026-01-01T00:00:00&endDate=2026-01-31T23:59:59")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 when startDate missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/print-job-history?endDate=2026-01-31T23:59:59") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 when endDate missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/print-job-history?startDate=2026-01-01T00:00:00") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for invalid startDate format`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/print-job-history?startDate=not-a-date&endDate=2026-01-31T23:59:59") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for invalid status filter`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/print-job-history?startDate=2026-01-01T00:00:00&endDate=2026-01-31T23:59:59&status=INVALID") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for invalid printType filter`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/print-job-history?startDate=2026-01-01T00:00:00&endDate=2026-01-31T23:59:59&printType=INVALID") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }
}
