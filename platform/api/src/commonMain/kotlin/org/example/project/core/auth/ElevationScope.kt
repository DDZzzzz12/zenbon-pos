package org.example.project.core.auth

import org.example.project.core.domain.enums.auth.Capability

/**
 * What a step-up may lend.
 *
 * A temporary session used to **replace** the cashier's identity with the approving manager's for
 * three minutes. That was wrong in both directions:
 *
 * - It granted far too much. A PIN entered to void one bill handed over the manager's entire reach
 *   — the back office, the catalog, the cash ledger — until the session expired.
 * - It could also take things *away*. Replacement means the manager's own restrictions applied, so
 *   a step-up at a narrowed station silently removed capabilities the cashier already had. That is
 *   what would have broken step-up billing at an `ORDER_STATION`.
 * - And it lied to the audit trail: every action for those three minutes was attributed to the
 *   manager, not to the cashier who actually performed it with a manager standing beside them.
 *
 * Elevation is now a **union**: the cashier keeps everything they hold and gains what is lent, for
 * the session's lifetime, on their own device, under their own identity.
 *
 * [TILL_OVERRIDES] is the default lend for the generic "manager override" dialog, which is opened
 * from the drawer and does not know which action prompted it. A request that *does* name the
 * capability it needs — the approval-request path knows the operation — is scoped to exactly that.
 *
 * Whatever is asked for, the server intersects it with what the approver actually holds. You cannot
 * lend what you do not have, so a shift-lead group with `ELEVATION_APPROVE` but no `BILL_VOID`
 * cannot conjure one.
 */
object ElevationScope {
    /**
     * The overrides a manager is called to the till for.
     *
     * Deliberately small and deliberately *listed*: an operator asking "what does the manager PIN
     * actually unlock?" should be able to read the answer. Everything here is a till-floor decision
     * a cashier can be blocked on mid-transaction with a customer waiting.
     *
     * Nothing fiscal-irreversible is in it. `ZBON_CLOSE` and `MONTHLY_CLOSE` are RESERVED close
     * operations, not overrides — a day is closed deliberately by the person responsible for it,
     * never by lending three minutes of authority across a counter.
     */
    val TILL_OVERRIDES: Set<Capability> =
        setOf(
            Capability.BILL_CHANGE_PAYMENT,
            Capability.BILL_VOID,
            Capability.DISCOUNT_GRANT,
            Capability.ORDER_ITEM_CANCEL,
            Capability.PRICE_OVERRIDE,
            Capability.TABLE_SESSION_OVERRIDE,
        )

    /**
     * What a manager temporarily logged in on an ORDER STATION may lend it (owner decision
     * 2026-10-03, emu21 NOTE-v): an item Storno and a table/item transfer — nothing else.
     *
     * A station cannot ask for a Storno approval at all (`requirePermissionOrDefer` refuses it with
     * `STATION_STORNO_REQUIRES_MANAGER`); the manager comes to the station, enters number and PIN,
     * and performs the correction there, attributed to the manager. Billing never happens at a
     * station, so `BILL_VOID`, `BILL_CHANGE_PAYMENT`, `PRICE_OVERRIDE` and `DISCOUNT_GRANT` stay out
     * even though they are till overrides. A strict subset of [TILL_OVERRIDES].
     */
    val STATION_OVERRIDES: Set<Capability> =
        setOf(
            Capability.ORDER_ITEM_CANCEL,
            Capability.TABLE_SESSION_OVERRIDE,
        )

    /**
     * Whether [capability] may be lent at all.
     *
     * A narrower question than "does the approver hold it". An ADMIN holds `BACKUP_RESTORE` and
     * `PERMISSION_ADMIN`; neither is something to hand across a counter for three minutes, so
     * naming one in a step-up request is refused outright rather than quietly intersected away.
     */
    fun isLendable(capability: Capability): Boolean = capability in TILL_OVERRIDES

    /**
     * What a step-up actually grants: what was asked for (or the till overrides), narrowed to what
     * the approver holds.
     *
     * Returns an empty set when the approver holds none of it — the caller must treat that as a
     * refusal rather than creating a session that lends nothing, which would look like success and
     * leave the cashier blocked with no explanation.
     *
     * [scope] is [TILL_OVERRIDES] at a till and [STATION_OVERRIDES] on an order station.
     */
    fun resolve(
        requested: Capability?,
        approverHolds: Set<Capability>,
        scope: Set<Capability> = TILL_OVERRIDES,
    ): Set<Capability> {
        val asked = requested?.let { setOf(it) } ?: scope
        return asked.filter { it in scope && it in approverHolds }.toSet()
    }
}
