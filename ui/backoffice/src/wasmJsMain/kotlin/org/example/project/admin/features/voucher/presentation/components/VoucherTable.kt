package org.example.project.admin.features.voucher.presentation.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.util.formatDashboardDateTime
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.bill.StornoReason
import org.example.project.core.presentation.strings.keys.BillReceiptKeys
import org.example.project.core.presentation.strings.keys.VoucherManagementKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosAdaptiveColumn
import org.example.project.ui.components.PosAdaptiveTable
import org.example.project.ui.components.PosCardRole
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosContentRow
import org.example.project.ui.components.PosMoneyText
import org.example.project.ui.components.PosTableAlign
import org.example.project.ui.components.PosTableColumn
import org.example.project.ui.components.PosTableMin

/** Voucher rows — used by the Ausgegeben and Aktiv tabs and by search results. */
@Composable
fun VoucherRowsTable(
    vouchers: List<VoucherDto.VoucherResponseDto>,
    currencyCode: String,
    onSelect: (VoucherDto.VoucherResponseDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val columns =
        listOf(
            PosAdaptiveColumn(
                PosTableColumn(str(VoucherManagementKeys.voucherCode), PosTableAlign.Start, weight = 1.2f, minWidth = PosTableMin.Id, nowrap = true),
                PosCardRole.Title,
            ),
            PosAdaptiveColumn(
                PosTableColumn(str(VoucherManagementKeys.createdAt), PosTableAlign.Start, weight = 1.4f, minWidth = PosTableMin.DateTime, nowrap = true),
                PosCardRole.Detail,
            ),
            PosAdaptiveColumn(PosTableColumn(str(VoucherManagementKeys.employee), PosTableAlign.Start, weight = 0.8f), PosCardRole.Detail),
            PosAdaptiveColumn(
                PosTableColumn(str(VoucherManagementKeys.originalValue), PosTableAlign.End, weight = 1f, minWidth = PosTableMin.Money, nowrap = true),
                PosCardRole.Detail,
            ),
            PosAdaptiveColumn(
                PosTableColumn(str(VoucherManagementKeys.remainingValue), PosTableAlign.End, weight = 1f, minWidth = PosTableMin.Money, nowrap = true),
                PosCardRole.Detail,
            ),
            PosAdaptiveColumn(
                PosTableColumn(str(VoucherManagementKeys.status), PosTableAlign.Start, weight = 0.9f, minWidth = PosTableMin.Pill),
                PosCardRole.Trailing,
            ),
        )
    val rows =
        vouchers.map { voucher ->
            PosContentRow(
                cells =
                    listOf(
                        { Text(voucher.voucherNumber, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                        { Text(formatDashboardDateTime(voucher.createdAt), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                        { Text(voucher.employeeNumber) },
                        { PosMoneyText(voucher.originalValue, currencyCode) },
                        { PosMoneyText(voucher.remainingValue, currencyCode) },
                        { VoucherStatusPill(voucher) },
                    ),
                onClick = { onSelect(voucher) },
            )
        }
    // The list sits inside a tonal card, so its phone cards are outlined to stay apart.
    PosAdaptiveTable(columns = columns, rows = rows, modifier = modifier, cardVariant = PosCardVariant.Plain)
}

/** Redemption rows — the Einlösungen tab. A redemption is an event, not a voucher. */
@Composable
fun RedemptionRowsTable(
    redemptions: List<VoucherDto.VoucherRedemptionSummaryDto>,
    currencyCode: String,
    onSelectVoucherNumber: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val columns =
        listOf(
            PosAdaptiveColumn(
                PosTableColumn(str(VoucherManagementKeys.voucherCode), PosTableAlign.Start, weight = 1.2f, minWidth = PosTableMin.Id, nowrap = true),
                PosCardRole.Title,
            ),
            PosAdaptiveColumn(
                PosTableColumn(str(VoucherManagementKeys.redeemedAt), PosTableAlign.Start, weight = 1.4f, minWidth = PosTableMin.DateTime, nowrap = true),
                PosCardRole.Detail,
            ),
            PosAdaptiveColumn(PosTableColumn(str(VoucherManagementKeys.redeemedBy), PosTableAlign.Start, weight = 0.8f), PosCardRole.Detail),
            PosAdaptiveColumn(
                PosTableColumn(str(VoucherManagementKeys.billId), PosTableAlign.Start, weight = 1f, minWidth = PosTableMin.Id, nowrap = true),
                PosCardRole.Detail,
            ),
            PosAdaptiveColumn(
                PosTableColumn(str(AdminStringKeys.VouchersColAmount), PosTableAlign.End, weight = 1f, minWidth = PosTableMin.Money, nowrap = true),
                PosCardRole.Trailing,
            ),
            PosAdaptiveColumn(
                PosTableColumn(str(VoucherManagementKeys.remainingValue), PosTableAlign.End, weight = 1f, minWidth = PosTableMin.Money, nowrap = true),
                PosCardRole.Detail,
            ),
        )
    val rows =
        redemptions.map { redemption ->
            val bill = redemption.billNumber + reversalSuffix(redemption)
            PosContentRow(
                cells =
                    listOf(
                        { Text(redemption.voucherNumber, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                        { Text(formatDashboardDateTime(redemption.redeemedAt), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                        { Text(redemption.redeemedBy) },
                        { Text(bill, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                        { PosMoneyText(redemption.redeemedAmount, currencyCode) },
                        { PosMoneyText(redemption.remainingValue, currencyCode) },
                    ),
                onClick = { onSelectVoucherNumber(redemption.voucherNumber) },
            )
        }
    // The list sits inside a tonal card, so its phone cards are outlined to stay apart.
    PosAdaptiveTable(columns = columns, rows = rows, modifier = modifier, cardVariant = PosCardVariant.Plain)
}

/**
 * " · Storniert" / " · Storniert (Zahlartwechsel)" behind the bill number of a redemption a Storno
 * undid, empty while it stands. admin20 FAIL-B20: such a row read like a standing redemption.
 */
@Composable
private fun reversalSuffix(redemption: VoucherDto.VoucherRedemptionSummaryDto): String =
    when {
        redemption.stands -> ""
        redemption.reversalReason == StornoReason.ZAHLARTWECHSEL -> " · " + str(BillReceiptKeys.relationCancelledRebook)
        else -> " · " + str(BillReceiptKeys.relationCancelled)
    }
