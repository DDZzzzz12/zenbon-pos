package org.example.project.ui.screens.reservation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.ReservationKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.reservation.presentation.state.TableAssignmentEvent
import org.example.project.features.reservation.presentation.state.TableAssignmentState
import org.example.project.ui.auth.holds
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextButton

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TableAssignmentSheet(
    uiState: TableAssignmentState,
    onEvent: (TableAssignmentEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val overlay = LocalGlobalOverlayViewModel.current
    // RESERVATION_DELETE is the manager-level capability in this domain — RESERVATION_READ and
    // RESERVATION_WRITE are till-level, held by every station. It gates the same people the
    // `isManagerOrHigher()` it replaces did.
    val isManager = holds(Capability.RESERVATION_DELETE)
    val reservation = uiState.reservation ?: return
    val assignTablesTitle = str(ReservationKeys.assignTables)
    val retryLabel: String? = null

    PosErrorWatcher(
        error = uiState.error,
        title = assignTablesTitle,
        // Stateless sheet: the caller owns uiState, so there is nothing to clear from here. The
        // code this replaced did not clear either, so the behaviour is unchanged.
        onClear = {},
        displayMode = DisplayMode.DIALOG,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
        ) {
            // Loading indicator
            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Section 1: Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = reservation.guestName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${reservation.reservationDate} ${reservation.reservationTime.take(
                            5,
                        )} — ${str(ReservationKeys.partySize)}: ${reservation.partySize}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PosIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = str(CommonKeys.close),
                    onClick = onDismiss,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Section 2: Current Assignments
            Text(
                text = str(ReservationKeys.currentAssignments),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.assignments.isEmpty()) {
                Text(
                    text = str(ReservationKeys.noTablesAssigned),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val groupedAssignments = uiState.assignments.groupBy { it.groupNumber }
                for (groupNumber in 1..reservation.tableGroups) {
                    val groupTables = groupedAssignments[groupNumber] ?: emptyList()
                    val groupSeats = groupTables.sumOf { it.seats }
                    Text(
                        text = "${str(
                            ReservationKeys.group,
                            groupNumber.toString(),
                        )} — ${str(ReservationKeys.seatsOf, groupSeats.toString(), reservation.partySize.toString())}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (groupTables.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            groupTables.forEach { assignment ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text("${assignment.tableNumber} (${assignment.seats})") },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = str(ReservationKeys.unassignAll),
                                            modifier =
                                                Modifier.clickable {
                                                    onEvent(TableAssignmentEvent.ToggleTable(assignment.tableId))
                                                },
                                        )
                                    },
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Section 3: Actions Row
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                PosButton(
                    text = str(ReservationKeys.autoAssign),
                    onClick = { onEvent(TableAssignmentEvent.AutoAssign) },
                    enabled = !uiState.isLoading,
                )
                if (isManager && uiState.assignments.isNotEmpty()) {
                    PosSecondaryButton(
                        text = str(ReservationKeys.unassignAll),
                        onClick = { onEvent(TableAssignmentEvent.RequestUnassignAll) },
                        enabled = !uiState.isLoading,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Section 4: Manual Assignment (expandable)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onEvent(TableAssignmentEvent.ToggleManualSection) }
                        .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = str(ReservationKeys.manualAssign),
                    style = MaterialTheme.typography.titleSmall,
                )
                Icon(
                    imageVector = if (uiState.isManualExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
            }

            AnimatedVisibility(
                visible = uiState.isManualExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Group selector
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (group in 1..reservation.tableGroups) {
                            FilterChip(
                                selected = uiState.selectedGroup == group,
                                onClick = { onEvent(TableAssignmentEvent.SelectGroup(group)) },
                                label = { Text(str(ReservationKeys.group, group.toString())) },
                            )
                        }
                        FilterChip(
                            selected = false,
                            onClick = { onEvent(TableAssignmentEvent.AddGroup) },
                            label = { Icon(Icons.Default.Add, contentDescription = str(ReservationKeys.addGroup)) },
                            enabled = reservation.tableGroups < reservation.partySize,
                        )
                    }

                    // Available tables grouped by location type
                    if (uiState.availableTables.isEmpty()) {
                        Text(
                            text = str(ReservationKeys.noAvailableTables),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val tablesByLocation = uiState.availableTables.groupBy { it.locationType }
                        tablesByLocation.forEach { (locationType, tables) ->
                            Text(
                                text = locationType,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                tables.forEach { table ->
                                    FilterChip(
                                        selected = table.tableId in uiState.selectedTableIds,
                                        onClick = { onEvent(TableAssignmentEvent.ToggleTable(table.tableId)) },
                                        label = { Text("${table.tableNumber} (${table.seats})") },
                                    )
                                }
                            }
                        }
                    }

                    // Assign Selected button
                    PosButton(
                        text = str(ReservationKeys.assignSelected),
                        onClick = { onEvent(TableAssignmentEvent.ManualAssign) },
                        enabled = uiState.selectedTableIds.isNotEmpty() && !uiState.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    // Section 5: Unassign confirmation dialog
    if (uiState.showUnassignConfirmDialog) {
        PosDialog(
            onDismiss = { onEvent(TableAssignmentEvent.DismissUnassignConfirm) },
            title = str(ReservationKeys.unassignAll),
            footer = {
                PosTextButton(
                    text = str(CommonKeys.cancel),
                    onClick = { onEvent(TableAssignmentEvent.DismissUnassignConfirm) },
                )
                PosButton(
                    text = str(CommonKeys.confirm),
                    onClick = { onEvent(TableAssignmentEvent.ConfirmUnassignAll) },
                )
            },
        ) {
            Text(str(ReservationKeys.confirmUnassign))
        }
    }
}
