package org.example.project.admin.features.staff.presentation

import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.dto.employee.PermissionGroupDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey

sealed interface StaffState {
    data object Loading : StaffState

    /** Null once dismissed — the branch still renders an inline retry surface. */
    data class Error(
        val error: UiError?,
    ) : StaffState

    data class Data(
        val all: List<EmployeeDto.EmployeeSummaryResponseDto>,
        val currentEmployeeId: String,
        val currentUsername: String,
        val selectedId: String? = null,
        val selected: EmployeeDto.EmployeeResponseDto? = null, // loaded on selection
        val search: String = "",
        val showInactive: Boolean = false,
        val detailTab: DetailTab = DetailTab.OVERVIEW,
        /**
         * Every permission group, for the assignment picker and for naming the selected employee's
         * group. Empty when the caller does not hold PERMISSION_ADMIN — the list endpoint requires
         * it — which is exactly when the assign control must not be offered.
         */
        val groups: List<PermissionGroupDto.PermissionGroupResponseDto> = emptyList(),
        /** The group-picker draft; null = closed. */
        val groupPicker: GroupPicker? = null,
        /** A failure that happened after the directory loaded — the directory stays usable. */
        val error: UiError? = null,
    ) : StaffState {
        val visible: List<EmployeeDto.EmployeeSummaryResponseDto>
            get() =
                all
                    .filter { showInactive || it.isActive }
                    .filter { it.matches(search.trim()) }
                    .sortedBy { it.shownName().lowercase() }

        /** Active ADMIN count — for the last-admin guard. */
        val activeAdminCount: Int get() = all.count { it.isActive && it.role == "ADMIN" }
    }
}

/** AUDIT is reserved for Staff Phase 7; adding it needs no change to the header or directory. */
enum class DetailTab { OVERVIEW, SCHEDULE }

/**
 * Draft for the create/edit employee form.
 *
 * The exception-permission set that used to live here is GONE: Stage 2a retired per-employee grants
 * server-side, so ticking one changed nothing while looking like it did. What an employee may reach
 * is decided by their permission group — see [GroupPicker].
 */
data class EmployeeEditor(
    val id: String? = null, // null = creating
    val username: String = "",
    val password: String = "", // create only
    val role: String = "CASHIER",
    val notes: String = "",
    val languagePreference: String = "de",
    val employmentStatus: String = "ACTIVE",
    val trainingMode: Boolean = false,
    val personnelNumber: String = "",
    val saving: Boolean = false,
    /** "roleGuard" / "save" / a server message — a refusal that belongs to no field. Field validation lives in [fieldErrors]. */
    val fieldError: String? = null,
    /** Per-field validation refusals from the Save click, shown at the field. */
    val fieldErrors: Map<EmployeeField, StringKey> = emptyMap(),
    /** Bumped on every Save click the validation refuses; the dialog reveals the first error on each bump. */
    val validationAttempt: Int = 0,
) {
    val isCreate: Boolean get() = id == null

    private fun comparable(): EmployeeEditor = copy(saving = false, fieldError = null, fieldErrors = emptyMap(), validationAttempt = 0)

    /** Differs from [original] in anything the operator can type; the transient save/validation flags are ignored. */
    fun isDirtyFrom(original: EmployeeEditor?): Boolean = original != null && comparable() != original.comparable()
}

/**
 * A credential-reset refusal that belongs to no field. The input refusals (mismatch, bad format,
 * missing or wrong manager password) are field errors in [CredentialsEditor.fieldErrors].
 */
enum class CredentialsError { SERVER, }

enum class CredentialsMode { PASSWORD, PIN }

/** Draft for the credential-reset dialog (re-auth gated). */
data class CredentialsEditor(
    val employeeId: String,
    val mode: CredentialsMode,
    val newValue: String = "",
    val confirmValue: String = "",
    val managerPassword: String = "",
    val saving: Boolean = false,
    val error: CredentialsError? = null,
    /** Per-field refusals from the Save click (or the re-auth), shown at the field. */
    val fieldErrors: Map<CredentialsField, StringKey> = emptyMap(),
    /** Bumped on every refused Save click; the dialog reveals the first error on each bump. */
    val validationAttempt: Int = 0,
) {
    /** Any of the three fields holds text. The leave guard only asks; it never stores these values. */
    val isDirty: Boolean get() = newValue.isNotBlank() || confirmValue.isNotBlank() || managerPassword.isNotBlank()
}

/**
 * Draft for the permission-group picker.
 *
 * Assignment is a separate endpoint from the employee edit on purpose: it requires
 * PERMISSION_ADMIN rather than EMPLOYEE_WRITE, so a manager who may edit staff records cannot hand
 * out capabilities through the same form.
 */
data class GroupPicker(
    val employeeId: String,
    val selectedCode: String,
    /** The employee's group when the picker opened; null when they have none. */
    val currentCode: String? = null,
    val saving: Boolean = false,
    val error: UiError? = null,
    /** The code the picker opened with; a different [selectedCode] is an unsaved choice. */
    val originalCode: String = selectedCode,
) {
    val isDirty: Boolean get() = selectedCode != originalCode

    /** Saving the group already held is a no-op write, so it stays disabled until the pick differs. */
    val canSave: Boolean get() = selectedCode.isNotBlank() && selectedCode != currentCode && !saving
}
