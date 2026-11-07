package org.example.project.core.auth

import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.employee.isTerminal

/**
 * Under whom a table may be billed — or its bill asked for, paid, re-tendered or voided.
 *
 * **By attribution, not by device** (owner's decision, 2026-09-28, finding OS-b). What matters is
 * WHO the bill is registered under: the shared station account — the `TERMINAL_DEVICE` employee a
 * station JWT and a bare station device token act as — bills in no way, because there is no person
 * behind it to answer for the till. A real person bills wherever they are logged in, a manager's own
 * login on the order-station device included: that bill is theirs (`bill.employee`, DSFinV-K
 * BEDIENER), signed under the device's terminal.
 *
 * **Not a capability**, for the same reason as `PriceAuthority.mayPriceLinesAs`: the order-station
 * mask already withholds every `BILL_*`, but a step-up is applied AFTER the mask and
 * `ElevationScope.TILL_OVERRIDES` lends `BILL_VOID` and `BILL_CHANGE_PAYMENT`. A step-up lends
 * capabilities to the account that asked for it; it does not change who the request is attributed
 * to, so a station JWT under a manager step-up is still the station account, and still refused.
 *
 * **In `shared/`** so the client hides the controls on the same answer the server refuses on (rule 8).
 * The server passes the role of the employee the request is attributed to, the client the role of its
 * own session (`TokenStore.role`, which a station session sets to `TERMINAL_DEVICE`).
 */
object BillingAuthority {
    /**
     * May a bill be registered — or asked for, re-tendered or voided — under an employee of
     * [attributedRole]? False for the station account ([EmployeeRole.isTerminal]), true for a person.
     */
    fun mayBillAs(attributedRole: EmployeeRole): Boolean = !attributedRole.isTerminal()
}
