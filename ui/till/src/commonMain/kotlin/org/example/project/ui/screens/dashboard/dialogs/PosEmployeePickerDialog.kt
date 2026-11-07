package org.example.project.ui.screens.dashboard.dialogs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.presentation.strings.keys.EmployeeSelectionKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.employee.EmployeePickerGrid

private val EXCLUDED_EMPLOYEE_PREFIXES = listOf("B", "T", "A")

fun List<EmployeeDto.EmployeeInfoDto>.filterForPicker(
    currentEmployeeNumber: String?,
    canSelectSelf: Boolean,
): List<EmployeeDto.EmployeeInfoDto> =
    this
        .filter { emp -> EXCLUDED_EMPLOYEE_PREFIXES.none { emp.employeeNumber.startsWith(it, ignoreCase = true) } }
        .filter { canSelectSelf || it.employeeNumber != currentEmployeeNumber }
        .sortedBy { it.employeeNumber }

@Composable
fun PosEmployeePickerDialog(
    availableEmployees: List<EmployeeDto.EmployeeInfoDto>,
    currentEmployeeNumber: String?,
    canSelectSelf: Boolean,
    onEmployeeSelected: (EmployeeDto.EmployeeInfoDto) -> Unit,
    onDismiss: () -> Unit,
    onLoadEmployees: () -> Unit = {},
) {
    LaunchedEffect(Unit) {
        onLoadEmployees()
    }

    val filteredEmployees =
        remember(availableEmployees, currentEmployeeNumber, canSelectSelf) {
            availableEmployees.filterForPicker(currentEmployeeNumber, canSelectSelf)
        }

    PosDialog(
        onDismiss = onDismiss,
        title = str(EmployeeSelectionKeys.title),
        titleIcon = Icons.Default.Person,
        maxWidth = 420.dp,
    ) {
        Text(
            text = str(EmployeeSelectionKeys.description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        EmployeePickerGrid(
            employees = filteredEmployees,
            selectedEmployeeNumber = currentEmployeeNumber,
            emptyText = str(EmployeeSelectionKeys.noEmployees),
            onEmployeeClick = { employee ->
                onEmployeeSelected(employee)
                onDismiss()
            },
        )
    }
}
