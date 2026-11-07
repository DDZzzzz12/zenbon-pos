package org.example.project.ui.components.zBon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalDisplayCurrency
import org.example.project.core.utils.formatCurrencyAmount

/**
 * The Z-Bon's per-promotion breakdown ("Aktionen"): one row per promotion with its name, how often
 * it was applied and what it deducted, then the promotions total. Renders nothing when the Z-Bon
 * carries no breakdown.
 *
 * Shared by the till's Z-Bon panel (composeApp) and the back office's (shared-ui [ZBonDetailPanel]),
 * so both show what the printed Z-Bon prints. Amounts use the caller's [LocalDisplayCurrency].
 */
@Composable
fun ZBonPromotionBreakdown(
    zBonData: ZBonDto.ZBonResponseDto,
    modifier: Modifier = Modifier,
) {
    val currencyCode = LocalDisplayCurrency.current
    val lines = zBonPromotionLines(zBonData, currencyCode)
    if (lines.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = str(ReportsKeys.promotions),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 2.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            HeaderCell(str(ReportsKeys.promotions), Modifier.weight(2f), TextAlign.Start)
            HeaderCell(str(ReportsKeys.timesApplied), Modifier.weight(1f))
            HeaderCell(str(ReportsKeys.discounts), Modifier.weight(1.5f), TextAlign.End)
        }
        lines.forEach { line ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = line.name,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(2f),
                    maxLines = 1,
                )
                Text(
                    text = line.timesApplied,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = line.totalDeducted,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1.5f),
                    textAlign = TextAlign.End,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 2.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = str(ReportsKeys.promotionsTotal),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatCurrencyAmount(zBonData.totalPromotions, currencyCode),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HeaderCell(
    text: String,
    modifier: Modifier,
    textAlign: TextAlign = TextAlign.Center,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign,
        modifier = modifier,
    )
}
