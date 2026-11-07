package org.example.project.ui.screens.dashboard.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.data.models.RTable
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.presentation.strings.keys.DashboardKeys
import org.example.project.core.presentation.strings.keys.TableTransferKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosTextField
import org.example.project.ui.theme.PosTheme

@Composable
fun TableSearchSelector(
    tables: List<RTable>,
    onTableClick: (RTable) -> Unit,
    modifier: Modifier = Modifier,
    loadTables: ((String) -> Unit)? = null,
    excludeTableId: Long? = null,
    enabled: Boolean = true,
    selectedTableId: Long? = null,
    requireSearch: Boolean = false,
) {
    var searchQuery by remember { mutableStateOf("") }
    val visibleTables =
        remember(tables, excludeTableId) {
            tables.filter { it.id != excludeTableId }
        }

    Column(modifier = modifier) {
        PosTextField(
            value = searchQuery,
            onValueChange = {
                searchQuery = it
                loadTables?.invoke(it)
            },
            label = str(TableTransferKeys.searchTable),
            leadingIcon = Icons.Default.Search,
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = {
                        // raw Material 3 by design: trailingIcon lambda slot of PosTextField requires composable with no parameters
                        searchQuery = ""
                        loadTables?.invoke("")
                    }) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }
            },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        when {
            requireSearch && searchQuery.isBlank() -> Unit
            visibleTables.isEmpty() -> {
                Text(
                    text =
                        when {
                            searchQuery.isNotBlank() -> str(TableTransferKeys.noSearchResults)
                            // A search-driven picker (loadTables != null) starts with an empty list
                            // because nothing has been searched yet — not because the restaurant has
                            // no tables. Only a picker handed a preloaded list can say that.
                            loadTables != null -> str(TableTransferKeys.searchToSeeTables)
                            else -> str(TableTransferKeys.noTablesAvailable)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                ) {
                    items(visibleTables, key = { it.id }) { table ->
                        TableStatusTile(
                            table = table,
                            isSelected = selectedTableId == table.id,
                            enabled = enabled,
                            onClick = { onTableClick(table) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableStatusTile(
    table: RTable,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val status =
        remember(table.tableStatus) {
            runCatching { TableStatus.valueOf(table.tableStatus) }.getOrDefault(TableStatus.FREE)
        }
    val isOccupied = status == TableStatus.OCCUPIED

    val borderColor =
        when {
            isSelected -> MaterialTheme.colorScheme.primary
            status == TableStatus.FREE -> PosTheme.colors.posAvailable
            status == TableStatus.OCCUPIED -> PosTheme.colors.posOccupied
            status == TableStatus.RESERVED -> PosTheme.colors.posReserved
            else -> MaterialTheme.colorScheme.outlineVariant
        }
    val borderWidth = if (isSelected) 3.dp else 2.dp
    val alpha = if (isOccupied) 0.5f else 1f

    val statusText =
        when {
            isSelected -> str(TableTransferKeys.selected)
            status == TableStatus.FREE -> str(DashboardKeys.statusFree)
            status == TableStatus.OCCUPIED -> str(DashboardKeys.statusOccupied)
            status == TableStatus.RESERVED -> str(DashboardKeys.statusReserved)
            else -> ""
        }

    Surface(
        shape = MaterialTheme.shapes.small,
        color =
            if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        border = BorderStroke(borderWidth, borderColor.copy(alpha = alpha)),
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 8.dp),
        ) {
            Text(
                text = table.tableNumber,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color =
                    when {
                        isOccupied -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurface
                    },
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color =
                    when {
                        isOccupied -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}
