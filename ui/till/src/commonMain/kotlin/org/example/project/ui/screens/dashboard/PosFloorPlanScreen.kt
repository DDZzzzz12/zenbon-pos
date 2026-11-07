package org.example.project.ui.screens.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.example.project.core.data.models.RTable
import org.example.project.core.domain.constants.WellKnownLocationTypes
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.DashboardKeys
import org.example.project.core.utils.LocalLanguageManager
import org.example.project.core.utils.LocalNotificationViewModel
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.features.dashboard.presentation.viewmodel.FloorPlanViewModel
import org.example.project.features.dashboard.presentation.viewmodel.TableGridViewModel
import org.example.project.features.notifications.presentation.viewmodel.NotificationUiEvent
import org.example.project.ui.components.PosErrorWatcher
import org.koin.compose.viewmodel.koinViewModel

/**
 * Floor plan screen for tablet/desktop with top bar, canvas, and detail panel.
 *
 * Composes PosFloorPlanTopBar, PosFloorPlanCanvas (or PosDashboardGrid for takeaway),
 * and PosTableDetailPanel into a cohesive floor plan management screen.
 *
 * @param onTableClick Callback when a table is tapped in view mode
 * @param onTableLongClick Callback when a table is long-pressed in view mode
 * @param modifier Modifier for customization
 * @param viewModel FloorPlanViewModel for canvas state
 * @param gridViewModel TableGridViewModel for grid/takeaway state
 * @param onRefreshClick Callback when refresh is tapped
 * @param onTableAction Callback for table actions from detail panel (table, action name)
 */
@Composable
fun PosFloorPlanScreen(
    onTableClick: (RTable) -> Unit,
    onTableLongClick: (RTable) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FloorPlanViewModel = koinViewModel(),
    gridViewModel: TableGridViewModel = koinViewModel(),
    onRefreshClick: () -> Unit = {},
    onTableAction: (RTable, String) -> Unit = { _, _ -> },
) {
    val windowSize = LocalWindowSizeClass.current
    val overlay = LocalGlobalOverlayViewModel.current
    val language = LocalLanguageManager.current.currentLanguage.value

    val uiState by viewModel.uiState.collectAsState()
    val gridState by gridViewModel.uiState.collectAsState()
    val locationTypes = gridState.locationTypes

    var selectedMode by remember { mutableIntStateOf(0) }

    // Get the currently selected location type
    val currentLocationType = locationTypes.getOrNull(selectedMode)
    val currentLocationTypeId = currentLocationType?.id ?: WellKnownLocationTypes.INDOOR
    val isTakeawayMode = currentLocationType?.isTakeaway == true

    // Reload data when selected mode or location types change
    LaunchedEffect(selectedMode, locationTypes) {
        if (isTakeawayMode) {
            gridViewModel.selectTableType(currentLocationTypeId)
        } else {
            viewModel.loadFloorPlanData(currentLocationTypeId)
        }
    }

    // Live table-status pushes from other devices (F55) — see PosDashboardScreen, which patches the grid.
    val notificationViewModel = LocalNotificationViewModel.current
    LaunchedEffect(Unit) {
        notificationViewModel.notificationEvents.collect { event ->
            if (event is NotificationUiEvent.TableStatusChanged) viewModel.onTableStatusChanged(event)
        }
    }

    // No displayMode: the bus routes by what the error carries — field errors and 5xx escalate to
    // PosErrorDialog, routine refusals toast rather than blocking the floor plan behind a modal.
    PosErrorWatcher(
        error = uiState.error,
        title = getString(DashboardKeys.floorPlanError, language),
        onClear = { viewModel.clearError() },
        retryAction = { viewModel.loadFloorPlanData(currentLocationTypeId) },
    )

    // Occupied table count
    val occupiedCount =
        remember(uiState.tables) {
            uiState.tables.count {
                try {
                    TableStatus.valueOf(it.tableStatus) == TableStatus.OCCUPIED
                } catch (_: Exception) {
                    false
                }
            }
        }

    // No table of this location has a position: a floor plan with nothing on it is never useful.
    val showGrid =
        shouldShowTableGrid(
            isTakeawayLocation = isTakeawayMode,
            isLoading = uiState.isLoading,
            tables = uiState.tables,
        )
    val unplacedGridTables =
        remember(uiState.tables) {
            uiState.tables.sortedBy { t -> t.tableNumber.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        }

    Column(modifier = modifier.fillMaxSize()) {
        PosFloorPlanTopBar(
            occupiedCount = occupiedCount,
            selectedMode = selectedMode,
            locationTypes = locationTypes,
            onModeSelected = { selectedMode = it },
            onRefreshClick = {
                viewModel.refresh(currentLocationTypeId)
                onRefreshClick()
            },
        )

        Box(modifier = Modifier.weight(1f)) {
            // Main content area — takeaway uses the grid view model's tables; a location whose
            // tables have no floor-plan position falls back to the grid over the floor plan's tables.
            if (isTakeawayMode) {
                PosDashboardGrid(
                    tables = gridState.tablesByType,
                    selectedLocationTypeId = gridState.selectedLocationTypeId,
                    locationTypes = emptyList(),
                    isLoading = gridState.isLoading,
                    onLocationTypeSelected = {},
                    onTableClick = { table ->
                        onTableClick(table)
                    },
                    onTableLongClick = { table ->
                        onTableLongClick(table)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (showGrid) {
                PosDashboardGrid(
                    tables = unplacedGridTables,
                    selectedLocationTypeId = currentLocationTypeId,
                    locationTypes = emptyList(),
                    isLoading = false,
                    onLocationTypeSelected = {},
                    onTableClick = { table ->
                        onTableClick(table)
                    },
                    onTableLongClick = { table ->
                        onTableLongClick(table)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                PosFloorPlanCanvas(
                    uiState = uiState,
                    viewModel = viewModel,
                    onTableClick = { table ->
                        onTableClick(table)
                    },
                    onTableLongClick = { table ->
                        onTableLongClick(table)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
