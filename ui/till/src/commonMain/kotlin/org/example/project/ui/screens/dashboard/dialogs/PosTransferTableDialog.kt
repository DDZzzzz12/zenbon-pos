package org.example.project.ui.screens.dashboard.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.data.models.RTable
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.reason.ActionReasons
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TableTransferKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.ReasonChips
import org.example.project.ui.components.ReasonSelectionState
import org.example.project.ui.components.employee.EmployeePickerGrid
import org.example.project.ui.screens.dashboard.components.TableSearchSelector

@Composable
fun PosTransferTableDialog(
    sourceTable: RTable,
    employees: List<EmployeeDto.EmployeeInfoDto>,
    currentEmployeeNumber: String?,
    canSelectSelf: Boolean,
    searchResults: List<RTable>,
    onSearchQueryChanged: (String) -> Unit,
    onTransfer: (targetTable: RTable?, employeeNumber: String, reason: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val filteredEmployees =
        remember(employees, currentEmployeeNumber, canSelectSelf) {
            employees
                .filterForPicker(currentEmployeeNumber, canSelectSelf)
        }

    var isTableTransferMode by remember { mutableStateOf(false) }
    var selectedEmployee by remember { mutableStateOf<EmployeeDto.EmployeeInfoDto?>(null) }
    var selectedTarget by remember { mutableStateOf<RTable?>(null) }
    val reasonState = remember { ReasonSelectionState(ActionReasons.transferTable) }

    // Reset table selection when switching modes
    LaunchedEffect(isTableTransferMode) {
        selectedTarget = null
        if (isTableTransferMode) {
            onSearchQueryChanged("")
        }
    }

    val title =
        if (isTableTransferMode) {
            str(TableTransferKeys.title)
        } else {
            str(TableTransferKeys.changeEmployeeTitle)
        }

    val isConfirmEnabled =
        if (isTableTransferMode) {
            selectedEmployee != null && selectedTarget != null
        } else {
            selectedEmployee != null
        }

    val confirmButtonText =
        when {
            selectedEmployee == null -> str(TableTransferKeys.transferToPlaceholder)
            !isTableTransferMode -> str(TableTransferKeys.transferToEmployee, selectedEmployee?.lastName ?: "")
            selectedTarget != null -> str(TableTransferKeys.transferToButton, selectedTarget!!.tableNumber)
            else -> str(TableTransferKeys.transferToPlaceholder)
        }

    PosDialog(
        onDismiss = onDismiss,
        title = title,
        maxWidth = 520.dp,
        footer = {
            Spacer(modifier = Modifier.weight(1f))
            PosSecondaryButton(
                text = str(CommonKeys.cancel),
                onClick = onDismiss,
            )
            PosButton(
                text = confirmButtonText,
                onClick = {
                    val employee = selectedEmployee ?: return@PosButton
                    val target = selectedTarget
                    if (isTableTransferMode && target == null) return@PosButton
                    val reason = reasonState.submitOrNull() ?: return@PosButton
                    onTransfer(if (isTableTransferMode) target else null, employee.employeeNumber, reason)
                    onDismiss()
                },
                enabled = isConfirmEnabled,
            )
        },
    ) {
        // Switch row: subtitle + toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text =
                    if (isTableTransferMode) {
                        str(TableTransferKeys.moveOrderSubtitle, sourceTable.tableNumber)
                    } else {
                        str(TableTransferKeys.changeEmployeeSubtitle, sourceTable.tableNumber)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = str(TableTransferKeys.includeTableTransfer),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = isTableTransferMode,
                    onCheckedChange = { isTableTransferMode = it },
                )
            }
        }
        // Employee selection header
        Text(
            text = str(TableTransferKeys.selectEmployee),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )

        Spacer(modifier = Modifier.height(2.dp))

        EmployeePickerGrid(
            employees = filteredEmployees,
            selectedEmployeeNumber = selectedEmployee?.employeeNumber,
            emptyText = str(TableTransferKeys.noEmployeeResults),
            onEmployeeClick = { employee ->
                selectedEmployee =
                    if (selectedEmployee?.employeeNumber == employee.employeeNumber) {
                        null
                    } else {
                        employee
                    }
            },
        )

        // Table transfer section (only shown when switch is ON)
        if (isTableTransferMode) {
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = str(TableTransferKeys.selectTargetTable),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )

            Spacer(modifier = Modifier.height(4.dp))

            TableSearchSelector(
                tables = searchResults,
                excludeTableId = sourceTable.id,
                selectedTableId = selectedTarget?.id,
                loadTables = onSearchQueryChanged,
                onTableClick = { table ->
                    selectedTarget = if (selectedTarget?.id == table.id) null else table
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        HorizontalDivider()
        ReasonChips(state = reasonState)
    }
}
