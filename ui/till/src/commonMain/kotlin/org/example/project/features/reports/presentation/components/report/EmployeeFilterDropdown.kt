/**
 * Employee filter dropdown component for manager reports.
 *
 * Provides a dropdown to select an employee for filtering statistics.
 * Only visible for MANAGER+ roles. Selecting "All Employees" shows
 * aggregate data, while selecting a specific employee filters to
 * that employee's data only.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.reports.presentation.components.report

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField

/**
 * Dropdown component for selecting an employee to filter statistics.
 *
 * Displays a dropdown with:
 * - "All Employees" option (shows aggregate data)
 * - List of active employees by employee number and username
 *
 * @param employees List of available employees to select from
 * @param selectedEmployeeId Currently selected employee ID (null = All Employees)
 * @param onEmployeeSelected Callback when an employee is selected
 * @param isLoading Whether the employee list is loading
 * @param language Current app language for localized strings
 * @param modifier Optional modifier for the component
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmployeeFilterDropdown(
    employees: List<EmployeeDto.EmployeeSummaryResponseDto>,
    selectedEmployeeId: String?,
    onEmployeeSelected: (String?) -> Unit,
    isLoading: Boolean,
    language: Language,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    // Find selected employee for display
    val selectedEmployee = employees.find { it.id == selectedEmployeeId }
    val displayText =
        when {
            isLoading -> str(ReportsKeys.loadingEmployees)
            selectedEmployee != null -> "${selectedEmployee.employeeNumber} - ${selectedEmployee.username}"
            else -> str(ReportsKeys.allEmployees)
        }

    // While the employees load the field is disabled and `displayText` says so, which is why the
    // loading spinner the leading icon used to carry is not missed here.
    Box(modifier) {
        PosPickerField(
            value = displayText,
            onClick = { expanded = true },
            label = str(ReportsKeys.filterByEmployeeLabel),
            leadingIcon = Icons.Default.Person,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
        )

        PosDropdown(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            // "All Employees" option
            PosDropdownItem(
                text = str(ReportsKeys.allEmployees),
                selected = selectedEmployeeId == null,
                leadingIcon = Icons.Default.Person,
                onClick = {
                    onEmployeeSelected(null)
                    expanded = false
                },
            )

            if (employees.isNotEmpty()) {
                HorizontalDivider()

                // Individual employee options
                employees.forEach { employee ->
                    val isSelected = employee.id == selectedEmployeeId
                    PosDropdownItem(
                        text = "${employee.employeeNumber} - ${employee.username}",
                        selected = isSelected,
                        leadingIcon = Icons.Default.Person,
                        onClick = {
                            onEmployeeSelected(employee.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
