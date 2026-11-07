/**
 * Implementation of TransactionAuditRepository.
 *
 * Provides database operations for transaction audit logging with
 * comprehensive querying capabilities. Exceptions propagate to service layer.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.audit.data.repository

import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.example.project.features.audit.domain.models.TransactionAuditLog
import org.example.project.features.audit.domain.repository.TransactionAuditRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.ExperimentalTime

/**
 * Transaction audit repository implementation using Exposed ORM.
 *
 * Provides efficient database operations for audit log persistence and querying.
 */
@OptIn(ExperimentalTime::class)
class TransactionAuditRepositoryImpl : TransactionAuditRepository {
    override fun create(
        transactionId: String,
        employeeId: String,
        employeeName: String?,
        employeeNumber: String,
        authorizationType: String,
        authorizingManagerId: String?,
        authorizingManagerName: String?,
        operation: String,
        entityType: String,
        entityId: String,
        entityName: String?,
        beforeState: String?,
        afterState: String?,
        changesSummary: String?,
        deviceId: String?,
        ipAddress: String?,
        reason: String?,
        amountImpact: Double?,
        currencyCode: String,
        metadata: String?,
        timestamp: String,
        fiscalRecordId: String?,
        traceId: String?,
    ): TransactionAuditLog =
        TransactionAuditLog.new {
            this.transactionId = transactionId
            this.employeeId = employeeId
            this.employeeNumber = employeeNumber
            this.authorizationType = authorizationType
            this.authorizingManagerId = authorizingManagerId
            this.authorizingManagerName = authorizingManagerName
            this.operation = operation
            this.entityType = entityType
            this.entityId = entityId
            this.entityName = entityName
            this.beforeState = beforeState
            this.afterState = afterState
            this.changesSummary = changesSummary
            this.deviceId = deviceId
            this.ipAddress = ipAddress
            this.reason = reason
            this.amountImpact = amountImpact?.toBigDecimal()
            this.currencyCode = currencyCode
            this.metadata = metadata
            this.timestamp = timestamp
            this.fiscalRecordId = fiscalRecordId
            this.traceId = traceId
            // Same clock as the `timestamp` the service hands in — see
            // TransactionAuditServiceImpl. A row whose two time columns disagree is harder to
            // spot than one that is wrong in both (E2E-2026-09-22 R3-5).
            this.createdAt = currentTimeStamp().toString()
        }

    override fun findByTransactionId(transactionId: String): TransactionAuditLog? =
        TransactionAuditLog
            .find {
                TransactionAuditLogTable.transactionId eq transactionId
            }.firstOrNull()

    override fun findByQuery(query: TransactionAuditDto.TransactionAuditQueryDto): List<TransactionAuditLog> {
        var queryBuilder = TransactionAuditLogTable.selectAll()

        // Apply filters
        query.employeeId?.let { employeeId ->
            queryBuilder = queryBuilder.andWhere { TransactionAuditLogTable.employeeId eq employeeId }
        }

        query.authorizingManagerId?.let { managerId ->
            queryBuilder = queryBuilder.andWhere { TransactionAuditLogTable.authorizingManagerId eq managerId }
        }

        query.operation?.let { operation ->
            queryBuilder = queryBuilder.andWhere { TransactionAuditLogTable.operation eq operation }
        }

        query.entityType?.let { entityType ->
            queryBuilder = queryBuilder.andWhere { TransactionAuditLogTable.entityType eq entityType }
        }

        query.entityId?.let { entityId ->
            queryBuilder = queryBuilder.andWhere { TransactionAuditLogTable.entityId eq entityId }
        }

        query.fromDate?.let { fromDate ->
            queryBuilder = queryBuilder.andWhere { TransactionAuditLogTable.timestamp greaterEq fromDate }
        }

        query.toDate?.let { toDate ->
            queryBuilder = queryBuilder.andWhere { TransactionAuditLogTable.timestamp lessEq toDate }
        }

        // Order by timestamp descending (most recent first)
        queryBuilder = queryBuilder.orderBy(TransactionAuditLogTable.timestamp, SortOrder.DESC)

        // Apply limit
        queryBuilder = queryBuilder.limit(query.limit)

        // Execute query and map to entities
        return TransactionAuditLog.wrapRows(queryBuilder).toList()
    }

    override fun findByEmployee(
        employeeId: String,
        limit: Int,
    ): List<TransactionAuditLog> =
        TransactionAuditLog
            .find {
                TransactionAuditLogTable.employeeId eq employeeId
            }.orderBy(TransactionAuditLogTable.timestamp to SortOrder.DESC)
            .limit(limit)
            .toList()

    override fun findByEntity(
        entityType: String,
        entityId: String,
    ): List<TransactionAuditLog> =
        TransactionAuditLog
            .find {
                (TransactionAuditLogTable.entityType eq entityType) and
                    (TransactionAuditLogTable.entityId eq entityId)
            }.orderBy(TransactionAuditLogTable.timestamp to SortOrder.DESC)
            .toList()

    override fun findPaginated(query: TransactionAuditDto.PaginatedTransactionAuditQueryDto): Pair<Long, List<TransactionAuditLog>> {
        var queryBuilder = TransactionAuditLogTable.selectAll()

        // Apply filters
        query.employeeId?.let { employeeId ->
            queryBuilder = queryBuilder.andWhere { TransactionAuditLogTable.employeeId eq employeeId }
        }

        query.employeeNumber?.let { empNumber ->
            queryBuilder = queryBuilder.andWhere { TransactionAuditLogTable.employeeNumber eq empNumber }
        }

        query.operation?.let { operation ->
            queryBuilder = queryBuilder.andWhere { TransactionAuditLogTable.operation eq operation }
        }

        query.entityType?.let { entityType ->
            queryBuilder = queryBuilder.andWhere { TransactionAuditLogTable.entityType eq entityType }
        }

        query.entityId?.let { entityId ->
            queryBuilder = queryBuilder.andWhere { TransactionAuditLogTable.entityId eq entityId }
        }

        query.entityName?.let { entityName ->
            queryBuilder = queryBuilder.andWhere { TransactionAuditLogTable.entityName like "%$entityName%" }
        }

        query.fromDate?.let { fromDate ->
            queryBuilder = queryBuilder.andWhere { TransactionAuditLogTable.timestamp greaterEq fromDate }
        }

        query.toDate?.let { toDate ->
            queryBuilder = queryBuilder.andWhere { TransactionAuditLogTable.timestamp lessEq toDate }
        }

        query.search?.takeIf { it.isNotBlank() }?.let { term ->
            val pattern = "%${term.lowercase()}%"
            queryBuilder =
                queryBuilder.andWhere {
                    (TransactionAuditLogTable.entityName.lowerCase() like pattern) or
                        (TransactionAuditLogTable.entityId.lowerCase() like pattern) or
                        (TransactionAuditLogTable.transactionId.lowerCase() like pattern) or
                        (TransactionAuditLogTable.reason.lowerCase() like pattern) or
                        (TransactionAuditLogTable.changesSummary.lowerCase() like pattern) or
                        (TransactionAuditLogTable.employeeNumber.lowerCase() like pattern)
                }
        }

        query.amountMin?.toBigDecimalOrNull()?.let { min ->
            queryBuilder = queryBuilder.andWhere { TransactionAuditLogTable.amountImpact greaterEq min }
        }

        query.amountMax?.toBigDecimalOrNull()?.let { max ->
            queryBuilder = queryBuilder.andWhere { TransactionAuditLogTable.amountImpact lessEq max }
        }

        query.authorizationType?.takeIf { it.isNotBlank() }?.let { authType ->
            queryBuilder = queryBuilder.andWhere { TransactionAuditLogTable.authorizationType eq authType }
        }

        // Count total items
        val totalItems = queryBuilder.count()

        // Apply pagination and ordering
        queryBuilder =
            queryBuilder
                .orderBy(TransactionAuditLogTable.timestamp, SortOrder.DESC)
                .limit(query.pageSize)
                .offset((query.page * query.pageSize).toLong())

        val logs = TransactionAuditLog.wrapRows(queryBuilder).toList()

        return Pair(totalItems, logs)
    }
}
