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

@DisplayName("Statistics Routes")
class StatisticsRoutesTest {
    private val validStartDate = "2026-01-01T00:00:00"
    private val validEndDate = "2026-12-31T23:59:59"

    @Nested
    @DisplayName("GET /api/statistics/sales/overview")
    inner class SalesOverview {
        @Test
        fun `returns 200 with valid params`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/statistics/sales/overview?start_date=$validStartDate&end_date=$validEndDate") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 400 when start_date missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/statistics/sales/overview?end_date=$validEndDate") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 when end_date missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/statistics/sales/overview?start_date=$validStartDate") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for invalid date format`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/statistics/sales/overview?start_date=bad-date&end_date=$validEndDate") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/statistics/sales/overview?start_date=$validStartDate&end_date=$validEndDate")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 403 when cashier filters by employee_id`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response =
                    client.get("/api/statistics/sales/overview?start_date=$validStartDate&end_date=$validEndDate&employee_id=some-id") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 403 when cashier requests training_only`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response =
                    client.get("/api/statistics/sales/overview?start_date=$validStartDate&end_date=$validEndDate&training_only=true") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/statistics/sales/bills")
    inner class SalesBills {
        @Test
        fun `returns 200 with valid params`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/statistics/sales/bills?start_date=$validStartDate&end_date=$validEndDate") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 400 when dates missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/statistics/sales/bills") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/statistics/sales/bills?start_date=$validStartDate&end_date=$validEndDate")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/statistics/sales/by-table")
    inner class SalesByTable {
        @Test
        fun `returns 200 with valid params`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/statistics/sales/by-table?start_date=$validStartDate&end_date=$validEndDate") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/statistics/sales/by-table?start_date=$validStartDate&end_date=$validEndDate")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/statistics/sales/by-employee")
    inner class SalesByEmployee {
        @Test
        fun `returns 200 with valid params`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/statistics/sales/by-employee?start_date=$validStartDate&end_date=$validEndDate") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/statistics/sales/by-employee?start_date=$validStartDate&end_date=$validEndDate")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/statistics/sales/payments")
    inner class SalesPayments {
        @Test
        fun `returns 200 with valid params`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/statistics/sales/payments?start_date=$validStartDate&end_date=$validEndDate") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/statistics/sales/payments?start_date=$validStartDate&end_date=$validEndDate")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/statistics/sales/sessions")
    inner class SalesSessions {
        @Test
        fun `returns 200 with valid params`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/statistics/sales/sessions?start_date=$validStartDate&end_date=$validEndDate") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/statistics/sales/sessions?start_date=$validStartDate&end_date=$validEndDate")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/statistics/sales/canceled-items")
    inner class CanceledItems {
        @Test
        fun `returns 200 with valid params`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/statistics/sales/canceled-items?start_date=$validStartDate&end_date=$validEndDate") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/statistics/sales/canceled-items?start_date=$validStartDate&end_date=$validEndDate")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }
}
