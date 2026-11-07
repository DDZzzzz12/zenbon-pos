package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.*
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

@DisplayName("Opening Hours Routes")
class OpeningHoursRoutesTest {
    @Nested
    @DisplayName("GET /api/opening-hours")
    inner class GetAll {
        @Test
        fun `returns 200 with empty list when no hours configured`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/opening-hours") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonArray
                assertEquals(0, body.size)
            }

        @Test
        fun `returns 200 with all 7 days when configured`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createOpeningHours(0, LocalTime(10, 0), LocalTime(22, 0))
                    TestData.createOpeningHours(1, LocalTime(10, 0), LocalTime(22, 0))
                    TestData.createOpeningHours(2, LocalTime(10, 0), LocalTime(22, 0))
                    TestData.createOpeningHours(3, LocalTime(10, 0), LocalTime(22, 0))
                    TestData.createOpeningHours(4, LocalTime(10, 0), LocalTime(22, 0))
                    TestData.createOpeningHours(5, LocalTime(10, 0), LocalTime(22, 0))
                    TestData.createOpeningHours(6, LocalTime(10, 0), LocalTime(22, 0))
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/opening-hours") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonArray
                assertEquals(7, body.size)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/opening-hours")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/opening-hours")
    inner class BulkUpdate {
        @Test
        fun `returns 200 when manager updates all days`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val body =
                    """
                    {
                      "days": [
                        { "dayOfWeek": 0, "openTime": "10:00", "closeTime": "22:00", "isClosed": false },
                        { "dayOfWeek": 1, "openTime": "10:00", "closeTime": "22:00", "isClosed": false },
                        { "dayOfWeek": 2, "openTime": "10:00", "closeTime": "22:00", "isClosed": false },
                        { "dayOfWeek": 3, "openTime": "10:00", "closeTime": "22:00", "isClosed": false },
                        { "dayOfWeek": 4, "openTime": "10:00", "closeTime": "22:00", "isClosed": false },
                        { "dayOfWeek": 5, "openTime": "10:00", "closeTime": "20:00", "isClosed": false },
                        { "dayOfWeek": 6, "openTime": "10:00", "closeTime": "20:00", "isClosed": false }
                      ]
                    }
                    """.trimIndent()
                val response =
                    client.put("/api/opening-hours") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val responseArray = testJson.parseToJsonElement(response.bodyAsText()).jsonArray
                assertEquals(7, responseArray.size)
            }

        @Test
        fun `returns 403 when cashier attempts update`() =
            testApp(
                setupData = {
                    TestData.createCashier()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val body =
                    """
                    {
                      "days": [
                        { "dayOfWeek": 0, "openTime": "10:00", "closeTime": "22:00", "isClosed": false }
                      ]
                    }
                    """.trimIndent()
                val response =
                    client.put("/api/opening-hours") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 400 with invalid request format`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response =
                    client.put("/api/opening-hours") {
                        bearerAuth(token)
                        setBody(TextContent("not valid json at all", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `upserts correctly — updates existing and inserts new`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createOpeningHours(0, LocalTime(9, 0), LocalTime(21, 0))
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val body =
                    """
                    {
                      "days": [
                        { "dayOfWeek": 0, "openTime": "10:00", "closeTime": "22:00", "isClosed": false },
                        { "dayOfWeek": 1, "openTime": "10:00", "closeTime": "22:00", "isClosed": false }
                      ]
                    }
                    """.trimIndent()
                val response =
                    client.put("/api/opening-hours") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val responseArray = testJson.parseToJsonElement(response.bodyAsText()).jsonArray
                assertEquals(2, responseArray.size)
                val day0 =
                    responseArray.first {
                        it.jsonObject["dayOfWeek"]
                            ?.jsonPrimitive
                            ?.content
                            ?.toInt() == 0
                    }
                assertEquals("10:00", day0.jsonObject["openTime"]?.jsonPrimitive?.content)
                assertEquals("22:00", day0.jsonObject["closeTime"]?.jsonPrimitive?.content)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/opening-hours") {
                        setBody(TextContent("{\"days\":[]}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/opening-hours/overrides")
    inner class GetOverrides {
        @Test
        fun `returns 200 with overrides in date range`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createOpeningHoursOverride(
                        date = LocalDate(2026, 4, 1),
                        isClosed = true,
                        reason = "Easter Monday",
                    )
                    TestData.createOpeningHoursOverride(
                        date = LocalDate(2026, 4, 5),
                        openTime = LocalTime(12, 0),
                        closeTime = LocalTime(18, 0),
                        isClosed = false,
                        reason = "Short Sunday",
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/opening-hours/overrides?startDate=2026-04-01&endDate=2026-04-30") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonArray
                assertEquals(2, body.size)
            }

        @Test
        fun `returns empty list when no overrides in range`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createOpeningHoursOverride(
                        date = LocalDate(2026, 12, 25),
                        isClosed = true,
                        reason = "Christmas",
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/opening-hours/overrides?startDate=2026-04-01&endDate=2026-04-30") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonArray
                assertEquals(0, body.size)
            }

        @Test
        fun `returns 400 when missing startDate`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/opening-hours/overrides?endDate=2026-04-30") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 when missing endDate`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/opening-hours/overrides?startDate=2026-04-01") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 when both startDate and endDate missing`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/opening-hours/overrides") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/opening-hours/overrides?startDate=2026-04-01&endDate=2026-04-30")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/opening-hours/overrides")
    inner class CreateOverride {
        @Test
        fun `returns 201 when manager creates override`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val body =
                    """
                    {
                      "overrideDate": "2026-05-01",
                      "isClosed": true,
                      "reason": "Labour Day"
                    }
                    """.trimIndent()
                val response =
                    client.post("/api/opening-hours/overrides") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Created, response.status)
                val responseObj = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("2026-05-01", responseObj["overrideDate"]?.jsonPrimitive?.content)
                assertEquals(true, responseObj["isClosed"]?.jsonPrimitive?.content?.toBoolean())
                assertEquals("Labour Day", responseObj["reason"]?.jsonPrimitive?.content)
            }

        @Test
        fun `returns 201 when manager creates non-closed override with times`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val body =
                    """
                    {
                      "overrideDate": "2026-05-10",
                      "openTime": "12:00",
                      "closeTime": "18:00",
                      "isClosed": false,
                      "reason": "Short hours"
                    }
                    """.trimIndent()
                val response =
                    client.post("/api/opening-hours/overrides") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Created, response.status)
                val responseObj = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(false, responseObj["isClosed"]?.jsonPrimitive?.content?.toBoolean())
                assertEquals("12:00", responseObj["openTime"]?.jsonPrimitive?.content)
            }

        @Test
        fun `returns 403 when cashier attempts create`() =
            testApp(
                setupData = {
                    TestData.createCashier()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val body =
                    """
                    {
                      "overrideDate": "2026-05-01",
                      "isClosed": true
                    }
                    """.trimIndent()
                val response =
                    client.post("/api/opening-hours/overrides") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns error when duplicate date`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createShopInfo()
                    TestData.createOpeningHoursOverride(
                        date = LocalDate(2026, 5, 1),
                        isClosed = true,
                        reason = "Existing override",
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val body =
                    """
                    {
                      "overrideDate": "2026-05-01",
                      "isClosed": true,
                      "reason": "Duplicate attempt"
                    }
                    """.trimIndent()
                assertStatus(HttpStatusCode.Conflict) {
                    client.post("/api/opening-hours/overrides") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                }
            }

        @Test
        fun `returns 400 with invalid request format`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response =
                    client.post("/api/opening-hours/overrides") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/opening-hours/overrides") {
                        setBody(TextContent("{\"overrideDate\":\"2026-05-01\",\"isClosed\":true}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/opening-hours/overrides/{id}")
    inner class UpdateOverride {
        @Test
        fun `returns 200 when manager updates override`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createOpeningHoursOverride(
                        date = LocalDate(2026, 6, 1),
                        isClosed = true,
                        reason = "Original reason",
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)

                val listResponse =
                    client.get("/api/opening-hours/overrides?startDate=2026-06-01&endDate=2026-06-01") {
                        bearerAuth(token)
                    }
                val overrides = testJson.parseToJsonElement(listResponse.bodyAsText()).jsonArray
                val overrideId =
                    overrides
                        .first()
                        .jsonObject["id"]
                        ?.jsonPrimitive
                        ?.long!!

                val body =
                    """
                    {
                      "isClosed": false,
                      "openTime": "11:00",
                      "closeTime": "17:00",
                      "reason": "Updated reason"
                    }
                    """.trimIndent()
                val response =
                    client.put("/api/opening-hours/overrides/$overrideId") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val responseObj = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(false, responseObj["isClosed"]?.jsonPrimitive?.content?.toBoolean())
                assertEquals("Updated reason", responseObj["reason"]?.jsonPrimitive?.content)
            }

        @Test
        fun `returns error for non-existent id`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val body =
                    """
                    {
                      "isClosed": true
                    }
                    """.trimIndent()
                val response =
                    client.put("/api/opening-hours/overrides/99999") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertTrue(
                    response.status.value in 400..499,
                    "Expected 4xx for non-existent id, got ${response.status}",
                )
            }

        @Test
        fun `returns 400 for invalid id format`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val body =
                    """
                    {
                      "isClosed": true
                    }
                    """.trimIndent()
                val response =
                    client.put("/api/opening-hours/overrides/not-a-number") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 403 when cashier attempts update`() =
            testApp(
                setupData = {
                    TestData.createCashier()
                    TestData.createOpeningHoursOverride(
                        date = LocalDate(2026, 6, 10),
                        isClosed = true,
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val body =
                    """
                    {
                      "isClosed": false
                    }
                    """.trimIndent()
                val response =
                    client.put("/api/opening-hours/overrides/1") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/opening-hours/overrides/1") {
                        setBody(TextContent("{\"isClosed\":true}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/opening-hours/overrides/{id}")
    inner class DeleteOverride {
        @Test
        fun `returns 204 when manager deletes override`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createOpeningHoursOverride(
                        date = LocalDate(2026, 7, 4),
                        isClosed = true,
                        reason = "Holiday",
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)

                val listResponse =
                    client.get("/api/opening-hours/overrides?startDate=2026-07-04&endDate=2026-07-04") {
                        bearerAuth(token)
                    }
                val overrides = testJson.parseToJsonElement(listResponse.bodyAsText()).jsonArray
                val overrideId =
                    overrides
                        .first()
                        .jsonObject["id"]
                        ?.jsonPrimitive
                        ?.long!!

                val response =
                    client.delete("/api/opening-hours/overrides/$overrideId") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.NoContent, response.status)

                val verifyResponse =
                    client.get("/api/opening-hours/overrides?startDate=2026-07-04&endDate=2026-07-04") {
                        bearerAuth(token)
                    }
                val remaining = testJson.parseToJsonElement(verifyResponse.bodyAsText()).jsonArray
                assertEquals(0, remaining.size)
            }

        @Test
        fun `returns 403 when cashier attempts delete`() =
            testApp(
                setupData = {
                    TestData.createCashier()
                    TestData.createOpeningHoursOverride(
                        date = LocalDate(2026, 7, 10),
                        isClosed = true,
                    )
                },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response =
                    client.delete("/api/opening-hours/overrides/1") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns error for non-existent id`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response =
                    client.delete("/api/opening-hours/overrides/99999") {
                        bearerAuth(token)
                    }
                assertTrue(
                    response.status.value in 400..499,
                    "Expected 4xx for non-existent id, got ${response.status}",
                )
            }

        @Test
        fun `returns 400 for invalid id format`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response =
                    client.delete("/api/opening-hours/overrides/not-a-number") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/opening-hours/overrides/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }
}
