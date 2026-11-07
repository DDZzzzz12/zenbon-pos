package org.example.project.core.data.domain.service

import org.example.project.core.domain.dto.sync.ChangeFeedDto

/** Collapse multiple changes for the same (entityType, entityId) to the latest by seq, sorted by seq. */
fun dedupeByKey(entries: List<ChangeFeedDto.EntryDto>): List<ChangeFeedDto.EntryDto> =
    entries
        .groupBy { it.entityType to it.entityId }
        .values
        .map { group -> group.maxBy { it.seq } }
        .sortedBy { it.seq }
