package org.example.project.ui.components.employee

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.EmployeeHubKeys
import org.example.project.core.presentation.strings.keys.VoucherManagementKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.employee.presentation.EmployeePickerViewModel
import org.example.project.features.employee.presentation.pickerLabel
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField
import org.koin.compose.viewmodel.koinViewModel

/**
 * Default width for [EmployeePicker] when the caller doesn't specify one. `PosPickerField` fills the
 * width it is given (via `PosTextField`'s `fillMaxWidth()`), and [EmployeePicker] wraps it in a `Box`
 * with no width constraint of its own — inside a `Row`, that lets it claim the entire row before any
 * `weight`-based sibling (e.g. `PeriodStepper`, `CompareToggle`) gets measured, crushing them to zero
 * or near-zero width. Bounding the width here keeps the picker acting like a compact filter control.
 */
private val DEFAULT_WIDTH = 220.dp

/**
 * Employee filter shared by back-office screens. [selectedEmployeeId] is a UUID string, or null for
 * "all employees" — the server treats a missing filter as all, for a manager-or-higher caller.
 *
 * Selection is a parameter, not ViewModel state, on purpose: see [EmployeePickerViewModel].
 *
 * A failed roster load degrades quietly — the field disables on the "all" label rather than raising
 * anything on the host screen, because a broken filter must not block the data behind it.
 */
@Composable
fun EmployeePicker(
    selectedEmployeeId: String?,
    onEmployeeSelected: (String?) -> Unit,
    modifier: Modifier = Modifier.width(DEFAULT_WIDTH),
    includeAllOption: Boolean = true,
    activeOnly: Boolean = true,
    enabled: Boolean = true,
    viewModel: EmployeePickerViewModel = koinViewModel(),
) {
    LaunchedEffect(activeOnly) { viewModel.load(activeOnly) }

    var expanded by remember { mutableStateOf(false) }
    val state = viewModel.state
    val allLabel = str(EmployeeHubKeys.allEmployees)
    val selected = state.employees.firstOrNull { it.id == selectedEmployeeId }
    val value = selected?.pickerLabel() ?: allLabel

    Box(modifier) {
        PosPickerField(
            value = value,
            onClick = { expanded = true },
            label = str(VoucherManagementKeys.employee),
            enabled = enabled && state.error == null && state.employees.isNotEmpty(),
        )
        PosDropdown(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (includeAllOption) {
                PosDropdownItem(
                    text = allLabel,
                    selected = selectedEmployeeId == null,
                    onClick = {
                        expanded = false
                        onEmployeeSelected(null)
                    },
                )
            }
            state.employees.forEach { employee ->
                PosDropdownItem(
                    text = employee.pickerLabel(),
                    selected = employee.id == selectedEmployeeId,
                    onClick = {
                        expanded = false
                        onEmployeeSelected(employee.id)
                    },
                )
            }
        }
    }
}
