package org.example.project.admin.features.terminal.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.terminal.presentation.TerminalStatusCounts
import org.example.project.admin.features.terminal.presentation.TerminalsState.TerminalFilter
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.ui.theme.LocalPosColors

/**
 * The counters over the whole terminal list (never the filtered view) that double as the status
 * filter: Alle, Aktiv, TSE fehlt, Kopplung offen, Inaktiv. A tile is a toggle: tapping the selected
 * one returns to "Alle". On a phone ([compact]) four tiles in a row (Inaktiv is picked in the Filter
 * panel) with centred figures; wider, five equal tiles with the figure over its label.
 */
@Composable
fun TerminalStatusStrip(
    counts: TerminalStatusCounts,
    selected: TerminalFilter,
    onSelect: (TerminalFilter) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val tiles = if (compact) TerminalStatusCounts.COMPACT_TILES else TerminalFilter.entries
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
    ) {
        tiles.forEach { filter ->
            StatusTile(
                label = str(tileLabelKey(filter, compact)),
                count = counts.countFor(filter),
                countColor = tileCountColor(filter),
                selected = filter == selected,
                compact = compact,
                // The selected tile toggles back to everything; "Alle" itself stays put.
                onClick = { onSelect(if (filter == selected) TerminalFilter.ALL else filter) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

private val COMPACT_TILE_PADDING = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
private val WIDE_TILE_PADDING = PaddingValues(horizontal = 14.dp, vertical = 10.dp)

@Composable
private fun StatusTile(
    label: String,
    count: Int,
    countColor: Color,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val background = if (selected) colors.primaryContainer else colors.background
    val border = BorderStroke(1.dp, if (selected) colors.primary else colors.outline)
    val countStyle: TextStyle = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge
    Column(
        modifier =
            modifier
                .heightIn(min = if (compact) 56.dp else 0.dp)
                .background(background)
                .border(border)
                .semantics { this.selected = selected }
                .clickable(role = Role.Button, onClick = onClick)
                .padding(if (compact) COMPACT_TILE_PADDING else WIDE_TILE_PADDING),
        horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = count.toString(),
            style = countStyle.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Bold,
            color = countColor,
            maxLines = 1,
        )
        Text(
            text = label,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
            fontWeight = if (compact) FontWeight.Normal else FontWeight.SemiBold,
            color = if (compact) colors.onSurfaceVariant else colors.onSurface,
            textAlign = if (compact) TextAlign.Center else TextAlign.Start,
            // Two lines at most: a narrow tile ("Kopplung offen" at 768) wraps its label, never cuts it.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The status colour of a tile's figure: green active, amber missing TSE, blue open pairing, grey inactive. */
@Composable
private fun tileCountColor(filter: TerminalFilter): Color {
    val pos = LocalPosColors.current
    return when (filter) {
        TerminalFilter.ALL -> MaterialTheme.colorScheme.onSurface
        TerminalFilter.ACTIVE -> pos.posAvailable
        TerminalFilter.TSE_MISSING -> MaterialTheme.colorScheme.tertiary
        TerminalFilter.PAIRING_OPEN -> pos.posReserved
        TerminalFilter.INACTIVE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun tileLabelKey(
    filter: TerminalFilter,
    compact: Boolean,
): StringKey = if (compact && filter == TerminalFilter.PAIRING_OPEN) AdminStringKeys.TerminalFilterPairingOpenShort else terminalFilterLabel(filter)
