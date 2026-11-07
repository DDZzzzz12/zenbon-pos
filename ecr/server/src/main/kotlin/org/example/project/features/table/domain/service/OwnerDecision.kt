package org.example.project.features.table.domain.service

import org.example.project.features.employee.domain.models.Employee

/**
 * Who owns a new table session, and how that was decided.
 *
 * @property employee the session owner.
 * @property rotated true when the round-robin pick ran (a person's open or an order station's) and so moved
 *   the rotation cursor; the caller publishes the new "next" after its transaction commits.
 * @property rotationShifted true when [rotated] and the caller's expected next employee (what the till showed)
 *   is not the one the pick handed the table to. Never a reason to refuse the open.
 */
data class OwnerDecision(
    val employee: Employee,
    val rotated: Boolean,
    val rotationShifted: Boolean,
)
