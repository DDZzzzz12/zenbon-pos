/**
 * Exposed table definition for TSE (Technical Security Element) transactions.
 *
 * Defines the database schema structure for digital receipt compliance with proper
 * column types and constraints. Supports German fiscal compliance requirements
 * for digital signatures and transaction tracking.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.tse.database.tables

import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.tse.domain.models.TseTransactionState
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Exposed table definition for tse_transaction table.
 *
 * Manages TSE (Technical Security Element) transactions for German fiscal compliance.
 * Stores digital signatures and transaction data required for tax authorities.
 */
object TseTransactionTable : LongIdTable("tse_transaction", "id") {
    /** Serial number of the TSE device */
    val tseSerialNumber = varchar("tse_serial_number", 100).nullable()

    /**
     * Sequential transaction number assigned by the TSE device.
     *
     * NOT globally unique: it is a per-device counter that restarts at 1 whenever the
     * TSE is replaced/re-initialized, and SIMULATION mode uses epoch-millis values under a
     * constant serial. Uniqueness is therefore the composite `(tse_serial_number,
     * transaction_number)` declared in the `init` block below — never this column alone.
     */
    val transactionNumber = long("transaction_number").nullable()

    /** Process type (e.g., "Bestellung-V1", "Kassenbeleg-V1") */
    val processType = varchar("process_type", 100).nullable()

    /** Process data signed by TSE (used for QR code generation) */
    // B1: this stores the EXACT processData handed to the TSE, so it must never truncate — a
    // truncated value would make the exported TSE_VORGANGSDATEN disagree with what was signed.
    // Bestellung-V1 is one CSV line per order line (Anhang I p. 116) and Kassenbeleg's
    // <Zahlungen> grows with split tenders, so both are unbounded; varchar(500) overflowed at
    // ~14 order lines. The TSE had already signed when the insert raised SQLSTATE 22001, leaving
    // a WORM signature with no DB row and a queue entry that re-signed every 60 s forever.
    // `text` matches publicKey/certificate above, which are unbounded for the same reason.
    val processDataHash = text("process_data_hash").nullable()

    /** Digital signature from TSE */
    val signature = varchar("signature", 1000).nullable()

    /** Transaction start timestamp */
    val startTime = datetime("start_time").nullable()

    /** Transaction end timestamp */
    val endTime = datetime("end_time").nullable()

    /** Cash register or workstation ID */
    val clientId = varchar("client_id", 100).nullable()

    /** TSE mode used for this transaction (SIMULATION, NORMAL, DISABLED) */
    val tseMode = enumerationByName<TseMode>("tse_mode", 20).default(TseMode.SIMULATION)

    /**
     * WHAT this signature was produced for — the Vorgang's id and kind (M7).
     *
     * Nothing recorded it before, so the TSE log could not be explained per Vorgang, and a
     * signature that persisted while its entity link did not (duplicate enqueue, crash between the
     * write and the link) was indistinguishable from "never signed" — the queue then signed the
     * same payload a SECOND time, permanently, into WORM storage.
     *
     * This does NOT close the lost-response window: when the response never arrives, no row was
     * written at all and the outcome is unknowable from our side. That case is handled by recording
     * the double-sign RISK instead — see `TseSigningQueueServiceImpl`.
     *
     * Nullable for rows written before this column existed.
     */
    val referenceId = varchar("reference_id", 100).nullable()

    /** Entity kind [referenceId] points at, so the id is unambiguous across sources. */
    val entityType = varchar("entity_type", 30).nullable()

    /** Reference to exported file (e.g., DSFinV-K export) */
    val exportedFileReference = varchar("exported_file_reference", 500).nullable()

    /** TSE log timestamp (Unix time in seconds from TSE hardware) */
    val tseTimestamp = long("tse_timestamp").nullable()

    /**
     * TSE StartTransaction log timestamp (Unix time in seconds from TSE hardware).
     *
     * Populated from `TransactionStartResultDto.timestamp` (the TSE's own StartTransaction
     * log time), distinct from [startTime] (the server clock) and [tseTimestamp] (the TSE's
     * FinishTransaction log time). Used to source `TSE_TA_START` in `transactions_tse.csv`.
     */
    val tseStartTimestamp = long("tse_start_timestamp").nullable()

    /** Sequential signature counter from TSE */
    val signatureCounter = long("signature_counter").nullable()

    /** Signature algorithm used by TSE (e.g., "ecdsa-plain-SHA384") */
    val signatureAlgorithm = varchar("signature_algorithm", 100).nullable()

    /**
     * m3: the TSE's internal log-time format (TSE_ZEITFORMAT, Anhang E p. 28) — a DEVICE property,
     * persisted per transaction because a DSFinV-K export re-derives historical closings and a live
     * device read cannot answer for a row a since-swapped TSE signed. Null on rows predating V102;
     * the export falls back to "unixTime" (correct for the Swissbit device those rows came from).
     */
    val logTimeFormat = varchar("log_time_format", 31).nullable()

    /** TSE public key (Base64 encoded) */
    val publicKey = text("public_key").nullable()

    /** TSE certificate (Base64 encoded) */
    val certificate = text("certificate").nullable()

    /** See [org.example.project.features.tse.domain.models.TseTransactionState]; V82. */
    val state = enumerationByName<TseTransactionState>("state", 16).default(TseTransactionState.FINISHED)

    /** The replacing row's id when this one is SUPERSEDED; V82. */
    val supersededById = long("superseded_by_id").nullable()

    /**
     * Canonical hex serial (TR-03153-1 §9.3.2): every comparison and grouping of device identity
     * uses this, not [tseSerialNumber].
     *
     * §9.3.2 allows only the hex string for identifying and assigning a TSE, so a Base64 value is
     * not a serial at all for that purpose. Rows written before V82 carry whatever form the device
     * delivered, which made ONE device appear under TWO serials — read them through
     * `TseTransaction.serialHex()`, which falls back to normalizing the raw column.
     *
     * One deliberate exception: `SimulatedSignatureRepository` matches BOTH columns, because a
     * pre-V82 row carries the simulator's serial only in the raw one and that check must not go
     * quiet. V82.
     */
    val tseSerialHex = varchar("tse_serial_hex", 100).nullable()

    init {
        // Per-device uniqueness: the same transaction_number may legitimately recur across
        // different TSE devices (each counter restarts at 1). A new/replacement TSE must not
        // be rejected because an old device already used numbers 1, 2, 3…
        uniqueIndex("tse_transaction_serial_number_unique", tseSerialNumber, transactionNumber)

        // The same pair on the canonical serial (V82). The raw-column index above cannot catch a
        // duplicate written in two encodings, because the two raw strings differ. A fresh install
        // gets this UNIQUE; a migrated one gets whatever V82 could create, which is plain when
        // history already holds such a duplicate.
        uniqueIndex("idx_tse_tx_serial_hex_tanr", tseSerialHex, transactionNumber)

        // V82's partial unique index: one non-SUPERSEDED row per (reference_id, entity_type), the
        // backstop against two workers signing one Vorgang twice. Partial because a Vorgang
        // legitimately keeps every replaced TANR. PostgreSQL only: Exposed SKIPS a filtered index
        // on H2 (it does not drop the WHERE), so the H2 suite has no such index at all. Declared
        // here so a fresh install gets it from the same declaration as everything else, and
        // SchemaManager re-creates it on every start if absent. A migrated install whose history
        // already held duplicates carries it PLAIN under the same name (V82's fallback), which the
        // by-name check leaves alone. `TseOneLiveRowPerVorgangIndexTest` covers both branches.
        uniqueIndex(
            "tse_transaction_one_live_per_vorgang",
            referenceId,
            entityType,
            filterCondition = {
                (state neq TseTransactionState.SUPERSEDED) and referenceId.isNotNull() and entityType.isNotNull()
            },
        )

        // Vorgang lookups (V82) — the plain twin of the guard above, which covers live rows only.
        index("idx_tse_transaction_reference", false, referenceId, entityType)

        // Date-range lookups (DSFinV-K export, financial docs) — kept in sync with
        // Flyway V86 (matching index name so fresh and existing installs converge).
        index("idx_tse_tx_start_time", false, startTime)
    }
}
