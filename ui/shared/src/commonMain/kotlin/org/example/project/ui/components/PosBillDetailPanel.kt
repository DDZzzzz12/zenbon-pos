package org.example.project.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.BillReceiptKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.PromotionKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.discountToPercentage
import org.example.project.core.utils.enumStrings.getName
import org.example.project.core.utils.formatShortDateTime
import org.example.project.core.utils.getLocalizedName
import org.example.project.core.utils.toPercentage
import org.example.project.money.Decimal
import org.example.project.ui.theme.PosSpacing

/**
 * Comprehensive bill detail panel displaying full receipt information.
 *
 * Content-only composable (no dialog wrapper). The caller decides
 * how to present it (dialog, side panel, full screen, etc.).
 *
 * Displays: bill info, order items, financial summary, payments,
 * voucher redemptions, deleted bill info, TSE QR code, and action buttons.
 *
 * @param bill The bill data to display
 * @param tableNumber Display table number
 * @param currency Currency code used to format money amounts (bill DTO carries none)
 * @param modifier Modifier for the root element
 * @param showSuccessHeader Whether to show the success header banner. Only the created-bill
 *   confirmation sets it, and only it is headed "Rechnung erstellt"; a bill opened to look at it
 *   (Berichte, history) is headed neutrally "Beleg" — a Storno or Neubuchung was never "created" by
 *   the operator reading it.
 * @param isLoading Whether actions are in progress
 * @param onActions Optional callback to open the actions dialog (print, invoice, Storno, Zahlartwechsel, etc.)
 * @param onPrint Optional callback to print this bill directly, without the actions dialog.
 * @param onClose Optional callback for close action
 * @param closeInHeader When true (and [onClose] is set) the title moves into a pinned header row
 *   with a close X; the bottom button stays. Off by default so the till's panel is unchanged.
 * @param onOpenLinkedBill When set, the related-bill links (Storno ↔ original,
 *   clearing → invoiced bill) are tappable and open the linked bill in this same panel; when null they
 *   render as plain text.
 * @param backTo The bill the operator came from by following a link; with [onBack] it shows a
 *   "Zurück zu RE-…" affordance above the title.
 */
@Composable
fun PosBillDetailPanel(
    bill: BillDto.BillResponseDto,
    tableNumber: String,
    modifier: Modifier = Modifier,
    currency: String = "EUR",
    showSuccessHeader: Boolean = false,
    isLoading: Boolean = false,
    onActions: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    onPrint: (() -> Unit)? = null,
    onOpenLinkedBill: ((BillDto.BillLinkDto) -> Unit)? = null,
    backTo: BillDto.BillLinkDto? = null,
    onBack: (() -> Unit)? = null,
    closeInHeader: Boolean = false,
) {
    val language = LocalAppLanguage.current
    val titleInHeader = closeInHeader && onClose != null
    val title = str(if (showSuccessHeader) BillReceiptKeys.dialogTitle else BillReceiptKeys.receiptTitle)

    val contentScroll = rememberScrollState()
    Column(modifier = modifier.fillMaxWidth()) {
        // Success header
        if (showSuccessHeader) {
            SuccessHeader()
        }

        if (titleInHeader && onClose != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                PosIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = str(CommonKeys.close),
                    onClick = onClose,
                )
            }
            // Once the receipt scrolls under the pinned title, a hairline separates the two.
            if (contentScroll.value > 0) HorizontalDivider()
        }

        // Scrollable content
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(contentScroll)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Back to the bill the operator came from by following a relation link
            if (backTo != null && onBack != null) {
                PosTextButton(
                    text = str(BillReceiptKeys.relationBackTo, backTo.billNumber),
                    onClick = onBack,
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                )
            }

            // Title
            if (!titleInHeader) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Status badges: Training, cancelled original
            StatusBadges(bill = bill)

            // Links to the related bills — Storno ↔ original, clearing → bill
            RelationLinksSection(relations = bill.relations, onOpenLinkedBill = onOpenLinkedBill)

            // Info section
            InfoSection(bill = bill, tableNumber = tableNumber, language = language)

            HorizontalDivider()

            // Items section
            ItemsSection(bill = bill, language = language, currency = currency)

            HorizontalDivider()

            // Financial summary
            FinancialSummarySection(bill = bill, language = language, currency = currency)

            HorizontalDivider()

            // Payments section
            PaymentsSection(bill = bill, currency = currency)

            // Voucher redemptions
            VoucherSection(bill = bill, currency = currency)

            // Deleted bill info
            DeletedBillSection(bill = bill)

            // TSE QR Code
            TseQrCodeSection(bill.tseQrCodeContent)

            // Nachsignierung: pending, signed late or abandoned, and why
            TseSigningNoteSection(bill.tseSigningNote)

            // Thank you message
            Text(
                text = str(BillReceiptKeys.thankYouMessage),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Action buttons
        ActionButtonsSection(
            isLoading = isLoading,
            onPrint = onPrint,
            onActions = onActions,
            onClose = onClose,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusBadges(bill: BillDto.BillResponseDto) {
    val labels = bill.detailStatusLabels()
    if (labels.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(PosSpacing.sm)) {
        labels.forEach { label ->
            val (background, foreground) =
                when {
                    label.key == BillReceiptKeys.trainingBadge ->
                        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                    label.isWarning -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                    // A Zahlartwechsel stornoed the bill but refunded nothing: neutral, not a warning.
                    else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                }
            PosStatusChip(text = label.text(), backgroundColor = background, textColor = foreground)
        }
    }
}

/**
 * The related-bill links ([detailLinks]). Tappable when [onOpenLinkedBill] is set — the host then
 * opens the linked bill in this same panel — and plain text otherwise (a host without navigation
 * still names the linked Belegnummer).
 */
@Composable
private fun RelationLinksSection(
    relations: BillDto.BillRelationsDto,
    onOpenLinkedBill: ((BillDto.BillLinkDto) -> Unit)?,
) {
    val links = relations.detailLinks()
    if (links.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = str(BillReceiptKeys.relationSectionTitle),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            links.forEach { link ->
                val target = link.target ?: return@forEach
                val color = if (link.isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                if (onOpenLinkedBill != null) {
                    PosTextButton(
                        text = link.text(),
                        onClick = { onOpenLinkedBill(target) },
                        height = 40.dp,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = color),
                    )
                } else {
                    Text(
                        text = link.text(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SuccessHeader() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Text(
                text = str(BillReceiptKeys.successMessage),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun InfoSection(
    bill: BillDto.BillResponseDto,
    tableNumber: String,
    language: org.example.project.core.domain.enums.setup.Language,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InfoRow(label = str(BillReceiptKeys.billNumberLabel), value = bill.billNumber)
            InfoRow(label = str(BillReceiptKeys.tableLabel), value = tableNumber)
            InfoRow(label = str(BillReceiptKeys.employeeNumber), value = bill.employeeNumber)
            InfoRow(
                label = str(BillReceiptKeys.startTimeLabel),
                value = bill.startTime.formatShortDateTime(language),
            )
            InfoRow(
                label = str(BillReceiptKeys.endTimeLabel),
                value = bill.endTime.formatShortDateTime(language),
            )
        }
    }
}

@Composable
private fun ItemsSection(
    bill: BillDto.BillResponseDto,
    language: org.example.project.core.domain.enums.setup.Language,
    currency: String,
) {
    Text(
        text = str(BillReceiptKeys.itemsLabel),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )

    bill.orderItems.forEach { item ->
        val isCancelled = item.itemStatus.uppercase() == "CANCELLED"
        val itemBadges =
            buildList {
                if (item.takeAway) add(str(BillReceiptKeys.takeawayLabel))
                if (item.transferred) add(str(BillReceiptKeys.transferredLabel))
                if (item.customPrice) add(str(BillReceiptKeys.customPriceLabel))
            }

        BillOrderItemRow(
            quantity = item.quantity,
            productName = "${item.product.productNumber}-${item.product.productName}",
            price = item.finalPrice,
            currency = currency,
            // Name plus delta (admin21 / emu21 NOTE-y: without the price the line total did not add
            // up), in the screen's money format — the print label's „(1.50)“ read as foreign on a
            // German screen (emu22 NOTE-gg).
            extras = item.extras?.joinToString(", ") { it.screenLabel(currency) },
            sideNotes = item.sideNotes,
            unitPrice = "${str(BillReceiptKeys.unitPriceLabel)} ${formatMoneyForDisplay(item.unitPrice, currency)}",
            discount =
                if (item.discount > 0 && item.discount < 1) {
                    "${str(BillReceiptKeys.discountLabel)}: -${formatPercentForDisplay(item.discount.discountToPercentage().toString(), language)}"
                } else {
                    null
                },
            badges = itemBadges,
            isCancelled = isCancelled,
        )
    }
}

@Composable
private fun FinancialSummarySection(
    bill: BillDto.BillResponseDto,
    language: org.example.project.core.domain.enums.setup.Language,
    currency: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PosSpacing.sm),
    ) {
        // Subtotal
        SummaryRow(
            label = str(BillReceiptKeys.subtotalLabel),
            value = formatAmount(bill.billAmount, currency),
        )

        // Discount
        if (Decimal.parse(bill.discountAmount) != Decimal.ZERO) {
            SummaryRow(
                label = str(BillReceiptKeys.discountLabel),
                value = formatAmount(bill.discountAmount, currency, negative = true),
                valueColor = MaterialTheme.colorScheme.error,
            )
        }

        // Promotions
        if (bill.promotions.isNotEmpty()) {
            bill.promotions.forEach { promo ->
                val itemName =
                    promo.orderItemId?.let { targetId ->
                        bill.orderItems
                            .firstOrNull { it.orderItemIds.contains(targetId) }
                            ?.product
                            ?.productName
                    }
                val label =
                    if (itemName != null) {
                        "${str(PromotionKeys.promotionDeduction)}: ${promo.promotionName} ($itemName)"
                    } else {
                        "${str(PromotionKeys.promotionDeduction)}: ${promo.promotionName}"
                    }
                SummaryRow(
                    label = label,
                    value = formatAmount(promo.deductedAmount, currency, negative = true),
                    valueColor = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Tips (Owner — tax included)
        if (Decimal.parse(bill.tips) != Decimal.ZERO && bill.tipRecipient == TipRecipient.OWNER.name) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        text = str(BillReceiptKeys.tipLabel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "(${str(BillReceiptKeys.tipRecipientOwner)}, ${str(BillReceiptKeys.taxIncluded)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatAmount(bill.tips, currency),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // VAT breakdown — table header
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = str(BillReceiptKeys.vatRate),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = str(BillReceiptKeys.vatAmount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = str(BillReceiptKeys.netAmount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = str(BillReceiptKeys.grossAmount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        // VAT breakdown — rows
        bill.vatDetails.forEach { vat ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${vat.vatId} ${formatPercentForDisplay(vat.vatRate.toPercentage(), language)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatAmount(vat.vatAmount, currency),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatAmount(vat.netAmount, currency),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatAmount(vat.totalAmount, currency),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Tips (Employee — after VAT)
        if (Decimal.parse(bill.tips) != Decimal.ZERO && bill.tipRecipient == TipRecipient.EMPLOYEE.name) {
            val recipientName =
                try {
                    TipRecipient.EMPLOYEE.getName(language)
                } catch (_: Exception) {
                    bill.tipRecipient
                }
            SummaryRow(
                label = "${str(BillReceiptKeys.tipLabel)} ($recipientName, ${str(BillReceiptKeys.notTaxable)})",
                value = formatAmount(bill.tips, currency),
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(4.dp))

        // Total
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = str(BillReceiptKeys.totalLabel),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = formatAmount(bill.paidAmount, currency),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PaymentsSection(
    bill: BillDto.BillResponseDto,
    currency: String,
) {
    val isDeleted = bill.deletedBillId != null
    val language = LocalAppLanguage.current

    Text(
        text = str(BillReceiptKeys.paymentsLabel),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )

    Spacer(modifier = Modifier.height(4.dp))

    bill.payments.forEach { payment ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = payment.getLocalizedName(language),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = formatAmount(payment.amount, currency, negative = isDeleted),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun VoucherSection(
    bill: BillDto.BillResponseDto,
    currency: String,
) {
    bill.voucherRedeem?.takeIf { it.isNotEmpty() }?.let { vouchers ->
        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = str(BillReceiptKeys.voucherRedeemLabel),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(4.dp))

        vouchers.forEach { voucher ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(modifier = Modifier.padding(PosSpacing.md)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = str(BillReceiptKeys.voucherCodeLabel),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = voucher.voucherNumber,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = str(BillReceiptKeys.voucherAmountLabel),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = formatAmount(voucher.redeemedAmount, currency),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeletedBillSection(bill: BillDto.BillResponseDto) {
    val deletedId = bill.deletedBillId ?: return

    Spacer(modifier = Modifier.height(4.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(4.dp))

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.05f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.2f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = str(BillReceiptKeys.deletedBillIdLabel),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = deletedId,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            bill.deletingReason?.let { reason ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = str(BillReceiptKeys.deletingReasonLabel),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButtonsSection(
    isLoading: Boolean,
    onPrint: (() -> Unit)?,
    onActions: (() -> Unit)?,
    onClose: (() -> Unit)?,
) {
    val hasAnyAction = onPrint != null || onActions != null || onClose != null
    if (!hasAnyAction) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            onPrint?.let {
                PosSecondaryButton(
                    text = str(BillReceiptKeys.printButton),
                    onClick = it,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                onActions?.let {
                    PosButton(
                        text = str(CommonKeys.actions),
                        onClick = it,
                        isLoading = isLoading,
                        modifier = Modifier.weight(1f),
                    )
                }
                onClose?.let {
                    PosButton(
                        text = str(CommonKeys.close),
                        onClick = it,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    // The label takes the remaining width and wraps; the amount keeps its intrinsic width on one line.
    // Without the weight a long promotion name ("Aktion: Herbstfest Sonderaktion …") measured first
    // and left the amount one character wide, drawn one glyph per line (QA admin18 NOTE-18).
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun formatAmount(
    amount: String,
    currency: String,
    negative: Boolean = false,
): String = formatMoneyForDisplay(if (negative) "-$amount" else amount, currency)

/**
 * Simplified order-item row for the bill detail panel.
 *
 * Replaces composeApp's `PosOrderItemCard` (which depends on composeApp-local
 * status palettes and qr-kit) with a text-only Material 3 row so the shared-ui
 * panel stays dependency-free. The bill detail panel renders items read-only,
 * so the expand/collapse interaction of the card is not needed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BillOrderItemRow(
    quantity: Int,
    productName: String,
    price: String,
    currency: String,
    extras: String?,
    sideNotes: String?,
    unitPrice: String?,
    discount: String?,
    badges: List<String>,
    isCancelled: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (isCancelled) MaterialTheme.colorScheme.error.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(PosSpacing.md),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = productName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (isCancelled) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f),
                )
                if (unitPrice != null) {
                    Text(
                        text = unitPrice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${quantity}x",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isCancelled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = formatAmount(price, currency),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (isCancelled) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                )
            }
            if (!extras.isNullOrBlank()) {
                Text(
                    text = "+ $extras",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            if (!sideNotes.isNullOrBlank()) {
                Text(
                    text = sideNotes,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                )
            }
            if (discount != null) {
                Text(
                    text = discount,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (badges.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    badges.forEach { badge ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = badge,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = PosSpacing.sm, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
