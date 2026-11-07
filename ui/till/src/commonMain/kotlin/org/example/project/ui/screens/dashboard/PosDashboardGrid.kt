package org.example.project.ui.screens.dashboard

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.project.core.data.models.RTable
import org.example.project.core.domain.dto.table.LocationTypeDto
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.DashboardKeys
import org.example.project.core.presentation.strings.keys.TableConfigKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalLanguageManager
import org.example.project.ui.components.PosTableCard
import org.example.project.ui.components.employee.EmployeeColors
import org.example.project.ui.theme.PosSpacing
import org.example.project.ui.theme.PosTheme

@Composable
fun PosDashboardGrid(
    tables: List<RTable>,
    selectedLocationTypeId: Long,
    locationTypes: List<LocationTypeDto.ResponseDto>,
    isLoading: Boolean,
    onLocationTypeSelected: (Long) -> Unit,
    onTableClick: (RTable) -> Unit,
    onTableLongClick: (RTable) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()

    LaunchedEffect(selectedLocationTypeId) {
        onLocationTypeSelected(selectedLocationTypeId)
    }

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        // Segmented tabs
        if (locationTypes.isNotEmpty()) {
            LocationSegmentedTabs(
                selectedLocationTypeId = selectedLocationTypeId,
                locationTypes = locationTypes,
                onLocationTypeSelected = onLocationTypeSelected,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        // Content
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            tables.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = str(DashboardKeys.noTablesAvailable),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(tables, key = { it.id }) { table ->
                        val tableStatus =
                            try {
                                TableStatus.valueOf(table.tableStatus)
                            } catch (_: Exception) {
                                TableStatus.FREE
                            }
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

                        PosTableCard(
                            tableNumber = table.tableNumber,
                            statusColor = statusColor,
                            statusText =
                                when (tableStatus) {
                                    TableStatus.FREE -> str(DashboardKeys.statusFree)
                                    TableStatus.OCCUPIED -> str(DashboardKeys.statusOccupied)
                                    TableStatus.RESERVED -> str(DashboardKeys.statusReserved)
                                },
                            secondaryText =
                                when {
                                    !table.isActive -> str(TableConfigKeys.inactive)
                                    tableStatus == TableStatus.OCCUPIED && table.currentEmployeeNumber != null ->
                                        table.currentEmployeeNumber ?: ""
                                    else -> "${table.seats} ${str(DashboardKeys.seatsLabel)}"
                                },
                            containerColor = employeeColorPair?.background,
                            borderColor = employeeColorPair?.border,
                            openAmount = if (tableStatus == TableStatus.OCCUPIED) table.openAmount else null,
                            isActive = table.isActive,
                            isTraining = table.isTraining,
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

@Composable
private fun LocationSegmentedTabs(
    selectedLocationTypeId: Long,
    locationTypes: List<LocationTypeDto.ResponseDto>,
    onLocationTypeSelected: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = LocalLanguageManager.current.currentLanguage.value
    val tabs =
        locationTypes.map { locationType ->
            val label =
                when (language) {
                    Language.ENGLISH -> locationType.englishName
                    Language.CHINESE -> locationType.chineseName
                    else -> locationType.name
                }
            locationType.id to label
        }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
    ) {
        BoxWithConstraints {
            // Tab width = the row's width less its padding, shared by the tabs, less the label's own 4 dp sides.
            val tabWidth = (maxWidth - PosSpacing.xs * 2) / tabs.size.coerceAtLeast(1) - 8.dp
            val tabFontSize =
                rememberLocationTabFontSize(
                    labels = tabs.map { it.second },
                    tabWidth = tabWidth,
                    style = MaterialTheme.typography.labelLarge,
                    minFontSize = 6.sp,
                )
            Row(
                modifier = Modifier.padding(PosSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { (locationTypeId, label) ->
                    val isSelected = selectedLocationTypeId == locationTypeId
                    Surface(
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 34.dp),
                        onClick = { onLocationTypeSelected(locationTypeId) },
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = MaterialTheme.shapes.small,
                        shadowElevation = if (isSelected) 1.dp else 0.dp,
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                fontSize = tabFontSize,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color =
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}
