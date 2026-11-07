package org.example.project.ui.components.beleg

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import org.example.project.ui.theme.LocalPosColors
import org.example.project.ui.theme.PosSpacing

/** Content width below which the action drops under the sentence (the design's Compact variant). */
private val STACK_BELOW = 600.dp

/**
 * The "Nächster Schritt" bar at the foot of a Belegfluss list: one sentence and one action.
 *
 * [text] may carry `**bold**` markers ([boldMarkupToAnnotated]). The action is omitted when
 * [actionLabel] is null (nothing left to do, or the caller may not act), which leaves a sentence-only
 * bar. The action is a 48dp-high text button on phone widths, 40dp wider.
 */
@Composable
fun PosNextStepBar(
    text: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
        val stacked = maxWidth < STACK_BELOW
        val sentence: @Composable (Modifier) -> Unit = { m ->
            Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PosSpacing.lg)) {
                Icon(
                    imageVector = Icons.Filled.Lightbulb,
                    contentDescription = null,
                    tint = LocalPosColors.current.posOccupied,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = boldMarkupToAnnotated(text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        val action: @Composable () -> Unit = {
            if (actionLabel != null) {
                TextButton(
                    onClick = onAction,
                    shape = RectangleShape,
                    modifier = Modifier.heightIn(min = if (stacked) 48.dp else 40.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(actionLabel, modifier = Modifier.padding(start = PosSpacing.md))
                }
            }
        }
        if (stacked) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(PosSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PosSpacing.md),
            ) {
                sentence(Modifier.fillMaxWidth())
                action()
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = PosSpacing.xl, vertical = PosSpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PosSpacing.lg),
            ) {
                sentence(Modifier.weight(1f))
                action()
            }
        }
    }
}
