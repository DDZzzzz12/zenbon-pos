/**
 * Ktor implementation of OrderItemApiClient.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.example.project.core.data.remote.ApprovalOutcome
import org.example.project.core.data.remote.OrderItemApiClient
import org.example.project.core.domain.dto.order.ItemTransferHistoryDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall
import org.example.project.core.network.error.apiCallRaw

class KtorOrderItemApiClient(
    private val httpCall: HttpCall,
) : OrderItemApiClient {
    companion object {
        private const val BASE_PATH = "api/items"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getOrderItemsForTable(
        sessionId: Long,
        itemStatus: ItemStatus?,
    ): List<OrderItemDto.OrderItemResponseDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_PATH/session/$sessionId")) {
                itemStatus?.let { parameter("status", it.name) }
            }
        }

    override suspend fun cancelOrderItems(
        request: OrderItemDto.CancelOrderItemsRequestDto,
        sourceTableId: Long,
    ): ApprovalOutcome =
        outcomeOf {
            post(url("$BASE_PATH/cancel/$sourceTableId")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun transferOrderItems(
        request: OrderItemDto.TransferOrderItemsRequestDto,
        sourceTableId: Long,
    ): ApprovalOutcome =
        outcomeOf {
            post(url("$BASE_PATH/transfer/$sourceTableId")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    /**
     * Runs a hybrid-authorization call and reports which of its two success shapes came back.
     *
     * 202 means `requirePermissionOrDefer` stored the action for a manager; the body is not read
     * because nothing has happened yet and the caller's job is to say so, not to render a result.
     */
    private suspend fun outcomeOf(block: suspend HttpClient.() -> HttpResponse): ApprovalOutcome {
        val response = httpCall.httpClient.apiCallRaw(block)
        return if (response.status == HttpStatusCode.Accepted) {
            ApprovalOutcome.AWAITING_APPROVAL
        } else {
            ApprovalOutcome.COMPLETED
        }
    }

    override suspend fun getCancellations(
        startDate: String,
        endDate: String,
    ): List<OrderItemDto.CancellationResponseDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_PATH/cancellations")) {
                parameter("startDate", startDate)
                parameter("endDate", endDate)
            }
        }

    override suspend fun getTransferHistory(tableSessionId: Long): List<ItemTransferHistoryDto.ItemTransferHistoryResponseDto> =
        httpCall.httpClient.apiCall { get(url("$BASE_PATH/transfers/$tableSessionId")) }

    override suspend fun getTransfers(
        startDate: String,
        endDate: String,
    ): List<ItemTransferHistoryDto.ItemTransferHistoryResponseDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_PATH/transfers")) {
                parameter("startDate", startDate)
                parameter("endDate", endDate)
            }
        }
}
