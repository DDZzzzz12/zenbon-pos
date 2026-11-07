package org.example.project.admin.features.dashboard.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.theme.LocalPosColors
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosLayout

/**
 * One figure on the dashboard: a bordered square tile, label over value.
 *
 * Neutral by default — a coloured accent on every tile is decoration. Only [critical] (an overdue
 * count) is coloured: a 4 dp error stripe, label and value in `pos-error`, so the colour always
 * carries a word. A tile with [onClick] is clickable as a whole and ends in a chevron. The label
 * wraps, it is never truncated.
 */
@Composable
fun DashboardTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    critical: Boolean = false,
) {
    val criticalColor = if (critical) LocalPosColors.current.posError else null
    val labelColor = criticalColor ?: MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = criticalColor ?: MaterialTheme.colorScheme.onSurface
    val valueStyle =
        if (LocalPosLayout.current == PosLayout.Large) {
            MaterialTheme.typography.displaySmall
        } else {
            MaterialTheme.typography.headlineSmall
        }
    PosCard(
        modifier = modifier,
        onClick = onClick,
        variant = PosCardVariant.Plain,
        accentColor = criticalColor,
    ) {
        body {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = labelColor,
                    modifier = Modifier.weight(1f),
                )
                if (onClick != null) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            PosAutoSizeText(
                text = value,
                style = valueStyle.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Bold,
                color = valueColor,
            )
        }
    }
}
