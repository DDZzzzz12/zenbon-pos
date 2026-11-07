package org.example.project.core.domain.dto.changelog

import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.ApiResponseDto
import kotlin.test.Test
import kotlin.test.assertEquals

class ChangelogDtoSerializationTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    @Test
    fun `ResponseDto roundtrips all locale variants`() {
        val dto =
            ChangelogDto.ResponseDto(
                releaseVersion = "1.0.10",
                releaseDate = "2026-05-11T00:00:00Z",
                appliedAt = "2026-05-11T00:00:00Z",
                titleEn = "Pre-1.0.11 changelog backfill",
                titleDe = "Changelog-Backfill vor 1.0.11",
                titleZh = "1.0.11 之前的变更日志回填",
                summaryEn = "Consolidated prior releases.",
                summaryDe = "Zusammengeführt.",
                summaryZh = "已合并。",
                highlightsEn = listOf("v1.0.10 (2026-05-11) — Pos* family consolidation"),
                highlightsDe = listOf("v1.0.10 (2026-05-11) — Pos*-Familie"),
                highlightsZh = listOf("v1.0.10 (2026-05-11) — Pos* 系列整合"),
                knownIssuesEn = emptyList(),
                knownIssuesDe = emptyList(),
                knownIssuesZh = emptyList(),
            )
        val s = json.encodeToString(ChangelogDto.ResponseDto.serializer(), dto)
        val back = json.decodeFromString(ChangelogDto.ResponseDto.serializer(), s)
        assertEquals(dto, back)
    }

    @Test
    fun `PaginatedChangelogResponseDto roundtrips with PaginationInfo`() {
        val dto =
            ChangelogDto.PaginatedChangelogResponseDto(
                items = listOf(),
                pagination =
                    ApiResponseDto.PaginationInfo(
                        currentPage = 0,
                        pageSize = 20,
                        totalItems = 1L,
                        totalPages = 1,
                        hasNext = false,
                        hasPrevious = false,
                    ),
            )
        val s = json.encodeToString(ChangelogDto.PaginatedChangelogResponseDto.serializer(), dto)
        val back = json.decodeFromString(ChangelogDto.PaginatedChangelogResponseDto.serializer(), s)
        assertEquals(dto, back)
    }
}
