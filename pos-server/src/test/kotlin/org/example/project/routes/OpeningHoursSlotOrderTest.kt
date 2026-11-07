package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.example.project.features.openinghours.domain.models.OpeningHoursOverride
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A slot must open before it closes, on the weekly plan and on date overrides (QA admin19 FAIL-D).
 *
 * Overnight slots are not modelled — `ResolvedHours.isTimeWithin` tests `open <= t < close` within
 * one day, so a reversed slot would refuse every reservation. The override paths had no check at all.
 */
@DisplayName("Opening hours — slot order")
class OpeningHoursSlotOrderTest {
    private suspend fun assertStatus(
        expected: HttpStatusCode,
        response: HttpResponse,
    ) = assertEquals(expected, response.status, response.bodyAsText())

    private fun json(body: String) = TextContent(body, ContentType.Application.Json)

    @Test
    fun `weekly plan refuses an opening time after the closing time`() =
        testApp(setupData = {
            TestData.createManager()
            TestData.createShopInfo()
        }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.put("/api/opening-hours") {
                    bearerAuth(token)
                    setBody(json("""{"days":[{"dayOfWeek":4,"openTime":"16:30","closeTime":"15:00","isClosed":false}]}"""))
                }
            assertStatus(HttpStatusCode.BadRequest, response)
        }

    @Test
    fun `override create refuses an opening time after the closing time`() =
        testApp(setupData = {
            TestData.createManager()
            TestData.createShopInfo()
        }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/opening-hours/overrides") {
                    bearerAuth(token)
                    setBody(json("""{"overrideDate":"2026-12-24","openTime":"16:30","closeTime":"15:00","isClosed":false}"""))
                }
            assertStatus(HttpStatusCode.BadRequest, response)
        }

    @Test
    fun `override update refuses moving one end past the other`() =
        testApp(setupData = {
            TestData.createManager()
            TestData.createShopInfo()
            TestData.createOpeningHoursOverride(
                date = LocalDate(2026, 12, 24),
                openTime = LocalTime(12, 0),
                closeTime = LocalTime(18, 0),
                isClosed = false,
            )
        }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val id = overrideId()
            val refused =
                client.put("/api/opening-hours/overrides/$id") {
                    bearerAuth(token)
                    setBody(json("""{"closeTime":"11:00"}"""))
                }
            assertStatus(HttpStatusCode.BadRequest, refused)

            val allowed =
                client.put("/api/opening-hours/overrides/$id") {
                    bearerAuth(token)
                    setBody(json("""{"closeTime":"20:00"}"""))
                }
            assertStatus(HttpStatusCode.OK, allowed)
        }

    @Test
    fun `a closed override needs no times`() =
        testApp(setupData = {
            TestData.createManager()
            TestData.createShopInfo()
        }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/opening-hours/overrides") {
                    bearerAuth(token)
                    setBody(json("""{"overrideDate":"2026-12-25","isClosed":true,"reason":"Weihnachten"}"""))
                }
            assertStatus(HttpStatusCode.Created, response)
        }

    private fun overrideId(): Long =
        transaction {
            OpeningHoursOverride
                .all()
                .first()
                .id.value
        }
}
