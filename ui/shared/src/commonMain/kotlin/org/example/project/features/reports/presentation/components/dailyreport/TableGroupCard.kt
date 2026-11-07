package org.example.project.features.reports.presentation.components.dailyreport

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.presentation.strings.keys.StatisticsDashboardKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.theme.PosSpacing

@Composable
fun TableGroupCard(
    card: StatisticsDto.TableCardDto,
    sessions: List<StatisticsDto.TableSessionSummaryDto>?,
    isExpanded: Boolean,
    isLoading: Boolean,
    isCompact: Boolean,
    onToggleExpand: () -> Unit,
    onBillClick: (BillDto.BillSummaryDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    PosCard(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        onClick = onToggleExpand,
        variant = PosCardVariant.Tonal,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.medium,
    ) {
        body {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.TableRestaurant,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column {
                        Text(
                            text =
                                if (card.isVirtual) {
                                    card.locationTypeName
                                } else {
                                    "${str(StatisticsDashboardKeys.table)} ${card.tableNumber}"
                                },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Text(
                            text = "${card.totalBillCount} ${str(
                                StatisticsDashboardKeys.billsLabel,
                            )} · ${card.sessionCount} ${str(StatisticsDashboardKeys.sessionsLabel)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = card.totalRevenue.toFormattedCurrency(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        if (card.totalTips.toFormattedCurrency() != "0,00 €") {
                            Text(
                                text = "${str(StatisticsDashboardKeys.logTips)}: ${card.totalTips.toFormattedCurrency()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                            )
                        }
                    }
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Expandable sessions list
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (isLoading) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    } else if (sessions != null) {
                        sessions.forEachIndexed { index, session ->
                            SessionSection(
                                session = session,
                                isCompact = isCompact,
                                onBillClick = onBillClick,
                            )
                            if (index < sessions.lastIndex) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionSection(
    session: StatisticsDto.TableSessionSummaryDto,
    isCompact: Boolean,
    onBillClick: (BillDto.BillSummaryDto) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Session header
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${formatSessionTime(
                    session.startTime,
                )} – ${session.endTime?.let { formatSessionTime(it) } ?: str(StatisticsDashboardKeys.ongoing)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
            )
            Text(
                text = "${session.billCount} ${str(StatisticsDashboardKeys.billsLabel)} · ${session.revenue.toFormattedCurrency()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
            )
        }

        // Session bills
        session.bills.forEachIndexed { index, bill ->
            BillTransactionRow(
                bill = bill,
                isCompact = isCompact,
                onClick = { onBillClick(bill) },
                showDivider = index < session.bills.lastIndex,
                showTableNumber = false,
            )
        }
    }
}

private fun formatSessionTime(dateTimeString: String): String =
    try {
        val dateTime = LocalDateTime.parse(dateTimeString)
        "${dateTime.hour.toString().padStart(2, '0')}:${dateTime.minute.toString().padStart(2, '0')}"
    } catch (e: Exception) {
        dateTimeString.takeLast(5)
    }
