package org.example.project.features.table.data.remote

import io.ktor.client.request.get
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

/**
 * Ktor HTTP implementation of [BillingRequestApiClient].
 *
 * Throws-style: raises [org.example.project.core.network.error.PosApiException] /
 * [org.example.project.core.network.error.NetworkApiException] on failure.
 */
class KtorBillingRequestApiClient(
    private val httpCall: HttpCall,
) : BillingRequestApiClient {
    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getPendingBillingRequests(): List<NotificationDto.BillingRequestNotificationDto> =
        httpCall.httpClient.apiCall { get(url("api/tables/billing-requests/pending")) }
}
