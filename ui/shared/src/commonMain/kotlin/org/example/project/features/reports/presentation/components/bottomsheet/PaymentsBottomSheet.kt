package org.example.project.features.reports.presentation.components.bottomsheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.StatisticsDashboardKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.getLocalizedName
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.ui.theme.PosSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentsBottomSheet(
    payments: List<StatisticsDto.PaymentRecordDto>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
) {
    var localSort by remember { mutableStateOf(StatisticsDto.StatisticsSortOption.RECENT) }

    val sortedPayments =
        remember(payments, localSort) { payments.sortedFor(localSort) }
    // The rows are keyed, so a re-sort would keep the previous first row in view and open the new
    // order part-way down; a changed sort starts at its first row.
    val listState = rememberLazyListState()
    LaunchedEffect(localSort) { listState.scrollToItem(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
        ) {
            Text(
                text = str(StatisticsDashboardKeys.paymentsDetail),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Sort chips — sorts locally, does NOT affect parent bills
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "${str(StatisticsDashboardKeys.sortBy)}:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                SortChip(
                    label = str(StatisticsDashboardKeys.sortRecent),
                    selected = localSort == StatisticsDto.StatisticsSortOption.RECENT,
                    onClick = { localSort = StatisticsDto.StatisticsSortOption.RECENT },
                )
                SortChip(
                    label = str(StatisticsDashboardKeys.sortHighest),
                    selected = localSort == StatisticsDto.StatisticsSortOption.HIGHEST,
                    onClick = { localSort = StatisticsDto.StatisticsSortOption.HIGHEST },
                )
                SortChip(
                    label = str(StatisticsDashboardKeys.sortLowest),
                    selected = localSort == StatisticsDto.StatisticsSortOption.LOWEST,
                    onClick = { localSort = StatisticsDto.StatisticsSortOption.LOWEST },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isLoading) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (sortedPayments.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = str(StatisticsDashboardKeys.noTransactions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(400.dp),
                ) {
                    items(sortedPayments, key = { it.id }) { payment ->
                        PaymentRecordRow(payment)
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentRecordRow(payment: StatisticsDto.PaymentRecordDto) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = PosSpacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "#${payment.billNumber}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${payment.getLocalizedName(LocalAppLanguage.current)} · ${formatPaymentTime(payment.paymentTime)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = payment.amount.toFormattedCurrency(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SortChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
    )
}

private fun formatPaymentTime(dateTimeString: String): String =
    try {
        val dateTime = LocalDateTime.parse(dateTimeString)
        "${dateTime.hour.toString().padStart(2, '0')}:${dateTime.minute.toString().padStart(2, '0')}"
    } catch (_: Exception) {
        dateTimeString.takeLast(5)
    }
