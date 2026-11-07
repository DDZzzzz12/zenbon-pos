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
import org.example.project.core.data.remote.impl.KtorOrderItemApiClient
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.network.HttpCall
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A till must be able to tell "cancelled" from "asked a manager".
 *
 * `requirePermissionOrDefer` answers **202 Accepted** when the caller may not perform the operation
 * itself: the action is stored and waits for a manager. The client discarded the status
 * (`apiCall<Unit>`), so 202 went down the same success path as 200 — the view model reloaded the
 * table, closed the dialog and reported success, and the cashier's device showed nothing at all
 * while the request sat live on the manager's screen (E2E-2026-09-20 #6). "Submitted, waiting" and
 * "the tap did nothing" looked identical.
 */
class OrderItemApprovalOutcomeTest {
    private fun clientFor(status: HttpStatusCode): KtorOrderItemApiClient {
        val engine =
            MockEngine { _ ->
                respond(
                    content = if (status == HttpStatusCode.Accepted) """{"requestId":"REQ-1"}""" else "",
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        val http =
            HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        return KtorOrderItemApiClient(
            object : HttpCall {
                override val httpClient: HttpClient = http

                override fun getBaseUrl(): String = "http://localhost/"
            },
        )
    }

    private val cancelRequest = OrderItemDto.CancelOrderItemsRequestDto(itemIds = listOf(1L), reason = "test")

    @Test
    fun `a 202 means the request is waiting for a manager`() =
        runTest {
            val outcome = clientFor(HttpStatusCode.Accepted).cancelOrderItems(cancelRequest, sourceTableId = 1L)

            assertEquals(ApprovalOutcome.AWAITING_APPROVAL, outcome)
        }

    @Test
    fun `a 200 means the items really were cancelled`() =
        runTest {
            val outcome = clientFor(HttpStatusCode.OK).cancelOrderItems(cancelRequest, sourceTableId = 1L)

            assertEquals(ApprovalOutcome.COMPLETED, outcome)
        }

    @Test
    fun `a transfer reports the same two outcomes`() =
        runTest {
            val request = OrderItemDto.TransferOrderItemsRequestDto(itemIds = listOf(1L), targetTableId = 2L)

            assertEquals(
                ApprovalOutcome.AWAITING_APPROVAL,
                clientFor(HttpStatusCode.Accepted).transferOrderItems(request, sourceTableId = 1L),
            )
            assertEquals(
                ApprovalOutcome.COMPLETED,
                clientFor(HttpStatusCode.OK).transferOrderItems(request, sourceTableId = 1L),
            )
        }
}
