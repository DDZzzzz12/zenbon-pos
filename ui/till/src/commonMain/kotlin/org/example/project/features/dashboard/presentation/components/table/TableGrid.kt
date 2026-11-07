package org.example.project.features.dashboard.presentation.components.table

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.data.models.RTable
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.presentation.strings.keys.DashboardKeys
import org.example.project.core.presentation.strings.keys.TableConfigKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalLanguageManager
import org.example.project.ui.components.PosTableCard
import org.example.project.ui.components.employee.EmployeeColors
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosTheme

@Composable
fun TableGrid(
    tables: List<RTable>,
    locationTypeLabel: String = "",
    onTableClick: (RTable) -> Unit,
    onTableLongClick: (RTable) -> Unit = {},
    showInactive: Boolean = false,
    onToggleShowInactive: () -> Unit = {},
) {
    val languageManager = LocalLanguageManager.current
    val currentLanguage by languageManager.currentLanguage

    if (tables.isEmpty() && !showInactive) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = str(DashboardKeys.noTablesForType, locationTypeLabel.lowercase()),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            // Filter chip row
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = showInactive,
                    onClick = onToggleShowInactive,
                    label = {
                        Text(str(TableConfigKeys.showInactive))
                    },
                    leadingIcon =
                        if (showInactive) {
                            { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                        } else {
                            null
                        },
                    shape = MaterialTheme.shapes.small,
                    colors =
                        FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                        ),
                    border =
                        if (!showInactive) {
                            FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = false,
                                borderColor = MaterialTheme.colorScheme.outlineVariant,
                            )
                        } else {
                            null
                        },
                )
            }

            if (tables.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = str(DashboardKeys.noTablesForType, locationTypeLabel.lowercase()),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(LocalPosLayout.current.gridGap),
                    verticalArrangement = Arrangement.spacedBy(LocalPosLayout.current.gridGap),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(tables) { table ->
                        val tableStatus =
                            try {
                                TableStatus.valueOf(table.tableStatus)
                            } catch (_: Exception) {
                                TableStatus.FREE
                            }
                        val isDark = isSystemInDarkTheme()
                        val employeeColorPair =
                            if (table.isActive && tableStatus == TableStatus.OCCUPIED) {
                                EmployeeColors.getColorPair(
                                    table.currentEmployeeNumber,
                                    isDark,
                                    PosTheme.colors.posOccupied,
                                )
                            } else {
                                null
                            }
                        val statusColor =
                            when {
                                !table.isActive -> androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                tableStatus == TableStatus.FREE -> PosTheme.colors.posAvailable
                                tableStatus == TableStatus.RESERVED -> PosTheme.colors.posReserved
                                tableStatus == TableStatus.OCCUPIED ->
                                    employeeColorPair?.border
                                        ?: PosTheme.colors.posOccupied
                                else -> PosTheme.colors.posAvailable
                            }
                        val statusText =
                            when (tableStatus) {
                                TableStatus.FREE -> str(DashboardKeys.statusFree)
                                TableStatus.OCCUPIED -> str(DashboardKeys.statusOccupied)
                                TableStatus.RESERVED -> str(DashboardKeys.statusReserved)
                            }
                        val secondaryText =
                            when {
                                !table.isActive -> str(TableConfigKeys.inactive)
                                tableStatus == TableStatus.OCCUPIED && table.currentEmployeeNumber != null ->
                                    table.currentEmployeeNumber
                                        ?: ""
                                else -> "${table.seats} ${str(DashboardKeys.seatsLabel)}"
                            }
                        PosTableCard(
                            tableNumber = table.tableNumber,
                            statusColor = statusColor,
                            statusText = statusText,
                            secondaryText = secondaryText,
                            openAmount = if (tableStatus == TableStatus.OCCUPIED) table.openAmount else null,
                            isActive = table.isActive,
                            isTraining = table.isTraining,
                            containerColor = employeeColorPair?.background,
                            borderColor = employeeColorPair?.border,
                            onClick = { onTableClick(table) },
                            onLongClick = { onTableLongClick(table) },
                            showDetailsAction = tableStatus != TableStatus.FREE,
                        )
                    }
                }
            }
        }
    }
}
