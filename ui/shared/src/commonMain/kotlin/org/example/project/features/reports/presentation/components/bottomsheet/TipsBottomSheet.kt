package org.example.project.features.reports.presentation.components.bottomsheet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.presentation.strings.keys.StatisticsDashboardKeys
import org.example.project.core.presentation.strings.str
import org.example.project.money.Decimal
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.theme.LocalPosColors
import org.example.project.ui.theme.PosSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipsBottomSheet(
    totalTips: String,
    averagePerBill: String,
    employeeGroups: List<StatisticsDto.EmployeeGroupDto>,
    currency: String,
    canReportShopWide: Boolean,
    onLoadTipEarners: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The groups behind the earners block are not loaded by the default tab; ask for them as the sheet opens.
    LaunchedEffect(canReportShopWide) { if (canReportShopWide) onLoadTipEarners() }

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
                text = str(StatisticsDashboardKeys.tipsDetail),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TipsStatItem(
                    label = str(StatisticsDashboardKeys.tips),
                    value = totalTips,
                    color = LocalPosColors.current.posAvailable,
                    modifier = Modifier.weight(1f),
                )
                TipsStatItem(
                    label = str(StatisticsDashboardKeys.averagePerBill),
                    value = averagePerBill,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            if (canReportShopWide && employeeGroups.isNotEmpty()) {
                Spacer(modifier = Modifier.height(PosSpacing.xl))
                Text(
                    text = str(StatisticsDashboardKeys.topTipEarners),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                employeeGroups.rankedByTips().forEach { group ->
                    EmployeeTipRow(employeeGroup = group, currency = currency)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun TipsStatItem(
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

@Composable
private fun EmployeeTipRow(
    employeeGroup: StatisticsDto.EmployeeGroupDto,
    currency: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = employeeGroup.shownName(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        // The accent marks an actual tip; a 0,00 row is not highlighted (UI review nit).
        val hasTips = Decimal.parseOrNull(employeeGroup.totalTips)?.isZero == false
        Text(
            text = formatMoneyForDisplay(employeeGroup.totalTips, currency),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (hasTips) FontWeight.SemiBold else FontWeight.Normal,
            color = if (hasTips) LocalPosColors.current.posAvailable else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
