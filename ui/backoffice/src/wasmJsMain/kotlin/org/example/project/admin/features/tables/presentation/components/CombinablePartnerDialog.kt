package org.example.project.admin.features.tables.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.models.RTable
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.guard.rememberGuardedDismiss
import kotlin.math.abs

/**
 * The tables that may be merged with [table] at the till: active, real tables of the same location
 * (the rule the chips used), the selected ones first, then by how close their number is to [table]'s.
 */
internal fun combinableCandidates(
    table: RTable,
    all: List<RTable>,
    selectedIds: Set<Long>,
): List<RTable> {
    val number = table.tableNumber.filter { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
    return all
        .filter { it.id != table.id && it.isActive && !it.isVirtual && it.locationTypeId == table.locationTypeId }
        .sortedWith(
            compareByDescending<RTable> { it.id in selectedIds }.thenBy {
                abs((it.tableNumber.filter { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE) - number)
            },
        )
}

/** "Tisch 7: Kombinierbar mit": a hint, "N ausgewählt" and a checkbox row per candidate (48dp, square boxes). */
@Composable
fun CombinablePartnerDialog(
    table: RTable,
    allTables: List<RTable>,
    savedIds: Set<Long>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val close = rememberGuardedDismiss(dirty = selectedIds != savedIds, onClose = onDismiss)
    val candidates = combinableCandidates(table, allTables, selectedIds)
    PosDialog(
        onDismiss = close,
        title = str(AdminStringKeys.TablesCombineTitle, table.tableNumber),
        kind = PosDialogKind.Form,
        primary = PosDialogAction(str(CommonKeys.save), onSave),
    ) {
        Text(
            text = str(AdminStringKeys.TablesCombineHint, table.tableNumber),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (candidates.isEmpty()) {
            Text(str(SettingsKeys.noCombinablePartners), style = MaterialTheme.typography.bodyMedium)
            return@PosDialog
        }
        Text(
            text = str(AdminStringKeys.TablesCombineSelected, selectedIds.size.toString()),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Column(Modifier.fillMaxWidth()) {
            candidates.forEachIndexed { index, candidate ->
                if (index == 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CandidateRow(candidate, checked = candidate.id in selectedIds, onToggle = { onToggle(candidate.id) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: RTable,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val seats = if (candidate.seats == 1L) str(AdminStringKeys.TablesSeatsCountOne) else str(AdminStringKeys.TablesSeatsCount, candidate.seats.toString())
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column {
            Text(str(AdminStringKeys.TablesRowTitle, candidate.tableNumber), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text("${candidate.locationTypeName} · $seats", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
