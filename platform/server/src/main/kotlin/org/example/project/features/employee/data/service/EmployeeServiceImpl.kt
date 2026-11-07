/**
 * Implementation of EmployeeService.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 10.
 */
package org.example.project.features.employee.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.employee.EmploymentStatus
import org.example.project.core.domain.enums.employee.isCashier
import org.example.project.core.domain.enums.employee.isManagerOrHigher
import org.example.project.core.domain.enums.employee.isTerminal
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.error.PosErrorCode
import org.example.project.core.websocket.NotificationHub
import org.example.project.features.auth.domain.service.RefreshTokenService
import org.example.project.features.employee.data.service.GroupCapabilityResolver
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.EmployeeHrNames
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.employee.domain.repository.EmployeeRepository
import org.example.project.features.employee.domain.repository.PermissionGroupRepository
import org.example.project.features.employee.domain.service.EmployeeService
import org.example.project.features.employee.domain.service.StaffRosterListener
import org.example.project.features.employee.mapper.toEmployeeResponse
import org.example.project.features.employee.mapper.toInfoDto
import org.example.project.features.employee.mapper.toSummaryResponse
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.hashPassword
import org.example.project.utils.formatter.verifyPassword
import org.example.project.utils.log.AuditEntityRef
import org.example.project.utils.log.EntryLogConfig
import org.example.project.utils.log.EntryLogData
import org.example.project.utils.log.EntryLogHelper
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The permission group that is the shared order-station identity — never a person's.
 *
 * Derived from the role rather than spelled as a literal: `CapabilityBundles` builds the four role
 * groups as `GroupDefinition(role.name, …)`, so this group's code IS the role's name by construction,
 * and renaming the role must not leave the guard below silently matching nothing.
 */
private val TERMINAL_DEVICE_GROUP_CODE = EmployeeRole.TERMINAL_DEVICE.name

/** `entity_type` of the employee audit rows (CREATE/UPDATE/DEACTIVATE_EMPLOYEE, ASSIGN_PERMISSION_GROUP). */
private const val EMPLOYEE_ENTITY_TYPE = "EMPLOYEE"

/**
 * The audit row's `after_state` for a staff change: who, which role and group, and the two switches
 * that change what the account may do. No PIN, password, notes or personal data.
 */
private fun EmployeeDto.EmployeeResponseDto.auditStateJson(): String =
    buildJsonObject {
        put("id", id)
        put("employeeNumber", employeeNumber)
        put("role", role)
        put("permissionGroupCode", permissionGroupCode)
        put("employmentStatus", employmentStatus)
        put("trainingMode", trainingMode)
    }.toString()

/**
 * `after_state` of a deactivation (emu21 NOTE-u): the block returns Unit, whose `toString()` landed
 * in the column as `kotlin.Unit`. The id and the status it now holds are what the act changed.
 */
private fun deactivationStateJson(employeeId: UUID): String =
    buildJsonObject {
        put("id", employeeId.toString())
        put("employmentStatus", EmploymentStatus.INACTIVE.name)
    }.toString()

/** `after_state` of a group assignment — previously the DTO's Kotlin `toString()` (emu21 NOTE-u). */
private fun EmployeeDto.PermissionGroupAssignmentDto.auditStateJson(): String =
    buildJsonObject {
        put("id", employeeId)
        put("permissionGroupCode", groupCode)
    }.toString()

/** Retired spellings [EmployeeRole.getRole] still reads as TERMINAL_DEVICE. */
private val LEGACY_TERMINAL_ROLE_SPELLINGS = setOf("TERMINAL", "BASIC_EMPLOYEE")

/**
 * Concrete implementation of EmployeeService with business logic, validation,
 * password hashing, and authentication.
 */
class EmployeeServiceImpl(
    private val employeeRepository: EmployeeRepository,
    private val refreshTokenService: RefreshTokenService,
    private val groupCapabilityResolver: GroupCapabilityResolver,
    private val permissionGroups: PermissionGroupRepository,
    private val listeners: List<StaffRosterListener>,
) : EmployeeService {
    private val logger = LoggerFactory.getLogger(EmployeeServiceImpl::class.java)

    /** Tells every listener the roster changed; callers invoke it after the commit, holding no lock. */
    private suspend fun notifyRosterChanged() = listeners.forEach { it.onRosterChanged() }

    /**
     * Maps one employee, resolving their permission group.
     *
     * Assumes an ambient transaction — every caller is already inside `dbQuery`. The lookup goes to
     * the repository rather than [GroupCapabilityResolver] because the resolver opens its own
     * transaction on a cache miss, and nesting one here would be a nested transaction.
     */
    private fun Employee.toResponse() = toEmployeeResponse(permissionGroups.groupCodesOf(listOf(id.value))[id.value])

    /** Maps a list, resolving every employee's group in ONE query rather than one per row. */
    private fun List<Employee>.toResponses(): List<EmployeeDto.EmployeeResponseDto> {
        val codes = permissionGroups.groupCodesOf(map { it.id.value })
        return map { it.toEmployeeResponse(codes[it.id.value]) }
    }

    override suspend fun getActiveEmployees(): List<EmployeeDto.EmployeeInfoDto> {
        val connectedEmployeeIds = NotificationHub.getEmployeesByActiveConnections().toSet()
        return dbQuery {
            employeeRepository
                .getAllEmployees(active = true)
                .filter { it.role.isManagerOrHigher() || it.role.isCashier() }
                // The till's colleague picker (table transfer, attribution) names staff by the
                // personnel last name for every authenticated caller: operational, deliberately
                // unchanged by follow-up 7 (an open question to the owner, see the review doc).
                .map { it.toInfoDto(isConnected = connectedEmployeeIds.contains(it.id.value.toString()), hrLastName = true) }
        }
    }

    override suspend fun createEmployee(
        employeeId: String,
        employeeRequest: EmployeeDto.CreateEmployeeRequestDto,
        actorCapabilities: Set<Capability>,
    ): EmployeeDto.EmployeeResponseDto =
        ExceptionConverter.catchingThrows(
            context = "createEmployee(employeeId=$employeeId, role=${employeeRequest.role})",
            employeeId = employeeId,
            // emu20 NOTE-p: staff changes left no audit row, successful or refused. A refused create
            // (e.g. a role conferring capabilities the actor lacks) writes a FAILED row with its reason.
            operation = "CREATE_EMPLOYEE",
            entityType = EMPLOYEE_ENTITY_TYPE,
            auditEntity = { AuditEntityRef(id = it.id, name = it.employeeNumber) },
            auditState = { it.auditStateJson() },
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.EMPLOYEE,
                    logOperation = LogOperation.CREATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.id, dto.username, "Created employee '${dto.username}' (${dto.employeeNumber})")
                    },
                ),
        ) {
            dbQuery {
                try {
                    employeeRequest.validate()
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid request")
                }

                // Check if username already exists
                val existing = employeeRepository.getEmployeeByUsername(employeeRequest.username)
                if (existing != null) {
                    throw usernameTaken(employeeRequest.username)
                }

                // An unrecognized role string would fall back to TERMINAL_DEVICE below and be refused
                // as the station's reserved role — the wrong reason (e2e: role AUDITOR). Name the real
                // rule first: it is not a role at all, and a group code is assigned separately.
                requireKnownRole(employeeRequest.role)

                // Resolved the way the repository will store it: the legacy spellings "TERMINAL" and
                // "BASIC_EMPLOYEE" map onto TERMINAL_DEVICE, so checking the raw string would let them through.
                if (EmployeeRole.getRole(employeeRequest.role).isTerminal()) {
                    throw terminalRoleNotAssignable(employeeRequest.role)
                }
                requireMayConfer(EmployeeRole.getRole(employeeRequest.role).name, actorCapabilities)

                // Auto-generate employee number from role prefix
                val prefix =
                    when (employeeRequest.role.uppercase()) {
                        "TERMINAL_DEVICE" -> "T"
                        "CASHIER" -> "K"
                        "MANAGER" -> "M"
                        "ADMIN" -> "A"
                        else -> "T"
                    }
                val employeeNumber = employeeRepository.getNextEmployeeNumber(prefix)

                val hashedPassword = hashPassword(employeeRequest.password)
                val employeeWithHashedPassword = employeeRequest.copy(password = hashedPassword)
                val employee = employeeRepository.createEmployee(employeeWithHashedPassword, employeeNumber)

                logger.info("createEmployee: created employee id={}", employee.id)
                employee.toResponse()
            }
        }

    override suspend fun getEmployeeById(employeeId: UUID): EmployeeDto.EmployeeResponseDto =
        dbQuery {
            val employee =
                employeeRepository.getEmployeeById(employeeId)
                    ?: throw employeeNotFound(employeeId.toString())
            employee.toResponse()
        }

    override suspend fun getEmployeeByUsername(username: String): EmployeeDto.EmployeeResponseDto? =
        dbQuery {
            employeeRepository.getEmployeeByUsername(username)?.toResponse()
        }

    override suspend fun getEmployeeSummaries(
        requestingEmployeeId: UUID,
        limit: Int,
        offset: Int,
        active: Boolean?,
        actorCapabilities: Set<Capability>,
    ): List<EmployeeDto.EmployeeSummaryResponseDto> {
        if (limit !in 1..1000) throw validationFailed("Limit must be between 1 and 1000")
        if (offset < 0) throw validationFailed("Offset must be non-negative")

        return dbQuery {
            val requestingEmployee =
                employeeRepository.getEmployeeById(requestingEmployeeId)
                    ?: throw employeeNotFound(requestingEmployeeId.toString())

            val allEmployees = employeeRepository.getAllEmployees(limit, offset, active)

            // A HIERARCHY rule, not a capability one: which colleagues you may see depends on where
            // you sit relative to them, and the route registry never sees the target. Reaching this
            // point already required EMPLOYEE_READ.
            //
            // The `else` branch used to return an EMPTY LIST and log that the caller "attempted to
            // access summaries" — accusing someone the gate had just admitted. It was unreachable
            // while every principal was one of the four roles; the purpose-built groups made it
            // reachable and it became a live defect: an AUDITOR holds EMPLOYEE_READ but may carry
            // role CASHIER, so adminApp's Staff screen rendered nothing for them and the server
            // logged a warning about it.
            //
            // They now get the same non-management view a MANAGER gets.
            //
            // An ADMIN sees every row, other admins and the owner included (FO-52): a directory that
            // hid the owner could not even show who holds the shop. This is READ-ONLY by
            // construction — listing a row grants no write; every update / deactivate / role-change /
            // credential-reset path keeps its own guards (self role change, last-active-admin,
            // `requireMayConfer`), none of which read this list.
            //
            // NOT converted to capabilities, deliberately: separating "sees everyone but other
            // admins" from "sees the shop floor" would need a capability that does not exist, and
            // inventing one here would decide a staff-privacy question in a refactor.
            val nonManagement = setOf(EmployeeRole.TERMINAL_DEVICE, EmployeeRole.CASHIER)
            val filteredEmployees =
                when (requestingEmployee.role) {
                    EmployeeRole.ADMIN -> allEmployees
                    else -> allEmployees.filter { it.role in nonManagement }
                }

            // Names are HR data: only a caller who may read the HR record gets them. One query for
            // the whole page, not one lookup per row.
            val names =
                if (EmployeeHrNames.visibleTo(actorCapabilities)) {
                    displayNamesOf(filteredEmployees.map { it.id.value })
                } else {
                    emptyMap()
                }
            filteredEmployees.map { it.toSummaryResponse(displayName = names[it.id.value]) }
        }
    }

    /** HR first + last name per employee id, from ONE query; employees without a record are absent. */
    private fun displayNamesOf(employeeIds: List<UUID>): Map<UUID, String> = EmployeeHrNames.byEmployeeId(employeeIds)

    override suspend fun getAllEmployees(
        limit: Int,
        offset: Int,
        active: Boolean?,
    ): List<EmployeeDto.EmployeeResponseDto> {
        if (limit !in 1..1000) throw validationFailed("Limit must be between 1 and 1000")
        if (offset < 0) throw validationFailed("Offset must be non-negative")
        return dbQuery {
            employeeRepository.getAllEmployees(limit, offset, active).toResponses()
        }
    }

    override suspend fun updateEmployee(
        employeeId: UUID,
        updateData: EmployeeDto.UpdateEmployeeRequestDto,
        userId: String,
        actorCapabilities: Set<Capability>,
    ): EmployeeDto.EmployeeResponseDto {
        // Status and role decide who the rotation may deal tables to. The entry-log config below makes
        // catchingThrows run the block AND the entry-log write in one transaction that commits only when
        // catchingThrows returns, so the publish has to sit outside it: inside, a failing entry-log write
        // would roll the change back after tills were already told about it.
        var rotationInputsChanged = false
        val response =
            ExceptionConverter.catchingThrows(
                // The requested role is in the context (metadata) so a refused role change is legible
                // from the failed row alone; a successful one shows the new role in after_state.
                context = "updateEmployee(employeeId=$employeeId, userId=$userId, role=${updateData.role})",
                employeeId = userId,
                operation = "UPDATE_EMPLOYEE",
                entityType = EMPLOYEE_ENTITY_TYPE,
                failedEntityId = employeeId.toString(),
                auditEntity = { AuditEntityRef(id = it.id, name = it.employeeNumber) },
                auditState = { it.auditStateJson() },
                entryLogConfig =
                    EntryLogConfig(
                        logType = ChangeEntityType.EMPLOYEE,
                        logOperation = LogOperation.UPDATE,
                        extractLogData = { dto ->
                            EntryLogData(dto.id, dto.username, "Updated employee '${dto.username}' (${dto.employeeNumber})")
                        },
                    ),
            ) {
                // Reset at the top of every attempt: dbQuery may retry the block.
                rotationInputsChanged = false
                dbQuery {
                    rotationInputsChanged = false
                    try {
                        updateData.validate()
                    } catch (e: IllegalArgumentException) {
                        throw validationFailed(e.message ?: "Invalid request")
                    }

                    val current =
                        employeeRepository.getEmployeeById(employeeId)
                            ?: throw employeeNotFound(employeeId.toString())

                    requireAdminActorForAdminTarget(current.role, userId)
                    guardRoleChange(employeeId, current, updateData.role, userId)
                    guardRoleEscalation(employeeId, current, updateData.role, actorCapabilities)
                    guardAdministrativeFields(current, updateData, actorCapabilities)

                    val statusBefore = current.employmentStatus
                    val roleBefore = current.role
                    val employee =
                        employeeRepository.updateEmployee(employeeId, updateData)
                            ?: throw employeeNotFound(employeeId.toString())

                    // The role — and therefore the permission group — may have just changed. The cache is
                    // keyed on employee id, so it must be evicted UNCONDITIONALLY: a conditional evict is
                    // what left TerminalCapabilityResolver serving revoked stations until restart.
                    groupCapabilityResolver.invalidate(employeeId)

                    rotationInputsChanged = employee.employmentStatus != statusBefore || employee.role != roleBefore
                    logger.info("updateEmployee: updated employee id={}", employeeId)
                    employee.toResponse()
                }
            }
        // Committed now, holding no lock; only when status or role really moved (a notes edit publishes nothing).
        if (rotationInputsChanged) notifyRosterChanged()
        return response
    }

    override suspend fun changeEmploymentStatus(
        employeeId: UUID,
        newStatus: EmploymentStatus,
        reason: String?,
    ): Boolean {
        // Reset at the top of every attempt: dbQuery may retry the block.
        var statusChanged = false
        val success =
            dbQuery {
                statusChanged = false
                val employee = employeeRepository.getEmployeeById(employeeId) ?: return@dbQuery false

                if (employee.employmentStatus == newStatus) {
                    logger.info("changeEmploymentStatus: employee {} already in status {}", employeeId, newStatus)
                    return@dbQuery true
                }

                // Guard: never let the last active ADMIN drop out of ACTIVE (client mirrors this in adminApp).
                if (employee.role == EmployeeRole.ADMIN &&
                    employee.employmentStatus == EmploymentStatus.ACTIVE &&
                    newStatus != EmploymentStatus.ACTIVE &&
                    countActiveAdmins() <= 1
                ) {
                    throw lastAdminForbidden("Cannot deactivate the last active administrator")
                }

                reason?.let { logger.info("changeEmploymentStatus: reason for {}: {}", employeeId, it) }

                val statusChangeRequest =
                    EmployeeDto.UpdateEmployeeRequestDto(
                        employmentStatus = newStatus.name,
                        notes =
                            reason?.let { existingNotes ->
                                val timestamp = currentTimeStamp()
                                val statusChangeNote = "[$timestamp] Status changed to $newStatus: $existingNotes"
                                if (employee.notes.isNullOrBlank()) {
                                    statusChangeNote
                                } else {
                                    "${employee.notes}\n$statusChangeNote"
                                }
                            } ?: employee.notes,
                    )

                val updated = employeeRepository.updateEmployee(employeeId, statusChangeRequest)
                if (updated != null) {
                    logger.info("changeEmploymentStatus: changed status for {} to {}", employeeId, newStatus)
                    statusChanged = true
                    true
                } else {
                    logger.error("changeEmploymentStatus: failed for {}", employeeId)
                    false
                }
            }
        // Only ACTIVE employees are dealt tables, so a real status move can change who is next. Published
        // after the commit and holding no lock, as listeners require.
        if (statusChanged) notifyRosterChanged()
        return success
    }

    override suspend fun deactivateEmployee(
        employeeId: UUID,
        actorId: String,
    ) {
        ExceptionConverter.catchingThrows(
            context = "deactivateEmployee(employeeId=$employeeId, actorId=$actorId)",
            employeeId = actorId,
            operation = "DEACTIVATE_EMPLOYEE",
            entityType = EMPLOYEE_ENTITY_TYPE,
            failedEntityId = employeeId.toString(),
            // The block returns Unit; the id is the parameter it acted on, known before it ran.
            auditEntity = { AuditEntityRef(id = employeeId.toString(), name = "INACTIVE") },
            auditState = { deactivationStateJson(employeeId) },
        ) {
            dbQuery {
                val target = employeeRepository.getEmployeeById(employeeId)
                if (target != null) requireAdminActorForAdminTarget(target.role, actorId)
            }
            // No reason, so the operator's Notizen stay theirs: a fixed English system line
            // ("[…] Status changed to INACTIVE: Employee deactivated") landed in a German screen
            // (admin20 / admin19 NOTE-m). The DEACTIVATE_EMPLOYEE audit row and the status itself
            // already record the act, who did it and when.
            val success = changeEmploymentStatus(employeeId, EmploymentStatus.INACTIVE, reason = null)
            if (!success) {
                throw employeeNotFound(employeeId.toString())
            }
            refreshTokenService.revokeAllForEmployee(employeeId.toString())
            // Defect D5 is what this closes: before Stage 2a a revocation only took effect at token
            // refresh, because capabilities were baked into the JWT.
            groupCapabilityResolver.invalidate(employeeId)
        }
    }

    override suspend fun deleteEmployee(
        employeeId: UUID,
        userId: String,
    ): String {
        // The entry-log config makes catchingThrows commit only when it returns, so the publish follows it.
        val employeeName = deleteEmployeeAudited(employeeId, userId)
        // Committed: flagged INACTIVE, the employee has left the rotation's candidates.
        notifyRosterChanged()
        return employeeName
    }

    private suspend fun deleteEmployeeAudited(
        employeeId: UUID,
        userId: String,
    ): String =
        ExceptionConverter.catchingThrows(
            context = "deleteEmployee(employeeId=$employeeId, userId=$userId)",
            employeeId = userId,
            // DELETE /api/employees/{id} only flags the employee INACTIVE — the same act as the
            // explicit deactivate route, so it writes the same audit operation.
            operation = "DEACTIVATE_EMPLOYEE",
            entityType = EMPLOYEE_ENTITY_TYPE,
            failedEntityId = employeeId.toString(),
            auditEntity = { AuditEntityRef(id = employeeId.toString(), name = it) },
            auditState = { deactivationStateJson(employeeId) },
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.EMPLOYEE,
                    logOperation = LogOperation.DELETE,
                    extractLogData = { username ->
                        EntryLogData(employeeId.toString(), username, "Deleted employee '$username'")
                    },
                ),
        ) {
            dbQuery {
                val target = Employee.findById(employeeId) ?: throw employeeNotFound(employeeId.toString())
                requireAdminActorForAdminTarget(target.role, userId)
                val employeeName = target.username

                employeeRepository.deleteEmployee(employeeId, userId)
                logger.info("deleteEmployee: deleted employeeId={}", employeeId)
                employeeName
            }
        }

    override suspend fun authenticateEmployee(
        identifier: String,
        secret: String,
    ): EmployeeDto.EmployeeResponseDto? {
        return dbQuery {
            try {
                val employee =
                    Employee.find { EmployeeTable.username eq identifier }.firstOrNull()
                        ?: Employee.find { EmployeeTable.employeeNumber eq identifier }.firstOrNull()
                        ?: return@dbQuery null

                if (employee.employmentStatus != EmploymentStatus.ACTIVE) {
                    logger.warn("authenticateEmployee: inactive employee identifier={}", identifier)
                    return@dbQuery null
                }

                val authenticated =
                    verifyPassword(secret, employee.passwordHash) ||
                        (employee.pin != null && employee.pin == secret)

                if (!authenticated) {
                    logger.warn("authenticateEmployee: failed attempt identifier={}", identifier)
                    return@dbQuery null
                }

                recordLogin(employee.id.value)
                logger.info("authenticateEmployee: authenticated employee={}", employee.username)
                employee.toResponse()
            } catch (e: Exception) {
                logger.error("authenticateEmployee: error for identifier=$identifier", e)
                null
            }
        }
    }

    override suspend fun recordLogin(employeeId: UUID): Boolean =
        dbQuery {
            val loginTime = currentTimeStamp()
            try {
                employeeRepository.updateLastLogin(employeeId, loginTime)
                true
            } catch (e: Exception) {
                logger.error("recordLogin: failed for {}", employeeId, e)
                false
            }
        }

    override suspend fun verifyEmployee(
        employeeId: UUID?,
        username: String?,
        employeeNumber: String?,
    ): Boolean =
        try {
            when {
                employeeId != null -> dbQuery { Employee.findById(employeeId) != null }
                username != null ->
                    dbQuery {
                        Employee.find { EmployeeTable.username eq username }.firstOrNull() != null
                    }
                employeeNumber != null ->
                    dbQuery {
                        Employee.find { EmployeeTable.employeeNumber eq employeeNumber }.firstOrNull() != null
                    }
                else -> false
            }
        } catch (e: Exception) {
            logger.error("verifyEmployee: failed for {}", employeeId, e)
            false
        }

    override suspend fun hasAdminAccount(): Boolean =
        dbQuery {
            countActiveAdmins() > 0
        }

    override suspend fun changePassword(
        employeeId: UUID,
        currentPassword: String,
        newPassword: String,
    ) {
        dbQuery {
            val employee = Employee.findById(employeeId) ?: throw employeeNotFound(employeeId.toString())

            if (!verifyPassword(currentPassword, employee.passwordHash)) {
                throw invalidCredentials("Current password is incorrect")
            }

            employee.passwordHash = hashPassword(newPassword)
            logger.info("changePassword: changed password for employeeId={}", employeeId)
        }
        refreshTokenService.revokeAllForEmployee(employeeId.toString())
        logger.info("changePassword: revoked all sessions for employeeId={}", employeeId)
    }

    override suspend fun updateEmployeeLanguage(
        employeeId: UUID,
        languageCode: String,
    ) {
        if (languageCode.isBlank()) throw validationFailed("Language code cannot be blank")
        dbQuery {
            employeeRepository.getEmployeeById(employeeId)
                ?: throw employeeNotFound(employeeId.toString())

            val req = EmployeeDto.UpdateEmployeeRequestDto(languagePreference = languageCode)
            employeeRepository.updateEmployee(employeeId, req)
                ?: throw employeeNotFound(employeeId.toString())

            logger.info("updateEmployeeLanguage: updated language for employeeId={}", employeeId)
        }
    }

    override suspend fun changeMyPin(
        employeeId: UUID,
        newPin: String,
    ) {
        dbQuery {
            employeeRepository.getEmployeeById(employeeId)
                ?: throw employeeNotFound(employeeId.toString())

            val req = EmployeeDto.UpdateEmployeeRequestDto(pin = newPin)
            employeeRepository.updateEmployee(employeeId, req)
                ?: throw employeeNotFound(employeeId.toString())

            logger.info("changeMyPin: updated PIN for employeeId={}", employeeId)
        }
        refreshTokenService.revokeAllForEmployee(employeeId.toString())
    }

    override suspend fun setRotationPool(
        id: UUID,
        enabled: Boolean,
    ): EmployeeDto.EmployeeResponseDto {
        val response = setRotationPoolInTransaction(id, enabled)
        // Committed: the pool changed, so who is next may have. Never inside the transaction above.
        notifyRosterChanged()
        return response
    }

    private suspend fun setRotationPoolInTransaction(
        id: UUID,
        enabled: Boolean,
    ): EmployeeDto.EmployeeResponseDto =
        dbQuery {
            val employee =
                employeeRepository.getEmployeeById(id)
                    ?: throw employeeNotFound(id.toString())
            // A terminal account (an order station's shared employee) never owns a table session, so
            // it may not be dealt tables (spec 2026-09-14 §4.3). Taking one OUT stays allowed.
            if (enabled && employee.role.isTerminal()) {
                throw validationFailed("Terminal account ${employee.employeeNumber} cannot join the rotation pool")
            }
            val updated =
                employeeRepository.setRotationPool(id, enabled)
                    ?: throw employeeNotFound(id.toString())
            logger.info("setRotationPool: rotation pool for employee {} set to {}", updated.employeeNumber, enabled)
            updated.toResponse()
        }

    /**
     * Assign an employee to an existing permission group.
     *
     * Stage 3. This is the employee→group pointer only; creating or editing a group is the Stage 5
     * API, whose guardrails are different and larger. Each check below has a test.
     */
    override suspend fun assignPermissionGroup(
        employeeId: UUID,
        groupCode: String,
        actorId: UUID,
    ): EmployeeDto.PermissionGroupAssignmentDto =
        ExceptionConverter.catchingThrows(
            context = "assignPermissionGroup(employeeId=$employeeId, groupCode=$groupCode, actorId=$actorId)",
            employeeId = actorId.toString(),
            operation = "ASSIGN_PERMISSION_GROUP",
            entityType = EMPLOYEE_ENTITY_TYPE,
            failedEntityId = employeeId.toString(),
            auditEntity = { AuditEntityRef(id = it.employeeId, name = it.groupCode) },
            auditState = { it.auditStateJson() },
        ) {
            assignPermissionGroupGuarded(employeeId, groupCode, actorId)
        }

    /** [assignPermissionGroup]'s guards and write; split out so the audit wrapper sees every refusal. */
    private suspend fun assignPermissionGroupGuarded(
        employeeId: UUID,
        groupCode: String,
        actorId: UUID,
    ): EmployeeDto.PermissionGroupAssignmentDto {
        // Fail fast, and before touching the database: an actor who demotes themselves cannot undo
        // it, because undoing it needs the PERMISSION_ADMIN they just gave away. Escalation is not
        // the hazard here — only ADMIN can reach this route today, and ADMIN already holds
        // everything — so the lockout is the whole reason this check exists.
        if (employeeId == actorId) {
            throw PosException(
                PosErrorCode.AUTH_FORBIDDEN,
                HttpStatusCode.Forbidden,
                "You cannot change your own permission group",
            )
        }

        // Only an ADMIN may regroup an ADMIN (the staff directory shows admin rows read-only to
        // everyone else). A missing target is left to the 404 further down.
        dbQuery {
            employeeRepository.getEmployeeById(employeeId)?.let { requireAdminActorForAdminTarget(it.role, actorId.toString()) }
        }

        // The shared order-station identity, never a person's. The ROLE half of this pair is guarded
        // in `guardRoleChange`; since Stage 2a the GROUP is what actually confers capabilities, so
        // without this the same identity stayed reachable by the other half — a person holding the
        // station's whole set while their role still read MANAGER.
        //
        // Keyed on the group CODE, deliberately NOT on what it confers: ORDER_ONLY is a strict subset
        // of TERMINAL_DEVICE (CapabilityBundles) and is a *person* group — the one order-taking staff
        // are put in — so a capability-overlap test would refuse exactly the assignment that group
        // exists for. Case-insensitive because the code is matched exactly further down: a lower-case
        // variant would otherwise be rejected as an UNKNOWN group, saying nothing about the reservation.
        if (groupCode.trim().uppercase() == TERMINAL_DEVICE_GROUP_CODE) {
            throw terminalGroupNotAssignable(groupCode)
        }

        // Resolved from the DATABASE, not from `CapabilityBundles`. The rows are what the group
        // actually confers; the compiled definitions are only the seed. Checking the code while
        // writing a pointer to the row means that once a group is editable, the guardrail below
        // validates a set nobody will be granted — and rejects a group an operator legitimately
        // created.
        val conferred =
            groupCapabilityResolver.forGroupCode(groupCode)
                ?: throw PosException(
                    PosErrorCode.VALIDATION_FAILED,
                    HttpStatusCode.BadRequest,
                    "Unknown permission group '$groupCode'",
                )

        // No conferring what you do not hold. Unreachable today for the reason above, and written
        // now anyway: Stage 5 widens who may hold PERMISSION_ADMIN, and a guardrail added after that
        // is a guardrail that was missing in between.
        val actorHolds = groupCapabilityResolver.forEmployee(actorId)
        val wouldConfer = conferred - actorHolds
        if (wouldConfer.isNotEmpty()) {
            throw PosException(
                PosErrorCode.AUTH_FORBIDDEN,
                HttpStatusCode.Forbidden,
                "You cannot grant capabilities you do not hold yourself: " +
                    wouldConfer.sortedBy { it.name }.joinToString { it.name },
            )
        }

        val assigned =
            dbQuery {
                val employee =
                    employeeRepository.getEmployeeById(employeeId)
                        ?: throw employeeNotFound(employeeId.toString())
                val previous = employeeRepository.getPermissionGroupCode(employeeId)

                val group =
                    employeeRepository.findPermissionGroup(groupCode)
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "No permission group row with code '$groupCode'",
                        )

                if (!employeeRepository.setPermissionGroupByCode(employeeId, groupCode)) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        "No permission group row with code '$groupCode'",
                    )
                }

                EntryLogHelper.logOperation(
                    type = ChangeEntityType.EMPLOYEE,
                    operation = LogOperation.UPDATE,
                    entityId = employeeId.toString(),
                    entityName = employee.employeeNumber,
                    message = "Permission group ${previous ?: "(none)"} -> $groupCode, by $actorId",
                    userId = actorId.toString(),
                )
                Triple(employee.employeeNumber, group.first, group.second)
            }
        val employeeName = assigned.first

        // Without this the change takes effect at process restart. That is precisely the defect
        // TerminalCapabilityResolver shipped with, and the reason GroupCapabilityResolver's KDoc
        // insists eviction be unconditional.
        groupCapabilityResolver.invalidate(employeeId)

        logger.info("assignPermissionGroup: employee {} -> group {} by {}", employeeName, groupCode, actorId)

        return EmployeeDto.PermissionGroupAssignmentDto(
            employeeId = employeeId.toString(),
            groupCode = assigned.second,
            groupName = assigned.third,
        )
    }

    override suspend fun resetPassword(
        employeeId: UUID,
        newPassword: String,
    ) {
        dbQuery {
            val employee = Employee.findById(employeeId) ?: throw employeeNotFound(employeeId.toString())
            employee.passwordHash = hashPassword(newPassword)
            logger.info("resetPassword: reset password for employeeId={}", employeeId)
        }
        refreshTokenService.revokeAllForEmployee(employeeId.toString())
        logger.info("resetPassword: revoked all sessions for employeeId={}", employeeId)
    }

    override suspend fun resetPin(
        employeeId: UUID,
        newPin: String,
    ) {
        dbQuery {
            employeeRepository.getEmployeeById(employeeId) ?: throw employeeNotFound(employeeId.toString())
            employeeRepository.updateEmployee(employeeId, EmployeeDto.UpdateEmployeeRequestDto(pin = newPin))
                ?: throw employeeNotFound(employeeId.toString())
            logger.info("resetPin: reset PIN for employeeId={}", employeeId)
        }
        refreshTokenService.revokeAllForEmployee(employeeId.toString())
        logger.info("resetPin: revoked all sessions for employeeId={}", employeeId)
    }

    /**
     * Guards a role change on [current] against the same rules the adminApp enforces client-side:
     * a user may not change their own role, the last active ADMIN may not be demoted, and nobody
     * may be moved onto a terminal role.
     * Must run inside an active transaction (called from within [dbQuery]).
     */
    private fun guardRoleChange(
        employeeId: UUID,
        current: Employee,
        newRoleName: String?,
        userId: String,
    ) {
        val newRole =
            newRoleName?.let {
                try {
                    EmployeeRole.valueOf(it)
                } catch (e: IllegalArgumentException) {
                    throw unknownRole(it)
                }
            } ?: return

        if (newRole == current.role) return

        // A terminal account never owns a table session (spec 2026-09-14 §10.5). Moving a person onto
        // the role would hand every session they own to a terminal account without touching a session
        // row, and drop them out of rotation. TERMINAL_DEVICE belongs to the shared station account,
        // which setup seeds directly; it is never assigned through this API. Moving an existing
        // holder OFF the role stays allowed — upgraded installs carry people who must be moved.
        if (newRole.isTerminal()) {
            throw terminalRoleNotAssignable(newRole.name)
        }

        if (userId == employeeId.toString()) {
            throw selfRoleChangeForbidden()
        }

        if (current.role == EmployeeRole.ADMIN &&
            current.employmentStatus == EmploymentStatus.ACTIVE &&
            newRole != EmployeeRole.ADMIN &&
            countActiveAdmins() <= 1
        ) {
            throw lastAdminForbidden("Cannot demote the last active administrator")
        }
    }

    /**
     * Refuses a role change that would confer more than the actor holds (QA admin19 NOTE-i).
     *
     * A role change moves the employee into the role's permission group
     * (`EmployeeRepositoryImpl.assignPermissionGroup`), so the role field is a back door to the
     * group endpoint's "no conferring what you do not hold" rule: a manager could otherwise make a
     * cashier an ADMIN, and with it hand out `PERMISSION_ADMIN`. Both ends are checked — the group
     * being entered, and the group being left — so a manager can neither promote someone above
     * themselves nor demote someone who outranks them. [guardRoleChange] has already validated the
     * role name and run its own refusals. Must run inside a transaction.
     */
    private fun guardRoleEscalation(
        employeeId: UUID,
        current: Employee,
        newRoleName: String?,
        actorCapabilities: Set<Capability>,
    ) {
        val newRole = newRoleName?.let { EmployeeRole.valueOf(it) } ?: return
        if (newRole == current.role) return
        requireMayConfer(newRole.name, actorCapabilities)
        val currentGroup = permissionGroups.groupCodesOf(listOf(employeeId))[employeeId] ?: current.role.name
        requireMayConfer(currentGroup, actorCapabilities)
    }

    /**
     * Throws 403 when the group [groupCode] confers a capability outside [actorCapabilities]. The
     * group's capabilities are read from the database, like `assignPermissionGroup` does: the rows
     * are what the group actually confers. A missing group is left to the repository, which refuses
     * it with its own message. Must run inside a transaction.
     */
    private fun requireMayConfer(
        groupCode: String,
        actorCapabilities: Set<Capability>,
    ) {
        val conferred = permissionGroups.findGroup(groupCode)?.capabilities ?: return
        val beyond = conferred - actorCapabilities
        if (beyond.isEmpty()) return
        throw PosException(
            PosErrorCode.AUTH_FORBIDDEN,
            HttpStatusCode.Forbidden,
            "Role/group $groupCode confers capabilities you do not hold yourself: " +
                beyond.sortedBy { it.name }.joinToString { it.name },
        )
    }

    /**
     * Which fields of [EmployeeDto.UpdateEmployeeRequestDto] are staff management (`EMPLOYEE_WRITE`)
     * rather than self-service (SET-01 and its follow-up).
     *
     * `PUT /api/employees/{id}` is `CapWithOwnership(SELF_WRITE, EMPLOYEE_WRITE)`, so the plugin lets
     * every employee edit their own row. Of the DTO's fields only two are legitimately a person's own
     * business: `pin` and `languagePreference` (the same two that have dedicated `SELF_WRITE` routes).
     * `role` is refused for oneself by [guardRoleChange]. Everything else is administrative and was
     * self-editable before this check:
     * - `trainingMode` moves every later sale out of Umsatz and the DSFinV-K money files;
     * - `employmentStatus` is the HR state (a person could mark themselves on leave, or terminated);
     * - `notes` are the manager's notes about the employee;
     * - `personnelNumber` and `defaultCostCenterId` feed payroll and cost-centre attribution.
     *
     * Only a real change is refused: a self-edit that echoes the current values back (adminApp's
     * editor sends the whole record) still saves. Must run inside a transaction.
     */
    private fun guardAdministrativeFields(
        current: Employee,
        update: EmployeeDto.UpdateEmployeeRequestDto,
        actorCapabilities: Set<Capability>,
    ) {
        if (Capability.EMPLOYEE_WRITE in actorCapabilities) return
        val changed = changedAdministrativeFields(current, update)
        if (changed.isEmpty()) return
        throw PosException(
            PosErrorCode.AUTH_FORBIDDEN,
            HttpStatusCode.Forbidden,
            "Changing ${changed.joinToString()} requires EMPLOYEE_WRITE, also on one's own record",
        )
    }

    /** The administrative fields [update] would actually change on [current]; null means "not sent". */
    private fun changedAdministrativeFields(
        current: Employee,
        update: EmployeeDto.UpdateEmployeeRequestDto,
    ): List<String> =
        buildList {
            if (update.trainingMode != null && update.trainingMode != current.trainingMode) add("trainingMode")
            if (update.employmentStatus != null && !update.employmentStatus.equals(current.employmentStatus.name, ignoreCase = true)) {
                add("employmentStatus")
            }
            if (update.notes != null && update.notes != current.notes.orEmpty()) add("notes")
            if (update.personnelNumber != null && update.personnelNumber != current.personnelNumber.orEmpty()) add("personnelNumber")
            update.defaultCostCenterId?.let { requested ->
                val currentCostCenter = current.defaultCostCenterId?.value?.toString()
                if (requested.ifBlank { null } != currentCostCenter) add("defaultCostCenterId")
            }
        }

    /**
     * Only an ADMIN may act on an ADMIN record (update, role/group change, deactivation). The
     * credential-reset routes carry the same rule at route level. An unknown actor id is treated as
     * a non-admin. Must run inside an active transaction.
     */
    private fun requireAdminActorForAdminTarget(
        targetRole: EmployeeRole,
        actorId: String,
    ) {
        if (targetRole != EmployeeRole.ADMIN) return
        val actorRole = runCatching { UUID.fromString(actorId) }.getOrNull()?.let { Employee.findById(it)?.role }
        if (actorRole == EmployeeRole.ADMIN) return
        throw PosException(
            PosErrorCode.AUTH_FORBIDDEN,
            HttpStatusCode.Forbidden,
            "Only an admin can change an admin's record",
        )
    }

    /** Number of active administrators. Must run inside an active transaction. */
    private fun countActiveAdmins(): Long =
        Employee
            .find {
                (EmployeeTable.role eq EmployeeRole.ADMIN) and
                    (EmployeeTable.employmentStatus eq EmploymentStatus.ACTIVE)
            }.count()

    private fun selfRoleChangeForbidden() =
        PosException(
            PosErrorCode.SELF_MODIFICATION_FORBIDDEN,
            HttpStatusCode.Conflict,
            "You cannot change your own role",
        )

    private fun lastAdminForbidden(msg: String) =
        PosException(
            PosErrorCode.LAST_ADMIN,
            HttpStatusCode.Conflict,
            msg,
        )

    private fun employeeNotFound(id: String) =
        PosException(
            PosErrorCode.EMPLOYEE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Employee $id not found",
        )

    private fun usernameTaken(username: String) =
        PosException(
            PosErrorCode.EMPLOYEE_USERNAME_TAKEN,
            HttpStatusCode.Conflict,
            "Username '$username' already taken",
        )

    /**
     * Refuses a role string that names no [EmployeeRole] (nor one of its two legacy terminal
     * spellings). Must run inside an active transaction: it looks the string up as a group code.
     */
    private fun requireKnownRole(role: String) {
        val upper = role.trim().uppercase()
        if (upper in LEGACY_TERMINAL_ROLE_SPELLINGS) return
        if (EmployeeRole.entries.any { it.name == upper }) return
        throw unknownRole(role)
    }

    /**
     * The message states the actual rule: roles are the [EmployeeRole] constants, and what an
     * employee may reach comes from their permission group, which has its own endpoint. AUDITOR,
     * ACCOUNTANT and ORDER_ONLY are groups, not roles — naming that is the whole point, because the
     * old path answered "TERMINAL_DEVICE is reserved", which sent the operator looking in the wrong place.
     */
    private fun unknownRole(role: String): PosException {
        val assignable =
            EmployeeRole.entries
                .filterNot { it.isTerminal() }
                .joinToString { it.name }
        val base = "'$role' is not an employee role; assignable roles are $assignable."
        val groupHint =
            if (permissionGroups.findGroup(role.trim().uppercase()) != null) {
                " '$role' is a permission group: create the employee with a role, then assign the group " +
                    "via PUT /api/employees/{id}/permission-group."
            } else {
                ""
            }
        return validationFailed(base + groupHint)
    }

    private fun terminalRoleNotAssignable(role: String) =
        validationFailed("Role '$role' cannot be assigned: TERMINAL_DEVICE is reserved for the shared order-station account")

    private fun terminalGroupNotAssignable(code: String) =
        validationFailed(
            "Permission group '$code' cannot be assigned: TERMINAL_DEVICE is reserved for the shared order-station account",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )

    private fun invalidCredentials(msg: String) =
        PosException(
            PosErrorCode.AUTH_INVALID_CREDENTIALS,
            HttpStatusCode.Unauthorized,
            msg,
        )
}
