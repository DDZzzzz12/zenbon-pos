/**
 * Ktor-based implementation of OrderApiClient.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.OrderApiClient
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall
import org.example.project.core.network.error.apiCallRaw
import kotlin.coroutines.cancellation.CancellationException

class KtorOrderApiClient(
    private val httpCall: HttpCall,
) : OrderApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/orders"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getOrdersByTableSessionId(tableSessionId: Long): List<OrderDto.OrderResponseDto> =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/by-table-session/$tableSessionId")) }

    /**
     * Non-2xx errors surface exactly as in [apiCall]. A 2xx means the order IS booked, so the body is
     * decoded leniently: an older server answers 201 with an empty body, and an undecodable body must
     * not turn a booked order into a failed submit (a retry would book it twice). Null = no
     * rotation information.
     */
    override suspend fun createOrder(request: OrderDto.CreateOrderRequestDto): OrderDto.OrderResponseDto? {
        val response =
            httpCall.httpClient.apiCallRaw {
                post(url(BASE_ENDPOINT)) {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            }
        if (response.bodyAsText().isBlank()) return null
        return try {
            response.body<OrderDto.OrderResponseDto>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("KtorOrderApiClient.createOrder: order booked but response body undecodable: ${e.message}")
            null
        }
    }

    override suspend fun printReferencePrint(request: OrderItemDto.ReferencePrintRequestDto) {
        httpCall.httpClient.apiCall<Unit> {
            post(url("$BASE_ENDPOINT/reference-print")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
    }
}
