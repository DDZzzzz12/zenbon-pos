package org.example.project.features.reports.presentation.components.dailyreport

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.StatisticsDashboardKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.formatPercentForDisplay

@Composable
fun KpiCard(
    label: String,
    value: String,
    accentColor: Color,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /**
     * Signed percentage against the like-for-like baseline, e.g. "12.40" or "-3.10". Null renders
     * no chip — which is the correct display when no baseline exists, and is why the source DTO
     * makes this nullable rather than defaulting to zero.
     */
    deltaPercent: String? = null,
    /** Baseline-window label shown beside the chip, e.g. "vs 1.–10. Mai". */
    deltaLabel: String? = null,
    /**
     * Always-rendered qualifier under the value, e.g. "42 · im Zeitraum" or "gesamt". Unlike
     * [deltaLabel] this does not require [deltaPercent] — a card can qualify its own scope without
     * having a baseline to compare against.
     */
    caption: String? = null,
) {
    PosCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        variant = PosCardVariant.Tonal,
        accentColor = accentColor,
    ) {
        body {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PosAutoSizeText(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = accentColor,
            )
            if (deltaPercent != null) {
                DeltaChip(deltaPercent = deltaPercent, deltaLabel = deltaLabel)
            }
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Only clickable cards advertise a detail view.
            if (onClick != null) {
                Text(
                    text = str(StatisticsDashboardKeys.tapForDetails),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Up-arrow-green / down-arrow-red delta against the baseline.
 *
 * Every KPI on the reports screen is a revenue-style figure where up is good, so the sign maps
 * directly to the colour. A later cost KPI (where falling is good) must NOT reuse this rule blindly
 * — hence the explicit note rather than a silent convention.
 */
@Composable
private fun DeltaChip(
    deltaPercent: String,
    deltaLabel: String?,
) {
    val negative = deltaPercent.startsWith("-")
    val tint = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (negative) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = formatPercentForDisplay(deltaPercent, LocalAppLanguage.current),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = tint,
        )
        if (deltaLabel != null) {
            Text(
                text = " $deltaLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}
