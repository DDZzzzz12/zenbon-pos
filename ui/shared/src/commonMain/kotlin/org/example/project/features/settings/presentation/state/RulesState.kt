package org.example.project.features.settings.presentation.state

import org.example.project.core.presentation.error.UiError

data class RulesState(
    // Order rules
    val employeeSelectionOnFreeTable: Boolean = false,
    val roundRobinAssignment: Boolean = false,
    val rotationPoolEmployees: List<EmployeeRotationItem> = emptyList(),
    // Billing rules
    val allowCrossEmployeeBilling: Boolean = false,
    val isLoading: Boolean = false,
    val error: UiError? = null,
)
