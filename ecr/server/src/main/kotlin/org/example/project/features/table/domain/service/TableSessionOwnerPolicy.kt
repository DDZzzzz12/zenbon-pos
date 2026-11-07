package org.example.project.features.table.domain.service

import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.table.domain.models.RTable

/**
 * Decides who owns a NEW table session. Never consulted while the table has an active session — an
 * active session is always reused and its owner never changes.
 *
 * Both methods run inside the caller's transaction, after the caller has locked the table row, and may
 * call `RotationAssignmentService.pickNextEmployee`, whose `dbQuery` joins that transaction.
 *
 * For a terminal-role acting employee (an order station) both methods apply one rule, first match
 * wins: a takeaway or virtual table → 409 STATION_TABLE_VIRTUAL; an owner named by the caller → 409
 * STATION_TABLE_REQUIRES_ROTATION; round-robin on → the rotation pick (empty pool → 409
 * ROTATION_POOL_EMPTY); round-robin off → 409 STATION_TABLE_REQUIRES_ROTATION.
 *
 * Spec: docs/superpowers/specs/2026-09-14-station-table-session-ownership-design.md §3, §4.1.
 */
interface TableSessionOwnerPolicy {
    /**
     * Owner for a table's opening: the first order on a free table (`POST /api/orders` with `tableId`)
     * and the legacy `GET /api/tables/{id}/currentSession` of a paired terminal. A person resolves as it
     * always has: [forEmployeeNumber], else the rotation pick when round-robin is on and the table is
     * not virtual, else [acting]. A [forEmployeeNumber] naming a terminal-role employee is refused with
     * 400 VALIDATION_FAILED — a terminal account can never own a table session.
     *
     * Same rule as [ownerDecisionForNewSession] without an expectation; returns only the owner.
     */
    suspend fun ownerForNewSession(
        table: RTable,
        acting: Employee,
        forEmployeeNumber: String?,
    ): Employee = ownerDecisionForNewSession(table, acting, forEmployeeNumber, expectedNextEmployeeId = null).employee

    /**
     * The owner of a table's opening (same rule as [ownerForNewSession]) plus whether the rotation pick ran
     * ([OwnerDecision.rotated]) and whether it handed the table to someone other than
     * [expectedNextEmployeeId], the next employee the till showed ([OwnerDecision.rotationShifted]). A
     * mismatch never refuses the open. Runs in the caller's transaction, so a retried transaction decides
     * afresh and a rolled-back one rolls the pick back with it.
     */
    suspend fun ownerDecisionForNewSession(
        table: RTable,
        acting: Employee,
        forEmployeeNumber: String?,
        expectedNextEmployeeId: String?,
    ): OwnerDecision

    /**
     * Owner for a session opened as a side effect of an item transfer. A person always owns it
     * themselves: rotation for persons on this path is out of scope (spec §8). Orders no longer come
     * here — the first order on a free table is the table's opening and uses [ownerForNewSession].
     */
    suspend fun ownerForBookingOpen(
        table: RTable,
        acting: Employee,
    ): Employee = ownerDecisionForBookingOpen(table, acting).employee

    /**
     * [ownerForBookingOpen] plus whether the rotation pick ran ([OwnerDecision.rotated]), so the caller can
     * publish the new "next" after its transaction commits. A station's open is always rotated and never
     * shifted; a person's is never rotated.
     */
    suspend fun ownerDecisionForBookingOpen(
        table: RTable,
        acting: Employee,
    ): OwnerDecision
}
