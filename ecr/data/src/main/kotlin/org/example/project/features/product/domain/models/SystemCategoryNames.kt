package org.example.project.features.product.domain.models

import org.example.project.core.utils.SystemRecords

/**
 * Names of the product categories the system creates itself. They sit at the very end of the sort
 * order (PFAND at 999.98, VOUCHER at 999.99; the column is `numeric(8,5)`), so they must never feed
 * the "next sort position" of an operator-created category: `max + 1` would overflow the column.
 */
object SystemCategoryNames {
    const val PFAND = SystemRecords.PFAND_CATEGORY_NAME
    const val VOUCHER = SystemRecords.VOUCHER_CATEGORY_NAME

    val ALL: Set<String> = SystemRecords.CATEGORY_NAMES

    /** Highest sort position an automatically placed category may take; stays below the system ones. */
    const val MAX_AUTO_SORT_POSITION = 998
}
