package org.example.project.admin.features.permissions.presentation

import org.example.project.core.domain.dto.employee.PermissionGroupDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey

/** Screen state for the Berechtigungen destination. */
sealed interface PermissionGroupsState {
    data object Loading : PermissionGroupsState

    data class Error(
        val error: UiError,
    ) : PermissionGroupsState

    data class Data(
        val groups: List<PermissionGroupDto.PermissionGroupResponseDto>,
        /**
         * Every capability the SERVER knows, with whether a group may be given it.
         *
         * Deliberately not the client's own `Capability` enum: a panel talking to a newer server
         * must offer the capabilities that server actually enforces, and a locally compiled list
         * would quietly omit them — the same reason capabilities cross the wire as strings.
         */
        val catalog: List<PermissionGroupDto.CapabilityInfoDto>,
        val selectedCode: String? = null,
        /** The unsaved edit for [selectedCode]; null once it matches the server again. */
        val draft: GroupDraft? = null,
        val saving: Boolean = false,
        val deleting: Boolean = false,
        /** A non-fatal action error to surface without dropping the list. */
        val actionError: UiError? = null,
        /** Per-field validation refusals of the editor's Save click. Not part of [isDirty]. */
        val draftErrors: Map<GroupEditorField, StringKey> = emptyMap(),
        /** Bumped on every editor Save click the validation refuses; the panel reveals the first error. */
        val validationAttempt: Int = 0,
    ) : PermissionGroupsState {
        val selected: PermissionGroupDto.PermissionGroupResponseDto?
            get() = groups.firstOrNull { it.code == selectedCode }

        /** True when the open draft differs from the server's group; an edit that was undone is clean. */
        val isDirty: Boolean
            get() {
                val current = draft ?: return false
                val server = selected ?: return false
                return current.differsFrom(server)
            }

        /**
         * Capabilities grouped by their name's first segment — ORDER_*, BILL_*, TSE_* and so on.
         *
         * Sorted by sorting the entries, not with `toSortedMap`: `java.util.SortedMap` does not
         * exist on wasm, and `LinkedHashMap` keeps the insertion order this produces.
         */
        val catalogByArea: Map<String, List<PermissionGroupDto.CapabilityInfoDto>>
            get() =
                catalog
                    .groupBy { it.name.substringBefore('_') }
                    .entries
                    .sortedBy { it.key }
                    .associate { it.key to it.value }
    }
}

/**
 * An in-progress edit of one group.
 *
 * Held separately from the loaded group so the panel can show what the operator typed while the
 * list still shows what the server holds — and so cancelling is just dropping this.
 */
data class GroupDraft(
    val code: String,
    val name: String,
    val description: String,
    val capabilities: Set<String>,
) {
    fun differsFrom(server: PermissionGroupDto.PermissionGroupResponseDto): Boolean =
        name.trim() != server.name ||
            description.trim() != (server.description ?: "") ||
            capabilities != server.capabilities

    companion object {
        fun of(group: PermissionGroupDto.PermissionGroupResponseDto) =
            GroupDraft(
                code = group.code,
                name = group.name,
                description = group.description ?: "",
                capabilities = group.capabilities,
            )
    }
}

/** Draft for the create-group dialog; null = closed. */
data class CreateGroupDraft(
    val code: String = "",
    val name: String = "",
    val description: String = "",
    val capabilities: Set<String> = emptySet(),
    val saving: Boolean = false,
    /** A server refusal (duplicate code, reserved capability) — belongs to no single field. */
    val error: UiError? = null,
    /** Per-field validation refusals from the Save click, shown at the field. */
    val fieldErrors: Map<CreateGroupField, StringKey> = emptyMap(),
    /** Bumped on every Save click the validation refuses; the dialog reveals the first error on each bump. */
    val validationAttempt: Int = 0,
) {
    /** True once the operator has typed or ticked anything; saving/error/validation flags are not input. */
    val hasInput: Boolean
        get() = code.isNotEmpty() || name.isNotEmpty() || description.isNotEmpty() || capabilities.isNotEmpty()

    /**
     * Codes are normalised server-side and immutable afterwards — employees point at a group by
     * code, so a rename would silently unassign every member. Previewing the normalised form here
     * stops an operator from believing they created "floor lead" and then not finding it.
     */
    val normalizedCode: String get() = code.trim().uppercase().replace(' ', '_')
}
