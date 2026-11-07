package org.example.project.admin.core.presentation.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val SELECTED_BAR_WIDTH = 3.dp
private val MIN_ROW_HEIGHT = 48.dp

/**
 * The one selectable row of an adminApp list rail (Ausgangsrechnungen, Zuordnung, Debitoren).
 *
 * Selected = `primaryContainer` fill + a 3dp `primary` bar on the leading edge + `onPrimaryContainer`
 * text; unselected = `surfaceVariant`. Hover tints an unselected row toward the selected fill but never
 * reaches it ([railRowFill]). The bar slot is always 3dp wide (transparent when unselected) so the
 * content does not shift when the selection moves.
 *
 * @param enabled `false` makes the row inert (a queue that is reloading); it keeps its look.
 */
@Composable
fun AdminRailRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val colors = MaterialTheme.colorScheme
    val fill =
        railRowFill(
            selected = selected,
            hovered = hovered && enabled,
            selectedFill = colors.primaryContainer,
            idleFill = colors.surfaceVariant,
        )
    CompositionLocalProvider(LocalContentColor provides if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant) {
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .heightIn(min = MIN_ROW_HEIGHT)
                    .clip(MaterialTheme.shapes.small)
                    .background(fill)
                    .hoverable(interactionSource, enabled)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        enabled = enabled,
                        onClick = onClick,
                    ).semantics { this.selected = selected }
                    .height(IntrinsicSize.Min),
        ) {
            Box(
                Modifier
                    .width(SELECTED_BAR_WIDTH)
                    .fillMaxHeight()
                    .background(if (selected) colors.primary else Color.Transparent),
            )
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                content = content,
            )
        }
    }
}
