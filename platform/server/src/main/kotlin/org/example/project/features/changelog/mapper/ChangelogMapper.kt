package org.example.project.features.changelog.mapper

import org.example.project.core.domain.dto.changelog.ChangelogDto
import org.example.project.features.changelog.domain.models.ChangelogRelease

/** Maps a ChangelogRelease entity to the wire DTO. Timestamps are ISO-8601 strings. */
fun ChangelogRelease.toResponse(): ChangelogDto.ResponseDto =
    ChangelogDto.ResponseDto(
        releaseVersion = id.value,
        releaseDate = releaseDate.toString(),
        appliedAt = appliedAt.toString(),
        titleEn = titleEn,
        titleDe = titleDe,
        titleZh = titleZh,
        summaryEn = summaryEn,
        summaryDe = summaryDe,
        summaryZh = summaryZh,
        highlightsEn = highlightsEn,
        highlightsDe = highlightsDe,
        highlightsZh = highlightsZh,
        knownIssuesEn = knownIssuesEn,
        knownIssuesDe = knownIssuesDe,
        knownIssuesZh = knownIssuesZh,
    )
