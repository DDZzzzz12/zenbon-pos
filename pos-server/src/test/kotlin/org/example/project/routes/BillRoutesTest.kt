package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("Bill Routes")
class BillRoutesTest {
    @Nested
    @DisplayName("GET /api/v1/bills")
    inner class GetAllBills {
        @Test
        fun `returns 200 with empty list`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/bills") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/bills")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/bills/{id}")
    inner class GetBillById {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/bills/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/bills/number/{billNumber}")
    inner class GetBillByNumber {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/bills/number/B001")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/bills/tse-transaction/{transactionNumber}")
    inner class GetBillByTseTransaction {
        @Test
        fun `returns 400 for invalid transaction number`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/bills/tse-transaction/invalid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/bills/tse-transaction/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/bills/table-session/{tableSessionId}")
    inner class GetBillsByTableSession {
        @Test
        fun `returns 400 for invalid session ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/bills/table-session/invalid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 200 for valid session ID`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createTableWithSession()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/bills/table-session/1") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/bills/table-session/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/bills/search")
    inner class SearchBills {
        @Test
        fun `returns 200 with no params`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/bills/search") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 400 for invalid date format`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/bills/search?startDate=not-a-date") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/bills/search")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/bills/summaries")
    inner class GetBillSummaries {
        @Test
        fun `returns 200`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/bills/summaries") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/bills/summaries")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/bills/statistics")
    inner class GetBillStatistics {
        @Test
        fun `returns 400 when dates missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/bills/statistics") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 200 with valid dates`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get(
                        "/api/v1/bills/statistics?startDate=2026-01-01T00:00:00&endDate=2026-12-31T23:59:59",
                    ) { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 400 for invalid date format`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/bills/statistics?startDate=bad&endDate=bad") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/bills/statistics?startDate=2026-01-01T00:00:00&endDate=2026-12-31T23:59:59")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/v1/bills")
    inner class CreateBill {
        private val deviceId = "bill-routes-create-device"

        // The terminal-capability ceiling in RouteAuthorizationPlugin runs in onCall, BEFORE
        // routing (and therefore before route-level authenticate("auth-jwt")) for the exact
        // transactional routes. A request with no auth at all also carries no resolvable device
        // identity, so it is now refused as "unpaired" (409) rather than reaching the JWT check
        // (401). This is the new, intended ordering for this exact route.
        @Test
        fun `returns 409 without auth (ceiling runs before authentication)`() =
            testApp { client ->
                val response =
                    client.post("/api/v1/bills") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Conflict, response.status)
            }

        @Test
        fun `returns 400 for invalid request body`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TER-BILL-CREATE")
                },
            ) { client ->
                val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
                val response =
                    client.post("/api/v1/bills") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/v1/bills — completed session guard")
    inner class CreateBillCompletedSession {
        private val deviceId = "bill-completed-session-device"

        @Test
        fun `returns 409 when creating a bill for a completed session`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    val tws = TestData.createTableWithSession()
                    TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TER-BILL-COMPLETED")
                    transaction { TableSession.findById(tws.session.id.value)!!.end() }
                },
            ) { client ->
                val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
                val billJson =
                    """{"tableSessionId":1,"orderItems":[],"billAmount":"0.00","payments":[]}"""
                val response =
                    client.post("/api/v1/bills") {
                        bearerAuth(token)
                        setBody(TextContent(billJson, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Conflict, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/v1/bills/{id}")
    inner class DeleteBill {
        // See CreateBill's equivalent test: the ceiling runs before authentication for this
        // exact transactional route, so a fully unauthenticated call is now refused as
        // "unpaired" (409) rather than reaching the JWT check (401).
        @Test
        fun `returns 409 without auth (ceiling runs before authentication)`() =
            testApp { client ->
                val response = client.delete("/api/v1/bills/1")
                assertEquals(HttpStatusCode.Conflict, response.status)
            }
    }
}
