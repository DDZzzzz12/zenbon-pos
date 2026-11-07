package org.example.project.admin.features.staff.presentation.hr

import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.enums.employee.ContractedHoursPeriod
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey

sealed interface HrProfileState {
    data object Loading : HrProfileState

    data class Error(
        val error: UiError,
    ) : HrProfileState

    /**
     * [info] is null when the employee has no HR record yet — the normal case for a freshly
     * created login, and NOT an error.
     */
    data class Data(
        val employeeId: String,
        val info: EmployeeInfoDto.EmployeeInfoResponseDto?,
    ) : HrProfileState
}

/** Why the editor refused, when it is not one field's fault (field errors live in [HrProfileEditor.fieldErrors]). */
enum class HrEditorError {
    SAVE_FAILED,
}

/**
 * Draft for the HR editor.
 *
 * Server merge semantics: `null` means "leave unchanged", `""` means "clear the stored value". A field
 * the operator emptied is sent as `""` when it held a value at open time (see [hrUpdateRequest]).
 */
data class HrProfileEditor(
    val employeeId: String,
    val isCreate: Boolean,
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val dateOfBirth: String = "",
    val street: String = "",
    val city: String = "",
    val state: String = "",
    val zipCode: String = "",
    val country: String = "",
    val jobTitle: String = "",
    val hireDate: String = "",
    val hourlyRate: String = "",
    val salary: String = "",
    val contractedHours: String = "",
    val contractedHoursPeriod: ContractedHoursPeriod = ContractedHoursPeriod.WEEKLY,
    val saving: Boolean = false,
    val error: HrEditorError? = null,
    /** Field errors of the last Save press; transient. */
    val fieldErrors: Map<HrField, StringKey> = emptyMap(),
    /** Bumped on every Save press that found errors, so the dialog scrolls to the first one each time. */
    val validationAttempt: Int = 0,
) {
    /** Differs from [original] in anything the operator can type; the transient save and validation flags are ignored. */
    fun isDirtyFrom(original: HrProfileEditor?): Boolean = original != null && comparable() != original.comparable()

    private fun comparable(): HrProfileEditor = copy(saving = false, error = null, fieldErrors = emptyMap(), validationAttempt = 0)
}
