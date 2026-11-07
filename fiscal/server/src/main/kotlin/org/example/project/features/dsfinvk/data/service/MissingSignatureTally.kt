package org.example.project.features.dsfinvk.data.service

/**
 * How many Vorgänge of each kind would go into a DSFinV-K export without a TSE signature.
 *
 * The pre-export check counted bills only. Over 2026-09-01…2026-09-20 of a restored production
 * archive it reported *"5 transactions are missing TSE signatures"* while `transactions_tse.csv`
 * came out with **15** rows carrying a non-empty `TSE_TA_FEHLER` — 5 bills, 5 Kassenbuch entries,
 * 2 orders and 3 cancellations (E2E-2026-09-20 #11). `DsfinvkExportRoutes` promises the check
 * "reports what would make the export non-compliant", so the operator was told the package was
 * three times cleaner than it was.
 *
 * The breakdown is part of the answer, not decoration: a missing bill signature drains by itself
 * through `TsePendingSweeper`, a missing Kassenbuch signature does not, so the two call for
 * different action.
 */
data class MissingSignatureTally(
    val bills: Int,
    val orders: Int,
    val kassenbuchEntries: Int,
    val cancellations: Int,
) {
    val total: Int get() = bills + orders + kassenbuchEntries + cancellations

    /** The warning line, or null when nothing is missing. */
    fun warning(): String? {
        if (total == 0) return null
        val breakdown =
            buildList {
                if (bills > 0) add("$bills bills")
                if (orders > 0) add("$orders orders")
                if (kassenbuchEntries > 0) add("$kassenbuchEntries cash book entries")
                if (cancellations > 0) add("$cancellations cancellations")
            }.joinToString(", ")
        return "$total transactions are missing TSE signatures ($breakdown)"
    }
}
