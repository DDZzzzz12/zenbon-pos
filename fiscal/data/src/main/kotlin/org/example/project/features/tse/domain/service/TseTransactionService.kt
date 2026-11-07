package org.example.project.features.tse.domain.service

import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.features.tse.domain.models.TseTransaction

/**
 * PROTECTED MODULE — Do not modify without explicit authorization.
 * TSE operations are fiscally regulated (KassenSichV) and changes require careful review.
 *
 * TSE Transaction Service interface for German fiscal compliance.
 *
 * Provides services for creating, managing, and tracking TSE (Technical Security Element)
 * transactions as required by German tax authorities. Handles digital signatures,
 * transaction logging, and compliance verification.
 *
 * Transaction Modes:
 * 1. **Quick Sign**: Single call for simple transactions (signTransaction)
 * 2. **Two-Step**: Start then Finish
 * 3. **Multi-Step**: Start, Update(s), Finish
 *
 * All methods throw `org.example.project.infrastructure.errors.PosException` on failure
 * (B1 amendment); see the cross-walk table in
 * `docs/superpowers/plans/2026-05-04-poserror-tse-migration.md`.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
interface TseTransactionService {
    companion object {
        const val DEFAULT_QUEUE_CLIENT_ID = "POS-QUEUE"

        // DSFinV-K Process Types (Vorgangstypen)
        const val PROCESS_TYPE_RECEIPT = "Kassenbeleg-V1"
        const val PROCESS_TYPE_ORDER = "Bestellung-V1"
        const val PROCESS_TYPE_OTHER = "SonstigerVorgang"

        // Transaction Types for Kassenbeleg-V1 ProcessData
        const val TX_TYPE_RECEIPT = "Beleg"
        const val TX_TYPE_TRANSFER = "AVTransfer"
        const val TX_TYPE_ORDER = "AVBestellung"
        const val TX_TYPE_TRAINING = "AVTraining"
        const val TX_TYPE_VOID = "AVBelegstorno"
        const val TX_TYPE_ABORT = "AVBelegabbruch"
        const val TX_TYPE_BENEFIT = "AVSachbezug"
        const val TX_TYPE_OTHER = "AVSonstige"
        const val TX_TYPE_INVOICE = "AVRechnung"
    }

    /**
     * Starts a new TSE transaction.
     *
     * Returns the started TseTransaction, or null if TSE is disabled.
     */
    suspend fun startTransaction(
        processType: String = PROCESS_TYPE_ORDER,
        processData: String,
        referenceId: String? = null,
        clientId: String,
    ): TseTransaction?

    /**
     * Updates an active TSE transaction with new process data.
     *
     * Returns the updated TseTransaction, or null if TSE is disabled.
     */
    suspend fun updateTransaction(
        tseTransactionId: Long,
        processData: String,
        clientId: String,
    ): TseTransaction?

    /**
     * Finishes an active TSE transaction.
     *
     * Returns the completed TseTransaction, or null if TSE is disabled.
     */
    suspend fun finishTransaction(
        tseTransactionId: Long,
        finalProcessData: String,
        clientId: String,
    ): TseTransaction?

    /**
     * Aborts an already-started TSE transaction as an `AVBelegabbruch`.
     *
     * This is the ONLY correct way to close out a Vorgang that was started on the TSE and then
     * abandoned. DSFinV-K 2.4 Anhang B p. 46 defines the case:
     * „Der Vorgangstyp ‚AVBelegabbruch' kennzeichnet alle Vorgänge, die nach Transaktionsbeginn
     * abgebrochen werden. Eine tatsächliche Bezahlung darf im Zusammenhang mit diesem Vorgangstyp
     * nicht erfolgen." Anhang I p. 120 fixes the payload to
     * `AVBelegabbruch^0.00_0.00_0.00_0.00_0.00^` — five zero buckets and an EMPTY payment section,
     * because no payment may attach to an aborted Vorgang.
     *
     * Callers must not hand-roll the payload; it is built once by
     * `ProcessDataBuilder.buildBelegabbruch()`. Leaving a started transaction unfinished instead is
     * not an option — it stays open on the WORM device indefinitely.
     *
     * **No current caller.** The two-step start/finish flow (quick sale) was removed; every live
     * signing path uses [signTransaction] (quickSign), which cannot leave a transaction open. This
     * method exists so that a future feature which does need [startTransaction] has the correct
     * abort semantics available rather than reinventing them.
     *
     * **Export obligation for any future caller:** an abort still consumes a TSE transaction number
     * and signature counter, and DSFinV-K §2.1 requires every TSE-secured Vorgang to appear as a
     * recognisable Vorgang in the export. The DSFinV-K export currently builds its Vorgänge from
     * bills, Kassenbuch entries, orders and cancellations only — so an `AVBelegabbruch` producer
     * must be added there BEFORE this method is used in anger, otherwise the TSE log will contain a
     * signed transaction the export cannot explain.
     *
     * @param tseTransactionId The persisted transaction to abort.
     * @param clientId TSE client id the transaction was started under.
     * @return The finished TseTransaction, or null if TSE is disabled.
     */
    suspend fun abortTransaction(
        tseTransactionId: Long,
        clientId: String,
    ): TseTransaction?

    /**
     * Signs one Vorgang through `TseVorgangSigner` (pos-server; not linkable from core-data).
     * ([referenceId], [entityType]) must be the same key the signing queue uses for this Vorgang.
     */
    suspend fun signTransaction(
        processType: String = PROCESS_TYPE_RECEIPT,
        processData: String,
        referenceId: String,
        clientId: String,
        entityType: TseEntityType,
    ): TseTransaction?

    /**
     * Retrieves a TSE transaction by ID.
     */
    suspend fun getTseTransactionById(tseTransactionId: Long): TseTransaction

    /**
     * Retrieves TSE transactions for a specific time range.
     */
    suspend fun getTseTransactionsByDateRange(
        startDate: String,
        endDate: String,
        limit: Int = 100,
        offset: Int = 0,
    ): List<TseTransaction>

    /**
     * Validates TSE transaction integrity.
     */
    suspend fun validateTransaction(tseTransactionId: Long): Boolean
}
