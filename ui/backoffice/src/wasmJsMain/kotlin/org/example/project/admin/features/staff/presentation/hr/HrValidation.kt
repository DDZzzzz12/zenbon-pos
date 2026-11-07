package org.example.project.admin.features.staff.presentation.hr

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.utils.isBlankOrIsoDate
import org.example.project.core.utils.toCanonicalMoneyOrNull

/**
 * Field-level validation of the HR draft: one message per field at fault, empty = savable.
 *
 * The same rules the editor applied as one top line before (both names, a plausible e-mail, readable
 * amounts), now on the field they concern, plus the two dates: the date fields hand over ISO for
 * anything they could read and the typed text otherwise, so a non-ISO value here is text that is not
 * a date and must not be stored as one.
 */
internal fun validateHrEditor(e: HrProfileEditor): Map<HrField, StringKey> =
    buildMap {
        if (e.firstName.isBlank()) put(HrField.FIRST_NAME, CommonKeys.fieldRequired)
        if (e.lastName.isBlank()) put(HrField.LAST_NAME, CommonKeys.fieldRequired)
        val email = e.email.trim()
        if (email.isNotEmpty() && !(email.contains("@") && email.contains("."))) {
            put(HrField.EMAIL, AdminStringKeys.StaffHrErrorEmailInvalid)
        }
        if (!isBlankOrIsoDate(e.dateOfBirth)) put(HrField.DATE_OF_BIRTH, CommonKeys.dateInvalid)
        if (!isBlankOrIsoDate(e.hireDate)) put(HrField.HIRE_DATE, CommonKeys.dateInvalid)
        if (e.hourlyRate.isUnreadableAmount()) put(HrField.HOURLY_RATE, AdminStringKeys.StaffHrErrorAmountInvalid)
        if (e.salary.isUnreadableAmount()) put(HrField.SALARY, AdminStringKeys.StaffHrErrorAmountInvalid)
        if (e.contractedHours.isUnreadableAmount()) put(HrField.CONTRACTED_HOURS, AdminStringKeys.StaffHrErrorAmountInvalid)
    }

/** The errors still standing after an edit: a field whose value changed loses its error. */
internal fun Map<HrField, StringKey>.clearedFor(
    previous: HrProfileEditor,
    current: HrProfileEditor,
): Map<HrField, StringKey> {
    if (isEmpty()) return this
    val changed =
        buildSet {
            if (previous.firstName != current.firstName) add(HrField.FIRST_NAME)
            if (previous.lastName != current.lastName) add(HrField.LAST_NAME)
            if (previous.email != current.email) add(HrField.EMAIL)
            if (previous.dateOfBirth != current.dateOfBirth) add(HrField.DATE_OF_BIRTH)
            if (previous.hireDate != current.hireDate) add(HrField.HIRE_DATE)
            if (previous.hourlyRate != current.hourlyRate) add(HrField.HOURLY_RATE)
            if (previous.salary != current.salary) add(HrField.SALARY)
            if (previous.contractedHours != current.contractedHours) add(HrField.CONTRACTED_HOURS)
        }
    return filterKeys { it !in changed }
}

private fun String.isUnreadableAmount(): Boolean = isNotBlank() && trim().toCanonicalMoneyOrNull() == null
