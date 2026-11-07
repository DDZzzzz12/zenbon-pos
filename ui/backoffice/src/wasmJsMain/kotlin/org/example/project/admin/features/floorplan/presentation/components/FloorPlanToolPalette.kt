package org.example.project.admin.features.floorplan.presentation.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.floorplan.presentation.FloorPlanTool
import org.example.project.core.presentation.strings.str

/** How the palette sits: a 176dp column beside the canvas, one row of five above it, or five small tiles on a phone. */
enum class FloorPlanPaletteLayout { COLUMN, ROW, TILES }

/** Width of the wide layout's palette column. */
val FloorPlanPaletteColumnWidth = 176.dp

/**
 * The "Hinzufügen" palette: Tisch, Wand, Tür, Fenster, Theke. Every tile is a 48dp+ outlined target;
 * [enabled] false (no location chosen yet) dims them. The column form also carries the placement hint.
 */
@Composable
fun FloorPlanToolPalette(
    layout: FloorPlanPaletteLayout,
    enabled: Boolean,
    onTool: (FloorPlanTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (layout) {
        FloorPlanPaletteLayout.COLUMN ->
            Column(modifier.width(FloorPlanPaletteColumnWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = str(AdminStringKeys.FloorPlanPaletteHeading).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                )
                FloorPlanTool.entries.forEach { tool -> ToolTile(tool, enabled, inline = true, onTool, Modifier.fillMaxWidth()) }
                Text(
                    text = str(AdminStringKeys.FloorPlanPaletteHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        FloorPlanPaletteLayout.ROW ->
            Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloorPlanTool.entries.forEach { tool -> ToolTile(tool, enabled, inline = true, onTool, Modifier.weight(1f)) }
            }
        FloorPlanPaletteLayout.TILES ->
            Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloorPlanTool.entries.forEach { tool -> ToolTile(tool, enabled, inline = false, onTool, Modifier.weight(1f)) }
            }
    }
}

/** One tile: icon beside the label ([inline]) or above it. */
@Composable
private fun ToolTile(
    tool: FloorPlanTool,
    enabled: Boolean,
    inline: Boolean,
    onTool: (FloorPlanTool) -> Unit,
    modifier: Modifier,
) {
    val label = str(tool.labelKey)
    val base =
        modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .clickable(enabled = enabled) { onTool(tool) }
            .defaultMinSize(minHeight = if (inline) 48.dp else 64.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    if (inline) {
        Row(base, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Icon(tool.icon, contentDescription = null, modifier = Modifier.padding(0.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    } else {
        Column(base, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
            Icon(tool.icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}

private const val DISABLED_ALPHA = 0.38f
