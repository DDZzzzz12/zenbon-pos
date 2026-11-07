package org.example.project.core.domain.dto.cardTerminal

import kotlinx.serialization.Serializable

/**
 * Container object for CardTerminal-related DTOs.
 *
 * Organizes DTOs by functionality: requests and responses for
 * card terminal management and diagnostics.
 */
object CardTerminalDto {
    @Serializable
    data class CardTerminalResponseDto(
        val id: Long,
        val name: String,
        val host: String,
        val port: Int,
        val terminalId: String?,
        val softwareVersion: String?,
        val isActive: Boolean,
        val isConnected: Boolean,
        val isBusy: Boolean,
        val shopInfoId: Long,
        val createdAt: String,
        val updatedAt: String,
    )

    @Serializable
    data class CreateCardTerminalRequestDto(
        val name: String,
        val host: String,
        val port: Int,
        val password: String,
        val shopInfoId: Long,
    ) {
        fun validate() {
            require(name.isNotBlank()) { "Terminal name is required" }
            require(host.isNotBlank()) { "Host is required" }
            require(port in 1..65535) { "Port must be between 1 and 65535" }
            require(password.isNotBlank()) { "Password is required" }
        }
    }

    @Serializable
    data class UpdateCardTerminalRequestDto(
        val name: String? = null,
        val host: String? = null,
        val port: Int? = null,
        val password: String? = null,
    ) {
        fun validate() {
            name?.let { require(it.isNotBlank()) { "Terminal name cannot be blank" } }
            host?.let { require(it.isNotBlank()) { "Host cannot be blank" } }
            port?.let { require(it in 1..65535) { "Port must be between 1 and 65535" } }
            password?.let { require(it.isNotBlank()) { "Password cannot be blank" } }
        }
    }

    @Serializable
    data class DiagnosisResponseDto(
        val terminalId: Long,
        val terminalName: String,
        val host: String,
        val port: Int,
        val softwareVersion: String?,
        val isConnected: Boolean,
        val diagnosisSuccess: Boolean,
        val resultCode: Int?,
        val message: String?,
    )
}
