package org.example.project.features.bill.domain

import io.ktor.http.HttpStatusCode
import org.example.project.core.auth.BillingAuthority
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.domain.models.Employee
import org.example.project.infrastructure.errors.PosException
import java.util.UUID

/**
 * Refuses billing — creating a bill (whole or partial, cash or card), a Zahlartwechsel, a Storno, or
 * asking for the bill — attributed to the shared station account (`BillingAuthority.mayBillAs`), with
 * 403. The server half of finding OS-b, as the owner decided it (2026-09-28): what matters is WHO the
 * bill is registered under, not the device it is sent from.
 *
 * [attributedRole] is the role of the employee the request is attributed to — the acting employee,
 * the one the token names (`TokenClaims.employeeId`), which is the bill's `employee` for a new bill
 * and the audit row's actor for a Storno or a Zahlartwechsel (those carry over the original bill's
 * employee). A station JWT and a bare station device token both act as the station account, so both
 * are refused; a manager step-up at the station lends capabilities to that account without changing
 * who the request is attributed to, so it is refused too. A person's own login on the station device
 * is that person, and bills.
 */
fun requireMayBillAs(attributedRole: EmployeeRole) {
    if (BillingAuthority.mayBillAs(attributedRole)) return
    throw PosException(
        PosErrorCode.AUTH_FORBIDDEN,
        HttpStatusCode.Forbidden,
        "No billing under the station account — the table is billed by a person",
    )
}

/**
 * As above, for a caller holding only the attributed employee's id (the card-payment route, which
 * must refuse before the card is charged). An id naming no employee is refused too: nothing could be
 * registered under it. Requires an ambient transaction.
 */
fun requireMayBillAs(attributedEmployeeId: String) {
    val employee =
        runCatching { UUID.fromString(attributedEmployeeId) }.getOrNull()?.let { Employee.findById(it) }
            ?: throw PosException(
                PosErrorCode.AUTH_FORBIDDEN,
                HttpStatusCode.Forbidden,
                "No billing under an unknown employee",
            )
    requireMayBillAs(employee.role)
}
