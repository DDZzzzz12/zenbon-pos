package org.example.project.admin.features.floorplan.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.floorplan.presentation.FloorPlanEditorViewModel
import org.example.project.admin.features.floorplan.presentation.FloorPlanSelection
import org.example.project.admin.features.floorplan.presentation.FloorPlanSelectionInfo
import org.example.project.admin.features.floorplan.presentation.selectionInfo
import org.example.project.admin.features.tables.presentation.components.tableStatusColors
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.getName
import org.example.project.ui.components.PosDangerOutlinedButton
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton

/** Where the selection controls sit: a 300dp inspector beside the canvas, a bar under it, or a phone card. */
enum class FloorPlanSelectionLayout { INSPECTOR, BAR, CARD }

/** Width of the wide layout's inspector. */
val FloorPlanInspectorWidth = 300.dp

/**
 * Size, rotation, position and the table actions for the current selection. Spatial properties live on
 * the plan, so this is the only place a footprint and angle can be edited; the controls dispatch to the
 * view model by the selected id, which clamps the size and normalises the angle, so they only nudge by
 * ±1 cell / +90°. Never a sibling that resizes the canvas pane (adminApp rule: the plan's cell size
 * derives from the pane): the inspector has constant width and the bar/card sit under a fixed-height canvas.
 *
 * @param onEditTable "Nummer und Plätze ändern" for a table; null hides it.
 * @param onDelete the confirmed delete of the selection.
 */
@Composable
fun FloorPlanSelectionPanel(
    editorVm: FloorPlanEditorViewModel,
    layout: FloorPlanSelectionLayout,
    onEditTable: ((tableId: Long) -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sel = editorVm.state.selection
    val info = editorVm.state.selectionInfo()
    val actions =
        info?.let {
            SelectionActions(
                rotate = { rotateSelection(editorVm, sel, it) },
                resize = { w, h -> resizeSelection(editorVm, sel, w, h) },
                edit = it.tableId?.let { id -> onEditTable?.let { edit -> { edit(id) } } },
                delete = onDelete,
            )
        }
    Column(modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant).background(MaterialTheme.colorScheme.surface)) {
        if (info == null || actions == null) {
            EmptySelection(layout)
        } else {
            when (layout) {
                FloorPlanSelectionLayout.INSPECTOR -> Inspector(info, actions)
                FloorPlanSelectionLayout.BAR -> Bar(info, actions)
                FloorPlanSelectionLayout.CARD -> Card(info, actions)
            }
        }
    }
}

private class SelectionActions(
    val rotate: () -> Unit,
    val resize: (Int, Int) -> Unit,
    val edit: (() -> Unit)?,
    val delete: () -> Unit,
)

private fun rotateSelection(
    vm: FloorPlanEditorViewModel,
    sel: FloorPlanSelection?,
    info: FloorPlanSelectionInfo,
) {
    when (sel) {
        is FloorPlanSelection.Table -> vm.rotateTable(sel.id, info.rotation + 90f)
        is FloorPlanSelection.Element -> vm.rotateElement(sel.id, info.rotation + 90f)
        null -> Unit
    }
}

private fun resizeSelection(
    vm: FloorPlanEditorViewModel,
    sel: FloorPlanSelection?,
    width: Int,
    height: Int,
) {
    when (sel) {
        is FloorPlanSelection.Table -> vm.resizeTable(sel.id, width, height)
        is FloorPlanSelection.Element -> vm.resizeElement(sel.id, width, height)
        null -> Unit
    }
}

@Composable
private fun EmptySelection(layout: FloorPlanSelectionLayout) {
    Text(
        text = str(AdminStringKeys.FloorPlanInspectorEmpty),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(if (layout == FloorPlanSelectionLayout.INSPECTOR) 16.dp else 12.dp),
    )
}

// ---- Large: the inspector -------------------------------------------------------------------------

@Composable
private fun Inspector(
    info: FloorPlanSelectionInfo,
    actions: SelectionActions,
) {
    Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Heading(info)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel(AdminStringKeys.FloorPlanSizeInCells)
            StepperRow(str(AdminStringKeys.FloorPlanWidth), info.width, info.minSide, 40.dp) { actions.resize(it, info.height) }
            StepperRow(str(AdminStringKeys.FloorPlanHeight), info.height, info.minSide, 40.dp) { actions.resize(info.width, it) }
            Hint(minSizeHint(info))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(str(AdminStringKeys.FloorPlanRotation), style = MaterialTheme.typography.bodyMedium)
                Hint(str(AdminStringKeys.FloorPlanRotationCurrent, info.rotationDegrees.toString()))
            }
            PosSecondaryButton(text = str(AdminStringKeys.FloorPlanRotate90), icon = Icons.Default.Replay, onClick = actions.rotate, height = 40.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(str(AdminStringKeys.FloorPlanPosition), style = MaterialTheme.typography.bodyMedium)
            Hint(str(AdminStringKeys.FloorPlanPositionValue, info.column.toString(), info.row.toString()) + ". " + str(AdminStringKeys.FloorPlanPositionHint))
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        actions.edit?.let {
            PosSecondaryButton(
                text = str(AdminStringKeys.FloorPlanEditNumberSeats),
                icon = Icons.Default.Edit,
                onClick = it,
                modifier = Modifier.fillMaxWidth(),
                height = 40.dp,
            )
        }
        PosDangerOutlinedButton(
            text = if (info.isTable) str(AdminStringKeys.FloorPlanDeleteTable) else str(AdminStringKeys.FloorPlanDeleteElement),
            icon = Icons.Default.Delete,
            onClick = actions.delete,
            modifier = Modifier.fillMaxWidth(),
            height = 40.dp,
        )
    }
}

// ---- Medium: the bar ------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Bar(
    info: FloorPlanSelectionInfo,
    actions: SelectionActions,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Heading(info, inline = true)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            StepperRow(str(AdminStringKeys.FloorPlanWidth), info.width, info.minSide, 40.dp, inline = true) { actions.resize(it, info.height) }
            StepperRow(str(AdminStringKeys.FloorPlanHeight), info.height, info.minSide, 40.dp, inline = true) { actions.resize(info.width, it) }
            PosSecondaryButton(text = str(AdminStringKeys.FloorPlanRotate90), icon = Icons.Default.Replay, onClick = actions.rotate, height = 40.dp)
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.edit?.let {
                PosSecondaryButton(text = str(AdminStringKeys.FloorPlanEditNumberSeats), icon = Icons.Default.Edit, onClick = it, height = 40.dp)
            }
            PosDangerOutlinedButton(text = str(AdminStringKeys.FloorPlanDeleteShort), icon = Icons.Default.Delete, onClick = actions.delete, height = 40.dp)
        }
    }
}

// ---- Compact: the card ----------------------------------------------------------------------------

@Composable
private fun Card(
    info: FloorPlanSelectionInfo,
    actions: SelectionActions,
) {
    Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading(info, withPosition = true)
        StepperRow(str(AdminStringKeys.FloorPlanWidthCells), info.width, info.minSide, 48.dp) { actions.resize(it, info.height) }
        StepperRow(str(AdminStringKeys.FloorPlanHeightCells), info.height, info.minSide, 48.dp) { actions.resize(info.width, it) }
        Hint(minSizeHint(info))
        PosSecondaryButton(
            text = str(AdminStringKeys.FloorPlanRotate90Current, info.rotationDegrees.toString()),
            icon = Icons.Default.Replay,
            onClick = actions.rotate,
            modifier = Modifier.fillMaxWidth(),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val edit = actions.edit
            if (edit != null) {
                PosSecondaryButton(text = str(AdminStringKeys.FloorPlanEditShort), icon = Icons.Default.Edit, onClick = edit, modifier = Modifier.weight(1f))
            }
            PosDangerOutlinedButton(
                text = str(AdminStringKeys.FloorPlanDeleteShort),
                icon = Icons.Default.Delete,
                onClick = actions.delete,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---- Shared pieces --------------------------------------------------------------------------------

@Composable
private fun minSizeHint(info: FloorPlanSelectionInfo): String =
    if (info.isTable) str(AdminStringKeys.FloorPlanMinSizeTable) else str(AdminStringKeys.FloorPlanMinSizeElement)

/** "Tisch 3" with status and seats under it (inspector, card), or on one line ([inline], the bar). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Heading(
    info: FloorPlanSelectionInfo,
    inline: Boolean = false,
    withPosition: Boolean = false,
) {
    val title = if (info.isTable) str(AdminStringKeys.TablesRowTitle, info.tableNumber.orEmpty()) else elementName(info.elementType)
    val seats = info.seats?.takeIf { it > 0 }?.let { str(AdminStringKeys.FloorPlanSeatsLine, it.toString()) }
    val position = if (withPosition) str(AdminStringKeys.FloorPlanPositionValue, info.column.toString(), info.row.toString()) else null
    val meta = listOfNotNull(seats, position).joinToString(" · ")
    val line: @Composable () -> Unit = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            info.tableStatus?.let { StatusDotWord(it) }
            if (meta.isNotEmpty()) Hint(meta, bare = true)
        }
    }
    if (inline) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            line()
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            line()
        }
    }
}

@Composable
private fun elementName(type: String?): String =
    when (type) {
        "WALL" -> str(AdminStringKeys.FloorPlanElementWall)
        "DOOR" -> str(AdminStringKeys.FloorPlanElementDoor)
        "WINDOW" -> str(AdminStringKeys.FloorPlanElementWindow)
        "COUNTER" -> str(AdminStringKeys.FloorPlanElementCounter)
        else -> type.orEmpty()
    }

@Composable
private fun StatusDotWord(status: String) {
    val known = TableStatus.entries.firstOrNull { it.name == status }
    val (dot, text) = tableStatusColors(known)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(
            text = known?.getName(LocalAppLanguage.current) ?: status,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = text,
        )
    }
}

@Composable
private fun SectionLabel(key: StringKey) {
    Text(str(key), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Hint(
    text: String,
    bare: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (bare) Modifier else Modifier.fillMaxWidth(),
    )
}

/** "Breite  [−] 6 [+]": the label, then a bordered stepper; [inline] keeps both together instead of pushing the stepper to the end. */
@Composable
private fun StepperRow(
    label: String,
    value: Int,
    min: Int,
    size: Dp,
    inline: Boolean = false,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = if (inline) Modifier else Modifier.fillMaxWidth(),
        horizontalArrangement = if (inline) Arrangement.spacedBy(8.dp) else Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperButton(Icons.Default.Remove, label, size, enabled = value > min) { onChange(value - 1) }
            Text(
                value.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(size),
            )
            StepperButton(Icons.Default.Add, label, size, enabled = true) { onChange(value + 1) }
        }
    }
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    size: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(Modifier.border(1.dp, MaterialTheme.colorScheme.outline)) {
        PosIconButton(icon = icon, contentDescription = description, onClick = onClick, enabled = enabled, size = size)
    }
}
