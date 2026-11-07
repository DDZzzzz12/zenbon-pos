/**
 * TSE (Technical Security Element) mode enumeration for German fiscal compliance.
 *
 * Defines the different TSE operation modes that can be configured for a shop
 * through the shop setup system. This determines which TSE service implementation
 * will be used for handling fiscal transactions.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.enums.setup

import kotlinx.serialization.Serializable

/**
 * Enumeration of supported TSE operation modes.
 *
 * Each mode corresponds to a different TSE service implementation:
 * - SIMULATION: Development/testing with simulated TSE operations
 * - NORMAL: Production with actual TSE hardware integration
 * - DISABLED: No TSE functionality for non-German markets
 */
@Serializable
enum class TseMode {
    /**
     * Mock TSE mode for development and testing.
     *
     * Uses simulated TSE operations with fast execution and predictable responses.
     * Ideal for development, testing, and demonstration environments.
     */
    SIMULATION,

    /**
     * Disabled TSE mode for non-German markets.
     *
     * Minimal TSE operations that return success responses without
     * actual transaction recording. Used for markets that don't
     * require German fiscal compliance.
     */
    DISABLED,

    /**
     * Real TSE mode for production environments.
     *
     * Integrates with actual TSE hardware for German fiscal compliance.
     * Required for live POS systems operating in Germany.
     */
    NORMAL,

    ;

    /**
     * Returns a human-readable description of the TSE mode.
     *
     * @return User-friendly description string
     */
    fun getDescription(): String =
        when (this) {
            SIMULATION -> "Mock TSE (Development/Testing)"
            NORMAL -> "Real TSE Hardware (Production)"
            DISABLED -> "TSE Disabled (Non-German Markets)"
        }

    /**
     * Determines if this TSE mode requires actual transaction recording.
     *
     * @return true if transactions should be recorded, false otherwise
     */
    fun requiresTransactionRecording(): Boolean =
        when (this) {
            SIMULATION, NORMAL -> true
            DISABLED -> false
        }

    /**
     * Determines if this TSE mode supports hardware operations.
     *
     * @return true if hardware integration is available, false otherwise
     */
    fun supportsHardware(): Boolean =
        when (this) {
            NORMAL -> true
            SIMULATION, DISABLED -> false
        }
}
