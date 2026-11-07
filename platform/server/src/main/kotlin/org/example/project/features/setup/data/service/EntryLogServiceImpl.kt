/**
 * Implementation of EntryLog service layer.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 4.
 */
package org.example.project.features.setup.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.ApiResponseDto
import org.example.project.core.domain.dto.setup.EntryLogDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.domain.models.EntryLog
import org.example.project.features.audit.domain.repository.EntryLogRepository
import org.example.project.features.audit.domain.service.EntryLogService
import org.example.project.features.audit.mapper.toResponseDto
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory
import java.util.Base64
import kotlin.math.ceil

/**
 * Service implementation for EntryLog operations.
 *
 * Handles business logic for querying operation logs, coordinating between
 * the repository layer and API layer.
 */
class EntryLogServiceImpl(
    private val repository: EntryLogRepository,
) : EntryLogService {
    private val logger = LoggerFactory.getLogger(EntryLogServiceImpl::class.java)

    override suspend fun getLogsByEntityId(entityId: String): List<EntryLogDto.EntryLogResponseDto> {
        logger.debug("getLogsByEntityId: entityId={}", entityId)
        return dbQuery {
            repository.getLogsByEntityId(entityId).map { it.toResponseDto() }
        }
    }

    override suspend fun getPaginatedLogs(query: EntryLogDto.EntryLogQueryDto): EntryLogDto.PaginatedEntryLogResponseDto {
        logger.debug(
            "getPaginatedLogs: page={}, pageSize={}, cursor={}",
            query.page,
            query.pageSize,
            query.cursor != null,
        )
        // Decode outside dbQuery — a malformed cursor is a request error, not a DB error.
        val cursor = query.cursor?.let { decodeCursor(it) }
        return dbQuery {
            val (totalItems, rows) = repository.findPaginated(query, cursor?.first, cursor?.second)
            // The repository fetches one look-ahead row beyond the page to detect
            // whether more rows exist; trim it before mapping.
            val hasMore = rows.size > query.pageSize
            val pageRows = if (hasMore) rows.subList(0, query.pageSize) else rows
            val dtos = pageRows.map { it.toResponseDto() }
            // nextCursor is emitted in BOTH modes so offset clients can switch to
            // keyset paging after any page.
            val nextCursor = if (hasMore) encodeCursor(pageRows.last()) else null
            val pagination =
                if (cursor != null) {
                    // Keyset mode: page numbers are meaningless; expose look-ahead-based
                    // hasNext and mark hasPrevious since a cursor implies a prior page.
                    buildPaginationInfo(query.page, query.pageSize, totalItems).copy(
                        hasNext = hasMore,
                        hasPrevious = true,
                    )
                } else {
                    buildPaginationInfo(query.page, query.pageSize, totalItems)
                }
            EntryLogDto.PaginatedEntryLogResponseDto(
                logs = dtos,
                pagination = pagination,
                nextCursor = nextCursor,
            )
        }
    }

    /**
     * Encode a keyset cursor for the given last-row-of-page entity.
     *
     * Format: URL-safe unpadded Base64 of `"<createdAt ISO-8601>|<id>"`. Opaque to
     * clients; both components are needed to continue the `(created_at DESC, id DESC)`
     * keyset on the server.
     */
    private fun encodeCursor(last: EntryLog): String {
        val raw = "${last.createdAt}|${last.id.value}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.encodeToByteArray())
    }

    /**
     * Decode a keyset cursor produced by [encodeCursor].
     *
     * @throws PosException 400 INVALID_REQUEST when the cursor is malformed.
     */
    private fun decodeCursor(cursor: String): Pair<LocalDateTime, Long> =
        try {
            val raw = Base64.getUrlDecoder().decode(cursor).decodeToString()
            val separator = raw.lastIndexOf('|')
            require(separator > 0) { "missing separator" }
            val createdAt = LocalDateTime.parse(raw.substring(0, separator))
            val id = raw.substring(separator + 1).toLong()
            createdAt to id
        } catch (e: Exception) {
            logger.debug("decodeCursor: rejected malformed cursor", e)
            throw PosException(
                PosErrorCode.INVALID_REQUEST,
                HttpStatusCode.BadRequest,
                "Invalid cursor parameter",
            )
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
}
