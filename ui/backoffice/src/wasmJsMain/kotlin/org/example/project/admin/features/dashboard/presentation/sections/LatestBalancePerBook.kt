package org.example.project.admin.features.dashboard.presentation.sections

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto

/**
 * The newest entry of each cash book (highest `headerId`), ordered by book id. A book's
 * `currentBalance` is only meaningful within that book, so balances of different books are never
 * collapsed into one line.
 */
internal fun latestEntryPerBook(entries: List<KassenbuchDto.KassenbuchGroupedSummaryDto>): List<KassenbuchDto.KassenbuchGroupedSummaryDto> =
    entries
        .groupBy { it.cashBookId }
        .map { (_, perBook) -> perBook.maxBy { it.headerId } }
        .sortedBy { it.cashBookId }
