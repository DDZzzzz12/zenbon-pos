package org.example.project.features.employee.data.service

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.employee.database.tables.EmployeePermissionGroupTable
import org.example.project.features.employee.database.tables.PermissionGroupCapabilityTable
import org.example.project.features.employee.database.tables.PermissionGroupTable
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves an employee to the capability set their permission group confers, cached in memory.
 *
 * A database read on every authenticated request is unacceptable, so the resolved set is cached and
 * invalidated **directly** on any employee mutation — the same shape as
 * [org.example.project.features.terminal.data.service.TerminalCapabilityResolver], including its
 * hard-won rule that **eviction must be unconditional**: a prior version of that resolver gated
 * eviction on which map had been populated, and a revoked station kept resolving its stale
 * capability until process restart.
 *
 * **Fails closed.** An employee with no group resolves to the empty set, never to a role-derived
 * default. Falling back would hand a mis-migrated employee somebody else's access, which is far
 * worse than a visible 403 — and the migration's own assertion already blocks a deploy that would
 * leave an employee ungrouped.
 *
 * Replaces `CapabilityBundles.resolve` as the authority (Stage 2a). The bundles survive as the seed
 * source, the shadow comparator, and the client's role-backed policy until track C2.
 */
class GroupCapabilityResolver {
    private val logger = LoggerFactory.getLogger(GroupCapabilityResolver::class.java)

    /**
     * One employee's resolved assignment.
     *
     * The code is carried alongside the capabilities, in the SAME cache entry, because callers that
     * need to know *which* group answered would otherwise either re-read the database or infer it by
     * matching capability sets — and matching sets is ambiguous the moment two groups happen to
     * confer the same thing.
     */
    data class Assignment(
        val groupCode: String?,
        val capabilities: Set<Capability>,
    )

    private val byEmployee = ConcurrentHashMap<UUID, Assignment>()

    /**
     * Group code -> capabilities, for principals who have no employee row.
     *
     * A login-less station authenticates by device token and belongs to no employee, so it used to
     * resolve through the hardcoded `CapabilityBundles.forRole(TERMINAL_DEVICE)`. That made the
     * database the authority for people and the code the authority for stations — fine while nobody
     * could edit a group, and wrong the moment Stage 5 lets them: an operator narrowing the
     * TERMINAL_DEVICE group would see it silently not apply to any station.
     */
    private val byGroupCode = ConcurrentHashMap<String, Set<Capability>>()

    /** The capabilities [employeeId]'s group confers. Cached; see [invalidate]. */
    suspend fun forEmployee(employeeId: UUID): Set<Capability> = assignmentFor(employeeId).capabilities

    /** The code of the group [employeeId] belongs to, or null if they belong to none. */
    suspend fun groupCodeFor(employeeId: UUID): String? = assignmentFor(employeeId).groupCode

    private suspend fun assignmentFor(employeeId: UUID): Assignment {
        byEmployee[employeeId]?.let { return it }
        val resolved = dbQuery { load(employeeId) }
        byEmployee[employeeId] = resolved
        return resolved
    }

    /**
     * What the group with this code confers, or null if no such group exists.
     *
     * Null means the row is missing, which after seeding and V110-V113 should be impossible — so it
     * is logged at ERROR and the caller fails closed rather than falling back to the compiled
     * bundle. A silent fallback is what let the two authorities diverge in the first place.
     */
    suspend fun forGroupCode(code: String): Set<Capability>? {
        byGroupCode[code]?.let { return it }
        val resolved = dbQuery { loadGroup(code) } ?: return null
        byGroupCode[code] = resolved
        return resolved
    }

    private fun loadGroup(code: String): Set<Capability>? {
        val groupId =
            PermissionGroupTable
                .selectAll()
                .where { PermissionGroupTable.code eq code }
                .firstOrNull()
                ?.get(PermissionGroupTable.id)

        if (groupId == null) {
            logger.error(
                "No permission_group with code '{}'. Seeding did not run, or a group was deleted " +
                    "out from under a principal that depends on it.",
                code,
            )
            return null
        }
        return capabilitiesOf(groupId)
    }

    /** Drop the cached set for one employee. Safe to call for an unknown id. */
    fun invalidate(employeeId: UUID) {
        byEmployee.remove(employeeId)
    }

    /**
     * Drop every cached set — for when a group's own capability set changes.
     *
     * Both maps, unconditionally. A version of this that cleared only [byEmployee] would leave every
     * login-less station resolving the OLD group after an edit, which is the same shape of bug as
     * the conditional eviction that once left revoked stations working until restart.
     */
    fun invalidateAll() {
        byEmployee.clear()
        byGroupCode.clear()
    }

    // Indexed lookups, not `selectAll().filter {}`. The filtered form read every row of all three
    // tables to answer a question about ONE employee — on a 200-staff shop that is 200 assignment
    // rows plus ~330 capability rows per cache miss, and misses recur because `invalidate` runs on
    // every employee update.
    private fun load(employeeId: UUID): Assignment {
        val groupId =
            EmployeePermissionGroupTable
                .selectAll()
                .where { EmployeePermissionGroupTable.employee eq employeeId }
                .firstOrNull()
                ?.get(EmployeePermissionGroupTable.group)

        if (groupId == null) {
            logger.warn(
                "Employee {} belongs to no permission group — resolving to NO capabilities. " +
                    "They can reach nothing until assigned.",
                employeeId,
            )
            return Assignment(groupCode = null, capabilities = emptySet())
        }

        val code =
            PermissionGroupTable
                .selectAll()
                .where { PermissionGroupTable.id eq groupId }
                .firstOrNull()
                ?.get(PermissionGroupTable.code)

        return Assignment(code, capabilitiesOf(groupId))
    }

    private fun capabilitiesOf(groupId: org.jetbrains.exposed.v1.core.dao.id.EntityID<Int>): Set<Capability> =
        PermissionGroupCapabilityTable
            .selectAll()
            .where { PermissionGroupCapabilityTable.group eq groupId }
            .mapNotNull { row ->
                val name = row[PermissionGroupCapabilityTable.capability]
                val capability = Capability.entries.firstOrNull { it.name == name }
                if (capability == null) {
                    // The column deliberately has no FK to the enum, so an unknown value means the
                    // database is ahead of this build. Drop it loudly rather than crash the request.
                    logger.error("Unknown capability '{}' in permission_group_capability", name)
                }
                capability
            }.toSet()
}
