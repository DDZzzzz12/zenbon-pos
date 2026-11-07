package org.example.project.features.tse.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.tse.TseSigningNoteDto
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.features.tse.database.tables.TseLateSigningTable
import org.example.project.features.tse.domain.repository.TseLateSigningRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

class TseLateSigningRepositoryImpl : TseLateSigningRepository {
    override fun recordQueued(
        entityType: TseEntityType,
        referenceId: String,
        queuedAt: LocalDateTime,
        reason: TseLateSigningReason,
        reasonText: String,
    ) {
        // Select-then-insert rather than a dialect-specific upsert: the unique index
        // uq_tse_late_signing_ref still refuses a concurrent duplicate.
        val exists = TseLateSigningTable.selectAll().where { matches(entityType, referenceId) }.any()
        if (exists) return
        TseLateSigningTable.insert {
            it[TseLateSigningTable.entityType] = entityType
            it[TseLateSigningTable.referenceId] = referenceId
            it[TseLateSigningTable.queuedAt] = queuedAt
            it[TseLateSigningTable.reasonCode] = reason.name
            it[TseLateSigningTable.reasonText] = reasonText.take(REASON_TEXT_MAX)
        }
    }

    override fun markSigned(
        entityType: TseEntityType,
        referenceId: String,
        signedAt: LocalDateTime,
        tseTransactionId: Long?,
    ) {
        TseLateSigningTable.update({ matches(entityType, referenceId) }) {
            it[TseLateSigningTable.signedAt] = signedAt
            if (tseTransactionId != null) it[TseLateSigningTable.tseTransactionId] = tseTransactionId
        }
    }

    override fun markAbandoned(
        entityType: TseEntityType,
        referenceId: String,
        at: LocalDateTime,
    ) {
        TseLateSigningTable.update({ matches(entityType, referenceId) }) {
            it[TseLateSigningTable.abandonedAt] = at
        }
    }

    override fun notesFor(
        entityType: TseEntityType,
        referenceIds: Collection<String>,
    ): Map<String, TseSigningNoteDto> =
        referenceIds
            .distinct()
            // Bounded IN lists: a month's export can hold tens of thousands of Vorgänge.
            .chunked(IN_LIST_CHUNK)
            .flatMap { chunk ->
                TseLateSigningTable
                    .selectAll()
                    .where { (TseLateSigningTable.entityType eq entityType) and (TseLateSigningTable.referenceId inList chunk) }
                    .map { row -> row[TseLateSigningTable.referenceId] to row.toNote() }
            }.toMap()

    override fun noteFor(
        entityType: TseEntityType,
        referenceId: String,
    ): TseSigningNoteDto? =
        TseLateSigningTable
            .selectAll()
            .where { matches(entityType, referenceId) }
            .singleOrNull()
            ?.toNote()

    private fun ResultRow.toNote(): TseSigningNoteDto {
        val signedAt = this[TseLateSigningTable.signedAt]
        val abandonedAt = this[TseLateSigningTable.abandonedAt]
        return TseSigningNoteDto(
            pending = signedAt == null && abandonedAt == null,
            reasonCode = this[TseLateSigningTable.reasonCode],
            reasonText = this[TseLateSigningTable.reasonText],
            queuedAt = this[TseLateSigningTable.queuedAt].toString(),
            signedAt = signedAt?.toString(),
            abandoned = abandonedAt != null,
        )
    }

    private fun matches(
        entityType: TseEntityType,
        referenceId: String,
    ) = (TseLateSigningTable.entityType eq entityType) and (TseLateSigningTable.referenceId eq referenceId)

    private companion object {
        const val REASON_TEXT_MAX = 500
        const val IN_LIST_CHUNK = 1000
    }
}
