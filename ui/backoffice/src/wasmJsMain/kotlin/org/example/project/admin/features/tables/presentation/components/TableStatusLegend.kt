package org.example.project.admin.features.tables.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.tables.presentation.TableStatusCounts
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str

/** "11 Tische · ● 6 frei · ● 3 besetzt · ● 2 reserviert": the count, then one dotted entry per status. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TableStatusLegend(
    counts: TableStatusCounts,
    modifier: Modifier = Modifier,
) {
    val total = if (counts.total == 1) AdminStringKeys.TablesResultCountOne else AdminStringKeys.TablesResultCount
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        LegendText(str(total, counts.total.toString()))
        LegendEntry(TableStatus.FREE, AdminStringKeys.TablesLegendFree, counts.free)
        LegendEntry(TableStatus.OCCUPIED, AdminStringKeys.TablesLegendOccupied, counts.occupied)
        LegendEntry(TableStatus.RESERVED, AdminStringKeys.TablesLegendReserved, counts.reserved)
    }
}

@Composable
private fun LegendEntry(
    status: TableStatus,
    key: StringKey,
    count: Int,
) {
    val dot: Color = tableStatusColors(status).first
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        LegendText(str(key, count.toString()))
    }
}

@Composable
private fun LegendText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
