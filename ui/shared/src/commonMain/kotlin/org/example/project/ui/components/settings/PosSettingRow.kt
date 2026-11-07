package org.example.project.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.ui.theme.PosSpacing

/** Minimum row height (design token `setting-row-min-height`); the till passes 64dp. */
private val MIN_ROW_HEIGHT = 56.dp

/**
 * One configurable option inside a [PosSettingGroup]: [title], an optional [description] that says
 * what changes and where ("Gilt für alle Kassen nach dem Speichern."), and its [control] at the end
 * — a `Switch`, a value + chevron, a button. Wide controls (a segmented row, a text field) set
 * [stacked] so they sit under the text instead of squeezing it.
 *
 * The row does not manage enablement or saving: pass `enabled` to the control yourself, and follow
 * the group's saving rule (immediate save for a standalone switch, draft + save bar otherwise).
 */
@Composable
fun PosSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    stacked: Boolean = false,
    minHeight: Dp = MIN_ROW_HEIGHT,
    control: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val padding =
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .padding(horizontal = PosSpacing.xl, vertical = PosSpacing.lg)
        if (stacked) {
            Column(modifier = padding, verticalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
                SettingText(title, description)
                control()
            }
        } else {
            Row(
                modifier = padding,
                horizontalArrangement = Arrangement.spacedBy(PosSpacing.xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) { SettingText(title, description) }
                control()
            }
        }
    }
}

@Composable
private fun SettingText(
    title: String,
    description: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.xs)) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
