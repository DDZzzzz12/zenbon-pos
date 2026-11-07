package org.example.project.ui.components.form

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.ui.theme.PosSpacing

/**
 * The small-caps heading of a section inside one long form ("GRUNDDATEN", "PFAND"), optionally
 * preceded by a divider. Lighter than [PosFormSection], which stacks full-width titled blocks.
 */
@Composable
fun PosFormSectionLabel(
    title: String,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(bottom = PosSpacing.lg))
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
