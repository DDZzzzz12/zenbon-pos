package org.example.project.admin.features.staff.presentation.hr

import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.utils.toCanonicalMoneyOrNull

/**
 * The PUT body for an HR profile edit.
 *
 * The server treats `null` as "leave unchanged" and `""` as "clear" (GDPR erasure, FO-77). So a
 * non-name field the operator emptied becomes `""` **only if it held a value when the editor opened**
 * ([original]); a field that was already empty and stays empty is `null`, so an untouched empty field
 * never turns into a write. The two names are required and can never be cleared.
 */
internal fun hrUpdateRequest(
    draft: HrProfileEditor,
    original: HrProfileEditor?,
): EmployeeInfoDto.UpdateEmployeeInfoRequestDto {
    fun text(
        now: String,
        before: String?,
    ): String? = now.trim().takeIf { it.isNotBlank() } ?: if (before.isNullOrBlank()) null else ""

    fun amount(
        now: String,
        before: String?,
    ): String? = now.trim().takeIf { it.isNotBlank() }?.toCanonicalMoneyOrNull() ?: if (before.isNullOrBlank()) null else ""

    return EmployeeInfoDto.UpdateEmployeeInfoRequestDto(
        firstName = draft.firstName.trim().takeIf { it.isNotBlank() },
        lastName = draft.lastName.trim().takeIf { it.isNotBlank() },
        email = text(draft.email, original?.email),
        phoneNumber = text(draft.phoneNumber, original?.phoneNumber),
        street = text(draft.street, original?.street),
        city = text(draft.city, original?.city),
        state = text(draft.state, original?.state),
        zipCode = text(draft.zipCode, original?.zipCode),
        country = text(draft.country, original?.country),
        dateOfBirth = text(draft.dateOfBirth, original?.dateOfBirth),
        hireDate = text(draft.hireDate, original?.hireDate),
        jobTitle = text(draft.jobTitle, original?.jobTitle),
        hourlyRate = amount(draft.hourlyRate, original?.hourlyRate),
        salary = amount(draft.salary, original?.salary),
        contractedHours = amount(draft.contractedHours, original?.contractedHours),
        contractedHoursPeriod = draft.contractedHoursPeriod,
    )
}
