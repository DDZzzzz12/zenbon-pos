package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("Reservation Availability Routes")
class ReservationAvailabilityRoutesTest {
    @Nested
    @DisplayName("Basic Availability")
    inner class BasicAvailability {
        @Test
        fun `returns 200 with table counts when tables exist and no reservations`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                    TestData.createTable("T1", 4)
                    TestData.createTable("T2", 4)
                    TestData.createTable("T3", 6)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(3, body["totalTables"]?.jsonPrimitive?.content?.toInt())
                assertEquals(3, body["availableTables"]?.jsonPrimitive?.content?.toInt())
                assertEquals(true, body["isOpen"]?.jsonPrimitive?.content?.toBoolean())
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/reservations/availability?date=2026-03-20")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 when date parameter missing`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/availability") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `excludes virtual tables TAKEAWAY from counts`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                    TestData.createTable("T1", 4, WellKnownLocationTypes.INDOOR)
                    TestData.createTable("TAKEAWAY", 0, WellKnownLocationTypes.TAKEAWAY)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(1, body["totalTables"]?.jsonPrimitive?.content?.toInt())
            }

        @Test
        fun `excludes virtual tables VOUCHER from counts`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                    TestData.createTable("T1", 4, WellKnownLocationTypes.INDOOR)
                    TestData.createTable("VOUCHER", 0, WellKnownLocationTypes.VOUCHER)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(1, body["totalTables"]?.jsonPrimitive?.content?.toInt())
            }

        @Test
        fun `excludes virtual tables QUICK_SALE from counts`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                    TestData.createTable("T1", 4, WellKnownLocationTypes.INDOOR)
                    TestData.createTable("QS", 0, WellKnownLocationTypes.QUICK_SALE)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(1, body["totalTables"]?.jsonPrimitive?.content?.toInt())
            }

        @Test
        fun `excludes inactive tables from counts`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                    TestData.createTable("T1", 4, isActive = true)
                    TestData.createTable("T2", 4, isActive = false)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(1, body["totalTables"]?.jsonPrimitive?.content?.toInt())
            }

        @Test
        fun `excludes tables with null seats from counts`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                    TestData.createTable("T1", 4)
                    TestData.createTable("T2", null)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(1, body["totalTables"]?.jsonPrimitive?.content?.toInt())
            }
    }

    @Nested
    @DisplayName("Opening Hours Integration")
    inner class OpeningHoursIntegration {
        @Test
        fun `returns isOpen=false when restaurant is closed on weekly schedule`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                    // Monday = ordinal 0 (dayOfWeek ordinal in this system)
                    TestData.createOpeningHours(dayOfWeek = 0, isClosed = true)
                    TestData.createTable("T1", 4)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                // 2026-03-23 is a Monday
                val response =
                    client.get("/api/reservations/availability?date=2026-03-23") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(false, body["isOpen"]?.jsonPrimitive?.content?.toBoolean())
            }

        @Test
        fun `returns isOpen=false when date has closed override`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                    TestData.createOpeningHoursOverride(
                        date = LocalDate(2026, 3, 20),
                        isClosed = true,
                        reason = "Public Holiday",
                    )
                    TestData.createTable("T1", 4)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(false, body["isOpen"]?.jsonPrimitive?.content?.toBoolean())
            }

        @Test
        fun `returns isOpen=true with openTime and closeTime when hours configured`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                    // Friday = dayOfWeek ordinal 4
                    TestData.createOpeningHours(
                        dayOfWeek = 4,
                        openTime = LocalTime(10, 0),
                        closeTime = LocalTime(22, 0),
                        isClosed = false,
                    )
                    TestData.createTable("T1", 4)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                // 2026-03-20 is a Friday
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(true, body["isOpen"]?.jsonPrimitive?.content?.toBoolean())
                assertNotNull(body["openTime"]?.jsonPrimitive?.content)
                assertNotNull(body["closeTime"]?.jsonPrimitive?.content)
            }

        @Test
        fun `returns isOpen=true when no hours configured (always open default)`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                    // No opening hours created — should default to open
                    TestData.createTable("T1", 4)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(true, body["isOpen"]?.jsonPrimitive?.content?.toBoolean())
            }
    }

    @Nested
    @DisplayName("Occupied Tables")
    inner class OccupiedTables {
        @Test
        fun `tables assigned to overlapping reservations are excluded from available count`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    val t1 = TestData.createTable("T1", 4)
                    TestData.createTable("T2", 4)
                    TestData.createTable("T3", 6)
                    val res =
                        TestData.createReservation(
                            employee = admin,
                            partySize = 3,
                            reservationDate = LocalDate(2026, 3, 20),
                            reservationTime = LocalTime(18, 0),
                            durationMinutes = 120,
                        )
                    TestData.createTableAssignment(res.id.value, t1.id.value, 1, admin)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20&time=18:00") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(3, body["totalTables"]?.jsonPrimitive?.content?.toInt())
                assertEquals(2, body["availableTables"]?.jsonPrimitive?.content?.toInt())
            }

        @Test
        fun `tables assigned to non-overlapping reservations are still available`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    val t1 = TestData.createTable("T1", 4)
                    TestData.createTable("T2", 4)
                    // Reservation from 12:00 to 14:00
                    val res =
                        TestData.createReservation(
                            employee = admin,
                            partySize = 3,
                            reservationDate = LocalDate(2026, 3, 20),
                            reservationTime = LocalTime(12, 0),
                            durationMinutes = 120,
                        )
                    TestData.createTableAssignment(res.id.value, t1.id.value, 1, admin)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                // Querying at 18:00, which doesn't overlap with 12:00-14:00
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20&time=18:00") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                // Both tables should be available since earlier reservation doesn't overlap
                assertEquals(2, body["availableTables"]?.jsonPrimitive?.content?.toInt())
            }

        @Test
        fun `cancelled reservations do not block tables`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    val t1 = TestData.createTable("T1", 4)
                    val res =
                        TestData.createReservation(
                            employee = admin,
                            partySize = 3,
                            reservationDate = LocalDate(2026, 3, 20),
                            reservationTime = LocalTime(18, 0),
                            durationMinutes = 120,
                            status = ReservationStatus.CANCELLED,
                        )
                    TestData.createTableAssignment(res.id.value, t1.id.value, 1, admin)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20&time=18:00") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(1, body["totalTables"]?.jsonPrimitive?.content?.toInt())
                assertEquals(1, body["availableTables"]?.jsonPrimitive?.content?.toInt())
            }

        @Test
        fun `no-show reservations do not block tables`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    val t1 = TestData.createTable("T1", 4)
                    val res =
                        TestData.createReservation(
                            employee = admin,
                            partySize = 3,
                            reservationDate = LocalDate(2026, 3, 20),
                            reservationTime = LocalTime(18, 0),
                            durationMinutes = 120,
                            status = ReservationStatus.NO_SHOW,
                        )
                    TestData.createTableAssignment(res.id.value, t1.id.value, 1, admin)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20&time=18:00") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(1, body["availableTables"]?.jsonPrimitive?.content?.toInt())
            }

        @Test
        fun `completed reservations do not block tables`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    val t1 = TestData.createTable("T1", 4)
                    val res =
                        TestData.createReservation(
                            employee = admin,
                            partySize = 3,
                            reservationDate = LocalDate(2026, 3, 20),
                            reservationTime = LocalTime(18, 0),
                            durationMinutes = 120,
                            status = ReservationStatus.COMPLETED,
                        )
                    TestData.createTableAssignment(res.id.value, t1.id.value, 1, admin)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20&time=18:00") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(1, body["availableTables"]?.jsonPrimitive?.content?.toInt())
            }

        @Test
        fun `tables with active walk-in sessions on today are excluded`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                    // createTableWithSession creates a table with an active session
                    TestData.createTableWithSession("T1", 4)
                    TestData.createTable("T2", 4)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                // Use today's date so the walk-in session exclusion logic applies
                val today =
                    java.time.LocalDate
                        .now()
                        .toString()
                val response =
                    client.get("/api/reservations/availability?date=$today") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                // T1 has active session so should be excluded; T2 is free
                assertEquals(2, body["totalTables"]?.jsonPrimitive?.content?.toInt())
                assertEquals(1, body["availableTables"]?.jsonPrimitive?.content?.toInt())
            }
    }

    @Nested
    @DisplayName("Party Size Accommodation")
    inner class PartySizeAccommodation {
        @Test
        fun `canAccommodate=true when single table fits party`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                    TestData.createTable("T1", 6)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20&partySize=4") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(true, body["canAccommodate"]?.jsonPrimitive?.content?.toBoolean())
            }

        @Test
        fun `canAccommodate=false when no table has enough seats`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                    TestData.createTable("T1", 2)
                    TestData.createTable("T2", 2)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20&partySize=8") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(false, body["canAccommodate"]?.jsonPrimitive?.content?.toBoolean())
            }

        @Test
        fun `canAccommodate=null when partySize not provided`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                    TestData.createTable("T1", 4)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                // When partySize is not provided, canAccommodate should be absent or null
                val canAccommodate = body["canAccommodate"]
                assertTrue(canAccommodate == null || canAccommodate.toString() == "null")
            }

        @Test
        fun `canAccommodate=true when combinable tables can seat party`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                    val t1 = TestData.createTable("T1", 3)
                    val t2 = TestData.createTable("T2", 3)
                    // Create combinable pair — combined they seat 6
                    TestData.createCombinablePair(t1.id.value, t2.id.value)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20&partySize=5") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(true, body["canAccommodate"]?.jsonPrimitive?.content?.toBoolean())
            }
    }

    @Nested
    @DisplayName("Available Table Details")
    inner class AvailableTableDetails {
        @Test
        fun `response includes availableTableDetails with correct fields`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                    TestData.createTable("T1", 4, WellKnownLocationTypes.INDOOR)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                val details = body["availableTableDetails"]?.jsonArray
                assertNotNull(details)
                assertTrue(details.isNotEmpty())
                val table = details.first().jsonObject
                assertNotNull(table["tableId"])
                assertNotNull(table["tableNumber"])
                assertNotNull(table["seats"])
                assertNotNull(table["locationType"])
                assertNotNull(table["combinableWith"])
            }

        @Test
        fun `combinableWith lists only IDs from available tables`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    val t1 = TestData.createTable("T1", 4)
                    val t2 = TestData.createTable("T2", 4)
                    val t3 = TestData.createTable("T3", 4)
                    // T1 is combinable with T2 and T3
                    TestData.createCombinablePair(t1.id.value, t2.id.value)
                    TestData.createCombinablePair(t1.id.value, t3.id.value)
                    // Make T3 occupied by a reservation at 18:00
                    val res =
                        TestData.createReservation(
                            employee = admin,
                            partySize = 2,
                            reservationDate = LocalDate(2026, 3, 20),
                            reservationTime = LocalTime(18, 0),
                            durationMinutes = 120,
                        )
                    TestData.createTableAssignment(res.id.value, t3.id.value, 1, admin)
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20&time=18:00") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                val details = body["availableTableDetails"]?.jsonArray ?: return@testApp
                // Find T1 in the details
                val t1Detail = details.map { it.jsonObject }.find { it["tableNumber"]?.jsonPrimitive?.content == "T1" }
                assertNotNull(t1Detail)
                val combinableWith = t1Detail["combinableWith"]?.jsonArray ?: return@testApp
                // T3 is occupied so should NOT be listed in combinableWith for T1
                val combinableIds = combinableWith.map { it.jsonPrimitive.content }
                assertTrue(
                    combinableIds.none { id ->
                        // t3 ID should not appear
                        details.none { it.jsonObject["tableId"]?.jsonPrimitive?.content == id }
                    },
                )
            }
    }
}
