package org.example.project.core.presentation.components.fileBrowser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.fileBrowser.models.SortOrder
import org.example.project.core.presentation.strings.keys.FileBrowserKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.presentation.viewmodel.FileBrowserViewMode
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosIconButton

/**
 * Top bar for the file browser with sort and view mode controls.
 */
@Composable
fun FileBrowserTopBar(
    sortOrder: SortOrder,
    viewMode: FileBrowserViewMode,
    hideHiddenFiles: Boolean,
    onSortChange: (SortOrder) -> Unit,
    onViewModeChange: (FileBrowserViewMode) -> Unit,
    onToggleHiddenFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSortMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Sort dropdown
            PosIconButton(
                icon = Icons.Default.SortByAlpha,
                contentDescription = str(FileBrowserKeys.sort),
                onClick = { showSortMenu = true },
            )

            PosDropdown(
                expanded = showSortMenu,
                onDismissRequest = { showSortMenu = false },
            ) {
                SortOrder.entries.forEach { order ->
                    PosDropdownItem(
                        text = getSortOrderLabel(order),
                        selected = order == sortOrder,
                        onClick = {
                            onSortChange(order)
                            showSortMenu = false
                        },
                    )
                }
            }

            // Hidden files toggle
            PosIconButton(
                icon =
                    if (hideHiddenFiles) {
                        Icons.Default.VisibilityOff
                    } else {
                        Icons.Default.Visibility
                    },
                contentDescription =
                    if (hideHiddenFiles) {
                        str(FileBrowserKeys.showHiddenFiles)
                    } else {
                        str(FileBrowserKeys.hideHiddenFiles)
                    },
                onClick = onToggleHiddenFiles,
            )
        }

        // View mode toggle
        PosIconButton(
            icon =
                if (viewMode == FileBrowserViewMode.GRID) {
                    Icons.AutoMirrored.Filled.ViewList
                } else {
                    Icons.Default.GridView
                },
            contentDescription = str(FileBrowserKeys.toggleViewMode),
            onClick = {
                onViewModeChange(
                    if (viewMode == FileBrowserViewMode.GRID) {
                        FileBrowserViewMode.LIST
                    } else {
                        FileBrowserViewMode.GRID
                    },
                )
            },
        )
    }
}

@Composable
private fun getSortOrderLabel(order: SortOrder): String =
    when (order) {
        SortOrder.NAME_ASC -> str(FileBrowserKeys.nameAsc)
        SortOrder.NAME_DESC -> str(FileBrowserKeys.nameDesc)
        SortOrder.DATE_ASC -> str(FileBrowserKeys.dateAsc)
        SortOrder.DATE_DESC -> str(FileBrowserKeys.dateDesc)
        SortOrder.SIZE_ASC -> str(FileBrowserKeys.sizeAsc)
        SortOrder.SIZE_DESC -> str(FileBrowserKeys.sizeDesc)
    }
