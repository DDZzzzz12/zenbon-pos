package org.example.project.core.domain.dto.employee

import kotlinx.serialization.Serializable

/**
 * The management API for permission groups.
 *
 * Capabilities cross the wire as **strings**, the same as on `EmployeeInfo`: a client meeting a
 * server that has added a capability must ignore the unknown name rather than fail to deserialize,
 * and the `permission_group_capability` column deliberately carries no foreign key to the enum for
 * the same reason. The server rejects a name it does not recognise on the way in — silently dropping
 * one would leave an operator believing they granted something they did not.
 */
object PermissionGroupDto {
    /**
     * A group as the console shows it.
     *
     * [isSystem] and [isDeletable] are what the editor greys out. They are reported rather than
     * inferred, because the rule they encode — a seeded group's capability set is immutable and it
     * cannot be deleted — is the server's to decide, and a client that recomputed it would
     * eventually disagree.
     *
     * [memberCount] is why a delete is refused: a group in use cannot simply vanish, because an
     * employee with no group resolves to no capabilities and can reach nothing at all.
     */
    @Serializable
    data class PermissionGroupResponseDto(
        val code: String,
        val name: String,
        val description: String?,
        val isSystem: Boolean,
        val isDeletable: Boolean,
        val capabilities: Set<String>,
        val memberCount: Int,
    )

    /**
     * Create a group.
     *
     * The code is immutable once created: employees point at the group by code, and renaming it
     * would silently unassign every member. Change [name] instead, which is the display label.
     */
    @Serializable
    data class CreateGroupRequestDto(
        val code: String,
        val name: String,
        val description: String? = null,
        val capabilities: Set<String> = emptySet(),
    )

    /**
     * Update a group's label and capability set.
     *
     * A null field is left alone; an empty [capabilities] set is a real instruction to confer
     * nothing, so the two must stay distinguishable — hence nullable rather than defaulted.
     */
    @Serializable
    data class UpdateGroupRequestDto(
        val name: String? = null,
        val description: String? = null,
        val capabilities: Set<String>? = null,
    )

    /** Every capability the server knows, for the editor's picker. */
    @Serializable
    data class CapabilityCatalogDto(
        val capabilities: List<CapabilityInfoDto>,
    )

    /**
     * One capability, and whether a group may be given it.
     *
     * [assignable] is false for `Assignability.RESERVED` — the fiscally critical ones that no group
     * edit may confer. Reported so the editor can grey them out rather than letting an operator pick
     * one and collect a rejection.
     */
    @Serializable
    data class CapabilityInfoDto(
        val name: String,
        val assignable: Boolean,
    )
}
