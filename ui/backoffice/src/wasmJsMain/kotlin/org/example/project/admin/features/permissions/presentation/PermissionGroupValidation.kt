package org.example.project.admin.features.permissions.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.employee.PermissionGroupDto
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys

/**
 * Client-side validation of the create-group form: one message key per invalid field; empty =
 * submittable. The code is judged in its normalised form, which is what the server stores.
 */
internal fun validateCreateGroupDraft(d: CreateGroupDraft): Map<CreateGroupField, StringKey> =
    buildMap {
        when {
            d.normalizedCode.isEmpty() -> put(CreateGroupField.CODE, CommonKeys.fieldRequired)
            !d.normalizedCode.all { it.isLetterOrDigit() || it == '_' } ->
                put(CreateGroupField.CODE, AdminStringKeys.PermissionGroupCodeInvalid)
        }
        if (d.name.isBlank()) put(CreateGroupField.NAME, CommonKeys.fieldRequired)
    }

/** Drops the error of every field whose value changed from [previous]. */
internal fun CreateGroupDraft.withEditedErrorsCleared(previous: CreateGroupDraft): CreateGroupDraft {
    if (fieldErrors.isEmpty()) return this
    val changed =
        buildSet {
            if (code != previous.code) add(CreateGroupField.CODE)
            if (name != previous.name) add(CreateGroupField.NAME)
        }
    return copy(fieldErrors = fieldErrors.filterKeys { it !in changed })
}

/**
 * Client-side validation of an edit to [server]: a custom group needs a name. A seeded group's name
 * is shown read-only (reset to the compiled definition on every boot), so nothing to check there.
 */
internal fun validateGroupDraft(
    draft: GroupDraft,
    server: PermissionGroupDto.PermissionGroupResponseDto,
): Map<GroupEditorField, StringKey> =
    buildMap {
        if (!server.isSystem && draft.name.isBlank()) put(GroupEditorField.NAME, CommonKeys.fieldRequired)
    }
