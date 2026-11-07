package org.example.project.features.order.domain

import org.example.project.core.domain.enums.employee.EmployeeRole

/**
 * The „K:" (Kellner) label on a kitchen/bar ticket.
 *
 * An order booked by a login-less order station is attributed to the shared terminal account, so the
 * ticket used to print that account's number — „K: TERMINAL-" once clipped to the half line (QA emu21
 * NOTE-x). The kitchen needs the person serving the table: the table session's waiter, whom the
 * station's rotation picked. Only when no person is attached at all does the station name stand in.
 * Display only: the order's fiscal attribution is untouched.
 */
object KitchenTicketWaiter {
    /** One candidate for the label: an employee number and the role that says whether it is a person. */
    data class Staff(
        val employeeNumber: String,
        val role: EmployeeRole,
    )

    /** Fallback when neither the order nor the session names a person and the station has no name. */
    const val STATION_FALLBACK = "Station"

    fun label(
        orderedBy: Staff,
        sessionWaiter: Staff?,
        stationName: String?,
    ): String =
        listOfNotNull(orderedBy, sessionWaiter)
            .firstOrNull { it.role != EmployeeRole.TERMINAL_DEVICE && it.employeeNumber.isNotBlank() }
            ?.employeeNumber
            ?: stationName?.trim()?.takeIf { it.isNotEmpty() }
            ?: STATION_FALLBACK
}
