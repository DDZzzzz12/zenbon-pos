package org.example.project.admin.features.permissions.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.PermissionGroupApiClient
import org.example.project.core.domain.dto.employee.PermissionGroupDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

/**
 * The permission-group editor.
 *
 * Every guardrail here is the client half of one the server enforces (CLAUDE.md rule 8) — a system
 * group's capabilities are not editable, a group with members is not deletable, a reserved
 * capability is not selectable. Hiding the control is a courtesy; the server refuses regardless, and
 * this ViewModel never assumes its own check was the one that mattered.
 */
class PermissionGroupsViewModel(
    private val api: PermissionGroupApiClient,
) : ViewModel() {
    private val log = Logger.withTag("permission-groups")

    var state by mutableStateOf<PermissionGroupsState>(PermissionGroupsState.Loading)
        private set

    /** Transient create-dialog draft; null = closed. */
    var createDraft by mutableStateOf<CreateGroupDraft?>(null)
        private set

    /**
     * Phone drill: whether the editor (not the group list) is showing. Only the drill reads it; side
     * by side both panes show regardless. Separate from `selectedCode`, which is never null once any
     * group exists, so it cannot say "the operator has not opened one yet".
     */
    var detailOpen by mutableStateOf(false)
        private set

    private inline fun withData(block: (PermissionGroupsState.Data) -> PermissionGroupsState.Data) {
        (state as? PermissionGroupsState.Data)?.let { state = block(it) }
    }

    fun clearError() {
        if (state is PermissionGroupsState.Error) state = PermissionGroupsState.Loading
    }

    fun clearActionError() = withData { it.copy(actionError = null) }

    fun refresh() {
        // Read the selection BEFORE dropping to Loading — reading it afterwards would always find
        // Loading and silently reset the panel to the first group on every reload.
        val keep = (state as? PermissionGroupsState.Data)?.selectedCode
        state = PermissionGroupsState.Loading
        viewModelScope.launch {
            // Wrapped in coroutineScope so a failing child is caught here rather than escaping to
            // viewModelScope — the wasm gotcha that has bitten parallel loads in this app before.
            runCatchingApi {
                coroutineScope {
                    val groups = async { api.getGroups() }
                    val catalog = async { api.getCapabilityCatalog() }
                    groups.await() to catalog.await()
                }
            }.onSuccess { (groups, catalog) ->
                // Sorted once, and the default selection taken from the SORTED list — taking it
                // from the unsorted response would highlight a row other than the first one shown.
                val ordered = groups.inDisplayOrder()
                state =
                    PermissionGroupsState.Data(
                        groups = ordered,
                        catalog = catalog.capabilities,
                        selectedCode = keep?.takeIf { code -> ordered.any { it.code == code } } ?: ordered.firstOrNull()?.code,
                    )
                // The open group vanished (deleted elsewhere): selection fell back to the first one,
                // so the phone drill must return to the list, as the delete path does.
                if (keep != null && ordered.none { it.code == keep }) detailOpen = false
            }.onError(retry = ::refresh) { uiError ->
                log.e { "load permission groups failed: $uiError" }
                state = PermissionGroupsState.Error(uiError)
            }
        }
    }

    fun select(code: String) {
        withData { data ->
            // Dropping the draft on selection is deliberate: carrying it to another group would let
            // an operator save one group's capabilities onto another without noticing.
            data.copy(selectedCode = code, draft = null, actionError = null, draftErrors = emptyMap())
        }
        detailOpen = true
    }

    /** Leaves the editor for the list (phone back button). Drops the draft exactly as [select] does. */
    fun closeDetail() {
        val data = state as? PermissionGroupsState.Data
        // A save or delete in flight owns the draft; dropping it now would lose the edit if it is rejected.
        if (data != null && (data.saving || data.deleting)) return
        withData { it.copy(draft = null, actionError = null, draftErrors = emptyMap()) }
        detailOpen = false
    }

    // ---------- Editing the selected group ----------

    private fun editDraft(block: (GroupDraft) -> GroupDraft) =
        withData { data ->
            val base = data.draft ?: data.selected?.let { GroupDraft.of(it) } ?: return@withData data
            data.copy(draft = block(base))
        }

    fun editName(value: String) {
        editDraft { it.copy(name = value) }
        withData { it.copy(draftErrors = it.draftErrors - GroupEditorField.NAME) }
    }

    fun editDescription(value: String) = editDraft { it.copy(description = value) }

    fun toggleCapability(name: String) =
        editDraft { draft ->
            draft.copy(
                capabilities =
                    if (name in draft.capabilities) draft.capabilities - name else draft.capabilities + name,
            )
        }

    fun discardDraft() = withData { it.copy(draft = null, draftErrors = emptyMap()) }

    fun save() {
        val data = state as? PermissionGroupsState.Data ?: return
        val draft = data.draft ?: return
        val server = data.selected ?: return
        val errors = validateGroupDraft(draft, server)
        if (errors.isNotEmpty()) {
            state = data.copy(draftErrors = errors, validationAttempt = data.validationAttempt + 1, actionError = null)
            return
        }

        state = data.copy(saving = true, actionError = null, draftErrors = emptyMap())
        viewModelScope.launch {
            runCatchingApi {
                api.updateGroup(
                    draft.code,
                    PermissionGroupDto.UpdateGroupRequestDto(
                        name = draft.name.trim(),
                        description = draft.description.trim().ifEmpty { null },
                        // A system group's capability set is fixed server-side. Sending it anyway
                        // would earn a 403 on an edit that only touched the name, so the field is
                        // omitted rather than sent unchanged — null means "leave it alone".
                        capabilities = draft.capabilities.takeIf { !server.isSystem },
                    ),
                )
            }.onSuccess { updated ->
                log.d { "saved group ${updated.code}" }
                withData { d ->
                    d.copy(
                        groups = d.groups.map { if (it.code == updated.code) updated else it },
                        draft = null,
                        saving = false,
                    )
                }
            }.onError { uiError ->
                log.e { "save group ${draft.code} failed: $uiError" }
                withData { it.copy(saving = false, actionError = uiError) }
            }
        }
    }

    fun deleteSelected() {
        val data = state as? PermissionGroupsState.Data ?: return
        val target = data.selected ?: return
        // Mirrors the disabled button (and the server's refusal): a seeded group or one with members
        // is never deleted, and a second confirm while the first delete runs is ignored.
        if (!target.isDeletable || target.memberCount > 0 || data.deleting) return

        state = data.copy(deleting = true, actionError = null)
        viewModelScope.launch {
            runCatchingApi { api.deleteGroup(target.code) }
                .onSuccess {
                    log.d { "deleted group ${target.code}" }
                    withData { d ->
                        val remaining = d.groups.filterNot { it.code == target.code }
                        d.copy(
                            groups = remaining,
                            selectedCode = remaining.firstOrNull()?.code,
                            draft = null,
                            deleting = false,
                        )
                    }
                    detailOpen = false
                }.onError { uiError ->
                    log.e { "delete group ${target.code} failed: $uiError" }
                    withData { it.copy(deleting = false, actionError = uiError) }
                }
        }
    }

    // ---------- Creating ----------

    fun openCreate() {
        createDraft = CreateGroupDraft()
    }

    fun closeCreate() {
        createDraft = null
    }

    fun editCreate(block: (CreateGroupDraft) -> CreateGroupDraft) {
        createDraft = createDraft?.let { previous -> block(previous).withEditedErrorsCleared(previous) }
    }

    fun submitCreate() {
        val draft = createDraft ?: return
        val errors = validateCreateGroupDraft(draft)
        if (errors.isNotEmpty()) {
            createDraft = draft.copy(fieldErrors = errors, validationAttempt = draft.validationAttempt + 1, error = null)
            return
        }
        createDraft = draft.copy(saving = true, error = null, fieldErrors = emptyMap())
        viewModelScope.launch {
            runCatchingApi {
                api.createGroup(
                    PermissionGroupDto.CreateGroupRequestDto(
                        code = draft.normalizedCode,
                        name = draft.name.trim(),
                        description = draft.description.trim().ifEmpty { null },
                        capabilities = draft.capabilities,
                    ),
                )
            }.onSuccess { created ->
                log.d { "created group ${created.code}" }
                createDraft = null
                withData { d ->
                    d.copy(
                        groups = (d.groups + created).inDisplayOrder(),
                        selectedCode = created.code,
                        draft = null,
                    )
                }
                detailOpen = true
            }.onError { uiError ->
                log.e { "create group ${draft.normalizedCode} failed: $uiError" }
                // Kept in the dialog rather than escalated: the operator is mid-form, and the
                // rejections here (duplicate code, reserved capability) are things they fix in place.
                createDraft = draft.copy(saving = false, error = uiError, fieldErrors = emptyMap())
            }
        }
    }
}

/**
 * Seeded groups first, then alphabetically by code.
 *
 * One definition, used by both the load and the create path, so a newly created group lands where
 * a reload would have put it rather than wherever it was appended.
 */
private fun List<PermissionGroupDto.PermissionGroupResponseDto>.inDisplayOrder() =
    sortedWith(compareByDescending<PermissionGroupDto.PermissionGroupResponseDto> { it.isSystem }.thenBy { it.code })
