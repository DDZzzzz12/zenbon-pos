package org.example.project.features.auth.domain.models

import org.example.project.core.domain.enums.employee.EmployeeRole

/**
 * Domain model representing an authenticated user.
 */
data class User(
    val id: String,
    val username: String,
    val employeeNumber: String,
    val firstName: String,
    val lastName: String,
    val role: EmployeeRole,
) {
    val fullName: String
        get() = "$firstName $lastName"
}
