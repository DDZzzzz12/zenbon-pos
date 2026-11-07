package org.example.project.features.terminal.domain.service

import org.jetbrains.exposed.v1.core.dao.id.EntityID

/**
 * Port through which the terminal service reaches the fiscal side: the TSE device (each fiscal
 * terminal is a TSE client, §146a AO), the TSE admin sitting, the ecr signing state of a
 * terminal's bills and orders, and the DSFinV-K Stammdaten freeze.
 *
 * Safety-critical. Every member keeps the exact semantics the terminal service relied on when it
 * called the TSE directly:
 * - an [org.example.project.infrastructure.errors.PosException] raised by the device or the admin
 *   sitting (for example `TSE_ADMIN_LOCKED` / 423) propagates **unchanged** — the caller decides
 *   whether to wrap it;
 * - `suspend` members talk to the device and are called **outside** any transaction;
 * - the two non-suspend database members run **inside the caller's** `dbQuery`, so their reads and
 *   writes commit or roll back with the caller's.
 */
interface TerminalFiscalGateway {
    /** Whether the TSE is commissioned. Never throws a PosException. */
    suspend fun isTseInitialized(): Boolean

    /** Whether the TSE admin sitting is unlocked now. */
    fun isAdminUnlocked(): Boolean

    /** The epoch of the live admin sitting, or null when it is locked. A new sitting has a new epoch. */
    fun currentAdminEpochIfUnlocked(): Long?

    /** Registers [clientId] at the TSE. A device refusal is thrown as a PosException, unchanged. */
    suspend fun registerClient(clientId: String): TseClientOpResult

    /** Deregisters [clientId] at the TSE. A device refusal is thrown as a PosException, unchanged. */
    suspend fun deregisterClient(clientId: String): TseClientOpResult

    /**
     * The client ids the TSE lists. The device's own PosException (e.g. 423 while the sitting is
     * locked) propagates unchanged; an unsuccessful answer is thrown as `INTERNAL_ERROR` / 500.
     */
    suspend fun listRegisteredClientIds(): Set<String>

    /** How many started (unfinished) transactions the TSE holds for [clientId]. */
    suspend fun startedTransactionCount(clientId: String): Int

    /** True if the terminal [terminalId] owns any PENDING-signed bill or order. Runs inside the caller's dbQuery. */
    fun hasPendingSigning(terminalId: EntityID<Long>): Boolean

    /**
     * Freezes the current (pre-rewrite) cash-register Stammdaten onto every closing that has no
     * snapshot yet. Must be called inside the caller's dbQuery, BEFORE a terminal's master data is
     * written in that same transaction.
     */
    fun freezeStammdatenBeforeTerminalRewrite()
}
