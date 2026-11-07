package org.example.project.features.table.data.remote

import org.example.project.core.domain.dto.websocket.NotificationDto

/**
 * API client for recoverable billing requests.
 *
 * Fetches billing requests parked server-side for the current employee so the
 * client can recover any missed live SSE event on reconcile-on-connect.
 *
 * Throws [org.example.project.core.network.error.PosApiException] /
 * [org.example.project.core.network.error.NetworkApiException] on failure.
 */
interface BillingRequestApiClient {
    suspend fun getPendingBillingRequests(): List<NotificationDto.BillingRequestNotificationDto>
}
