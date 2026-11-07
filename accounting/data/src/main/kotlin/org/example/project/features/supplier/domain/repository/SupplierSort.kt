package org.example.project.features.supplier.domain.repository

/**
 * The columns the creditor list can be ordered by.
 *
 * [OPEN_TOTAL] and [YTD_SPEND] are **aggregates**, not stored columns: the open total is a sum over
 * the supplier's unpaid invoices, the year-to-date spend a sum over the allocations against them.
 * Both are ordered by a correlated subquery so the sort happens *before* paging — sorting the
 * fetched page instead would order one page while claiming to have ordered the list.
 *
 * [YTD_SPEND] additionally needs the year boundary to sum from, which is why
 * [SupplierRepository.list] takes a `ytdSince`.
 */
enum class SupplierSort {
    NAME,
    VAT_ID,
    CREDITOR_NO,
    STATUS,
    OPEN_TOTAL,
    YTD_SPEND,
    ;

    /** Whether this ordering needs `ytdSince`; without it the sum has no lower bound to run from. */
    val needsYtdBoundary: Boolean get() = this == YTD_SPEND

    companion object {
        /**
         * Maps a query parameter onto a column, falling back to [NAME].
         *
         * Never throws: an unknown `sort=` is a client that is ahead or behind this build, and
         * answering it with the default order is more useful than a 400 on a read.
         */
        fun parse(raw: String?): SupplierSort = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: NAME
    }
}
