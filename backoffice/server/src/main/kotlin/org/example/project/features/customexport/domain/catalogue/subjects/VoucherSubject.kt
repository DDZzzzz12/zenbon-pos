package org.example.project.features.customexport.domain.catalogue.subjects

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customexport.domain.catalogue.ExportSubject
import org.example.project.features.voucher.database.tables.VoucherTable

/**
 * Gutscheine — issued vouchers with what is left on them, which is the list behind a figure an
 * advisor asks for every year: the outstanding voucher liability, money the shop has been paid and
 * still owes in goods.
 *
 * The root is [VoucherTable]. It reaches nothing: its two foreign keys point at `order_item` and
 * `zbon`, neither of which is registered, so the walk stops immediately and the subject offers its
 * own columns alone. The aggregates the registry declares on the table are therefore **not** reachable
 * from here — an aggregate attaches to a to-many *path*, and a root is not one — but they are declared
 * now rather than later so that whichever table gains a foreign key into `voucher` next finds the
 * liability figure already mapped instead of having to rediscover it. `SupplierBankAccountTable` was
 * registered under the same reasoning one slice before its aggregates could be reached.
 *
 * ## The Zeitraum means the issue date
 *
 * [ExportSubject.dateColumn] and the registry's `periodColumn` both point at `created_at`. It is the
 * only date every voucher has: `fully_redeemed_at` and `invalidated_at` stay null until something
 * happens to the row, so scoping by either would silently drop every voucher still in circulation —
 * precisely the population an outstanding-liability figure is about, and a shorter file looks like a
 * shorter file rather than like a filter nobody asked for.
 *
 * ## No shop column
 *
 * `voucher` carries no shop. Stated rather than omitted, for the reason [ProductSubject] gives.
 *
 * ## The capability is [Capability.VOUCHER_REPORT], not [Capability.VOUCHER_READ]
 *
 * `VoucherRules` already draws this line: `VOUCHER_READ` is the till validating one code and reading
 * that voucher's redemption history, while `/statistics`, `/created-by-date` and `/redeemed-by-date`
 * carry `VOUCHER_REPORT` so a terminal login cannot read shop-wide voucher reporting — an explicit
 * behaviour change made against defect D4, with the routes it moved enumerated in
 * `TerminalBundleTrimTest`. A voucher export with a summed remaining value *is* that reporting, in a
 * file; it costs what it costs there.
 */
object VoucherSubject {
    val subject: ExportSubject =
        ExportSubject(
            key = "gutschein",
            label = "Gutscheine",
            root = VoucherTable,
            // No cast, unlike the AP invoice's `issueDate`: `voucher.created_at` is NOT NULL.
            dateColumn = VoucherTable.createdAt,
            shopColumn = null,
        )
}
