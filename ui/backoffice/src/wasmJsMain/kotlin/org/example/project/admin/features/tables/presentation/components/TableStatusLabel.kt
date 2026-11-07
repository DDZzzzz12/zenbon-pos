package org.example.project.admin.features.tables.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.models.RTable
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.getName
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.ui.theme.LocalPosColors

/** The status dot and text colours: green, amber (ink tone for the text) and blue; an unknown status is neutral. */
@Composable
internal fun tableStatusColors(status: TableStatus?): Pair<Color, Color> {
    val colors = LocalPosColors.current
    return when (status) {
        TableStatus.FREE -> colors.posAvailable to colors.posAvailable
        TableStatus.OCCUPIED -> colors.posOccupied to colors.posOccupiedInk
        TableStatus.RESERVED -> colors.posReserved to colors.posReserved
        null -> MaterialTheme.colorScheme.outline to MaterialTheme.colorScheme.onSurface
    }
}

/** The table's status as dot + word; null for a status this client does not know. */
internal fun RTable.statusOrNull(): TableStatus? = TableStatus.entries.firstOrNull { it.name == tableStatus }

/**
 * Dot + word (a colour never stands alone), then one quiet line: "Inaktiv" for a table out of service,
 * otherwise, for an occupied table, who serves it and what is open ("MA 104 · 38,50 €"). [showNote] false
 * leaves the line to the host (the phone row prints it under the table's own text).
 */
@Composable
fun TableStatusLabel(
    table: RTable,
    modifier: Modifier = Modifier,
    showNote: Boolean = true,
) {
    val status = table.statusOrNull()
    val (dot, text) = tableStatusColors(status)
    val note = if (showNote) tableStatusNote(table, status) else null
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text(
                text = status?.getName(LocalAppLanguage.current) ?: table.tableStatus,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = text,
            )
        }
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The note under the status word, null when there is nothing to add. */
@Composable
internal fun tableStatusNote(
    table: RTable,
    status: TableStatus? = table.statusOrNull(),
): String? {
    if (!table.isActive) return str(SettingsKeys.inactive)
    if (status != TableStatus.OCCUPIED) return null
    val waiter = table.currentEmployeeNumber?.let { str(AdminStringKeys.TablesWaiter, it) }
    val amount = table.openAmount?.toFormattedCurrency()
    return listOfNotNull(waiter, amount).joinToString(" · ").ifEmpty { null }
}
