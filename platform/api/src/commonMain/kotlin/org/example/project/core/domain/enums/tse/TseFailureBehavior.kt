/**
 * TSE failure behavior enumeration for configuring system response to TSE unavailability.
 *
 * Determines how the POS system should behave when TSE signing fails due to
 * TSE device being offline, unreachable, or experiencing errors.
 *
 * This setting is stored in ShopConfiguration and checked during transaction
 * creation to decide whether to:
 * 1. Queue the transaction for later signing (QUEUE) - KassenSichV compliant
 * 2. Fail the transaction and require manual retry (FAIL) - Strict mode
 *
 * German Legal Context:
 * - KassenSichV allows "Nachsignieren" (late signing) within 24 hours
 * - QUEUE mode enables business continuity during TSE outages
 * - FAIL mode ensures every transaction is immediately signed
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.enums.tse

import kotlinx.serialization.Serializable

/**
 * Enumeration of TSE failure handling behaviors.
 *
 * Configured per-shop to control transaction behavior when TSE is unavailable.
 */
@Serializable
enum class TseFailureBehavior {
    /**
     * Queue transactions for later signing (Nachsignieren).
     *
     * When TSE is unavailable:
     * - Transaction completes successfully
     * - Entry added to signing queue
     * - Background job retries signing when TSE recovers
     * - Customer receives receipt (marked as pending signature)
     *
     * Benefits:
     * - Business continuity during TSE outages
     * - KassenSichV compliant (Nachsignieren allowed)
     * - No customer-facing delays
     *
     * Considerations:
     * - Requires monitoring of queue depth
     * - Must resolve TSE issues within legal timeframe
     */
    QUEUE,

    /**
     * Fail transaction immediately if TSE unavailable.
     *
     * When TSE is unavailable:
     * - Transaction is rolled back
     * - Error returned to caller
     * - Customer must wait for TSE recovery
     *
     * Benefits:
     * - Every transaction guaranteed to have signature
     * - Simpler compliance audit trail
     * - No pending signatures to manage
     *
     * Considerations:
     * - Business interruption during TSE outages
     * - Customer-facing delays
     * - May impact revenue during outages
     */
    FAIL,

    ;

    /**
     * Returns a human-readable description of the failure behavior.
     *
     * @return User-friendly description string
     */
    fun getDescription(): String =
        when (this) {
            QUEUE -> "Queue for later signing (allows business continuity)"
            FAIL -> "Fail immediately (strict signing requirement)"
        }

    /**
     * Determines if this behavior allows transaction completion without immediate signing.
     *
     * @return true if transactions can complete without immediate TSE signature
     */
    fun allowsDelayedSigning(): Boolean =
        when (this) {
            QUEUE -> true
            FAIL -> false
        }

    /**
     * Returns the German legal term for this behavior.
     *
     * @return German term describing the behavior
     */
    fun getLegalTerm(): String =
        when (this) {
            QUEUE -> "Nachsignieren"
            FAIL -> "Sofortsignierung"
        }
}
