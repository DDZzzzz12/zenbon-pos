package org.example.project.core.domain.dto.changelog

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.ApiResponseDto

/**
 * Wire DTOs for the `changelog_release` table.
 *
 * Release notes are stored as three language variants per text field (EN/DE/ZH).
 * The client picks the variant by current locale; the server never negotiates
 * Accept-Language. The `str(Key)` localization system is NOT used for the
 * release-note content (too large for the keys system) — only for the screen
 * chrome labels around it.
 *
 * Single source of truth for user-facing release notes per spec
 * `docs/superpowers/specs/2026-08-02-changelog-release-notes-table-design.md`.
 *
 * An element of a `highlights*` / `knownIssues*` list that begins with
 * [ChangelogSection.HEADING_PREFIX] is a section heading, not a bullet — see [ChangelogSection].
 */
object ChangelogDto {
    @Serializable
    data class ResponseDto(
        val releaseVersion: String,
        val releaseDate: String, // ISO-8601 instant string
        val appliedAt: String, // ISO-8601 instant string
        val titleEn: String,
        val titleDe: String,
        val titleZh: String,
        val summaryEn: String,
        val summaryDe: String,
        val summaryZh: String,
        val highlightsEn: List<String>,
        val highlightsDe: List<String>,
        val highlightsZh: List<String>,
        val knownIssuesEn: List<String>,
        val knownIssuesDe: List<String>,
        val knownIssuesZh: List<String>,
    )

    @Serializable
    data class PaginatedChangelogResponseDto(
        val items: List<ResponseDto>,
        val pagination: ApiResponseDto.PaginationInfo,
    )
}
