/**
 * Shared badge for non-STANDARD kassenbuch line kinds.
 *
 * Renders nothing for STANDARD lines; otherwise shows the localized kind label
 * with an optional payment-type suffix, guarded against overflow.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.ui.components.kassenbuch

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.ui.theme.PosSpacing

/**
 * Badge showing the line kind (and payment type, if any) for non-STANDARD lines.
 *
 * @param kind Line kind; STANDARD renders nothing
 * @param paymentTypeName Optional payment type name appended after the kind label
 */
@Composable
fun LineKindBadge(
    kind: KassenbuchLineKind,
    paymentTypeName: String?,
) {
    if (kind == KassenbuchLineKind.STANDARD) return
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = kindLabel(kind) + (paymentTypeName?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = PosSpacing.sm, vertical = 2.dp),
        )
    }
}
