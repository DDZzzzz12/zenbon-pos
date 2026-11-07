/**
 * TSE entity type enumeration for identifying what kind of business transaction
 * requires TSE signing.
 *
 * Used by the TSE signing queue to properly route and process different
 * transaction types, each with potentially different signing requirements
 * and process data formats.
 *
 * DSFinV-K Mapping:
 * - BILL → Kassenbeleg-V1 (final receipt with payment)
 * - ORDER → Bestellung-V1 (order items, updateable)
 * - KASSENBUCH → SonstigerVorgang (cash book entries)
 * - CANCELLATION → Kassenbeleg-V1 with AVBelegstorno
 * - VOUCHER → SonstigerVorgang (voucher transactions)
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.enums.tse

import kotlinx.serialization.Serializable

/**
 * Enumeration of entity types that require TSE signing.
 *
 * Each entity type corresponds to a specific business transaction
 * that must be recorded in the TSE for German fiscal compliance.
 */
@Serializable
enum class TseEntityType {
    /**
     * Bill/Receipt entity (Kassenbeleg).
     *
     * Final sales receipt with payment information.
     * Uses DSFinV-K process type: Kassenbeleg-V1
     * Transaction type: Beleg
     */
    BILL,

    /**
     * Order entity (Bestellung).
     *
     * Order with items that may be updated before billing.
     * Uses DSFinV-K process type: Bestellung-V1
     * Supports updates via updateTransaction.
     */
    ORDER,

    /**
     * Kassenbuch entry (Cash book).
     *
     * Cash register book entries for cash-in/cash-out.
     * Uses DSFinV-K process type: SonstigerVorgang
     */
    KASSENBUCH,

    /**
     * Cancellation/Storno entity.
     *
     * Cancellation of a previous transaction (Storno).
     * Uses DSFinV-K process type: Kassenbeleg-V1
     * Transaction type: AVBelegstorno
     */
    CANCELLATION,

    /**
     * Voucher transaction entity.
     *
     * Voucher sales, redemptions, and related transactions.
     * Uses DSFinV-K process type: SonstigerVorgang
     */
    VOUCHER,

    ;

    /**
     * Returns a human-readable description of the entity type.
     *
     * @return User-friendly description string
     */
    fun getDescription(): String =
        when (this) {
            BILL -> "Bill/Receipt (Kassenbeleg)"
            ORDER -> "Order (Bestellung)"
            KASSENBUCH -> "Cash Book Entry (Kassenbuch)"
            CANCELLATION -> "Cancellation (Storno)"
            VOUCHER -> "Voucher Transaction"
        }

    /**
     * Returns the DSFinV-K process type for this entity.
     *
     * @return Process type string for TSE signing
     */
    fun getProcessType(): String =
        when (this) {
            BILL -> "Kassenbeleg-V1"
            ORDER -> "Bestellung-V1"
            KASSENBUCH -> "SonstigerVorgang"
            CANCELLATION -> "Kassenbeleg-V1"
            VOUCHER -> "SonstigerVorgang"
        }

    /**
     * Determines if this entity type supports TSE transaction updates.
     *
     * Only Bestellung-V1 and SonstigerVorgang support updates.
     * Kassenbeleg-V1 is a one-shot transaction.
     *
     * @return true if updates are allowed, false otherwise
     */
    fun supportsUpdates(): Boolean =
        when (this) {
            ORDER -> true
            KASSENBUCH, VOUCHER -> true
            BILL, CANCELLATION -> false
        }

    /**
     * Written to the queue row's `priority` column but no longer used for ordering: the queue
     * drains oldest first (E2E R2-4, 2026-09-22).
     */
    fun getSigningPriority(): Int =
        when (this) {
            BILL -> 1 // Highest priority - customer waiting
            CANCELLATION -> 2 // High priority - reversal transaction
            ORDER -> 5 // Medium priority
            VOUCHER -> 7 // Lower priority
            KASSENBUCH -> 10 // Lowest priority - internal record
        }
}
