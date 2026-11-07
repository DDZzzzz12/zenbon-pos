package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.http.*
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("Order Item Routes")
class OrderItemRoutesTest {
    @Nested
    @DisplayName("GET /api/items/session/{sessionId}")
    inner class GetItemsBySession {
        @Test
        fun `returns 200 for valid session`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createTableWithSession()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/items/session/1") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 400 for invalid session ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/items/session/invalid") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for negative session ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/items/session/-1") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `supports status filter query param`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createTableWithSession()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/items/session/1?status=OPEN") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/items/session/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/items/cancellations")
    inner class GetCancellations {
        @Test
        fun `returns 200 with valid date range for manager`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response =
                    client.get("/api/items/cancellations?startDate=2026-01-01T00:00:00&endDate=2026-12-31T23:59:59") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 400 when dates missing`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response =
                    client.get("/api/items/cancellations") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/items/cancellations?startDate=2026-01-01&endDate=2026-12-31")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("TableSession.end() — dangling pointer invariant")
    inner class EndClearsDanglingPointer {
        @Test
        fun `end clears dangling table pointer even when table is not OCCUPIED`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    val tws = TestData.createTableWithSession()
                    transaction {
                        val s = TableSession.findById(tws.session.id.value)!!
                        // Simulate a table that lost OCCUPIED status but still references the session
                        s.table.tableStatus = TableStatus.FREE
                        // currentSession still points at s (dangling precondition)
                        s.end()
                        // Invariant: a completed session must not remain a table's currentSession
                        assertEquals(null, s.table.currentSession)
                        assertEquals(true, s.completed)
                    }
                },
            ) { _ -> }
    }

    @Nested
    @DisplayName("cancel items — completed session guard")
    inner class CancelOnCompletedSession {
        private val deviceId = "cancel-completed-session-device"

        @Test
        fun `returns 409 when cancelling items on a completed-but-current session`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    val tws = TestData.createTableWithSession()
                    TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TER-CANCEL-COMPLETED")
                    transaction {
                        val s = TableSession.findById(tws.session.id.value)!!
                        // Simulate the corrupt state: session completed but table still
                        // OCCUPIED and pointing at it.
                        s.completed = true
                        s.endTime = currentTimeStamp()
                        val t = s.table
                        t.tableStatus = TableStatus.OCCUPIED
                        t.currentSession = s
                    }
                },
            ) { client ->
                val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
                val response =
                    client.post("/api/items/cancel/1") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("""{"itemIds":[1],"reason":"Falscher Artikel erfasst"}""")
                    }
                assertEquals(HttpStatusCode.Conflict, response.status)
            }
    }
}
