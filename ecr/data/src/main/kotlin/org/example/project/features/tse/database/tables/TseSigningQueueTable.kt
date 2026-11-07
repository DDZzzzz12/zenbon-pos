/**
 * Exposed table definition for TSE signing queue.
 *
 * Defines the database schema for the queue-based TSE signing system that
 * enables "Nachsignieren" (late signing) for German KassenSichV compliance.
 *
 * The queue stores transactions that need TSE signatures, allowing:
 * - Business continuity during TSE outages
 * - Automatic retry with exponential backoff
 * - Priority-based processing
 * - Complete audit trail for compliance
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.tse.database.tables

import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Exposed table definition for tse_signing_queue table.
 *
 * Manages the queue of transactions awaiting TSE signature. Supports
 * automatic retry, priority ordering, and status tracking for the
 * background signing service.
 */
object TseSigningQueueTable : LongIdTable("tse_signing_queue", "id") {
    /**
     * Reference ID of the entity that needs signing.
     * For Bills: UUID string
     * For Orders/Kassenbuch: Long ID as string
     */
    val referenceId = varchar("reference_id", 100)

    /**
     * TSE transaction number already STARTED for this Vorgang (quick-sale finish failure).
     *
     * VESTIGIAL since the signer resumes by (reference, entity type): it finds the Vorgang's
     * STARTED `tse_transaction` row itself and finishes THAT transaction number, so nothing reads
     * this value any more. Column and parameter are kept so stored rows stay readable and callers
     * need not change; do not build new behaviour on it (TSER-12).
     */
    val openTseTransactionNumber = long("open_tse_transaction_number").nullable()

    /**
     * Type of entity that needs signing (BILL, ORDER, KASSENBUCH, etc.).
     */
    val entityType = enumerationByName<TseEntityType>("entity_type", 20)

    /**
     * DSFinV-K process type for TSE signing.
     * E.g., "Kassenbeleg-V1", "Bestellung-V1", "SonstigerVorgang"
     */
    val processType = varchar("process_type", 50)

    /**
     * JSON process data to be signed by TSE.
     * Contains the DSFinV-K formatted transaction data.
     */
    val processData = text("process_data")

    /**
     * TSE client ID used for signing.
     * Identifies the POS terminal/workstation.
     */
    val clientId = varchar("client_id", 100)

    /**
     * Current signing status.
     * Tracks the lifecycle of the signing request.
     */
    val signingStatus =
        enumerationByName<TseSigningStatus>("signing_status", 20)
            .default(TseSigningStatus.PENDING)

    /**
     * Number of retry attempts made.
     */
    val retryCount = integer("retry_count").default(0)

    /**
     * Maximum number of retries before marking as FAILED.
     */
    val maxRetries = integer("max_retries").default(10)

    /**
     * Last error message if signing failed.
     */
    val lastError = text("last_error").nullable()

    /**
     * Last error code for categorization.
     */
    val lastErrorCode = varchar("last_error_code", 50).nullable()

    /**
     * Timestamp when the queue entry was created.
     */
    val createdAt = datetime("created_at")

    /**
     * Timestamp of the last signing attempt.
     */
    val lastAttemptAt = datetime("last_attempt_at").nullable()

    /**
     * Timestamp when signing was completed successfully.
     */
    val signedAt = datetime("signed_at").nullable()

    /**
     * Timestamp for the next retry attempt.
     * Used for exponential backoff scheduling.
     */
    val nextRetryAt = datetime("next_retry_at").nullable()

    /**
     * Priority for queue processing.
     * Lower values = higher priority.
     * 1 = Bills (customer waiting)
     * 5 = Orders (medium)
     * 10 = Kassenbuch (low)
     */
    val priority = integer("priority").default(5)

    /**
     * Reference to the TSE transaction after successful signing.
     */
    val tseTransactionId = reference("tse_transaction_id", TseTransactionTable).nullable()

    init {
        // Poller hot path (PENDING/RETRY entries due for retry) — kept in sync with
        // Flyway V86 (matching index name so fresh installs (Exposed-created schema)
        // and existing installs (migration) converge).
        index("idx_tse_queue_status_retry", false, signingStatus, nextRetryAt)
    }
}
