package org.example.project.ui.components.floorplan

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Euro
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.data.models.RTable
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.presentation.strings.keys.FloorPlanKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardStatus
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.employee.EmployeeColors
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.components.posCardStatusColor
import org.example.project.ui.components.posCardStatusContainerColor
import org.example.project.ui.theme.LocalPosColors
import org.example.project.ui.theme.PosSpacing

/**
 * The till's floor-plan table card — the single rendering both the till and the admin floor-plan
 * editor use, so what an operator arranges in admin is what staff see.
 *
 * Placement, size and rotation are the caller's job: pass `Modifier.tableCell(table)` from a
 * `FloorPlanScope`. Gestures are the caller's too — the till layers its combine-drag onto the same
 * modifier — so this composable carries no app-specific interaction.
 *
 * @param content replaces the zoom-dependent body (number, employee/amount, seats, badges) with the
 *   host's own, for a host whose card size is not the till's: the admin editor prints the number as typed,
 *   sized to fit the card. The card's colours, border and shape still come from the table's status.
 * @param borderWidthPx stroke width, in the card's own (pre-zoom) pixels, of the status border; null keeps the
 *   till's 4. A zoomed host passes `n / zoom` to get an n-pixel border on screen. With a [content] slot the
 *   border is also drawn inside a square-cornered box, like a CSS border.
 * @param dashLengthPx length of one dash and one gap of the FREE/RESERVED border; null keeps the till's 8/10.
 * @param currencyCode ISO code used to format [openAmount]. Supplied by the host because
 *   [RTable] carries no currency column and composeApp's setting manager cannot cross to wasm.
 */
@Composable
fun PosFloorPlanTableCard(
    table: RTable,
    currencyCode: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onTableLongClick: () -> Unit = {},
    zoomLevel: Float = 1.0f,
    openAmount: String? = null,
    showAmountCycle: Boolean = false,
    reservationTime: String? = null,
    isCombineMode: Boolean = false,
    combineState: TableCombineState = TableCombineState.NONE,
    content: (@Composable (RTable) -> Unit)? = null,
    borderWidthPx: Float? = null,
    dashLengthPx: Float? = null,
) {
    // Parse table status with fallback to FREE
    val tableStatus =
        try {
            TableStatus.valueOf(table.tableStatus)
        } catch (e: IllegalArgumentException) {
            TableStatus.FREE
        }

    // Determine container and border colors based on status using POS semantic colors
    val isDark = isSystemInDarkTheme()
    val employeeColorPair =
        EmployeeColors.getColorPair(
            table.currentEmployeeNumber,
            isDark,
            posCardStatusColor(PosCardStatus.Occupied),
        )
    val availableColor = posCardStatusColor(PosCardStatus.Available)
    val (containerColor, borderColor) =
        when {
            !table.isActive -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
            tableStatus == TableStatus.FREE ->
                posCardStatusContainerColor(PosCardStatus.Available) to availableColor
            tableStatus == TableStatus.RESERVED ->
                posCardStatusContainerColor(PosCardStatus.Reserved) to posCardStatusColor(PosCardStatus.Reserved)
            table.isTraining -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
            tableStatus == TableStatus.OCCUPIED -> employeeColorPair.background to employeeColorPair.border
            else -> posCardStatusContainerColor(PosCardStatus.Available) to availableColor
        }

    // Override colors in combine mode based on combine state
    val statusColors = LocalPosColors.current
    val (effectiveContainerColor, effectiveBorderColor) =
        if (isCombineMode) {
            when (combineState) {
                TableCombineState.CONNECTABLE -> containerColor to statusColors.posAvailable
                TableCombineState.SOURCE -> statusColors.posReserved.copy(alpha = 0.15f) to statusColors.posReserved
                TableCombineState.VALID_TARGET -> statusColors.posAvailable.copy(alpha = 0.15f) to statusColors.posAvailable
                TableCombineState.DISCONNECT_TARGET -> statusColors.posError.copy(alpha = 0.15f) to statusColors.posError
                TableCombineState.CONNECTED -> containerColor to statusColors.posAvailable
                else -> containerColor to borderColor
            }
        } else {
            containerColor to borderColor
        }

    // Combine mode alpha for incompatible tables
    val combineAlpha = if (isCombineMode && combineState == TableCombineState.INCOMPATIBLE) 0.4f else 1f

    // Determine if table is circular based on equal width and height
    val isCircular = table.isCircular()

    // Determine layout mode based on zoom level
    val layoutMode =
        when {
            zoomLevel < 1.5f -> PosTableLayoutMode.MINIMAL
            zoomLevel < 2.5f -> PosTableLayoutMode.COMPACT
            else -> PosTableLayoutMode.EXPANDED
        }

    PosCard(
        modifier =
            modifier
                .alpha((if (table.isActive) 1f else 0.5f) * combineAlpha)
                .drawBehind {
                    if (content == null) {
                        drawStatusBorder(
                            isCombineMode,
                            combineState,
                            tableStatus,
                            isCircular,
                            effectiveBorderColor,
                            borderWidthPx,
                            dashLengthPx,
                            hosted = false,
                        )
                    }
                }.drawWithContent {
                    drawContent()
                    if (content != null) {
                        drawStatusBorder(isCombineMode, combineState, tableStatus, isCircular, effectiveBorderColor, borderWidthPx, dashLengthPx, hosted = true)
                    }
                }.combinedClickable(
                    onClick = { onClick() },
                    onLongClick = { onTableLongClick() },
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                ),
        containerColor = effectiveContainerColor,
        shape = if (isCircular) CircleShape else RectangleShape,
        tonalElevation = 0.dp,
        variant = PosCardVariant.Tonal,
        // Tighter than the default 14.dp/6.dp — these cards are small and fixed-size,
        // so the generous default padding overflows and clips the bottom content.
        contentPadding = if (content != null) PaddingValues(0.dp) else PaddingValues(horizontal = 8.dp, vertical = PosSpacing.sm),
        contentSpacing = 2.dp,
    ) {
        body {
            if (content != null) {
                content(table)
                return@body
            }
            when (layoutMode) {
                PosTableLayoutMode.MINIMAL ->
                    PosMinimalTableContent(table, tableStatus, openAmount, showAmountCycle, currencyCode)
                PosTableLayoutMode.COMPACT ->
                    PosCompactTableContent(table, tableStatus, openAmount, reservationTime, showAmountCycle, currencyCode)
                PosTableLayoutMode.EXPANDED ->
                    PosExpandedTableContent(table, tableStatus, openAmount, reservationTime, currencyCode)
            }
        }
    }
}

/**
 * The status border of a table card: combine-mode rings, or in normal mode a dashed border for FREE and RESERVED
 * and (for a [hosted] card only) a solid one for OCCUPIED. A hosted card is drawn over its content, inside its box.
 */
private fun DrawScope.drawStatusBorder(
    isCombineMode: Boolean,
    combineState: TableCombineState,
    tableStatus: TableStatus,
    isCircular: Boolean,
    effectiveBorderColor: Color,
    borderWidthPx: Float?,
    dashLengthPx: Float?,
    hosted: Boolean,
) {
    if (isCombineMode && combineState != TableCombineState.NONE && combineState != TableCombineState.INCOMPATIBLE) {
        // Combine mode border rendering
        val strokeWidth =
            when (combineState) {
                TableCombineState.VALID_TARGET, TableCombineState.DISCONNECT_TARGET -> 6f
                else -> 4f
            }
        val pathEffect =
            when (combineState) {
                TableCombineState.CONNECTABLE -> PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                else -> null
            }

        if (isCircular) {
            drawCircle(
                color = effectiveBorderColor,
                radius = size.minDimension / 2f,
                style = Stroke(width = strokeWidth, pathEffect = pathEffect),
            )
        } else {
            drawRoundRect(
                color = effectiveBorderColor,
                cornerRadius = CornerRadius(20.dp.toPx()),
                style = Stroke(width = strokeWidth, pathEffect = pathEffect),
            )
        }
    } else if (!isCombineMode && drawsSolidStatusBorder(tableStatus)) {
        // An occupied table carries a solid border in the employee colour, as the till's table grid card does
        // (its 1 dp outline and 4 dp stripe); a bare 15 % tint read as much paler than the grid.
        val strokeWidth = borderWidthPx ?: 4f
        val inset = strokeWidth / 2f
        if (isCircular) {
            drawCircle(color = effectiveBorderColor, radius = size.minDimension / 2f - inset, style = Stroke(width = strokeWidth))
        } else {
            drawRect(
                color = effectiveBorderColor,
                topLeft = Offset(inset, inset),
                size = Size(size.width - 2 * inset, size.height - 2 * inset),
                style = Stroke(width = strokeWidth),
            )
        }
    } else if (!isCombineMode && drawsDashedStatusBorder(tableStatus)) {
        // Normal mode: only RESERVED tables carry a dashed border; a free table is plain.
        val strokeWidth = borderWidthPx ?: 4f
        val pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLengthPx ?: 10f, dashLengthPx ?: 10f), 0f)
        // A hosted card draws the stroke inside its box with square corners (the editor's
        // CSS-like border); the till's centred stroke and 20dp corners stay as they were.
        val inset = if (hosted) strokeWidth / 2f else 0f

        if (isCircular) {
            drawCircle(
                color = effectiveBorderColor,
                radius = size.minDimension / 2f - inset,
                style = Stroke(width = strokeWidth, pathEffect = pathEffect),
            )
        } else if (hosted) {
            drawRect(
                color = effectiveBorderColor,
                topLeft = Offset(inset, inset),
                size = Size(size.width - 2 * inset, size.height - 2 * inset),
                style = Stroke(width = strokeWidth, pathEffect = pathEffect),
            )
        } else {
            drawRoundRect(
                color = effectiveBorderColor,
                cornerRadius = CornerRadius(20.dp.toPx()),
                style = Stroke(width = strokeWidth, pathEffect = pathEffect),
            )
        }
    }
}

/** An occupied table is outlined in its employee colour, on the till and in the editor alike. */
internal fun drawsSolidStatusBorder(tableStatus: TableStatus): Boolean = tableStatus == TableStatus.OCCUPIED

/** A free table is drawn without an edge line; only a reserved one keeps its dashed border. */
internal fun drawsDashedStatusBorder(tableStatus: TableStatus): Boolean = tableStatus == TableStatus.RESERVED

private enum class PosTableLayoutMode {
    MINIMAL,
    COMPACT,
    EXPANDED,
}

/**
 * Info slot that alternates between the serving employee number and the open amount.
 * When both are present it rolls between them (vertical slide + fade) on the shared
 * [showAmount] phase; when only one exists it shows that statically; when neither,
 * it renders nothing. The default size transform animates the width change between
 * a short employee number and a longer amount so the swap reads as one smooth motion.
 */
@Composable
private fun AlternatingInfoSlot(
    employee: String?,
    amount: String?,
    showAmount: Boolean,
    textStyle: TextStyle,
) {
    val text = alternatingSlotText(showAmount = showAmount, employee = employee, amount = amount)
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (slideInHorizontally { w -> w } + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally { w -> -w } + fadeOut(tween(220)))
        },
        label = "tableInfoSlot",
    ) { value ->
        if (value != null) {
            Text(
                text = value,
                style = textStyle,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PosMinimalTableContent(
    table: RTable,
    status: TableStatus,
    openAmount: String?,
    showAmountCycle: Boolean,
    currencyCode: String,
) {
    val contentColor = LocalContentColor.current
    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        // Background watermark: large faded number
        val tableNumber = table.tableNumber.filter { it.isDigit() }
        Text(
            text = tableNumber.removePrefix("0"),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Black,
            color = contentColor.copy(alpha = 0.06f),
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
        )
        // Centered content
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = tableNumber.removePrefix("0"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            AlternatingInfoSlot(
                employee = table.currentEmployeeNumber,
                amount = if (status == TableStatus.OCCUPIED && openAmount != null) formatMoneyForDisplay(openAmount, currencyCode) else null,
                showAmount = showAmountCycle,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            if (table.isTraining) {
                Text(
                    text = str(FloorPlanKeys.trainingShort),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (!table.isActive) {
                Text(
                    text = str(FloorPlanKeys.inactiveShort),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PosCompactTableContent(
    table: RTable,
    status: TableStatus,
    openAmount: String?,
    reservationTime: String?,
    showAmountCycle: Boolean,
    currencyCode: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Table number formatted as "T-01"
        val tableNumber = table.tableNumber.filter { it.isDigit() }
        val formattedNumber = "T-${tableNumber.padStart(2, '0')}"
        Text(
            text = formattedNumber,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        // Alternates employee number <-> open amount on the shared 2s phase.
        AlternatingInfoSlot(
            employee = table.currentEmployeeNumber,
            amount = if (status == TableStatus.OCCUPIED && openAmount != null) formatMoneyForDisplay(openAmount, currencyCode) else null,
            showAmount = showAmountCycle,
            textStyle = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(4.dp))

        // Seat count pill
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ) {
            Text(
                text = str(FloorPlanKeys.seatsCount, table.seats.toString()),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        // Training badge
        if (table.isTraining) {
            Spacer(Modifier.height(4.dp))
            Surface(
                color = MaterialTheme.colorScheme.tertiary,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = str(FloorPlanKeys.training),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiary,
                    modifier = Modifier.padding(horizontal = PosSpacing.sm, vertical = 2.dp),
                )
            }
        }

        // Inactive badge
        if (!table.isActive) {
            Spacer(Modifier.height(4.dp))
            Surface(
                color = MaterialTheme.colorScheme.error,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = str(FloorPlanKeys.inactive),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.padding(horizontal = PosSpacing.sm, vertical = 2.dp),
                )
            }
        }

        // Reservation indicator (open amount is shown in the alternating slot above)
        if (status == TableStatus.RESERVED && !reservationTime.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = reservationTime,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun PosExpandedTableContent(
    table: RTable,
    status: TableStatus,
    openAmount: String?,
    reservationTime: String?,
    currencyCode: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header: Table number + status badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tableNumber = table.tableNumber.filter { it.isDigit() }
            Column {
                Text(
                    text = str(FloorPlanKeys.tableLabel, tableNumber.padStart(2, '0')),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                table.currentEmployeeNumber?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Normal,
                    )
                }
            }

            // Status pill badge
            val (statusLabel, statusColor) =
                when {
                    !table.isActive -> str(FloorPlanKeys.inactive) to MaterialTheme.colorScheme.error
                    table.isTraining -> str(FloorPlanKeys.training) to MaterialTheme.colorScheme.tertiary
                    status == TableStatus.OCCUPIED -> str(FloorPlanKeys.statusOccupied) to posCardStatusColor(PosCardStatus.Occupied)
                    status == TableStatus.RESERVED -> str(FloorPlanKeys.statusReserved) to posCardStatusColor(PosCardStatus.Reserved)
                    else -> str(FloorPlanKeys.statusAvailable) to posCardStatusColor(PosCardStatus.Available)
                }
            Surface(
                color = statusColor.copy(alpha = 0.15f),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Seats as uppercase pill
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.People,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    str(FloorPlanKeys.seatsCount, table.seats.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // Order amount (if occupied)
        if (status == TableStatus.OCCUPIED && openAmount != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Euro,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    formatMoneyForDisplay(openAmount, currencyCode),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Reservation time (if reserved)
        if (status == TableStatus.RESERVED && !reservationTime.isNullOrBlank()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    reservationTime,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
