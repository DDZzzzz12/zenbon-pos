package org.example.project.admin.features.staff.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys

private const val MIN_PASSWORD_LENGTH = 6
private val PIN_LENGTH = 4..6

/** Whether [value] is a credential of [mode]'s shape: a password of ≥6 characters, a PIN of 4–6 digits. */
internal fun isValidCredential(
    mode: CredentialsMode,
    value: String,
): Boolean =
    when (mode) {
        CredentialsMode.PASSWORD -> value.isNotBlank() && value.length >= MIN_PASSWORD_LENGTH
        CredentialsMode.PIN -> value.length in PIN_LENGTH && value.all { it.isDigit() }
    }

/** Client-side validation of a credential reset: one message key per invalid field; empty = submittable. */
internal fun validateCredentialsDraft(e: CredentialsEditor): Map<CredentialsField, StringKey> =
    buildMap {
        when {
            e.newValue.isBlank() -> put(CredentialsField.NEW_VALUE, CommonKeys.fieldRequired)
            !isValidCredential(e.mode, e.newValue) -> put(CredentialsField.NEW_VALUE, AdminStringKeys.StaffCredErrorInvalid)
        }
        when {
            e.confirmValue.isBlank() -> put(CredentialsField.CONFIRM_VALUE, CommonKeys.fieldRequired)
            e.confirmValue != e.newValue -> put(CredentialsField.CONFIRM_VALUE, AdminStringKeys.StaffCredErrorMismatch)
        }
        if (e.managerPassword.isBlank()) put(CredentialsField.MANAGER_PASSWORD, CommonKeys.fieldRequired)
    }

/** Drops the error of every field whose value changed from [previous]. */
internal fun CredentialsEditor.withEditedErrorsCleared(previous: CredentialsEditor): CredentialsEditor {
    if (fieldErrors.isEmpty()) return this
    val changed =
        buildSet {
            if (newValue != previous.newValue) add(CredentialsField.NEW_VALUE)
            if (confirmValue != previous.confirmValue) add(CredentialsField.CONFIRM_VALUE)
            if (managerPassword != previous.managerPassword) add(CredentialsField.MANAGER_PASSWORD)
        }
    return copy(fieldErrors = fieldErrors.filterKeys { it !in changed })
}
