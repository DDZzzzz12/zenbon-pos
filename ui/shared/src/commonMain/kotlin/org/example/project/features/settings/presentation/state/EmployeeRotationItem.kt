package org.example.project.features.settings.presentation.state

data class EmployeeRotationItem(
    val id: String,
    val displayName: String,
    val employeeNumber: String,
    val inRotationPool: Boolean,
    val isActive: Boolean,
)
