/**
 * Employment status enumeration for employee management.
 *
 * Defines the possible employment states for employees in the POS system,
 * used for managing employee lifecycle and access control.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.enums.employee

import kotlinx.serialization.Serializable

/**
 * Enum representing the employment status of an employee.
 *
 * Used to track the current state of an employee's employment and
 * determine their access rights within the system.
 */
@Serializable
enum class EmploymentStatus {
    /** Employee is currently active and can access the system */
    ACTIVE,

    /** Employee is temporarily inactive but not terminated */
    INACTIVE,

    /** Employee has been permanently terminated */
    TERMINATED,

    /** Employee is on temporary leave (vacation, sick leave, etc.) */
    ON_LEAVE,
}
