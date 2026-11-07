package org.example.project.core.domain.dto.tse

import kotlinx.serialization.Serializable

/**
 * Container object for TSE informational endpoint response DTOs.
 *
 * These are strongly-typed response models used by the TSE info routes for
 * serialization/deserialization of TSE informational endpoints.
 *
 * @since 2.1.0
 */
object TseInfoResponseDto {
    /**
     * Response for GET / — service welcome/info endpoint.
     */
    @Serializable
    data class WelcomeResponse(
        val service: String,
        val version: String,
        val framework: String,
        val status: String,
        val mode: String,
        val features: List<String>,
        val endpoints: EndpointsInfo,
    )

    /**
     * Nested endpoint information for the welcome response.
     */
    @Serializable
    data class EndpointsInfo(
        val signing: SigningEndpoints,
        val export: ExportEndpoints,
        val admin: String,
        val status: String,
        val health: String,
        val ping: String,
    )

    /**
     * Signing endpoint descriptions.
     */
    @Serializable
    data class SigningEndpoints(
        val complete: String,
        val start: String,
        val update: String,
        val finish: String,
    )

    /**
     * Export endpoint descriptions.
     */
    @Serializable
    data class ExportEndpoints(
        val execute: String,
        val size: String,
        val delete: String,
    )

    /**
     * Response for GET /ping — simple availability check.
     */
    @Serializable
    data class PingResponse(
        val status: String,
        val timestamp: Long,
    )

    /**
     * Response for POST /mode/{mode} — mode change result.
     */
    @Serializable
    data class ModeChangeResponse(
        val success: Boolean,
        val mode: String,
        val message: String,
    )

    /**
     * Response for POST /restart — restart acknowledgment.
     */
    @Serializable
    data class RestartResponse(
        val success: Boolean,
        val message: String,
    )

    /**
     * Response for GET /mode — current mode information.
     */
    @Serializable
    data class ModeInfoResponse(
        val mode: String,
        val description: String,
    )
}
