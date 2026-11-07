package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.employee.EmploymentStatus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * Returns the localized name for this EmploymentStatus.
 *
 * @param language The target language for localization
 * @return Localized display name for the employment status
 */
fun EmploymentStatus.getName(language: Language): String =
    when (this) {
        EmploymentStatus.ACTIVE -> getString(EnumKeys.employmentStatusActive, language)
        EmploymentStatus.INACTIVE -> getString(EnumKeys.employmentStatusInactive, language)
        EmploymentStatus.TERMINATED -> getString(EnumKeys.employmentStatusTerminated, language)
        EmploymentStatus.ON_LEAVE -> getString(EnumKeys.employmentStatusOnLeave, language)
    }
