package org.example.project.features.employee.domain

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.employee.database.tables.EmployeeInfoTable
import org.example.project.features.employee.domain.models.EmployeeInfo
import org.jetbrains.exposed.v1.core.inList
import java.util.UUID

/**
 * The HR name (first + last name from `employee_info`) of employees, looked up in ONE query.
 *
 * The single lookup behind the staff directory's `displayName` (FO-31) and the statistics
 * operator rollups' `displayName`. Employees without a personnel record, or with a blank one, are
 * absent from the result: the caller decides the fallback (usually the username, see
 * `TerminalEmployee.displayName`).
 *
 * The name is personnel data, so it is shown only to a caller who may read the personnel record:
 * [visibleTo] is that one rule, for every path that hands a name out.
 *
 * Requires an ambient Exposed transaction.
 */
object EmployeeHrNames {
    /**
     * Whether a caller holding [capabilities] may see HR names: `EMPLOYEE_READ`, the capability that
     * reads a colleague's record. Everyone else sees the username.
     */
    fun visibleTo(capabilities: Set<Capability>): Boolean = Capability.EMPLOYEE_READ in capabilities

    fun byEmployeeId(employeeIds: Collection<UUID>): Map<UUID, String> {
        if (employeeIds.isEmpty()) return emptyMap()
        return EmployeeInfo
            .find { EmployeeInfoTable.employeeId inList employeeIds }
            .associate { info ->
                info.readValues[EmployeeInfoTable.employeeId].value to joined(info.firstName, info.lastName)
            }.filterValues { it.isNotBlank() }
    }

    private fun joined(
        firstName: String,
        lastName: String,
    ): String = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
}
