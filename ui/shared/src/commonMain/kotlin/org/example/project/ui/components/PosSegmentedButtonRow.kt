package org.example.project.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.ui.theme.PosSpacing

/**
 * A flat single-choice segmented control: options butted together in one bordered, rounded strip with
 * hairline dividers, the selected one filled with a subtle accent tint and its label in the accent
 * colour. Deliberately lighter than Material 3's `SegmentedButton` — no filled-tonal container, no
 * check icon, tighter padding — so it reads as a compact toggle rather than a row of buttons.
 *
 * Generic and reusable: pass the options, the selected one, and a (composable) label lambda so the
 * caller can localise via `str(...)`. Segments size to their content, matching a natural toggle.
 * [optionIcon] is optional and null for most callers — the theme picker is the one place where the
 * icon carries meaning the label does not.
 * Colours come from the ambient [MaterialTheme] so it themes correctly in both clients (adminApp
 * provides MaterialTheme fallbacks; composeApp its PosTheme).
 */
@Composable
fun <T> PosSegmentedButtonRow(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    optionIcon: (@Composable (T) -> ImageVector?)? = null,
    optionLabel: @Composable (T) -> String,
) {
    val shape = MaterialTheme.shapes.small
    val outline = MaterialTheme.colorScheme.outline
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier =
            modifier
                .height(IntrinsicSize.Min)
                .clip(shape)
                .border(1.dp, outline, shape)
                .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(dividerColor),
                )
            }
            val selected = option == selectedOption
            val background =
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    Color.Transparent
                }
            val contentColor =
                when {
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            Box(
                // background before selectable so the ripple draws on top of the tint, not under it.
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .background(background)
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onOptionSelected(option) },
                        ).padding(horizontal = PosSpacing.lg, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(PosSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    optionIcon?.invoke(option)?.let { vector ->
                        Icon(
                            imageVector = vector,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        text = optionLabel(option),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = contentColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
