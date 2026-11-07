package org.example.project.features.audit.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.setup.EntryLogDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.features.audit.database.tables.EntryLogTable
import org.example.project.features.audit.domain.models.EntryLog
import org.example.project.features.audit.domain.repository.EntryLogRepository
import org.example.project.features.employee.database.tables.EmployeeTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed ORM implementation of EntryLogRepository.
 *
 * Handles all database operations for EntryLog entities.
 * Exceptions propagate to the service layer.
 */
class EntryLogRepositoryImpl : EntryLogRepository {
    override fun getLogsByEntityId(entityId: String): List<EntryLog> =
        EntryLog.Companion
            .find {
                EntryLogTable.entityId eq entityId
            }.orderBy(EntryLogTable.createdAt to SortOrder.ASC)
            .toList()

    override fun findPaginated(
        query: EntryLogDto.EntryLogQueryDto,
        cursorCreatedAt: LocalDateTime?,
        cursorId: Long?,
    ): Pair<Long, List<EntryLog>> {
        // Build dynamic query with filters
        var queryBuilder = EntryLogTable.selectAll()

        query.type?.let { type ->
            val logType = ChangeEntityType.valueOf(type)
            queryBuilder = queryBuilder.andWhere { EntryLogTable.type eq logType }
        }

        query.operation?.let { operation ->
            val logOp = LogOperation.valueOf(operation)
            queryBuilder = queryBuilder.andWhere { EntryLogTable.operation eq logOp }
        }

        query.entityName?.let { name ->
            queryBuilder = queryBuilder.andWhere { EntryLogTable.entityName like "%$name%" }
        }

        query.entityId?.let { id ->
            queryBuilder = queryBuilder.andWhere { EntryLogTable.entityId eq id }
        }

        // Filter by employee number via join
        query.employeeNumber?.let { empNumber ->
            queryBuilder =
                queryBuilder.andWhere {
                    EntryLogTable.userId.inSubQuery(
                        EmployeeTable.select(EmployeeTable.id).where { EmployeeTable.employeeNumber eq empNumber },
                    )
                }
        }

        query.fromDate?.let { fromDate ->
            val from = LocalDateTime.parse(fromDate)
            queryBuilder = queryBuilder.andWhere { EntryLogTable.createdAt greaterEq from }
        }

        query.toDate?.let { toDate ->
            val to = LocalDateTime.parse(toDate)
            queryBuilder = queryBuilder.andWhere { EntryLogTable.createdAt lessEq to }
        }

        query.search?.takeIf { it.isNotBlank() }?.let { term ->
            val pattern = "%${term.lowercase()}%"
            queryBuilder =
                queryBuilder.andWhere {
                    (EntryLogTable.entityName.lowerCase() like pattern) or
                        (EntryLogTable.entityId.lowerCase() like pattern) or
                        (EntryLogTable.message.lowerCase() like pattern)
                }
        }

        // Count total items matching the filters. Deliberately computed BEFORE the
        // keyset predicate is applied so the total stays stable across cursor pages.
        val totalItems = queryBuilder.count()

        // Keyset (cursor) mode: constant-cost deep paging. Replicates the
        // (created_at DESC, id DESC) sort as a compound "strictly before the last
        // seen row" predicate: rows with an older created_at, or the same
        // created_at and a smaller id.
        if (cursorCreatedAt != null && cursorId != null) {
            queryBuilder =
                queryBuilder.andWhere {
                    (EntryLogTable.createdAt less cursorCreatedAt) or
                        ((EntryLogTable.createdAt eq cursorCreatedAt) and (EntryLogTable.id less cursorId))
                }
        }

        // Ordering: id DESC tiebreaker keeps the sort total (created_at has second
        // precision — bursts of writes share a timestamp), which keyset paging needs
        // for gap/duplicate-free pages. Fetch one look-ahead row beyond the page so
        // the caller can detect whether a next page exists (caller trims it).
        queryBuilder =
            queryBuilder
                .orderBy(EntryLogTable.createdAt to SortOrder.DESC, EntryLogTable.id to SortOrder.DESC)
                .limit(query.pageSize + 1)

        // Offset mode only — a cursor already positions the window.
        if (cursorCreatedAt == null || cursorId == null) {
            queryBuilder = queryBuilder.offset((query.page * query.pageSize).toLong())
        }

        val logs = EntryLog.wrapRows(queryBuilder).toList()

        return Pair(totalItems, logs)
    }

    override fun getChangesSince(
        sinceSeq: Long,
        limit: Int,
    ): List<EntryLog> {
        val syncedTypes = ChangeEntityType.entries.filter { it.clientSynced }
        return EntryLog.Companion
            .find {
                (EntryLogTable.id greater sinceSeq) and
                    (EntryLogTable.type inList syncedTypes) and
                    (EntryLogTable.operation neq LogOperation.ERROR)
            }.orderBy(EntryLogTable.id to SortOrder.ASC)
            .limit(limit.coerceAtLeast(0))
            .toList()
    }

    override fun minSeq(): Long? {
        val minExpr = EntryLogTable.id.min()
        return EntryLogTable
            .select(minExpr)
            .firstOrNull()
            ?.get(minExpr)
            ?.value
    }

    override fun maxSeq(): Long? {
        val maxExpr = EntryLogTable.id.max()
        return EntryLogTable
            .select(maxExpr)
            .firstOrNull()
            ?.get(maxExpr)
            ?.value
    }

    override fun maxClientSyncedSeq(): Long? {
        val syncedTypes = ChangeEntityType.entries.filter { it.clientSynced }
        val maxExpr = EntryLogTable.id.max()
        return EntryLogTable
            .select(maxExpr)
            .where {
                (EntryLogTable.type inList syncedTypes) and
                    (EntryLogTable.operation neq LogOperation.ERROR)
            }.firstOrNull()
            ?.get(maxExpr)
            ?.value
    }

    override fun deleteOlderThan(cutoff: LocalDateTime): Int = EntryLogTable.deleteWhere { EntryLogTable.createdAt less cutoff }
}
