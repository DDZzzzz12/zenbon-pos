package org.example.project.ui.screens.dashboard

/**
 * Which item corrections the table panel offers — owner decision 2026-10-03 (emu21 NOTE-v).
 *
 * „Station should not be able to request approval for Storno, but when a manager is temporary
 * logged in, the manager could approve a Storno or transfer."
 *
 * - **Storno** on an order station is offered only while the session holds `ORDER_ITEM_CANCEL`,
 *   i.e. while a manager is temporarily signed in there (the manager sign-in lends it). Without one
 *   the server refuses the cancel with `STATION_STORNO_REQUIRES_MANAGER` instead of queueing an
 *   approval request that, with no manager online, only expired. At a till nothing changes: a
 *   cashier without the capability still gets the approval request.
 * - **Transfer** stays offered: a station's transfer still raises an approval request, and under a
 *   manager sign-in it executes at once.
 *
 * The server half is `requirePermissionOrDefer`; this is the client half of the same guard.
 */
object StationCorrectionActions {
    /**
     * @param isStation `TokenStore.isStation` — true only for the order station's own session.
     * @param holdsItemCancel whether the session holds `ORDER_ITEM_CANCEL` right now (own or lent).
     */
    fun offersStorno(
        isStation: Boolean,
        holdsItemCancel: Boolean,
    ): Boolean = !isStation || holdsItemCancel
}
