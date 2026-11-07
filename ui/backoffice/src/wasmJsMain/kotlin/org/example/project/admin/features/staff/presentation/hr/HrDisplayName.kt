package org.example.project.admin.features.staff.presentation.hr

import org.example.project.core.domain.dto.employee.EmployeeInfoDto

/**
 * The person's name from the HR record ("Anna Beispiel"), or null when there is no record or no
 * name on it. The login username is the fallback at the call site: it stays the secondary line.
 */
fun EmployeeInfoDto.EmployeeInfoResponseDto?.hrDisplayName(): String? {
    if (this == null) return null
    return "${firstName.trim()} ${lastName.trim()}".trim().ifEmpty { null }
}
