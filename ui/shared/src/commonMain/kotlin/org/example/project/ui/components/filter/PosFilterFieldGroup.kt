package org.example.project.ui.components.filter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.ui.theme.PosSpacing

/**
 * One captioned field of a [PosFilterPanel] — a period, a chip group, a segmented row — with the
 * caption above it in the panel's label style ("Zeitraum": `PosPatternKeys.filterPeriod`).
 */
@Composable
fun PosFilterFieldGroup(
    caption: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
        Text(text = caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
