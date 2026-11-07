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
import org.example.project.core.domain.enums.reservation.ReservationStatus
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.assertStatus
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("Reservation Routes")
class
ReservationRoutesTest {
    @Nested
    @DisplayName("GET /api/reservations")
    inner class GetByDate {
        @Test
        fun `returns 200 with reservations for date`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    TestData.createReservation(
                        employee = admin,
                        partySize = 4,
                        reservationDate = LocalDate(2026, 3, 20),
                        reservationTime = LocalTime(18, 0),
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations?date=2026-03-20") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonArray
                assertTrue(body.isNotEmpty())
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
                    client.get("/api/reservations") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for invalid status filter`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations?date=2026-03-20&status=INVALID") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/reservations?date=2026-03-20")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/reservations/{id}")
    inner class GetById {
        @Test
        fun `returns 200 for existing reservation`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    TestData.createReservation(
                        employee = admin,
                        partySize = 4,
                        reservationDate = LocalDate(2026, 3, 20),
                        reservationTime = LocalTime(18, 0),
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/1") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("Test Guest", body["guestName"]?.jsonPrimitive?.content)
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
                assertStatus(HttpStatusCode.NotFound) {
                    client.get("/api/reservations/99999") {
                        bearerAuth(token)
                    }
                }
            }

        @Test
        fun `returns 400 for invalid id`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/invalid") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/reservations/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/reservations")
    inner class Create {
        @Test
        fun `returns 201 when creating reservation`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val requestBody = """{
                "guestName": "John Doe",
                "guestPhone": "123456",
                "partySize": 4,
                "reservationDate": "2026-03-25",
                "reservationTime": "19:00",
                "durationMinutes": 120
            }"""
                val response =
                    client.post("/api/reservations") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Created, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("John Doe", body["guestName"]?.jsonPrimitive?.content)
            }

        @Test
        fun `returns 400 for invalid request body`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val requestBody = """{"invalid": "data"}"""
                val response =
                    client.post("/api/reservations") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/reservations") {
                        setBody(
                            TextContent(
                                """{"guestName":"Test","partySize":2,"reservationDate":"2026-03-25","reservationTime":"19:00"}""",
                                ContentType.Application.Json,
                            ),
                        )
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/reservations/{id}")
    inner class Update {
        @Test
        fun `returns 200 when updating reservation`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    TestData.createReservation(
                        employee = admin,
                        partySize = 4,
                        reservationDate = LocalDate(2026, 3, 20),
                        reservationTime = LocalTime(18, 0),
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val requestBody = """{"partySize": 6}"""
                val response =
                    client.put("/api/reservations/1") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 400 for invalid id`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/reservations/invalid") {
                        bearerAuth(token)
                        setBody(TextContent("""{"partySize": 6}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/reservations/1") {
                        setBody(TextContent("""{"partySize": 6}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/reservations/{id}/status")
    inner class UpdateStatus {
        @Test
        fun `returns 200 when updating status`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    TestData.createReservation(
                        employee = admin,
                        partySize = 4,
                        reservationDate = LocalDate(2026, 3, 20),
                        reservationTime = LocalTime(18, 0),
                        status = ReservationStatus.CONFIRMED,
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val requestBody = """{"status":"CANCELLED"}"""
                val response =
                    client.put("/api/reservations/1/status") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 400 for invalid id`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/reservations/invalid/status") {
                        bearerAuth(token)
                        setBody(TextContent("""{"status":"CANCELLED"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/reservations/1/status") {
                        setBody(TextContent("""{"status":"CANCELLED"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/reservations/{id}")
    inner class Delete {
        @Test
        fun `returns 204 when manager deletes`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val manager = TestData.createManager()
                    TestData.createReservation(
                        employee = manager,
                        partySize = 4,
                        reservationDate = LocalDate(2026, 3, 20),
                        reservationTime = LocalTime(18, 0),
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response =
                    client.delete("/api/reservations/1") {
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
                    TestData.createReservation(
                        employee = cashier,
                        partySize = 4,
                        reservationDate = LocalDate(2026, 3, 20),
                        reservationTime = LocalTime(18, 0),
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response =
                    client.delete("/api/reservations/1") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 400 for invalid id`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createManager()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response =
                    client.delete("/api/reservations/invalid") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/reservations/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/reservations/search")
    inner class Search {
        @Test
        fun `returns 200 with results`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    val admin = TestData.createAdmin()
                    TestData.createReservation(
                        employee = admin,
                        guestName = "Alice Smith",
                        partySize = 4,
                        reservationDate = LocalDate(2026, 3, 20),
                        reservationTime = LocalTime(18, 0),
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/search?q=Alice") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 400 when query missing`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/search") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/reservations/search?q=test")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/reservations/availability")
    inner class Availability {
        @Test
        fun `returns 200 for valid date`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservations/availability?date=2026-03-20") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 400 when date missing`() =
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
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/reservations/availability?date=2026-03-20")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }
}
