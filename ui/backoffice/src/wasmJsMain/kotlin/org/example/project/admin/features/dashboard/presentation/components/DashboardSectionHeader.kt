package org.example.project.admin.features.dashboard.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.theme.LocalPosLayout

/**
 * A section heading (`headline-small`, `title-large` on a phone) with an optional link on the right.
 * A link without a label or handler is simply not shown, so a gated route leaves no dead control.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    linkText: String? = null,
    onLinkClick: (() -> Unit)? = null,
) {
    val style =
        if (LocalPosLayout.current.isCompact) {
            MaterialTheme.typography.titleLarge
        } else {
            MaterialTheme.typography.headlineSmall
        }
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = style)
        if (linkText != null && onLinkClick != null) {
            PosTextButton(text = linkText, onClick = onLinkClick)
        }
    }
}
