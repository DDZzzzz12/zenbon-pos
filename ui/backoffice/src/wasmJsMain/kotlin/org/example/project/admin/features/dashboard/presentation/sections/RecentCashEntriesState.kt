package org.example.project.admin.features.dashboard.presentation.sections

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto

data class RecentCashEntriesState(
    val isLoading: Boolean = true,
    val failed: Boolean = false,
    val entries: List<KassenbuchDto.KassenbuchGroupedSummaryDto> = emptyList(),
    /** Cash-book names by id; empty when the book list could not be read (balance lines then go unnamed). */
    val bookNames: Map<Long, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = entries.isEmpty()
}
