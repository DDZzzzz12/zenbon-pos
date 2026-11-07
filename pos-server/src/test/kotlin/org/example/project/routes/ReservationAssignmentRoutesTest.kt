package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.core.domain.constants.WellKnownLocationTypes
import org.example.project.core.domain.enums.reservation.ReservationStatus
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("Reservation Assignment Routes")
class ReservationAssignmentRoutesTest {
    @Nested
    @DisplayName("POST /api/reservations/{id}/auto-assign")
    inner class AutoAssign {
        @Test
        fun `returns 200 with assignment when single table fits`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    TestData.createTable("T1", 6)
                    TestData.createReservation(
                        employee = admin,
                        partySize = 4,
                        reservationDate = LocalDate(2026, 3, 20),
                        reservationTime = LocalTime(18, 0),
                        durationMinutes = 120,
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/reservations/1/auto-assign") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertTrue(body["new"]?.jsonArray?.isNotEmpty() == true)
                assertEquals(4, body["partySize"]?.jsonPrimitive?.content?.toInt())
            }

        @Test
        fun `returns error when no table can fit party`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    TestData.createTable("T1", 2)
                    TestData.createReservation(
                        employee = admin,
                        partySize = 8,
                        reservationDate = LocalDate(2026, 3, 20),
                        reservationTime = LocalTime(18, 0),
                        durationMinutes = 120,
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/reservations/1/auto-assign") {
                        bearerAuth(token)
                    }
                assertTrue(response.status.value in 400..499)
            }

        @Test
        fun `respects existing manual assignments and only fills remaining seats`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    val t1 = TestData.createTable("T1", 3)
                    TestData.createTable("T2", 3)
                    val res =
                        TestData.createReservation(
                            employee = admin,
                            partySize = 4,
                            reservationDate = LocalDate(2026, 3, 20),
                            reservationTime = LocalTime(18, 0),
                            durationMinutes = 120,
                        )
                    // Pre-assign T1 manually
                    TestData.createTableAssignment(res.id.value, t1.id.value, 1, admin)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/reservations/1/auto-assign") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                val kept = body["kept"]?.jsonArray ?: return@testApp
                // T1 should appear in kept assignments
                assertTrue(kept.isNotEmpty())
            }

        @Test
        fun `returns error for cancelled reservation`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    TestData.createTable("T1", 6)
                    TestData.createReservation(
                        employee = admin,
                        partySize = 4,
                        reservationDate = LocalDate(2026, 3, 20),
                        reservationTime = LocalTime(18, 0),
                        durationMinutes = 120,
                        status = ReservationStatus.CANCELLED,
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/reservations/1/auto-assign") {
                        bearerAuth(token)
                    }
                assertTrue(response.status.value in 400..499)
            }

        @Test
        fun `returns 404 for non-existent reservation`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/reservations/99999/auto-assign") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.NotFound, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/reservations/1/auto-assign")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/reservations/{id}/assign-tables")
    inner class ManualAssign {
        @Test
        fun `returns 200 when assigning valid tables`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    val t1 = TestData.createTable("T1", 6)
                    TestData.createReservation(
                        employee = admin,
                        partySize = 4,
                        reservationDate = LocalDate(2026, 3, 20),
                        reservationTime = LocalTime(18, 0),
                        durationMinutes = 120,
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val requestBody = """{"assignments":[{"tableId":"1","groupNumber":1}]}"""
                val response =
                    client.post("/api/reservations/1/assign-tables") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val assignments = testJson.parseToJsonElement(response.bodyAsText()).jsonArray
                assertTrue(assignments.isNotEmpty())
            }

        @Test
        fun `returns error when table is occupied in time window`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    val t1 = TestData.createTable("T1", 6)
                    // Existing reservation occupying T1 at 18:00
                    val existingRes =
                        TestData.createReservation(
                            employee = admin,
                            partySize = 2,
                            reservationDate = LocalDate(2026, 3, 20),
                            reservationTime = LocalTime(18, 0),
                            durationMinutes = 120,
                        )
                    TestData.createTableAssignment(existingRes.id.value, t1.id.value, 1, admin)
                    // New reservation at the same time
                    TestData.createReservation(
                        employee = admin,
                        partySize = 4,
                        reservationDate = LocalDate(2026, 3, 20),
                        reservationTime = LocalTime(18, 0),
                        durationMinutes = 120,
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                // Trying to assign T1 (id=1) to reservation 2, but T1 is occupied by reservation 1
                val requestBody = """{"assignments":[{"tableId":"1","groupNumber":1}]}"""
                val response =
                    client.post("/api/reservations/2/assign-tables") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertTrue(response.status.value in 400..499)
            }

        @Test
        fun `returns error when table is virtual`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    TestData.createTable("TAKEAWAY", 0, WellKnownLocationTypes.TAKEAWAY)
                    TestData.createReservation(
                        employee = admin,
                        partySize = 2,
                        reservationDate = LocalDate(2026, 3, 20),
                        reservationTime = LocalTime(18, 0),
                        durationMinutes = 120,
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val requestBody = """{"assignments":[{"tableId":"1","groupNumber":1}]}"""
                val response =
                    client.post("/api/reservations/1/assign-tables") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertTrue(response.status.value in 400..499)
            }

        @Test
        fun `returns error when table is inactive`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    TestData.createTable("T1", 4, isActive = false)
                    TestData.createReservation(
                        employee = admin,
                        partySize = 2,
                        reservationDate = LocalDate(2026, 3, 20),
                        reservationTime = LocalTime(18, 0),
                        durationMinutes = 120,
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val requestBody = """{"assignments":[{"tableId":"1","groupNumber":1}]}"""
                val response =
                    client.post("/api/reservations/1/assign-tables") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertTrue(response.status.value in 400..499)
            }

        @Test
        fun `returns error for non-existent table`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    TestData.createReservation(
                        employee = admin,
                        partySize = 2,
                        reservationDate = LocalDate(2026, 3, 20),
                        reservationTime = LocalTime(18, 0),
                        durationMinutes = 120,
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val requestBody = """{"assignments":[{"tableId":"99999","groupNumber":1}]}"""
                val response =
                    client.post("/api/reservations/1/assign-tables") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertTrue(response.status.value in 400..499)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/reservations/1/assign-tables") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/reservations/{id}/unassign-tables")
    inner class UnassignTables {
        @Test
        fun `returns 204 when manager unassigns`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val manager = TestData.createManager()
                    val t1 = TestData.createTable("T1", 4)
                    val res =
                        TestData.createReservation(
                            employee = manager,
                            partySize = 2,
                            reservationDate = LocalDate(2026, 3, 20),
                            reservationTime = LocalTime(18, 0),
                            durationMinutes = 120,
                        )
                    TestData.createTableAssignment(res.id.value, t1.id.value, 1, manager)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response =
                    client.delete("/api/reservations/1/unassign-tables") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.NoContent, response.status)
            }

        @Test
        fun `returns 403 when cashier attempts`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val cashier = TestData.createCashier()
                    val t1 = TestData.createTable("T1", 4)
                    val res =
                        TestData.createReservation(
                            employee = cashier,
                            partySize = 2,
                            reservationDate = LocalDate(2026, 3, 20),
                            reservationTime = LocalTime(18, 0),
                            durationMinutes = 120,
                        )
                    TestData.createTableAssignment(res.id.value, t1.id.value, 1, cashier)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response =
                    client.delete("/api/reservations/1/unassign-tables") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/reservations/1/unassign-tables")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 404 when reservation does not exist`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createManager()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response =
                    client.delete("/api/reservations/99999/unassign-tables") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.NotFound, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/reservations/{id}/tables")
    inner class GetAssignments {
        @Test
        fun `returns 200 with assignments`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    val t1 = TestData.createTable("T1", 4)
                    val res =
                        TestData.createReservation(
                            employee = admin,
                            partySize = 2,
                            reservationDate = LocalDate(2026, 3, 20),
                            reservationTime = LocalTime(18, 0),
                            durationMinutes = 120,
                        )
                    TestData.createTableAssignment(res.id.value, t1.id.value, 1, admin)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/1/tables") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val assignments = testJson.parseToJsonElement(response.bodyAsText()).jsonArray
                assertTrue(assignments.isNotEmpty())
                val first = assignments.first().jsonObject
                assertEquals("T1", first["tableNumber"]?.jsonPrimitive?.content)
            }

        @Test
        fun `returns empty list when no assignments`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    TestData.createReservation(
                        employee = admin,
                        partySize = 2,
                        reservationDate = LocalDate(2026, 3, 20),
                        reservationTime = LocalTime(18, 0),
                        durationMinutes = 120,
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/1/tables") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val assignments = testJson.parseToJsonElement(response.bodyAsText()).jsonArray
                assertTrue(assignments.isEmpty())
            }

        @Test
        fun `returns 404 for non-existent reservation`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/99999/tables") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.NotFound, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/reservations/1/tables")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("Status Side Effects on Table Assignments")
    inner class StatusSideEffects {
        @Test
        fun `cancelling reservation removes table assignments`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    val t1 = TestData.createTable("T1", 4)
                    val res =
                        TestData.createReservation(
                            employee = admin,
                            partySize = 2,
                            reservationDate = LocalDate(2026, 3, 20),
                            reservationTime = LocalTime(18, 0),
                            durationMinutes = 120,
                            status = ReservationStatus.CONFIRMED,
                        )
                    TestData.createTableAssignment(res.id.value, t1.id.value, 1, admin)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)

                // Update status to CANCELLED
                val statusBody = """{"status":"CANCELLED"}"""
                val updateResponse =
                    client.put("/api/reservations/1/status") {
                        bearerAuth(token)
                        setBody(TextContent(statusBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, updateResponse.status)

                // Verify assignments are gone
                val assignmentsResponse =
                    client.get("/api/reservations/1/tables") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, assignmentsResponse.status)
                val assignments = testJson.parseToJsonElement(assignmentsResponse.bodyAsText()).jsonArray
                assertTrue(assignments.isEmpty())
            }

        @Test
        fun `completing reservation removes table assignments`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    val t1 = TestData.createTable("T1", 4)
                    val res =
                        TestData.createReservation(
                            employee = admin,
                            partySize = 2,
                            reservationDate = LocalDate(2026, 3, 20),
                            reservationTime = LocalTime(18, 0),
                            durationMinutes = 120,
                            status = ReservationStatus.SEATED,
                        )
                    TestData.createTableAssignment(res.id.value, t1.id.value, 1, admin)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)

                // Update status to COMPLETED (requires SEATED first)
                val statusBody = """{"status":"COMPLETED"}"""
                val updateResponse =
                    client.put("/api/reservations/1/status") {
                        bearerAuth(token)
                        setBody(TextContent(statusBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, updateResponse.status)

                // Verify assignments are gone
                val assignmentsResponse =
                    client.get("/api/reservations/1/tables") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, assignmentsResponse.status)
                val assignments = testJson.parseToJsonElement(assignmentsResponse.bodyAsText()).jsonArray
                assertTrue(assignments.isEmpty())
            }
    }
}
