package org.example.project.admin.features.voucher.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.util.formatDashboardDateTime
import org.example.project.admin.features.voucher.presentation.redeemedFraction
import org.example.project.admin.features.voucher.presentation.runningBalances
import org.example.project.admin.features.voucher.presentation.voucherInvalidationSourceKey
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.VoucherManagementKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.EMPTY_VALUE
import org.example.project.money.Decimal
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSideSheet
import org.example.project.ui.components.formatMoneyForDisplay

/**
 * Negates a server money string exactly once, regardless of whether it already carries a sign.
 * Blind string concatenation (`"-$amount"`) would double up on an already-negative amount.
 */
private fun negated(amount: String): String = Decimal.parseOrNull(amount)?.let { (-it).toPlainString() } ?: amount

/**
 * Slide-in detail panel. Opens from the row data already in hand and fills [history] behind its own
 * spinner, so the panel never blocks on the history request.
 *
 * Each redemption's closing balance comes from [runningBalances], not from the DTO's
 * `remainingValueAfter`, which the server computes wrongly for every entry.
 */
@Composable
fun VoucherDetailDrawer(
    voucher: VoucherDto.VoucherResponseDto,
    history: VoucherDto.VoucherRedemptionHistoryDto?,
    isHistoryLoading: Boolean,
    currencyCode: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    asSheet: Boolean = false,
) {
    if (asSheet) {
        PosSideSheet(
            onDismiss = onClose,
            title = voucher.voucherNumber,
            headerExtras = { VoucherStatusPill(voucher) },
        ) {
            VoucherDetailBody(voucher, history, isHistoryLoading, currencyCode)
        }
        return
    }
    Surface(
        modifier = modifier.fillMaxHeight(),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = voucher.voucherNumber,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VoucherStatusPill(voucher)
                    PosIconButton(
                        icon = Icons.Default.Close,
                        contentDescription = str(CommonKeys.close),
                        onClick = onClose,
                    )
                }
            }
            VoucherDetailBody(voucher, history, isHistoryLoading, currencyCode)
        }
    }
}

@Composable
private fun VoucherDetailBody(
    voucher: VoucherDto.VoucherResponseDto,
    history: VoucherDto.VoucherRedemptionHistoryDto?,
    isHistoryLoading: Boolean,
    currencyCode: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = formatMoneyForDisplay(voucher.remainingValue, currencyCode),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        // An INVALIDATED voucher's balance was voided by a storno, not spent — a "% redeemed"
        // claim would misrepresent what happened even though the status pill already reads
        // "Storniert". Suppress the progress bar and percentage line entirely in that case.
        if (voucher.status == "INVALIDATED") {
            Text(
                text = str(VoucherManagementKeys.invalidatedNotRedeemed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val fraction = redeemedFraction(voucher.originalValue, voucher.remainingValue)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text =
                    str(
                        AdminStringKeys.VouchersRedeemedPercent,
                        (fraction * 100).toInt().toString(),
                        formatMoneyForDisplay(voucher.originalValue, currencyCode),
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()
        DetailRow(str(VoucherManagementKeys.createdAt), formatDashboardDateTime(voucher.createdAt))
        DetailRow(str(VoucherManagementKeys.employee), voucher.employeeNumber)
        DetailRow(
            str(VoucherManagementKeys.originalValue),
            formatMoneyForDisplay(voucher.originalValue, currencyCode),
        )
        DetailRow(
            str(VoucherManagementKeys.totalRedeemedAmount),
            formatMoneyForDisplay(voucher.totalRedeemed, currencyCode),
        )

        if (voucher.status == "INVALIDATED") {
            HorizontalDivider()
            Text(
                text = str(VoucherManagementKeys.invalidationDetails),
                style = MaterialTheme.typography.labelLarge,
            )
            voucher.invalidatedAt?.let {
                DetailRow(str(VoucherManagementKeys.invalidatedAt), formatDashboardDateTime(it))
            }
            voucher.invalidationReason?.let {
                DetailRow(str(VoucherManagementKeys.invalidationReason), it)
            }
            voucher.invalidationSource?.let { source ->
                val sourceKey = voucherInvalidationSourceKey(source)
                DetailRow(str(VoucherManagementKeys.invalidationSource), if (sourceKey != null) str(sourceKey) else source)
            }
        }

        HorizontalDivider()
        Text(
            text = str(VoucherManagementKeys.redemptionHistory),
            style = MaterialTheme.typography.labelLarge,
        )
        when {
            isHistoryLoading ->
                Box(Modifier.fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() }
            history == null || history.redemptions.isEmpty() ->
                Text(
                    text = str(VoucherManagementKeys.noRedemptions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else -> {
                val balances = runningBalances(voucher.originalValue, history.redemptions)
                history.redemptions.forEach { entry ->
                    val balanceText =
                        balances[entry.redemptionId]?.let { formatMoneyForDisplay(it.toPlainString(), currencyCode) }
                            ?: EMPTY_VALUE
                    DetailRow(
                        label = "${formatDashboardDateTime(entry.redeemedAt)} · ${entry.billNumber ?: EMPTY_VALUE}",
                        value =
                            formatMoneyForDisplay(negated(entry.redeemedAmount), currencyCode) +
                                " → " +
                                balanceText,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
