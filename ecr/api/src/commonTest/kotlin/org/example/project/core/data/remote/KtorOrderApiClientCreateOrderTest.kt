package org.example.project.core.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.example.project.core.data.remote.impl.KtorOrderApiClient
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.PosApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The order IS booked once the server answers 2xx. A not-yet-upgraded server answers 201 with an
 * EMPTY body; failing the submit on that would show the cashier an error for a booked order and
 * invite a double booking on retry.
 */
class KtorOrderApiClientCreateOrderTest {
    private fun clientFor(
        status: HttpStatusCode,
        body: String,
    ): KtorOrderApiClient {
        val engine =
            MockEngine { _ ->
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        val http =
            HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        return KtorOrderApiClient(
            object : HttpCall {
                override val httpClient: HttpClient = http

                override fun getBaseUrl(): String = "http://localhost/"
            },
        )
    }

    private val request = OrderDto.CreateOrderRequestDto(tableInfoId = 0L, tableId = 3L, orderItems = emptyList())

    @Test
    fun `an empty 201 from an older server yields null instead of failing`() =
        runTest {
            assertNull(clientFor(HttpStatusCode.Created, "").createOrder(request))
        }

    @Test
    fun `a 201 with a body is decoded including the rotation fields`() =
        runTest {
            val body =
                """{"orderId":1,"orderNumber":"O1","employeeId":"id-ben","tableNumber":"3",
                "orderTime":"2026-10-16T10:00:00","orderTotal":"1.00","rotationShifted":true,
                "rotationOwner":{"employeeId":"id-ben","employeeNumber":"E2","displayName":"Ben"},"unknownKey":1}"""
            val response = clientFor(HttpStatusCode.Created, body).createOrder(request)

            assertNotNull(response)
            assertEquals(true, response.rotationShifted)
            assertEquals("Ben", response.rotationOwner?.displayName)
        }

    @Test
    fun `a non-empty undecodable 201 body yields null`() =
        runTest {
            assertNull(clientFor(HttpStatusCode.Created, "not json").createOrder(request))
        }

    @Test
    fun `a 4xx still throws`() =
        runTest {
            assertFailsWith<PosApiException> {
                clientFor(HttpStatusCode.Conflict, """{"title":"x","status":409}""").createOrder(request)
            }
            Unit
        }
}
