package org.example.project.features.employee.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.employee.EmploymentStatus
import org.example.project.features.employee.database.tables.EmployeePermissionGroupTable
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.database.tables.PermissionGroupTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.employee.domain.repository.EmployeeRepository
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.dao.load
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/**
 * Implementation of EmployeeRepository using Exposed ORM.
 *
 * Provides concrete implementation of employee data access operations
 * using Exposed database framework. Returns plain entities or nullable types;
 * exceptions propagate naturally to the service layer.
 */
class EmployeeRepositoryImpl : EmployeeRepository {
    override fun createEmployee(
        employeeRequest: EmployeeDto.CreateEmployeeRequestDto,
        employeeNumber: String,
    ): Employee {
        // Create the main employee record
        val timestamp = currentTimeStamp()
        val employee =
            Employee.new {
                username = employeeRequest.username
                passwordHash = employeeRequest.password // Note: Should be hashed in service layer
                this.employeeNumber = employeeNumber
                role = EmployeeRole.getRole(employeeRequest.role)
                employmentStatus = EmploymentStatus.valueOf(employeeRequest.employmentStatus)
                pin = employeeRequest.pin
                notes = employeeRequest.notes
                languagePreference = employeeRequest.languagePreference.uppercase()
                trainingMode = employeeRequest.trainingMode
                personnelNumber = employeeRequest.personnelNumber?.trim()?.ifBlank { null }
                createdAt = timestamp
                updatedAt = timestamp
            }

        assignPermissionGroup(employee.id, employee.role)

        // Eagerly load relationships to prevent lazy loading issues
        employee.load(Employee::employeeInfo)

        return employee
    }

    override fun getEmployeeById(employeeId: UUID): Employee? {
        val emp = Employee.findById(employeeId)
        // Eagerly load relationships to prevent lazy loading issues
        emp?.load(Employee::employeeInfo)
        return emp
    }

    override fun getEmployeeByUsername(username: String): Employee? = Employee.find { EmployeeTable.username eq username }.firstOrNull()

    override fun getEmployeeByEmployeeNumber(employeeNumber: String): Employee? =
        Employee
            .find {
                EmployeeTable.employeeNumber eq employeeNumber
            }.singleOrNull()

    override fun getNextEmployeeNumber(prefix: String): String {
        val lastEmployee =
            Employee
                .find { EmployeeTable.employeeNumber like "$prefix%" }
                .orderBy(EmployeeTable.employeeNumber to SortOrder.DESC)
                .limit(1)
                .firstOrNull()

        val nextNumber =
            if (lastEmployee != null) {
                val numPart =
                    lastEmployee.employeeNumber
                        .removePrefix(prefix)
                        .removePrefix("-")
                        .toIntOrNull() ?: 0
                numPart + 1
            } else {
                1
            }

        return "$prefix${nextNumber.toString().padStart(3, '0')}"
    }

    /**
     * The roster read: everyone who works here, paged.
     *
     * The shared login-less `terminal-device` account is excluded. It is a device principal, not a
     * person — empty password hash, one row for however many stations — and it exists only to give an
     * unattended station a real employee id to attribute orders to. Listed on the staff screen it
     * reads as a colleague nobody can place, and it adds one to every headcount taken from this list.
     *
     * Excluded **in the query**, not in a filter over the result: this read is paged, so removing the
     * row afterwards would return 19 rows for a page of 20 and make the caller believe the roster had
     * ended one short.
     *
     * Every install is affected, not only a fresh one — `V79` seeds the same row on an upgrade.
     */
    override fun getAllEmployees(
        limit: Int,
        offset: Int,
        active: Boolean?,
    ): List<Employee> {
        val notADevice = TerminalEmployee.isNotDeviceAccount()
        val query =
            if (active != null) {
                val status = if (active) EmploymentStatus.ACTIVE else EmploymentStatus.INACTIVE
                Employee.find { (EmployeeTable.employmentStatus eq status) and notADevice }
            } else {
                Employee.find { notADevice }
            }

        // Ordered by employee number: without it Postgres returns heap order, which shifts after any
        // UPDATE and makes both the paging and the rotation-pool list jump around.
        return query
            .orderBy(EmployeeTable.employeeNumber to SortOrder.ASC)
            .limit(limit)
            .offset(offset.toLong())
            .toList()
    }

    override fun updateEmployee(
        employeeId: UUID,
        updateData: EmployeeDto.UpdateEmployeeRequestDto,
    ): Employee? {
        val employee = Employee.Companion.findById(employeeId) ?: return null

        val roleBefore = employee.role

        // Update basic employee information
        employee.apply {
            updateData.role?.let { role = EmployeeRole.getRole(it) }
            updateData.pin?.let { pin = it }
            updateData.notes?.let { notes = it.ifBlank { null } }
            updateData.languagePreference?.let { languagePreference = it }
            updateData.employmentStatus?.let { employmentStatus = EmploymentStatus.valueOf(it) }
            updateData.trainingMode?.let { trainingMode = it }
            updateData.personnelNumber?.let { personnelNumber = it.trim().ifBlank { null } }
            updateData.defaultCostCenterId?.let {
                defaultCostCenterId =
                    if (it.isBlank()) {
                        null
                    } else {
                        org.jetbrains.exposed.v1.core.dao.id.EntityID(
                            UUID.fromString(it),
                            org.example.project.features.timetracking.database.tables.CostCenterTable,
                        )
                    }
            }
            updatedAt = currentTimeStamp()
        }

        // Follow a role CHANGE only.
        //
        // This used to run unconditionally, which meant an explicit group assignment (an accountant,
        // an auditor) was silently overwritten by the next unrelated edit — a changed phone number
        // would revoke their access with nothing in the log to explain it. Re-deriving only when the
        // role actually moved lets an explicit assignment survive ordinary edits, while a genuine
        // role change still wins, and the `hasNoGroup` clause still heals an employee who somehow
        // has none (they would otherwise resolve to no capabilities and reach nothing).
        val roleChanged = employee.role != roleBefore
        if (roleChanged || hasNoPermissionGroup(employee.id)) {
            assignPermissionGroup(employee.id, employee.role)
        }

        return employee
    }

    override fun deleteEmployee(
        employeeId: UUID,
        userId: String,
    ) {
        val employee =
            Employee.Companion.findById(employeeId)
                ?: throw NoSuchElementException("Employee not found: $employeeId")

        if (employee.role == EmployeeRole.ADMIN) {
            throw IllegalArgumentException("Cannot delete admin employee")
        }

        employee.employmentStatus = EmploymentStatus.INACTIVE
        employee.updatedAt = currentTimeStamp()
    }

    override fun updateLastLogin(
        employeeId: UUID,
        loginTime: LocalDateTime,
    ) {
        val employee =
            Employee.findById(employeeId)
                ?: throw NoSuchElementException("Employee not found: $employeeId")

        employee.lastLogin = loginTime
        employee.updatedAt = currentTimeStamp()
    }

    override fun setRotationPool(
        id: UUID,
        enabled: Boolean,
    ): Employee? {
        val employee = Employee.findById(id) ?: return null
        employee.inRotationPool = enabled
        return employee
    }

    /**
     * Active employees flagged for round-robin, in employee-number order.
     *
     * A terminal account (an order station's shared `terminal-device`) never owns a table session, so
     * it is never dealt one — whatever its `inRotationPool` flag says (spec 2026-09-14 §4.3).
     */
    override fun getActiveRotationPool(): List<Employee> =
        Employee
            .find {
                (EmployeeTable.inRotationPool eq true) and
                    (EmployeeTable.employmentStatus eq EmploymentStatus.ACTIVE) and
                    (EmployeeTable.role neq EmployeeRole.TERMINAL_DEVICE)
            }.orderBy(EmployeeTable.employeeNumber to SortOrder.ASC)
            .toList()

    /**
     * Put the employee in the permission group matching their role.
     *
     * The GROUP is the authority for capabilities as of Stage 2a; `role` survives for the JWT claim
     * and client display. Both are written here, and `SeededGroupsMatchBundlesTest` plus
     * `BackfillCoverageTest` are what stop them drifting apart.
     *
     * Idempotent: the primary key is the employee alone, so an existing assignment is replaced.
     * An employee with no assignment resolves to NO capabilities and can reach nothing, which is why
     * this runs on create as well as on any role change.
     */
    override fun setPermissionGroupByCode(
        id: UUID,
        groupCode: String,
    ): Boolean {
        val groupId =
            PermissionGroupTable
                .selectAll()
                .firstOrNull { it[PermissionGroupTable.code] == groupCode }
                ?.get(PermissionGroupTable.id)
                ?: return false

        val employeeId =
            org.jetbrains.exposed.v1.core.dao.id
                .EntityID(id, EmployeeTable)

        EmployeePermissionGroupTable.deleteWhere { EmployeePermissionGroupTable.employee eq employeeId }
        EmployeePermissionGroupTable.insert {
            it[EmployeePermissionGroupTable.employee] = employeeId
            it[EmployeePermissionGroupTable.group] = groupId
        }
        return true
    }

    override fun findPermissionGroup(code: String): Pair<String, String>? =
        PermissionGroupTable
            .selectAll()
            .where { PermissionGroupTable.code eq code }
            .firstOrNull()
            ?.let { it[PermissionGroupTable.code] to it[PermissionGroupTable.name] }

    override fun getPermissionGroupCode(id: UUID): String? {
        val groupId =
            EmployeePermissionGroupTable
                .selectAll()
                .firstOrNull { it[EmployeePermissionGroupTable.employee].value == id }
                ?.get(EmployeePermissionGroupTable.group)
                ?: return null

        return PermissionGroupTable
            .selectAll()
            .firstOrNull { it[PermissionGroupTable.id] == groupId }
            ?.get(PermissionGroupTable.code)
    }

    private fun hasNoPermissionGroup(employeeId: org.jetbrains.exposed.v1.core.dao.id.EntityID<UUID>): Boolean =
        EmployeePermissionGroupTable
            .selectAll()
            .where { EmployeePermissionGroupTable.employee eq employeeId }
            .empty()

    private fun assignPermissionGroup(
        employeeId: org.jetbrains.exposed.v1.core.dao.id.EntityID<UUID>,
        role: EmployeeRole,
    ) {
        val groupId =
            PermissionGroupTable
                .selectAll()
                .firstOrNull { it[PermissionGroupTable.code] == role.name }
                ?.get(PermissionGroupTable.id)
                ?: throw IllegalStateException(
                    "No permission_group with code '${role.name}'. Seeding did not run — every " +
                        "employee in this group would silently hold no capabilities.",
                )

        EmployeePermissionGroupTable.deleteWhere { EmployeePermissionGroupTable.employee eq employeeId }
        EmployeePermissionGroupTable.insert {
            it[EmployeePermissionGroupTable.employee] = employeeId
            it[EmployeePermissionGroupTable.group] = groupId
        }
    }
}
