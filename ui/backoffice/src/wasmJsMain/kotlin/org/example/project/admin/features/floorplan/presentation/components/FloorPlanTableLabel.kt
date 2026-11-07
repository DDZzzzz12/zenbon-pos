package org.example.project.admin.features.floorplan.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.models.RTable
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.FloorPlanPlacement

/** A table shows "N Plätze" under its number only from this many cells of width (the design's rule). */
internal const val SEATS_LABEL_MIN_WIDTH_CELLS = 3

/** The design draws the number at 16px and the seats line at 11px on a 24px cell; everything here scales with the cell. */
private const val NUMBER_PER_CELL = 16f / 24f
private const val SEATS_PER_CELL = 11f / 24f
private const val NUMBER_LINE_HEIGHT = 20f / 16f
private const val SEATS_LINE_HEIGHT = 14f / 11f

/** Rough width of one character as a fraction of the font size, for fitting a long name into the card. */
private const val CHAR_WIDTH_RATIO = 0.62f

/** Never smaller: a card measured at zero size (first frame, or collapsed) would give a zero font size, which Skia rejects. */
private val MIN_FONT_SIZE = 2.dp

/**
 * What a table card says on the plan, as in the design: its number exactly as entered ("3", "A1"), bold, and
 * "6 Plätze" under it when the table is at least [SEATS_LABEL_MIN_WIDTH_CELLS] cells wide.
 *
 * The design's text is a fixed 16px/11px on 24px cells, so here both sizes are proportional to the CELL size
 * (the card's measured width over its cell span). The plan is laid out small and enlarged by the zoom
 * layer, so using the cell keeps the labels in the design's proportion at any zoom, and every table at a
 * plan shows the same size text. The till's card, in contrast, prints a fixed "T-01" and only digits.
 */
@Composable
fun FloorPlanTableLabel(table: RTable) {
    val showSeats = (table.gridWidth ?: 2) >= SEATS_LABEL_MIN_WIDTH_CELLS && table.seats > 0
    val seatsText =
        if (table.seats == 1L) str(AdminStringKeys.TablesSeatsCountOne) else str(AdminStringKeys.FloorPlanSeatsLine, table.seats.toString())
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cell = maxWidth / FloorPlanPlacement.tableSpan(table.gridWidth)
        val sizes = labelSizes(cell, maxWidth, table.tableNumber.length, if (showSeats) seatsText.length else 0)
        val density = LocalDensity.current
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = table.tableNumber,
                style =
                    MaterialTheme.typography.titleMedium.copy(
                        fontSize = with(density) { sizes.number.toSp() },
                        lineHeight = with(density) { (sizes.number * NUMBER_LINE_HEIGHT).toSp() },
                        fontFeatureSettings = "tnum",
                    ),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
            if (showSeats) {
                Text(
                    text = seatsText,
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontSize = with(density) { sizes.seats.toSp() },
                            lineHeight = with(density) { (sizes.seats * SEATS_LINE_HEIGHT).toSp() },
                            fontFeatureSettings = "tnum",
                        ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/** The two font sizes of a label. */
internal data class FloorPlanLabelSizes(
    val number: Dp,
    val seats: Dp,
)

/**
 * The design's proportions for a [cell]-sized grid (number 2/3 of a cell, seats 11/24), each limited so a long
 * name or seats text still fits the card [width]. Pure so it can be tested.
 */
internal fun labelSizes(
    cell: Dp,
    width: Dp,
    numberLength: Int,
    seatsLength: Int,
): FloorPlanLabelSizes {
    val number = minOf(cell * NUMBER_PER_CELL, width / (numberLength.coerceAtLeast(1) * CHAR_WIDTH_RATIO)).coerceAtLeast(MIN_FONT_SIZE)
    val seats = minOf(cell * SEATS_PER_CELL, width / (seatsLength.coerceAtLeast(1) * CHAR_WIDTH_RATIO)).coerceAtLeast(MIN_FONT_SIZE)
    return FloorPlanLabelSizes(number, seats)
}
