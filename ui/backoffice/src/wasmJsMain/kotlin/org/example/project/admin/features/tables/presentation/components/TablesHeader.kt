package org.example.project.admin.features.tables.presentation.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.AdminPageTitle
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.tables.presentation.TableStatusCounts
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSearchBar
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.theme.LocalPosLayout

/**
 * The wide layout's header: title with the status legend under it, then search, "Grundriss bearbeiten"
 * (only for an operator who may edit the floor plan) and the refresh icon. [counts] null on the
 * location-types tab, which has neither legend nor table search.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TablesWideHeader(
    counts: TableStatusCounts?,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onEditFloorPlan: (() -> Unit)?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = LocalPosLayout.current
    FlowRow(
        modifier.fillMaxWidth().padding(start = layout.screenPadding, end = layout.screenPadding, top = layout.screenPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(layout.gridGap),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AdminPageTitle(str(AdminStringKeys.NavTables))
            if (counts != null) TableStatusLegend(counts)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(layout.gridGap), verticalAlignment = Alignment.CenterVertically) {
            if (counts != null) {
                PosSearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchChange,
                    placeholder = str(SettingsKeys.searchTables),
                    modifier = Modifier.width(SEARCH_WIDTH),
                    onClear = { onSearchChange("") },
                )
            }
            if (onEditFloorPlan != null) {
                PosSecondaryButton(
                    text = str(AdminStringKeys.NavFloorPlanEdit),
                    icon = Icons.Default.GridView,
                    onClick = onEditFloorPlan,
                    height = 40.dp,
                )
            }
            RefreshButton(onRefresh)
        }
    }
}

/** The medium layout's header: title left; refresh, "Grundriss bearbeiten" and the primary "Tisch anlegen" right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TablesMediumHeader(
    onEditFloorPlan: (() -> Unit)?,
    onCreate: (() -> Unit)?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = LocalPosLayout.current
    FlowRow(
        modifier.fillMaxWidth().padding(start = layout.screenPadding, end = layout.screenPadding, top = layout.screenPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(layout.gridGap),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        AdminPageTitle(str(AdminStringKeys.NavTables))
        Row(horizontalArrangement = Arrangement.spacedBy(layout.gridGap), verticalAlignment = Alignment.CenterVertically) {
            RefreshButton(onRefresh)
            if (onEditFloorPlan != null) {
                PosSecondaryButton(
                    text = str(AdminStringKeys.NavFloorPlanEdit),
                    icon = Icons.Default.GridView,
                    onClick = onEditFloorPlan,
                    height = 40.dp,
                )
            }
            if (onCreate != null) {
                PosButton(text = str(AdminStringKeys.TablesCreateButton), icon = Icons.Default.Add, onClick = onCreate, height = 40.dp)
            }
        }
    }
}

/** The phone's action row: the full-width primary "Tisch anlegen" and the floor-plan icon button. */
@Composable
fun TablesCompactActions(
    onCreate: (() -> Unit)?,
    onEditFloorPlan: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (onCreate == null && onEditFloorPlan == null) return
    val layout = LocalPosLayout.current
    val floorPlanLabel = str(AdminStringKeys.NavFloorPlanEdit)
    Row(
        modifier.fillMaxWidth().padding(start = layout.screenPadding, end = layout.screenPadding, top = layout.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(layout.gridGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onCreate != null) {
            PosButton(
                text = str(AdminStringKeys.TablesCreateButton),
                icon = Icons.Default.Add,
                onClick = onCreate,
                modifier = Modifier.weight(1f),
            )
        }
        if (onEditFloorPlan != null) {
            // Outlined 48dp square, like the design's grid button.
            Box(Modifier.border(1.dp, MaterialTheme.colorScheme.outline)) {
                PosIconButton(icon = Icons.Default.GridView, contentDescription = floorPlanLabel, onClick = onEditFloorPlan, size = 48.dp)
            }
        }
    }
}

@Composable
private fun RefreshButton(onRefresh: () -> Unit) {
    PosIconButton(
        icon = Icons.Default.Refresh,
        contentDescription = str(SettingsKeys.sync),
        onClick = onRefresh,
        size = 40.dp,
    )
}

private val SEARCH_WIDTH = 320.dp
