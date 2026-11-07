/**
 * TSE signing status enumeration for tracking transaction signature state.
 *
 * Defines the lifecycle states for TSE (Technical Security Element) signing
 * operations as part of the resilience system that supports German KassenSichV
 * compliance with "Nachsignieren" (late signing) capability.
 *
 * State Transitions:
 * - PENDING → PROCESSING → SIGNED (success path)
 * - PENDING → PROCESSING → QUEUED (TSE unavailable, retry later)
 * - PENDING → PROCESSING → FAILED (max retries exceeded)
 * - Any → CANCELLED (manual cancellation)
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.enums.tse

import kotlinx.serialization.Serializable

/**
 * Enumeration of TSE signing states for transaction lifecycle tracking.
 *
 * This status is used by the TSE signing queue to track whether transactions
 * have been successfully signed by the TSE device or are awaiting signature.
 */
@Serializable
enum class TseSigningStatus {
    /**
     * Transaction created but signing not yet attempted.
     *
     * Initial state for new transactions that need TSE signature.
     * The signing queue will pick up these entries for processing.
     */
    PENDING,

    /**
     * TSE signing is currently in progress.
     *
     * The background retry service is actively attempting to sign
     * this transaction. Prevents duplicate processing.
     */
    PROCESSING,

    /**
     * Transaction queued for later signing (Nachsignieren).
     *
     * TSE was unavailable at time of transaction. The transaction
     * has been queued for automatic retry when TSE recovers.
     * This is a KassenSichV-compliant state.
     */
    QUEUED,

    /**
     * Transaction successfully signed by TSE.
     *
     * Terminal state indicating the transaction has a valid
     * TSE signature and meets German fiscal compliance requirements.
     */
    SIGNED,

    /**
     * TSE signing failed after maximum retry attempts.
     *
     * Terminal failure state. Manual intervention required.
     * Should be investigated for root cause analysis.
     */
    FAILED,

    /**
     * TSE signing cancelled by operator.
     *
     * Manual cancellation of a pending signing operation.
     * Used for voided transactions or administrative corrections.
     */
    CANCELLED,

    /**
     * No TSE transaction is required for this record. Written in two cases:
     *  - entries in a **non-fiscal cash book** (Tresor, Nebenkasse) — not part of an elektronisches
     *    Aufzeichnungssystem (§146a AO);
     *  - the fiscal **Z-Bon `CASH_SALE` aggregate**, which restates sales each bill already signed as
     *    its own Kassenbeleg (AEAO zu § 146a Nr. 1.11.2). Since spec 2026-10-09; earlier aggregates
     *    keep their signature.
     *
     * Terminal and never reached from another state. Purely descriptive: what actually
     * prevents signing is the skipped Phase 2 in `KassenbuchServiceImpl` plus the guard in
     * `TseSigningQueueService`. This value exists so the stored row does not claim
     * `PENDING` — "awaiting signature" — forever in a GoBD-relevant table.
     */
    NOT_APPLICABLE,

    DISABLED,
    SIMULATION,
    ;

    /**
     * Returns a human-readable description of the signing status.
     *
     * @return User-friendly description string
     */
    fun getDescription(): String =
        when (this) {
            PENDING -> "Awaiting TSE signature"
            PROCESSING -> "TSE signing in progress"
            QUEUED -> "Queued for later signing (Nachsignieren)"
            SIGNED -> "Successfully signed by TSE"
            FAILED -> "TSE signing failed"
            CANCELLED -> "Signing cancelled"
            DISABLED -> "Signing disabled"
            SIMULATION -> "Signed in simulation mode"
            NOT_APPLICABLE -> "Signing not applicable (non-fiscal cash book, or a Z-Bon aggregate)"
        }

    /**
     * Determines if this status represents a terminal state.
     *
     * Terminal states cannot transition to other states through
     * normal processing (except QUEUED which can retry).
     *
     * @return true if this is a terminal state, false otherwise
     */
    fun isTerminal(): Boolean =
        when (this) {
            SIGNED, FAILED, CANCELLED, DISABLED, SIMULATION, NOT_APPLICABLE -> true
            PENDING, PROCESSING, QUEUED -> false
        }

    /**
     * Determines if this status requires attention/action.
     *
     * Used for monitoring and alerting purposes.
     *
     * @return true if action may be required, false otherwise
     */
    fun requiresAttention(): Boolean =
        when (this) {
            FAILED -> true
            QUEUED -> true // Many queued entries may indicate TSE issues
            PENDING, PROCESSING, SIGNED, CANCELLED, DISABLED, SIMULATION, NOT_APPLICABLE -> false
        }

    /**
     * Determines if retry is allowed from this status.
     *
     * @return true if manual or automatic retry is permitted
     */
    fun canRetry(): Boolean =
        when (this) {
            PENDING, QUEUED, FAILED, DISABLED -> true
            PROCESSING, SIGNED, CANCELLED, SIMULATION, NOT_APPLICABLE -> false
        }
}
