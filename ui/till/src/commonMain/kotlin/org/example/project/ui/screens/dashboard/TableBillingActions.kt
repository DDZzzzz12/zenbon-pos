package org.example.project.ui.screens.dashboard

/**
 * Which billing actions the table panel offers: "Rechnung" ([bill]) and "Rechnung anfordern"
 * ([requestBill]).
 *
 * A pure function of its inputs so the station/till split is testable without composition — finding
 * OS-b was exactly this decision going wrong at runtime.
 */
internal data class TableBillingActions(
    val bill: Boolean,
    val requestBill: Boolean,
) {
    companion object {
        private val NONE = TableBillingActions(bill = false, requestBill = false)

        /**
         * @param isStation `TokenStore.isStation` — true only for the order station's own session.
         *   Decides first and alone: nothing is billed under the station account (OS-b), whatever the
         *   shop's cross-employee setting, the table's owner or a step-up says.
         * @param ownsBill whether this bill is the caller's to create ([ownsBill] below).
         * @param canWriteBills holds `BILL_WRITE`.
         * @param canOverrideSession holds `TABLE_SESSION_OVERRIDE` — the manager's ownership escape.
         */
        fun resolve(
            isStation: Boolean,
            ownsBill: Boolean,
            canWriteBills: Boolean,
            canOverrideSession: Boolean,
        ): TableBillingActions {
            if (!billingOffered(isStation)) return NONE
            return TableBillingActions(
                bill = canWriteBills && (ownsBill || canOverrideSession),
                requestBill = !ownsBill,
            )
        }

        /**
         * Whether billing is offered to this session at all — the client half of the server's
         * `BillingAuthority.mayBillAs`. The server decides by attribution: nothing is billed under the
         * station's shared account. On the client that account is exactly the station session —
         * `storeStationSession` is the only writer of the station marker, and `storeTokens` clears it
         * on any person's login — so a person's own login is offered billing on any device, the
         * station's included, and the server decides.
         *
         * Asked through the station marker, not the session's role: UI does not branch on a role
         * (`NoRoleGatesInScreensTest`). `TableBillingActionsTest` pins that the station session's role
         * is one `mayBillAs` refuses, so the two halves cannot drift apart.
         */
        fun billingOffered(isStation: Boolean): Boolean = !isStation

        /**
         * The till's ownership rule, unchanged: the caller's own table, or any table when the shop
         * allows cross-employee billing. Only meaningful where [billingOffered] is true.
         */
        fun ownsBill(
            currentEmployeeNumber: String?,
            tableEmployeeNumber: String?,
            allowCrossEmployeeBilling: Boolean,
        ): Boolean = currentEmployeeNumber != null && (currentEmployeeNumber == tableEmployeeNumber || allowCrossEmployeeBilling)
    }
}
