package org.example.project.admin.core.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.theme.PosSpacing

/** Vertical breathing room around the empty state, so it reads as a state and not as a missing row. */
private val VERTICAL_PADDING = 48.dp

private val ICON_SIZE = 36.dp

/**
 * What a list shows instead of rows.
 *
 * The eight Belegfluss screens each showed one grey sentence here, which tells an operator that
 * there is nothing without telling them what to do about it. [actionText] is the way out — reset the
 * filter, upload a file, create the first record — and is omitted only where there genuinely is no
 * next step.
 *
 * Every string is localized by the caller; this composable never calls `str` itself, so it stays
 * usable by a screen whose keys live in its own feature.
 */
@Composable
fun AdminListEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    hint: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = VERTICAL_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PosSpacing.md),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(ICON_SIZE),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = message,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionText != null && onAction != null) {
            PosSecondaryButton(text = actionText, onClick = onAction)
        }
    }
}
