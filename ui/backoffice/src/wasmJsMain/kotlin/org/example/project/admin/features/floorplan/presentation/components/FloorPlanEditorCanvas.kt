package org.example.project.admin.features.floorplan.presentation.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.example.project.admin.features.floorplan.presentation.FloorPlanSelection
import org.example.project.core.data.models.RTable
import org.example.project.core.data.models.RoomElement
import org.example.project.core.utils.FloorPlanPlacement
import org.example.project.core.utils.GridCellDimensions
import org.example.project.core.utils.GridConverter
import org.example.project.ui.components.floorplan.FloorPlanGridStyle
import org.example.project.ui.components.floorplan.FloorPlanViewport
import org.example.project.ui.components.floorplan.PosFloorPlanRoomElement
import org.example.project.ui.components.floorplan.PosFloorPlanSurface
import org.example.project.ui.components.floorplan.PosFloorPlanTableCard
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * RTable carries no currency column and adminApp has no setting manager, so the editor formats an
 * open amount in euros. Matches the existing precedent in PromotionsManagerDialog and
 * VatBreakdownSection for DTOs that predate the currency column.
 */
private const val EDITOR_CURRENCY = "EUR"

/** The editor may zoom out far below the till's 1x floor, so a plan with stray content can be surveyed. */
const val EDITOR_MIN_ZOOM = 0.1f

/** Fit may zoom in this far to frame a small plan (the till's fit never goes past 1x); the design shows a small plan at about 24px per cell. */
const val EDITOR_FIT_MAX_ZOOM = 4f

/** How far (fraction of the pane per side) the editor lets the plan be pulled off-centre. */
const val EDITOR_PAN_OVERSCROLL = 0.5f

/** Accumulated wheel delta that makes one zoom step (a mouse notch is about 100). */
private const val WHEEL_STEP_DELTA = 50f

/**
 * Converts a drag on the floor plan into a snapped grid position.
 *
 * [dragX]/[dragY] are the accumulated deltas of `detectDragGestures` on an item that lives inside the
 * surface's scaled layer, so they are already in **plan** pixels: Compose maps pointer positions into
 * the item's own, unscaled space. Dividing by the zoom again made a table land at 1/zoom of the pointer's
 * movement when zoomed in. Kept as a pure function so the conversion is unit-testable.
 *
 * @return the snapped grid column and row, clamped into the grid by [GridConverter]
 */
internal fun dragToGrid(
    startGridX: Int,
    startGridY: Int,
    dragX: Float,
    dragY: Float,
    cells: GridCellDimensions,
    wCells: Int = 1,
    hCells: Int = 1,
): Pair<Int, Int> {
    // The card is drawn where FloorPlanPlacement clamps it, so a table stored off the grid starts
    // its drag from that cell, not from its stored (invisible) position.
    val start = GridConverter.clampToGrid(startGridX, startGridY, wCells, hCells)
    val planX = start.gridX * cells.cellWidth + dragX
    val planY = start.gridY * cells.cellHeight + dragY
    // The drop keeps the whole item on the grid, the same rule the drawing applies.
    val dropped =
        GridConverter.clampToGrid(
            gridX = GridConverter.pixelToGridX(pixelX = planX, cellSize = cells.cellWidth),
            gridY = GridConverter.pixelToGridY(pixelY = planY, cellSize = cells.cellHeight),
            gridWidth = start.gridWidth,
            gridHeight = start.gridHeight,
        )
    return dropped.gridX to dropped.gridY
}

/**
 * The admin floor-plan editor's canvas.
 *
 * Rendering is the till's: [PosFloorPlanSurface] owns the geometry and [PosFloorPlanTableCard] /
 * [PosFloorPlanRoomElement] draw the contents, so what is arranged here is what staff see. Only the
 * editing gestures — tap to select, drag to move, and the selection ring — live in this file.
 *
 * The grid is drawn with [FloorPlanGridStyle.Lines] rather than the till's dots: guides are a design
 * aid, not content, and the editor is where alignment matters.
 */
@Composable
fun FloorPlanEditorCanvas(
    tables: List<RTable>,
    elements: List<RoomElement>,
    selection: FloorPlanSelection?,
    zoom: Float,
    pan: Offset,
    onTransform: (zoom: Float, pan: Offset) -> Unit,
    onViewportChanged: (paneWidth: Float, paneHeight: Float) -> Unit,
    onWheelZoom: (factor: Float) -> Unit,
    onSelect: (FloorPlanSelection?) -> Unit,
    onMoveTable: (id: Long, gridX: Int, gridY: Int) -> Unit,
    onMoveElement: (id: Long, gridX: Int, gridY: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The wheel handler is long-lived (pointerInput(Unit)); read the callback through
    // rememberUpdatedState so it never calls a stale lambda.
    val latestWheelZoom by rememberUpdatedState(onWheelZoom)
    val density = LocalDensity.current

    PosFloorPlanSurface(
        modifier =
            modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    // A wheel notch is ~100; a trackpad sends many small deltas per gesture, so deltas are
                    // accumulated and one zoom step fires per WHEEL_STEP_DELTA instead of one per event.
                    var accumulated = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val dy =
                                event.changes
                                    .firstOrNull()
                                    ?.scrollDelta
                                    ?.y ?: 0f
                            if (dy != 0f) {
                                accumulated += dy
                                if (abs(accumulated) >= WHEEL_STEP_DELTA) {
                                    latestWheelZoom(FloorPlanViewport.wheelFactor(accumulated))
                                    accumulated = 0f
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            },
        zoom = zoom,
        pan = pan,
        onTransform = onTransform,
        onViewportChanged = { w, h, _ -> onViewportChanged(w, h) },
        zoomRange = EDITOR_MIN_ZOOM..FloorPlanViewport.MAX_ZOOM,
        panOverscroll = EDITOR_PAN_OVERSCROLL,
        gridStyle = FloorPlanGridStyle.Lines,
        onBackgroundTap = { onSelect(null) },
    ) {
        elements.forEach { element ->
            val isSelected = selection == FloorPlanSelection.Element(element.id)
            var drag by remember(element.id, element.gridX, element.gridY) { mutableStateOf(Offset.Zero) }

            PosFloorPlanRoomElement(
                element = element,
                content = { FloorPlanElementContent(it, zoom) },
                modifier =
                    Modifier
                        .elementCell(element)
                        // The live drag. `drag` accumulated through the gesture but nothing drew it:
                        // elementCell places the element from its stored gridX/gridY, which does not
                        // change until onDragEnd commits, so it stood still and then jumped to the
                        // release point. Offsetting by the accumulated delta makes it follow the
                        // pointer; onDragEnd still snaps it to the grid.
                        .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
                        .selectionRing(isSelected, circular = false, zoom = zoom)
                        .pointerInput(element.id) { detectTapGestures { onSelect(FloorPlanSelection.Element(element.id)) } }
                        .pointerInput(element.id, element.gridX, element.gridY, element.gridWidth, element.gridHeight, cells, zoom) {
                            detectDragGestures(
                                onDragStart = { onSelect(FloorPlanSelection.Element(element.id)) },
                                onDragEnd = {
                                    val (gx, gy) =
                                        dragToGrid(
                                            element.gridX,
                                            element.gridY,
                                            drag.x,
                                            drag.y,
                                            cells,
                                            element.gridWidth,
                                            element.gridHeight,
                                        )
                                    drag = Offset.Zero
                                    onMoveElement(element.id, gx, gy)
                                },
                            ) { change, amount ->
                                change.consume()
                                drag += amount
                            }
                        },
            )
        }

        tables.forEach { table ->
            val isSelected = selection == FloorPlanSelection.Table(table.id)
            val startX = table.gridX ?: 0
            val startY = table.gridY ?: 0
            val spanW = FloorPlanPlacement.tableSpan(table.gridWidth)
            val spanH = if (table.isCircular()) spanW else FloorPlanPlacement.tableSpan(table.gridHeight)
            var drag by remember(table.id, startX, startY) { mutableStateOf(Offset.Zero) }

            PosFloorPlanTableCard(
                table = table,
                currencyCode = EDITOR_CURRENCY,
                modifier =
                    Modifier
                        .tableCell(table)
                        // See the element above: without this the card does not move until the drag
                        // ends, then teleports to where the pointer was released.
                        .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
                        .selectionRing(isSelected, circular = table.isCircular(), zoom = zoom)
                        .pointerInput(table.id, startX, startY, spanW, spanH, cells, zoom) {
                            detectDragGestures(
                                onDragStart = { onSelect(FloorPlanSelection.Table(table.id)) },
                                onDragEnd = {
                                    val (gx, gy) =
                                        dragToGrid(startX, startY, drag.x, drag.y, cells, spanW, spanH)
                                    drag = Offset.Zero
                                    onMoveTable(table.id, gx, gy)
                                },
                            ) { change, amount ->
                                change.consume()
                                drag += amount
                            }
                        },
                zoomLevel = zoom,
                openAmount = table.openAmount,
                onClick = { onSelect(FloorPlanSelection.Table(table.id)) },
                content = { FloorPlanTableLabel(it) },
                // The design's 2px dashed border (a dash is 6px), enlarged back down by the zoom layer.
                borderWidthPx = with(density) { (2.dp / zoom.coerceAtLeast(MIN_RING_ZOOM)).toPx() },
                dashLengthPx = with(density) { (6.dp / zoom.coerceAtLeast(MIN_RING_ZOOM)).toPx() },
            )
        }
    }
}

/**
 * Selection affordance, drawn by the editor rather than passed into the shared card — the till has
 * no notion of a selected table, and shared-ui should not grow one for admin's side. Like the design's
 * `box-shadow: 0 0 0 3px white, 0 0 0 5px teal`: a 3px white band, then a 2px teal one, both OUTSIDE the box.
 * The plan is enlarged by a zoom layer, so the widths are `n dp / zoom` to come out as n screen pixels.
 */
@Composable
private fun Modifier.selectionRing(
    selected: Boolean,
    circular: Boolean,
    zoom: Float,
): Modifier {
    if (!selected) return this
    val primary = MaterialTheme.colorScheme.primary
    val gap = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current
    val one = with(density) { (1.dp / zoom.coerceAtLeast(MIN_RING_ZOOM)).toPx() }
    return drawWithContent {
        drawContent()
        // Centres of the two bands, measured outwards from the edge: 1.5px and 4px.
        drawRingBand(gap, width = 3f * one, outset = 1.5f * one, circular = circular)
        drawRingBand(primary, width = 2f * one, outset = 4f * one, circular = circular)
    }
}

private const val MIN_RING_ZOOM = 0.05f

private fun DrawScope.drawRingBand(
    color: androidx.compose.ui.graphics.Color,
    width: Float,
    outset: Float,
    circular: Boolean,
) {
    if (circular) {
        drawCircle(color = color, radius = size.minDimension / 2f + outset, style = Stroke(width = width))
    } else {
        drawRect(
            color = color,
            topLeft = Offset(-outset, -outset),
            size = Size(size.width + 2 * outset, size.height + 2 * outset),
            style = Stroke(width = width),
        )
    }
}
