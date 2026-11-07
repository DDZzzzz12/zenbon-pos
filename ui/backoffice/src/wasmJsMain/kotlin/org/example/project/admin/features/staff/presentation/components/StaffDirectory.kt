package org.example.project.admin.features.staff.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.staff.presentation.isReadOnlyStaffTarget
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.employee.EmployeeCard

@Composable
fun StaffDirectory(
    employees: List<EmployeeDto.EmployeeSummaryResponseDto>,
    selectedId: String?,
    search: String,
    showInactive: Boolean,
    canManage: Boolean,
    onSearch: (String) -> Unit,
    onToggleInactive: () -> Unit,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onResetPassword: (String) -> Unit,
    onResetPin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.width(300.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PosTextField(
            value = search,
            onValueChange = onSearch,
            label = str(AdminStringKeys.StaffSearch),
            leadingIcon = Icons.Default.Search,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(str(AdminStringKeys.StaffShowInactive), style = MaterialTheme.typography.labelMedium)
            Switch(showInactive, { onToggleInactive() })
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(employees, key = { it.id }) { e ->
                EmployeeCard(
                    employee = e,
                    isSelected = selectedId == e.id,
                    onClick = { onSelect(e.id) },
                    trailing =
                        if (canManage && !isReadOnlyStaffTarget(e.role)) {
                            {
                                EmployeeRowMenu(
                                    onResetPassword = { onResetPassword(e.id) },
                                    onResetPin = { onResetPin(e.id) },
                                )
                            }
                        } else {
                            null
                        },
                )
            }
        }
        if (canManage) {
            PosButton(
                text = str(AdminStringKeys.StaffAdd),
                icon = Icons.Default.Add,
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
