/**
 * Implementation of TransactionAuditService.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 9.
 */
package org.example.project.features.audit.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.ApiResponseDto
import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.domain.repository.TransactionAuditRepository
import org.example.project.features.audit.domain.service.TransactionAuditService
import org.example.project.features.audit.mapper.toDto
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.math.ceil
import kotlin.time.ExperimentalTime

/**
 * Transaction audit service implementation.
 *
 * @param repository Repository for persisting audit logs
 * @param json JSON serializer for encoding state objects (injected from DI)
 */
@OptIn(ExperimentalTime::class)
class TransactionAuditServiceImpl(
    private val repository: TransactionAuditRepository,
    private val json: Json,
) : TransactionAuditService {
    private val logger = LoggerFactory.getLogger(TransactionAuditServiceImpl::class.java)

    override suspend fun logTransaction(
        employeeId: String,
        employeeName: String?,
        employeeNumber: String,
        authorizationType: String,
        authorizingManagerId: String,
        operation: String,
        entityType: String,
        entityId: String,
        entityName: String?,
        beforeState: Any?,
        afterState: Any?,
        deviceId: String?,
        ipAddress: String?,
        reason: String?,
        amountImpact: Double?,
        fiscalRecordId: String?,
        metadata: Map<String, Any>?,
        traceId: String?,
    ): String {
        logger.info(
            "logTransaction: operation={}, entityType={}, entityId={}, employeeId={}",
            operation,
            entityType,
            entityId,
            employeeId,
        )

        // Generate unique transaction ID
        val transactionId = "TXN-${UUID.randomUUID()}"

        // The shop's wall time, exactly as ExceptionConverter stamps the rows it audits. NOT
        // Clock.System.now(): that is a UTC instant, and this column is compared, ordered and
        // date-filtered as a plain string against rows written in local time, so a UTC row sorted
        // and filtered as if its UTC wall time were local, and the audit-log screen — which only
        // truncates the string — displayed it that way too (E2E-2026-09-22 R3-5).
        val timestamp = currentTimeStamp().toString()

        // Serialize states to JSON
        val beforeStateJson = beforeState?.let { serializeToJson(it) }
        val afterStateJson = afterState?.let { serializeToJson(it) }
        // `json.encodeToString(it)` on a `Map<String, Any>` resolved a serializer for `Any` and threw
        // `SerializationException: Serializer for class 'Any' is not found` — for EVERY non-null
        // metadata map, including an empty one, because resolution happens before encoding. Three
        // call sites across two methods pass metadata, and each wraps this call in a catch-and-log,
        // so the exception was swallowed and the ROW WAS NEVER WRITTEN: `TSE_QUEUE_SIGNED`,
        // `TSE_POSSIBLE_DOUBLE_SIGN` and
        // `TSE_QUEUE_CANCELLED` all silently produced nothing. The M8 row recording that an operator
        // abandoned a Vorgang's Nachsignierung — a permanently unsigned Beleg under §146a AO — was
        // the most consequential casualty. Its own test never saw it: it asserts against a recording
        // fake, which never serializes.
        //
        // Values are stringified rather than the parameter being narrowed to `Map<String, String>`
        // because stringifying is TOTAL: it cannot throw, which is the right shape for a bug whose
        // whole damage came from an exception being swallowed. Narrowing would change implementors
        // rather than callers, and this change already edits the test fakes, so that was not the
        // reason either way.
        val metadataJson = metadata?.let { entries -> json.encodeToString(entries.mapValues { (_, value) -> value.toString() }) }

        // Generate changes summary if both states provided
        val changesSummary = generateChangesSummary(beforeState, afterState, operation, entityName)

        dbQuery {
            repository.create(
                transactionId = transactionId,
                employeeId = employeeId,
                employeeName = employeeName,
                employeeNumber = employeeNumber,
                authorizationType = authorizationType,
                authorizingManagerId = authorizingManagerId,
                operation = operation,
                entityType = entityType,
                entityId = entityId,
                entityName = entityName,
                beforeState = beforeStateJson,
                afterState = afterStateJson,
                changesSummary = changesSummary,
                deviceId = deviceId,
                ipAddress = ipAddress,
                reason = reason,
                amountImpact = amountImpact,
                currencyCode = "EUR",
                metadata = metadataJson,
                timestamp = timestamp,
                fiscalRecordId = fiscalRecordId,
                traceId = traceId,
            )
        }

        logger.info("logTransaction: created transactionId={}", transactionId)
        return transactionId
    }

    override suspend fun getTransactionLogs(query: TransactionAuditDto.TransactionAuditQueryDto): List<TransactionAuditDto.TransactionAuditLogDto> {
        logger.debug("getTransactionLogs: querying transaction logs")
        return dbQuery {
            repository.findByQuery(query).map { it.toDto() }
        }
    }

    override suspend fun getTransactionById(transactionId: String): TransactionAuditDto.TransactionAuditLogDto =
        dbQuery {
            val log =
                repository.findByTransactionId(transactionId)
                    ?: throw transactionLogNotFound(transactionId)
            log.toDto()
        }

    override suspend fun getEmployeeTransactions(
        employeeId: String,
        limit: Int,
    ): List<TransactionAuditDto.TransactionAuditLogDto> {
        logger.debug("getEmployeeTransactions: employeeId={}, limit={}", employeeId, limit)
        return dbQuery {
            repository.findByEmployee(employeeId, limit).map { it.toDto() }
        }
    }

    override suspend fun getEntityTransactions(
        entityType: String,
        entityId: String,
    ): List<TransactionAuditDto.TransactionAuditLogDto> {
        logger.debug("getEntityTransactions: entityType={}, entityId={}", entityType, entityId)
        return dbQuery {
            repository.findByEntity(entityType, entityId).map { it.toDto() }
        }
    }

    override suspend fun getPaginatedTransactionLogs(
        query: TransactionAuditDto.PaginatedTransactionAuditQueryDto,
    ): TransactionAuditDto.PaginatedTransactionAuditResponseDto {
        logger.debug("getPaginatedTransactionLogs: page={}, pageSize={}", query.page, query.pageSize)
        return dbQuery {
            val (totalItems, logs) = repository.findPaginated(query)
            val dtos = logs.map { it.toDto() }
            val pagination = buildPaginationInfo(query.page, query.pageSize, totalItems)
            TransactionAuditDto.PaginatedTransactionAuditResponseDto(
                logs = dtos,
                pagination = pagination,
            )
        }
    }

    /**
     * Build pagination info from page, size, and total items.
     */
    private fun buildPaginationInfo(
        page: Int,
        pageSize: Int,
        totalItems: Long,
    ): ApiResponseDto.PaginationInfo {
        val totalPages = if (totalItems == 0L) 0 else ceil(totalItems.toDouble() / pageSize).toInt()
        return ApiResponseDto.PaginationInfo(
            currentPage = page,
            pageSize = pageSize,
            totalItems = totalItems,
            totalPages = totalPages,
            hasNext = page < totalPages - 1,
            hasPrevious = page > 0,
        )
    }

    /**
     * Serialize any object to JSON string.
     * Handles various types safely.
     */
    private fun serializeToJson(obj: Any): String =
        try {
            when (obj) {
                is String -> obj
                // The same defect the metadata encoding above had, and it NEVER once produced JSON:
                // `obj` smart-casts to `Map<*, *>`, kotlinx refuses star projections in type
                // arguments, and the throw landed in the catch below — so `before_state` and
                // `after_state` have always stored Kotlin map text (`{losingId=42, losingName=Acme}`)
                // rather than JSON. The live caller is `BusinessPartnerServiceImpl`'s merge row,
                // which passes a map for both states. Stringify the entries so a real
                // `Map<String, String>` serializer can be resolved.
                is Map<*, *> -> json.encodeToString(obj.entries.associate { (k, v) -> k.toString() to v.toString() })
                else -> json.encodeToString(obj.toString())
            }
        } catch (e: Exception) {
            // Last resort only. This used to run for EVERY map — a 100% failure rate the wording
            // "if serialization fails" concealed, because the output still looked plausible.
            obj.toString()
        }

    /**
     * Generate human-readable summary of changes.
     */
    private fun generateChangesSummary(
        beforeState: Any?,
        afterState: Any?,
        operation: String,
        entityName: String?,
    ): String? {
        if (beforeState == null && afterState == null) {
            return null
        }

        val entityDesc = entityName?.let { " for '$it'" } ?: ""
        return when {
            beforeState == null && afterState != null -> "Created$entityDesc"
            beforeState != null && afterState == null -> "Deleted$entityDesc"
            else -> "${operation.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }}$entityDesc"
        }
    }

    private fun transactionLogNotFound(id: String) =
        PosException(
            PosErrorCode.TRANSACTION_LOG_NOT_FOUND,
            HttpStatusCode.NotFound,
            "TransactionAuditLog not found: $id",
        )
}
