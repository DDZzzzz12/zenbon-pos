package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.assertStatus
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("ReservationRule Routes")
class ReservationRuleRoutesTest {
    @Nested
    @DisplayName("GET /api/reservation-rules")
    inner class GetByDateRange {
        @Test
        fun `returns 200 with rules`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservation-rules?startDate=2026-03-01&endDate=2026-03-31") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 400 when startDate missing`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservation-rules?endDate=2026-03-31") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 when endDate missing`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservation-rules?startDate=2026-03-01") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 when both dates missing`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/reservation-rules") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/reservation-rules?startDate=2026-03-01&endDate=2026-03-31")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/reservation-rules/{id}")
    inner class GetById {
        @Test
        fun `returns 404 for non-existent rule`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createAdmin()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                assertStatus(HttpStatusCode.NotFound) {
                    client.get("/api/reservation-rules/99999") {
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
                    client.get("/api/reservation-rules/invalid") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/reservation-rules/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/reservation-rules")
    inner class Create {
        @Test
        fun `returns 201 when manager creates FIXED_DURATION rule`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createManager()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val requestBody = """{
                "ruleDate": "2026-03-25",
                "ruleType": "FIXED_DURATION",
                "durationMinutes": 90,
                "startTime": "11:00",
                "endTime": "22:00"
            }"""
                val response =
                    client.post("/api/reservation-rules") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Created, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("FIXED_DURATION", body["ruleType"]?.jsonPrimitive?.content)
            }

        @Test
        fun `returns 201 when manager creates TIME_SLOTS rule`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createManager()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val requestBody = """{
                "ruleDate": "2026-03-25",
                "ruleType": "TIME_SLOTS",
                "timeSlots": ["11:00", "13:00", "18:00", "20:00"]
            }"""
                val response =
                    client.post("/api/reservation-rules") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Created, response.status)
            }

        @Test
        fun `returns 403 when cashier attempts`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createCashier()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val requestBody = """{
                "ruleDate": "2026-03-25",
                "ruleType": "FIXED_DURATION",
                "durationMinutes": 90
            }"""
                val response =
                    client.post("/api/reservation-rules") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 400 for invalid request`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createManager()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val requestBody = """{"invalid": "data"}"""
                val response =
                    client.post("/api/reservation-rules") {
                        bearerAuth(token)
                        setBody(TextContent(requestBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/reservation-rules") {
                        setBody(
                            TextContent(
                                """{"ruleDate":"2026-03-25","ruleType":"FIXED_DURATION","durationMinutes":90}""",
                                ContentType.Application.Json,
                            ),
                        )
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/reservation-rules/{id}")
    inner class Update {
        @Test
        fun `returns 200 when manager updates rule`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createManager()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                // Create a rule first
                val createBody = """{"ruleDate":"2026-03-25","ruleType":"FIXED_DURATION","durationMinutes":90,"startTime":"11:00","endTime":"22:00"}"""
                val createResponse =
                    client.post("/api/reservation-rules") {
                        bearerAuth(token)
                        setBody(TextContent(createBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Created, createResponse.status)
                val created = testJson.parseToJsonElement(createResponse.bodyAsText()).jsonObject
                val id = created["id"]?.jsonPrimitive?.content

                // Update
                val updateBody = """{"durationMinutes": 120}"""
                val response =
                    client.put("/api/reservation-rules/$id") {
                        bearerAuth(token)
                        setBody(TextContent(updateBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 403 when cashier attempts`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createCashier()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response =
                    client.put("/api/reservation-rules/1") {
                        bearerAuth(token)
                        setBody(TextContent("""{"durationMinutes": 120}""", ContentType.Application.Json))
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
                    client.put("/api/reservation-rules/invalid") {
                        bearerAuth(token)
                        setBody(TextContent("""{"durationMinutes": 120}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/reservation-rules/1") {
                        setBody(TextContent("""{"durationMinutes": 120}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/reservation-rules/{id}")
    inner class Delete {
        @Test
        fun `returns 204 when manager deletes rule`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createManager()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                // Create a rule first
                val createBody = """{"ruleDate":"2026-03-25","ruleType":"FIXED_DURATION","durationMinutes":90,"startTime":"11:00","endTime":"22:00"}"""
                val createResponse =
                    client.post("/api/reservation-rules") {
                        bearerAuth(token)
                        setBody(TextContent(createBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Created, createResponse.status)
                val created = testJson.parseToJsonElement(createResponse.bodyAsText()).jsonObject
                val id = created["id"]?.jsonPrimitive?.content

                val response =
                    client.delete("/api/reservation-rules/$id") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.NoContent, response.status)
            }

        @Test
        fun `returns 403 when cashier attempts`() =
            testApp(
                setupData = {
                    TestData.createShopInfo()
                    TestData.createCashier()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response =
                    client.delete("/api/reservation-rules/1") {
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
                    client.delete("/api/reservation-rules/invalid") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/reservation-rules/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }
}
