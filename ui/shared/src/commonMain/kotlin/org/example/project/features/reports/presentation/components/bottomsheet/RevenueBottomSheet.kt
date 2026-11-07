package org.example.project.features.reports.presentation.components.bottomsheet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.presentation.strings.keys.StatisticsDashboardKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.isZeroAmountDisplay
import org.example.project.features.reports.presentation.components.dailyreport.PaymentDotsRow
import org.example.project.ui.theme.LocalPosColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RevenueBottomSheet(
    totalRevenue: String,
    billedRevenue: String,
    refundRevenue: String,
    totalTips: String,
    totalDiscounts: String,
    payments: List<PaymentDto.PaymentSummary>,
    onDismiss: () -> Unit,
    receivableCleared: String? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
        ) {
            Text(
                text = str(StatisticsDashboardKeys.revenueBreakdown),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatItem(
                    label = str(StatisticsDashboardKeys.totalRevenue),
                    value = totalRevenue,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                StatItem(
                    label = str(StatisticsDashboardKeys.billedRevenue),
                    value = billedRevenue,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatItem(
                    label = str(StatisticsDashboardKeys.tips),
                    value = totalTips,
                    color = LocalPosColors.current.posAvailable,
                    modifier = Modifier.weight(1f),
                )
                StatItem(
                    label = str(StatisticsDashboardKeys.discounts),
                    value = totalDiscounts,
                    color = amountColor(totalDiscounts),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatItem(
                    label = str(StatisticsDashboardKeys.refunds),
                    value = refundRevenue,
                    color = amountColor(refundRevenue),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            PaymentDotsRow(payments = payments)
            // A receivable collected at the till is in the payment methods above but not in the
            // revenue: the turnover was booked when the invoice was issued (as on the Z-Bon).
            receivableCleared?.let { cleared ->
                Spacer(modifier = Modifier.height(12.dp))
                StatItem(
                    label = str(ReportsKeys.receivableCleared),
                    value = cleared,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = str(ReportsKeys.receivableClearedNote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(end = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

/** Red only for a real amount; a zero is not an alarm. */
@Composable
private fun amountColor(display: String): Color = if (isZeroAmountDisplay(display)) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
