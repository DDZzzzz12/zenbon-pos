package org.example.project.features.finance

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.example.project.core.domain.dto.finance.BookkeepingStartDto
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HTTP contract for the Buchungsbeginn.
 *
 * Statuses are asserted exactly rather than as "not 403": a bare `assertNotEquals(Forbidden)` would
 * also be satisfied by a 500 from a broken route, while the exact status proves the request reached
 * the service.
 *
 * `needsAcknowledgement` is the important one — it must survive repeated reads, because it drives a
 * banner the operator has to be able to come back to, and be cleared only by an explicit
 * acknowledgement or by choosing a date.
 */
@DisplayName("Buchungsbeginn Routes")
class BookkeepingStartRoutesTest {
    private val path = "/api/finance/bookkeeping-start"

    private fun setup() {
        TestData.createShopInfo()
        TestData.createManager()
        TestData.createCashier()
    }

    private fun body(text: String): BookkeepingStartDto.ResponseDto = testJson.decodeFromString(text)

    @Test
    fun `reading it without a token is 401`() {
        testApp(setupData = { setup() }) { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
        }
    }

    @Test
    fun `reading it as a cashier is 403`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            assertEquals(HttpStatusCode.Forbidden, client.get(path) { bearerAuth(token) }.status)
        }
    }

    @Test
    fun `acknowledging it as a cashier is 403`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("$path/acknowledge") { bearerAuth(token) }.status,
            )
        }
    }

    @Test
    fun `repeated reads report the same unacknowledged date`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)

            val first = client.get(path) { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, first.status)
            val firstBody = body(first.bodyAsText())
            assertTrue(firstBody.needsAcknowledgement, "nobody has confirmed the date yet")

            val second = client.get(path) { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, second.status)
            val secondBody = body(second.bodyAsText())
            assertEquals(firstBody.startDate, secondBody.startDate)
            assertTrue(secondBody.needsAcknowledgement, "a read must not consume the banner")
        }
    }

    @Test
    fun `acknowledging it clears the flag for good`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)

            val response = client.post("$path/acknowledge") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertFalse(body(response.bodyAsText()).needsAcknowledgement)

            val reread = client.get(path) { bearerAuth(token) }
            assertFalse(body(reread.bodyAsText()).needsAcknowledgement, "the acknowledgement is stored")
        }
    }

    @Test
    fun `a manager can replace it`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)

            val response =
                client.put(path) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(testJson.encodeToString(BookkeepingStartDto.UpdateRequestDto("2025-01-01")))
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val updated = body(response.bodyAsText())
            assertEquals("2025-01-01", updated.startDate)
            assertFalse(updated.needsAcknowledgement)

            val reread = body(client.get(path) { bearerAuth(token) }.bodyAsText())
            assertEquals("2025-01-01", reread.startDate)
            assertFalse(reread.needsAcknowledgement, "choosing a date counts as having seen it")
        }
    }

    @Test
    fun `a German-formatted date is 400`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)

            val response =
                client.put(path) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(testJson.encodeToString(BookkeepingStartDto.UpdateRequestDto("01.09.2026")))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }
}
