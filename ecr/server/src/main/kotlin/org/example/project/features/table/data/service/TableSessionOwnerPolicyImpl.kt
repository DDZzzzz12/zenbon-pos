package org.example.project.features.table.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.enums.employee.isTerminal
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.service.RotationAssignmentService
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.service.OwnerDecision
import org.example.project.features.table.domain.service.TableSessionOwnerPolicy
import org.example.project.infrastructure.errors.PosException
import org.jetbrains.exposed.v1.core.eq
import java.util.UUID

class TableSessionOwnerPolicyImpl(
    private val configManager: IShopConfigurationManager,
    private val rotationAssignmentService: RotationAssignmentService,
) : TableSessionOwnerPolicy {
    override suspend fun ownerDecisionForNewSession(
        table: RTable,
        acting: Employee,
        forEmployeeNumber: String?,
        expectedNextEmployeeId: String?,
    ): OwnerDecision {
        // A station that gets past stationOwner's refusals is always owned by the rotation pick. A station
        // ignores the till's expectation (spec §3.5): it never reports a shift.
        if (acting.role.isTerminal()) return rotatedTo(stationOwner(table, forEmployeeNumber), expectedNextEmployeeId = null)
        return when {
            forEmployeeNumber != null -> notRotated(resolveNamedOwner(forEmployeeNumber))
            // Takeaway (e.g. the seeded "Abholung") and virtual (e.g. voucher) tables never draw from
            // the waiter rotation — they fall through to the acting employee. A takeaway location is
            // not virtual, so testing isVirtual alone let every pickup order burn a waiter's turn (T9-18).
            configManager.isRoundRobinAssignment() && !table.isTakeaway && !table.isVirtual ->
                rotatedTo(rotationPick(), expectedNextEmployeeId)
            else -> notRotated(acting)
        }
    }

    private fun notRotated(owner: Employee) = OwnerDecision(owner, rotated = false, rotationShifted = false)

    /** Shifted only when the caller said whom it expected and the pick gave the table to someone else. */
    private fun rotatedTo(
        picked: Employee,
        expectedNextEmployeeId: String?,
    ) = OwnerDecision(
        employee = picked,
        rotated = true,
        rotationShifted =
            expectedNextEmployeeId != null &&
                !picked.id.value
                    .toString()
                    .equals(expectedNextEmployeeId, ignoreCase = true),
    )

    /**
     * A named owner can never be a terminal account — that account is not a real person, and later
     * session creation refuses it with an `IllegalStateException` (500) if it slips through here.
     */
    private fun resolveNamedOwner(forEmployeeNumber: String): Employee {
        val requested =
            Employee.find { EmployeeTable.employeeNumber eq forEmployeeNumber }.firstOrNull()
                ?: throw employeeNotFound(forEmployeeNumber)
        if (requested.role.isTerminal()) throw terminalCannotOwnSession(requested)
        return requested
    }

    override suspend fun ownerDecisionForBookingOpen(
        table: RTable,
        acting: Employee,
    ): OwnerDecision =
        if (acting.role.isTerminal()) {
            rotatedTo(stationOwner(table, forEmployeeNumber = null), expectedNextEmployeeId = null)
        } else {
            notRotated(acting)
        }

    /** Spec §3: rows are checked top to bottom and the first match wins. */
    private suspend fun stationOwner(
        table: RTable,
        forEmployeeNumber: String?,
    ): Employee {
        if (table.isTakeaway || table.isVirtual) {
            throw PosException(
                PosErrorCode.STATION_TABLE_VIRTUAL,
                HttpStatusCode.Conflict,
                "An order station cannot open takeaway or virtual table ${table.tableNumber}",
            )
        }
        if (forEmployeeNumber != null) {
            throw requiresRotation("An order station cannot choose who owns table ${table.tableNumber}")
        }
        if (!configManager.isRoundRobinAssignment()) {
            throw requiresRotation("An order station opens free table ${table.tableNumber} only with round-robin assignment on")
        }
        return rotationPick()
    }

    private suspend fun rotationPick(): Employee {
        val picked = rotationAssignmentService.pickNextEmployee()
        return Employee.findById(UUID.fromString(picked.id)) ?: throw employeeNotFound(picked.id)
    }

    private fun requiresRotation(msg: String) =
        PosException(
            PosErrorCode.STATION_TABLE_REQUIRES_ROTATION,
            HttpStatusCode.Conflict,
            msg,
        )

    private fun employeeNotFound(idOrNumber: String) =
        PosException(
            PosErrorCode.EMPLOYEE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Employee '$idOrNumber' not found",
        )

    private fun terminalCannotOwnSession(employee: Employee) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            "Terminal account ${employee.employeeNumber} cannot own a table session",
        )
}
