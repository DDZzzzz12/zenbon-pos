package org.example.project.features.changelog.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.ApiResponseDto
import org.example.project.core.domain.dto.changelog.ChangelogDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.changelog.domain.repository.ChangelogRepository
import org.example.project.features.changelog.domain.service.ChangelogService
import org.example.project.features.changelog.mapper.toResponse
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery

class ChangelogServiceImpl(
    private val repository: ChangelogRepository,
) : ChangelogService {
    override suspend fun list(
        page: Int,
        size: Int,
    ): ChangelogDto.PaginatedChangelogResponseDto {
        if (page < 0) throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "page must be >= 0")
        if (size < 1 || size > 100) throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "size must be in 1..100")

        val itemsPage = dbQuery { repository.list(page, size) }
        val total = dbQuery { repository.count() }
        val totalPages = if (total == 0L) 0 else ((total + size - 1) / size).toInt()
        return ChangelogDto.PaginatedChangelogResponseDto(
            items = itemsPage.map { it.toResponse() },
            pagination =
                ApiResponseDto.PaginationInfo(
                    currentPage = page,
                    pageSize = itemsPage.size,
                    totalItems = total,
                    totalPages = totalPages,
                    hasNext = (page + 1) * size < total,
                    hasPrevious = page > 0,
                ),
        )
    }
}
