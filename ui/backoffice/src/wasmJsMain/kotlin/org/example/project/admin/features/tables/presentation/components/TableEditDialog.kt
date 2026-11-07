package org.example.project.admin.features.tables.presentation.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.models.RTable
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.PosPatternKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.PosChangedMarker
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.guard.rememberGuardedDismiss

/** The values the dialog hands back: number, optional seats and the location. */
data class TableEditValues(
    val tableNumber: String,
    val seats: Int?,
    val locationTypeId: Long,
)

/**
 * One table's number, seats and location in a dialog: "Tisch 7 bearbeiten" with a "Geändert" marker while
 * the draft differs, or "Tisch anlegen" when [table] is null. An existing table also gets the
 * "Kombinierbar mit · N Tische" row ([combineCount], [onOpenCombine]) and a red Löschen at the footer's
 * start ([onDelete]). Save stays enabled while the form is invalid: pressing it marks the number field.
 * Leaving with edits asks first (the shared guard).
 */
@Composable
fun TableEditDialog(
    table: RTable?,
    locations: List<Pair<Long, String>>,
    combineCount: Int,
    onSave: (TableEditValues) -> Unit,
    onOpenCombine: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val seedNumber = table?.tableNumber.orEmpty()
    val seedSeats =
        table
            ?.seats
            ?.takeIf { it > 0L }
            ?.toString()
            .orEmpty()
    val seedLocation = table?.locationTypeId ?: locations.firstOrNull()?.first
    var number by remember(table?.id) { mutableStateOf(seedNumber) }
    var seats by remember(table?.id) { mutableStateOf(seedSeats) }
    var locationId by remember(table?.id) { mutableStateOf(seedLocation) }
    var numberError by remember(table?.id) { mutableStateOf(false) }

    val dirty = number != seedNumber || seats != seedSeats || locationId != seedLocation
    val close = rememberGuardedDismiss(dirty = dirty, onClose = onDismiss)
    val title = if (table == null) str(AdminStringKeys.TablesCreateButton) else str(AdminStringKeys.TablesEditTitle, table.tableNumber)
    val submit = {
        val location = locationId
        if (number.isBlank() || location == null) {
            numberError = number.isBlank()
        } else {
            onSave(TableEditValues(number.trim(), seats.toIntOrNull(), location))
        }
    }

    PosDialog(
        onDismiss = close,
        title = title,
        kind = PosDialogKind.Form,
        primary = PosDialogAction(str(CommonKeys.save), submit),
        startAction = if (table != null) PosDialogAction(str(CommonKeys.delete), onDelete) else null,
        startActionIcon = Icons.Default.Delete,
        titleExtras = if (dirty && table != null) ({ PosChangedMarker() }) else null,
    ) {
        PosTextField(
            value = number,
            onValueChange = {
                number = it
                numberError = false
            },
            label = requiredLabel(str(SettingsKeys.tableNumber)),
            modifier = Modifier.fillMaxWidth(),
            isError = numberError,
            errorMessage = if (numberError) str(CommonKeys.fieldRequired) else null,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PosTextField(
                value = seats,
                onValueChange = { input -> if (input.all { it.isDigit() }) seats = input },
                label = str(SettingsKeys.seats),
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
            LocationPicker(locations, locationId, { locationId = it }, Modifier.weight(1f))
        }
        if (table != null) CombineRow(combineCount, onOpenCombine)
        Text(
            text = str(PosPatternKeys.formRequiredNote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LocationPicker(
    locations: List<Pair<Long, String>>,
    selectedId: Long?,
    onSelected: (Long) -> Unit,
    modifier: Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val name = locations.firstOrNull { it.first == selectedId }?.second.orEmpty()
    Box(modifier) {
        PosPickerField(value = name, onClick = { expanded = true }, label = str(SettingsKeys.locationLabel), modifier = Modifier.fillMaxWidth())
        PosDropdown(expanded = expanded, onDismissRequest = { expanded = false }) {
            locations.forEach { (id, label) ->
                PosDropdownItem(text = label, selected = id == selectedId, onClick = {
                    onSelected(id)
                    expanded = false
                })
            }
        }
    }
}

/** "Kombinierbar mit · 2 Tische" with a link icon and a chevron: opens the partner picker. */
@Composable
private fun CombineRow(
    count: Int,
    onClick: () -> Unit,
) {
    val countText =
        when (count) {
            0 -> str(AdminStringKeys.TablesCombineCountNone)
            1 -> str(AdminStringKeys.TablesCombineCountOne)
            else -> str(AdminStringKeys.TablesCombineCount, count.toString())
        }
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(str(SettingsKeys.canCombineWith), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(countText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
