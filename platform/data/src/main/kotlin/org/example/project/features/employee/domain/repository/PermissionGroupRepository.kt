package org.example.project.features.employee.domain.repository

import org.example.project.core.domain.enums.auth.Capability
import java.util.UUID

/**
 * Data access for the `permission_group` table and its capability rows.
 *
 * Separate from [EmployeeRepository] because the group is not an employee concern: employees point
 * at a group, and Stage 6's editor manages groups without touching staff at all.
 *
 * Plain data access, per the repository contract — every guardrail lives in the service. A
 * repository that refused an edit would put half the policy here and half there, which is how the
 * two authorities in Stage 5a came to disagree in the first place.
 */
interface PermissionGroupRepository {
    /** Every group, with its capabilities and how many employees point at it. */
    fun listGroups(): List<GroupRecord>

    /** One group by code, or null. */
    fun findGroup(code: String): GroupRecord?

    /** Creates the group and its capability rows. Returns false if the code is already taken. */
    fun createGroup(
        code: String,
        name: String,
        description: String?,
        capabilities: Set<Capability>,
    ): Boolean

    /** Updates the display fields. A null argument leaves that field alone. */
    fun updateGroupMetadata(
        code: String,
        name: String?,
        description: String?,
    ): Boolean

    /**
     * Replaces a group's capability rows wholesale.
     *
     * Removes before inserting, so a capability moved between groups is never briefly held by both —
     * the same ordering `SchemaManager`'s reconciling seed uses.
     */
    fun replaceCapabilities(
        code: String,
        capabilities: Set<Capability>,
    ): Boolean

    /** Deletes the group and its capability rows. Returns false if no such group. */
    fun deleteGroup(code: String): Boolean

    /** How many employees point at this group. A group with members must not be deleted. */
    fun memberCount(code: String): Int

    /**
     * The group code each of [employeeIds] belongs to. Employees in no group are simply absent.
     *
     * Takes a collection so a caller mapping many employees at once (`EmployeeService.getAllEmployees`)
     * pays one lookup rather than an N+1 that grows with the staff table. Note `/api/employees/summary`
     * does NOT go through here — it returns the summary DTO, which carries no group. Reads through the
     * tables rather than
     * `GroupCapabilityResolver`, whose cache miss opens its own transaction — nesting one inside the
     * mapper's would be a nested transaction, which this codebase forbids.
     */
    fun groupCodesOf(employeeIds: Collection<UUID>): Map<UUID, String>

    /**
     * The number of ACTIVE employees whose group confers [capability].
     *
     * The self-lockout guardrail's input: an edit that would take this to zero for `PERMISSION_ADMIN`
     * or `SYSTEM_CONFIG` leaves nobody able to undo it.
     */
    fun activeHolderCount(capability: Capability): Int

    /** One group as the API reports it. */
    data class GroupRecord(
        val code: String,
        val name: String,
        val description: String?,
        val isSystem: Boolean,
        val isDeletable: Boolean,
        val capabilities: Set<Capability>,
        val memberCount: Int,
    )
}
