package org.example.project.admin.features.staff.presentation

import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys

/**
 * Client-side validation of an employee draft: one message key per invalid field; empty = savable.
 *
 * Username and password are only asked for on create — on edit the username is immutable and the
 * password is reset through its own re-auth-gated dialog. The server keeps its own checks (rule 8).
 */
internal fun validateEmployeeDraft(e: EmployeeEditor): Map<EmployeeField, StringKey> =
    buildMap {
        if (!e.isCreate) return@buildMap
        if (e.username.isBlank()) put(EmployeeField.USERNAME, CommonKeys.fieldRequired)
        if (e.password.isBlank()) put(EmployeeField.PASSWORD, CommonKeys.fieldRequired)
    }

/** Drops the error of every field whose value changed from [previous]. */
internal fun EmployeeEditor.withEditedErrorsCleared(previous: EmployeeEditor): EmployeeEditor {
    if (fieldErrors.isEmpty()) return this
    val changed =
        buildSet {
            if (username != previous.username) add(EmployeeField.USERNAME)
            if (password != previous.password) add(EmployeeField.PASSWORD)
        }
    return copy(fieldErrors = fieldErrors.filterKeys { it !in changed })
}
