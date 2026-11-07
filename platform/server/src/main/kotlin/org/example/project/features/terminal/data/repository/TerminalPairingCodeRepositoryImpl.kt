/**
 * Implementation of TerminalPairingCodeRepository using Exposed ORM.
 *
 * Provides concrete implementation of pairing-code data access operations
 * using Exposed database framework. Exceptions propagate to service layer.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.terminal.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.terminal.database.tables.TerminalPairingCodeTable
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.terminal.domain.models.TerminalPairingCode
import org.example.project.features.terminal.domain.repository.TerminalPairingCodeRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Exposed-based implementation of TerminalPairingCodeRepository.
 *
 * Provides data access for short-lived, single-use pairing codes.
 * Note: Does NOT wrap in dbQuery - service layer controls transactions.
 */
class TerminalPairingCodeRepositoryImpl : TerminalPairingCodeRepository {
    override fun create(
        terminal: Terminal,
        code: String,
        expiresAt: LocalDateTime,
        adminSessionEpoch: Long?,
        registerTseClient: Boolean,
    ): TerminalPairingCode =
        TerminalPairingCode.new {
            this.terminal = terminal
            this.code = code
            this.expiresAt = expiresAt
            this.consumedAt = null
            this.adminSessionEpoch = adminSessionEpoch
            this.registerTseClient = registerTseClient
            this.createdAt = currentTimeStamp()
        }

    override fun findUnconsumedByCode(code: String): TerminalPairingCode? =
        TerminalPairingCode
            .find { (TerminalPairingCodeTable.code eq code) and (TerminalPairingCodeTable.consumedAt.isNull()) }
            .orderBy(TerminalPairingCodeTable.createdAt to SortOrder.DESC)
            .firstOrNull()

    override fun findLiveExpiryByTerminal(
        terminalIds: List<Long>,
        now: LocalDateTime,
    ): Map<Long, LocalDateTime> {
        if (terminalIds.isEmpty()) return emptyMap()
        return TerminalPairingCodeTable
            .selectAll()
            .where {
                (TerminalPairingCodeTable.terminalId inList terminalIds) and
                    TerminalPairingCodeTable.consumedAt.isNull() and
                    (TerminalPairingCodeTable.expiresAt greaterEq now)
            }.groupBy { it[TerminalPairingCodeTable.terminalId].value }
            // A terminal can hold several unconsumed codes if one was minted before an earlier one
            // lapsed. The one that matters to an operator is the one that lasts longest.
            .mapValues { (_, rows) -> rows.maxOf { it[TerminalPairingCodeTable.expiresAt] } }
    }

    override fun isCodeLive(
        code: String,
        now: LocalDateTime,
    ): Boolean {
        val newest = findUnconsumedByCode(code) ?: return false
        return newest.expiresAt >= now
    }

    override fun markConsumed(
        entity: TerminalPairingCode,
        consumedAt: LocalDateTime,
    ) {
        entity.consumedAt = consumedAt
    }

    override fun consumeAllForTerminal(
        terminal: Terminal,
        consumedAt: LocalDateTime,
    ) {
        TerminalPairingCode
            .find { (TerminalPairingCodeTable.terminalId eq terminal.id) and (TerminalPairingCodeTable.consumedAt.isNull()) }
            .forEach { it.consumedAt = consumedAt }
    }
}
