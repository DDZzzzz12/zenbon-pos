package org.example.project.ui.components.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.ui.theme.PosSpacing

/**
 * A titled card of related [PosSettingRow]s (Zenbon "SettingRow" pattern) — one card per group,
 * never one card per setting.
 *
 * [danger] marks the final "Gefahrenbereich" group (title `str(PosPatternKeys.settingsDangerZone)`):
 * `error` title and border, for destructive or fiscal actions that open a confirmation dialog.
 *
 * Saving rule: a group either saves each control immediately or collects into a draft saved with a
 * `PosSaveBar` — never both in one group.
 */
@Composable
fun PosSettingGroup(
    title: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = PosSpacing.xs),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainer,
            // Each row draws a hairline at its top edge; the card's border is drawn over the first
            // row's, so rows need no "am I first" flag.
            border = BorderStroke(1.dp, accent),
        ) {
            Column(content = content)
        }
    }
}
