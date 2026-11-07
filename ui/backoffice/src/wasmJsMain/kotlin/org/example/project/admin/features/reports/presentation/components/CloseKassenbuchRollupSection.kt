package org.example.project.admin.features.reports.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.MonthlyCloseKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.getName
import org.example.project.core.utils.shownBookingText
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.theme.PosSpacing

/**
 * Opening balance, the period's cash-book entries grouped by type, and the closing balance. Every
 * amount is the server's own signed string (`datevAmountString`, `subtotal`) — the document sign is
 * already applied there, so nothing is negated or re-summed here; it is only formatted for display
 * with the response's currency.
 */
@Composable
fun CloseKassenbuchRollupSection(
    rollup: MonthlyCloseDto.KassenbuchRollupDto,
    currencyCode: String,
    modifier: Modifier = Modifier,
) {
    PosCard(modifier = modifier.fillMaxWidth()) {
        body {
            Text(
                text = str(MonthlyCloseKeys.sectionKassenbuchRollup),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            BalanceRow(str(MonthlyCloseKeys.openingBalanceLabel), formatMoneyForDisplay(rollup.openingBalance, currencyCode))
            HorizontalDivider()
            rollup.groups.forEach { group -> GroupRow(group, currencyCode) }
            HorizontalDivider()
            BalanceRow(
                str(MonthlyCloseKeys.closingBalanceLabel),
                formatMoneyForDisplay(rollup.closingBalance, currencyCode),
                emphasized = true,
            )
        }
    }
}

@Composable
private fun BalanceRow(
    label: String,
    amount: String,
    emphasized: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = amount,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun GroupRow(
    group: MonthlyCloseDto.KassenbuchRollupGroupDto,
    currencyCode: String,
) {
    var expanded by remember { mutableStateOf(false) }
    val language = LocalAppLanguage.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = PosSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // The server sends the enum name ("CASH_SALE"); show its localized label.
                text = KassenbuchEntryType.entries.firstOrNull { it.name == group.entryType }?.getName(language) ?: group.entryType,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatMoneyForDisplay(group.subtotal, currencyCode),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                HorizontalDivider()
                group.entries.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.belegtext.shownBookingText(), style = MaterialTheme.typography.bodyMedium)
                            entry.belegNr?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = entry.belegDatum,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = datevAmountForDisplay(entry.datevAmountString, currencyCode),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }
}
