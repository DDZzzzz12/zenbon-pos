package org.example.project.ui.screens.dashboard

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.example.project.core.auth.LocalSettingManager
import org.example.project.core.data.models.RTable
import org.example.project.core.presentation.strings.keys.FloorPlanKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.dashboard.presentation.state.FloorPlanUiState
import org.example.project.features.dashboard.presentation.viewmodel.FloorPlanViewModel
import org.example.project.ui.components.floorplan.PosFloorPlanRoomElement
import org.example.project.ui.components.floorplan.PosFloorPlanSurface
import org.example.project.ui.components.floorplan.PosFloorPlanTableCard
import org.example.project.ui.components.floorplan.TableCombineState

/**
 * Zoomable and pannable floor plan canvas with grid, tables and room elements.
 *
 * Geometry — cell size, plan bounds, the pan/zoom transform and cell placement — belongs to
 * [PosFloorPlanSurface] in shared-ui, so the admin floor-plan editor renders through exactly the
 * same code. What stays here is till-only: combine mode, its connection-line overlay and the FAB.
 *
 * Layout authoring lives in the web admin panel, not on the till.
 *
 * @param uiState Current floor plan UI state
 * @param viewModel FloorPlanViewModel for state mutations
 * @param onTableClick Callback when a table is clicked
 * @param onTableLongClick Callback when a table is long-clicked
 * @param modifier Modifier for customization
 */
@Composable
fun PosFloorPlanCanvas(
    uiState: FloorPlanUiState,
    viewModel: FloorPlanViewModel,
    onTableClick: (RTable) -> Unit,
    onTableLongClick: (RTable) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            return@Box
        }

        var combineDragAccX by remember { mutableStateOf(0f) }
        var combineDragAccY by remember { mutableStateOf(0f) }

        val currencyCode by LocalSettingManager.current.defaultCurrency.collectAsState()

        // Shared 2s phase so every compact/minimal card flips employee<->amount in unison.
        var showAmountCycle by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(2000)
                showAmountCycle = !showAmountCycle
            }
        }

        PosFloorPlanSurface(
            zoom = uiState.zoomLevel,
            pan = Offset(uiState.panOffsetX, uiState.panOffsetY),
            onTransform = { zoom, pan ->
                viewModel.updateZoom(zoom)
                viewModel.updatePan(pan.x, pan.y)
            },
            onViewportChanged = { paneWidth, paneHeight, _ ->
                // Combine-mode hit-testing reads cell size off the state. updateGridCellDimensions
                // takes the canvas size and derives the cells itself, through the same
                // GridConverter call the surface makes, so the two cannot disagree.
                viewModel.updateGridCellDimensions(paneWidth, paneHeight)
            },
        ) {
            uiState.roomElements.forEach { element ->
                PosFloorPlanRoomElement(
                    element = element,
                    modifier = Modifier.elementCell(element),
                )
            }

            uiState.tables.forEach { table ->
                if (!table.hasPosition()) return@forEach

                val combineState =
                    if (uiState.isCombineMode) {
                        val sourceId = uiState.combiningFromTableId
                        val sourceTable = if (sourceId != null) uiState.tables.find { it.id == sourceId } else null
                        val isConnectedToSource =
                            sourceId != null &&
                                uiState.combineConnections[sourceId]?.contains(table.id) == true
                        val isSameLocationType = sourceTable == null || table.locationTypeId == sourceTable.locationTypeId

                        when {
                            table.id == sourceId -> TableCombineState.SOURCE
                            !table.isActive || table.isVirtual -> TableCombineState.INCOMPATIBLE
                            sourceTable != null && !isSameLocationType -> TableCombineState.INCOMPATIBLE
                            uiState.combineHoverTableId == table.id && isConnectedToSource -> TableCombineState.DISCONNECT_TARGET
                            uiState.combineHoverTableId == table.id && isSameLocationType -> TableCombineState.VALID_TARGET
                            uiState.combineConnections[table.id]?.isNotEmpty() == true -> TableCombineState.CONNECTED
                            else -> TableCombineState.CONNECTABLE
                        }
                    } else {
                        TableCombineState.NONE
                    }

                val canCombineDrag = uiState.isCombineMode && combineState != TableCombineState.INCOMPATIBLE

                PosFloorPlanTableCard(
                    table = table,
                    currencyCode = currencyCode,
                    modifier =
                        Modifier
                            .tableCell(table)
                            .padding(vertical = 2.dp, horizontal = 4.dp)
                            .pointerInput(canCombineDrag) {
                                if (!canCombineDrag) return@pointerInput
                                detectDragGestures(
                                    onDragStart = {
                                        combineDragAccX = 0f
                                        combineDragAccY = 0f
                                        viewModel.startCombineDrag(table.id)
                                    },
                                    onDragEnd = { viewModel.endCombineDrag() },
                                ) { change, dragAmount ->
                                    change.consume()
                                    combineDragAccX += dragAmount.x
                                    combineDragAccY += dragAmount.y
                                    val cellDims = uiState.gridCellDimensions
                                    val tablePixelX =
                                        (table.gridX ?: 0) * cellDims.cellWidth +
                                            (table.gridWidth ?: 2) * cellDims.cellWidth / 2f
                                    val tablePixelY =
                                        (table.gridY ?: 0) * cellDims.cellHeight +
                                            (table.gridHeight ?: 2) * cellDims.cellHeight / 2f
                                    val targetX = tablePixelX + combineDragAccX / uiState.zoomLevel
                                    val targetY = tablePixelY + combineDragAccY / uiState.zoomLevel
                                    viewModel.updateCombineDragTarget(targetX, targetY)

                                    val hitTable =
                                        uiState.tables.find { t ->
                                            if (t.id == table.id || t.isVirtual || !t.isActive) return@find false
                                            val tLeft = (t.gridX ?: 0) * cellDims.cellWidth
                                            val tTop = (t.gridY ?: 0) * cellDims.cellHeight
                                            val tRight = tLeft + (t.gridWidth ?: 2) * cellDims.cellWidth
                                            val tBottom = tTop + (t.gridHeight ?: 2) * cellDims.cellHeight
                                            targetX in tLeft..tRight && targetY in tTop..tBottom
                                        }
                                    viewModel.updateCombineHoverTable(hitTable?.id)
                                }
                            },
                    onClick = { onTableClick(table) },
                    onTableLongClick = { onTableLongClick(table) },
                    zoomLevel = uiState.zoomLevel,
                    openAmount = table.openAmount,
                    showAmountCycle = showAmountCycle,
                    reservationTime = null,
                    isCombineMode = uiState.isCombineMode,
                    combineState = combineState,
                )
            }

            if (uiState.isCombineMode) {
                PosFloorPlanConnectionLines(
                    tables = uiState.tables,
                    connections = uiState.combineConnections,
                    combiningFromTableId = uiState.combiningFromTableId,
                    combineLineEndX = uiState.combineLineEndX,
                    combineLineEndY = uiState.combineLineEndY,
                    combineHoverTableId = uiState.combineHoverTableId,
                    cellDimensions = uiState.gridCellDimensions,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (uiState.isCombineMode) {
            Surface(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
            ) {
                Text(
                    text = str(FloorPlanKeys.combineModeTip),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = TextAlign.Center,
                )
            }
        }

        PosFloorPlanFAB(
            isCombineMode = uiState.isCombineMode,
            onCombineModeToggle = { viewModel.toggleCombineMode() },
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}
