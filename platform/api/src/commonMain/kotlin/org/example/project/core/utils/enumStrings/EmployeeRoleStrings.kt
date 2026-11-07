package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * Returns the localized name for this EmployeeRole.
 *
 * @param language The target language for localization
 * @return Localized display name for the employee role
 */
fun EmployeeRole.getName(language: Language): String =
    when (this) {
        EmployeeRole.TERMINAL_DEVICE -> getString(EnumKeys.employeeRoleTerminal, language)
        EmployeeRole.CASHIER -> getString(EnumKeys.employeeRoleCashier, language)
        EmployeeRole.MANAGER -> getString(EnumKeys.employeeRoleManager, language)
        EmployeeRole.ADMIN -> getString(EnumKeys.employeeRoleAdmin, language)
    }

/** Every role string the server has ever sent, including the legacy aliases [EmployeeRole.getRole] folds. */
private val KNOWN_ROLE_NAMES = setOf("TERMINAL", "TERMINAL_DEVICE", "BASIC_EMPLOYEE", "CASHIER", "MANAGER", "ADMIN")

/**
 * A role as it arrives on the wire ("CASHIER") as the operator reads it ("Kassierer").
 *
 * An unrecognized value is shown as sent: [EmployeeRole.getRole] would fold it into the terminal
 * role, and a wrong label is worse than a raw one.
 */
fun localizedEmployeeRoleName(
    role: String,
    language: Language,
): String = if (role.uppercase() in KNOWN_ROLE_NAMES) EmployeeRole.getRole(role).getName(language) else role
