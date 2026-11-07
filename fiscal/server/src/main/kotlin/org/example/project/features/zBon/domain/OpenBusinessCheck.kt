package org.example.project.features.zBon.domain

import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.kassenbuch.database.tables.CashBookTable
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.CashBook
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.zBon.domain.models.ZBon
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select

/**
 * The single definition of "there is business open since the last Kassenabschluss".
 *
 * Extracted rather than copied. It is consumed by two callers with opposite polarity:
 *
 *  * `ZBonServiceImpl.createReport` refuses to create a closing when there is NOTHING open
 *    (nothing to close), and
 *  * `ShopInfoServiceImpl.updateShopInfo` refuses to change the Stammdaten while there IS
 *    something open — DSFinV-K Tz. 3.2 (p.23): „Werden Änderungen an den … Stammdaten
 *    vorgenommen, ist zuvor automatisch ein Abschluss zu erstellen" (audit KABS-2).
 *
 * Two copies of this predicate would drift, and drift here means the two rules stop describing the
 * same boundary — a window in which a shop edit is accepted for turnover that a later closing then
 * exports under the new identity.
 */
object OpenBusinessCheck {
    /**
     * `true` when at least one bill **or Kassenbuch entry in [scope]** has not yet been assigned
     * to a Kassenabschluss.
     *
     * `scope` is required, with no default — a default would let a future caller silently pick up
     * the wrong scope on a fiscal path. See [CashScope] for what each value means and why both
     * callers need a different one.
     *
     * Requires an ambient Exposed transaction.
     */
    fun hasOpenBusiness(scope: CashScope): Boolean = hasOpenBills() || hasOpenKassenbuchEntries(scope)

    /**
     * The first cash book with an unclosed period, or `null` when everything is closed.
     *
     * Exists so a blocked Stammdaten edit can name WHICH book is blocking, instead of a generic
     * "close your books first" when e.g. the register itself was closed an hour ago and it is the
     * Tresor holding things up.
     *
     * Always scans **all** books, mirroring [CashScope.ALL_BOOKS] — naming the blocking book is
     * only useful to the [CashScope.ALL_BOOKS] caller (`ShopInfoServiceImpl`); the
     * [CashScope.FISCAL_ONLY] caller (`ZBonServiceImpl`) does not call this.
     *
     * Requires an ambient Exposed transaction.
     */
    fun blockingBook(): CashBook? =
        KassenbuchHeader
            .find { KassenbuchHeaderTable.isLocked eq false }
            .limit(1)
            .firstOrNull()
            ?.cashBook

    /**
     * Scoped to bills started after the previous closing's `endTime`, mirroring how a closing
     * collects its bills: a bill left unassigned from BEFORE that boundary is a data defect for
     * the repair endpoint to resolve, not open business that a new closing would sweep up.
     */
    private fun hasOpenBills(): Boolean {
        val previousEnd = ZBon.all().maxByOrNull { it.zbonNummer }?.endTime
        return !Bill
            .find {
                if (previousEnd != null) {
                    (BillTable.zBonId eq null) and (BillTable.startTime greater previousEnd)
                } else {
                    BillTable.zBonId eq null
                }
            }.limit(1)
            .empty()
    }

    /**
     * Round-7 C9. A Kassenbuch Vorgang — Privateinlage, Barausgabe, Geldtransit, Kassendifferenz —
     * is recorded cash movement under the Stammdaten identity in force when it was booked, exactly
     * as a bill is. Reasoning only over bills let a shop that had booked nothing but Kassenbuch
     * entries since its last closing rewrite its fiscal identity with no Kassenabschluss, and that
     * turnover then exported under the new identity (Tz. 3.2, p.23).
     *
     * **`isLocked`, not `zBonId`.** `isLocked` is written unconditionally by the sweep, while
     * `zBon` is written only `if (header.zBon == null)`, so `isLocked` is the stricter and safer
     * of the two; it is also the flag the closing asserts as its own post-condition (it rejects if
     * any unlocked entry survives). Both have exactly ONE writer in main code — the sweep in
     * `ZBonServiceImpl.createReport` — so nothing else can clear this predicate.
     *
     * That post-condition is also why this arm needs **no time window**, unlike the bill arm
     * above: a closing that commits leaves no unlocked entry *visible to its own snapshot*, so an
     * unlocked entry is normally younger than the last closing. The invariant is snapshot-relative,
     * not global — an entry committed inside the closing's REPEATABLE_READ snapshot is invisible to
     * both the sweep and the post-condition and is stranded unlocked (pre-existing race; see the
     * round-7 findings log). A time window would paper over that by ageing the row out of the
     * predicate, which is exactly C9 reopening: it would silently stop counting genuinely unswept
     * cash movement. The fix belongs in `createSignedEntry`'s locking, not here.
     *
     * The bill arm needs its explicit window because an unassigned pre-boundary bill IS reachable
     * there, and counting one as open business would deadlock both callers — permanently blocking
     * shop edits behind a closing that can never sweep it.
     *
     * **The `scope` parameter** (added when cash books stopped being singular). `ALL_BOOKS` keeps
     * exactly the predicate above — every unlocked entry, on any book. `FISCAL_ONLY` narrows to
     * entries whose `cashBookId` is one of the shop's fiscal books, so a Tresor entry (a safe, not
     * an elektronisches Aufzeichnungssystem) no longer satisfies a Z-Bon's "is there anything to
     * close" question — see [CashScope] for why the two callers need different scopes here.
     */
    private fun hasOpenKassenbuchEntries(scope: CashScope): Boolean {
        val predicate: Op<Boolean> =
            when (scope) {
                CashScope.ALL_BOOKS -> KassenbuchHeaderTable.isLocked eq false
                CashScope.FISCAL_ONLY -> {
                    val fiscalBookIds =
                        CashBookTable
                            .select(CashBookTable.id)
                            .where { CashBookTable.isFiscal eq true }
                            .map { it[CashBookTable.id].value }
                    (KassenbuchHeaderTable.isLocked eq false) and
                        (KassenbuchHeaderTable.cashBookId inList fiscalBookIds)
                }
            }
        return !KassenbuchHeader.find { predicate }.limit(1).empty()
    }
}
