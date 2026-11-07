package org.example.project.features.reports.presentation.components.zBon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalDisplayCurrency
import org.example.project.core.utils.enumStrings.getName
import org.example.project.core.utils.formatShortDateTime
import org.example.project.core.utils.partitionZBonKassenbuch
import org.example.project.core.utils.shownBookingText
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.core.utils.zBonTipSections
import org.example.project.money.Decimal
import org.example.project.money.sum
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.components.formatPercentForDisplay
import org.example.project.ui.components.zBon.ZBonPromotionBreakdown
import org.example.project.ui.theme.PosSpacing

@Composable
fun ZBonDetailPanel(
    zBonData: ZBonDto.ZBonResponseDto,
    onClose: () -> Unit,
    onPrint: (() -> Unit)? = null,
    onViewPdf: (() -> Unit)? = null,
    language: Language,
    modifier: Modifier = Modifier,
    /** Opens the tip-payout dialog for one employee; null hides the action (no `CASHBOOK_WRITE`). */
    onPayOutTip: ((ZBonDto.EmployeeTipPayoutStatusDto) -> Unit)? = null,
    /** Opens the storno dialog for one booked payout; null hides the action (no `CASHBOOK_WRITE`). */
    onReverseTipPayout: ((ZBonDto.EmployeeTipPayoutStatusDto, ZBonDto.TipPayoutRecordDto) -> Unit)? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header with close button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Receipt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "${str(ReportsKeys.zBonTitle)} #${zBonData.zbonNummer}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            PosIconButton(
                icon = Icons.Default.Close,
                contentDescription = null,
                onClick = onClose,
            )
        }

        // Shop info
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = zBonData.shopName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "${zBonData.shopAddress}, ${zBonData.shopZipCode} ${zBonData.shopCity}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (zBonData.shopTaxNumber.isNotBlank()) {
                Text(
                    text = "${str(ReportsKeys.taxNumberShort)}: ${zBonData.shopTaxNumber}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Report info
        SectionCard {
            LabelValueRow(str(ReportsKeys.zBonNumber), "#${zBonData.zbonNummer}")
            LabelValueRow(str(ReportsKeys.createdAt), zBonData.createdAt.formatShortDateTime(language))
            LabelValueRow(str(ReportsKeys.employeeNumber), zBonData.employeeNumber)
        }

        // Period
        SectionCard(title = str(ReportsKeys.reportPeriod)) {
            LabelValueRow(str(ReportsKeys.from), zBonData.startTime.formatShortDateTime(language))
            LabelValueRow(str(ReportsKeys.to), zBonData.endTime.formatShortDateTime(language))
        }

        val tips = zBonTipSections(zBonData)

        // Employee revenue. The stored per-employee figures are Σ(billAmount − promotionAmount); the
        // owner tip is turnover inside Gesamtumsatz but in no employee's row, so it is listed as its
        // own row — the printed Z-Bon's "(inkl. Inhabertrinkgeld)" — and the rows add up to the total
        // (admin17 NOTE-15: 254,52 listed under a 258,52 Gesamtumsatz).
        if (!zBonData.employeeSummaries.isNullOrEmpty()) {
            SectionCard(title = str(ReportsKeys.employeeRevenue)) {
                zBonData.employeeSummaries!!.forEach { summary ->
                    EmployeeSummaryRow(summary)
                }
                tips.ownerTip?.let { ownerTip ->
                    LabelValueRow(
                        label = str(ReportsKeys.ownerTipRow),
                        value = ownerTip.toFormattedCurrency(),
                    )
                }
            }
        }

        // Employee tips (separate from revenue). The total sums exactly the listed lines; the owner
        // tip is revenue, so it stands on its own line and is never added to that total.
        if (!tips.isEmpty) {
            SectionCard(title = str(ReportsKeys.employeeTips)) {
                tips.employeeTips.forEach { summary ->
                    LabelValueRow(
                        label = summary.employeeNumber,
                        value = summary.amount.toFormattedCurrency(),
                    )
                }
                tips.employeeTipTotal?.let { totalTips ->
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    LabelValueRow(
                        label = str(ReportsKeys.totalEmployeeTips),
                        value = totalTips.toFormattedCurrency(),
                        bold = true,
                    )
                }
                tips.ownerTip?.let { ownerTip ->
                    LabelValueRow(
                        label = str(ReportsKeys.ownerTipRevenue),
                        value = ownerTip.toFormattedCurrency(),
                    )
                }
            }
        }

        // Employee tips still to be handed out of the drawer (nothing for an older closing).
        TipPayoutSection(positions = zBonData.tipPayouts, onPayOut = onPayOutTip, onReverse = onReverseTipPayout)

        // Vouchers issued
        if (!zBonData.vouchers.isNullOrEmpty()) {
            SectionCard(title = "${str(ReportsKeys.vouchersIssued)} (${zBonData.vouchers!!.size})") {
                zBonData.vouchers!!.forEach { voucher ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = voucher.voucherNumber,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "${str(ReportsKeys.issuedBy)}: ${voucher.employeeNumber}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = voucher.originalValue.toFormattedCurrency(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                LabelValueRow(
                    label = str(ReportsKeys.total),
                    value =
                        zBonData.vouchers!!
                            .mapNotNull { Decimal.parseOrNull(it.originalValue) }
                            .sum()
                            .toFormattedCurrency(),
                    bold = true,
                )
            }
        }

        // Vouchers redeemed
        if (!zBonData.voucherRedeems.isNullOrEmpty()) {
            SectionCard(title = "${str(ReportsKeys.vouchersRedeemed)} (${zBonData.voucherRedeems!!.size})") {
                zBonData.voucherRedeems!!.forEach { redemption ->
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f),
                            ) {
                                PosAutoSizeText(
                                    text = redemption.voucherNumber,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Surface(
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = getVoucherSourceColor(redemption.voucherSource),
                                ) {
                                    Text(
                                        text = redemption.voucherSource.getName(language),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = PosSpacing.sm, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                text = redemption.redeemedAmount.toFormattedCurrency(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            text = redemption.redeemedAt.formatShortDateTime(language),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                LabelValueRow(
                    label = str(ReportsKeys.total),
                    value =
                        zBonData.voucherRedeems!!
                            .mapNotNull { Decimal.parseOrNull(it.redeemedAmount) }
                            .sum()
                            .toFormattedCurrency(),
                    bold = true,
                )
            }
        }

        HorizontalDivider(thickness = 3.dp)

        // Payment methods
        if (!zBonData.paymentSummary.isNullOrEmpty()) {
            SectionCard(title = str(ReportsKeys.paymentBreakdown)) {
                zBonData.paymentSummary!!.forEach { payment ->
                    LabelValueRow(payment.paymentName, payment.amount.toFormattedCurrency())
                }
            }
        }

//        // VAT summary
//        if (!zBonData.vatGroups.isNullOrEmpty()) {
//            SectionCard(title = str(ReportsKeys.vat)) {
//                Row(
//                    modifier = Modifier.fillMaxWidth(),
//                    horizontalArrangement = Arrangement.SpaceBetween
//                ) {
//                    TableHeaderCell(str(ReportsKeys.vatRateShort), Modifier.weight(1f))
//                    TableHeaderCell(str(ReportsKeys.vatShort), Modifier.weight(1f))
//                    TableHeaderCell(str(ReportsKeys.netAmount), Modifier.weight(1f))
//                    TableHeaderCell(str(ReportsKeys.grossAmount), Modifier.weight(1f))
//                }
//                HorizontalDivider(
//                    modifier = Modifier.padding(vertical = 4.dp),
//                    color = MaterialTheme.colorScheme.outlineVariant
//                )
//                zBonData.vatGroups!!.forEach { vat ->
//                    Row(
//                        modifier = Modifier.fillMaxWidth(),
//                        horizontalArrangement = Arrangement.SpaceBetween
//                    ) {
//                        TableCell(formatPercentForDisplay(vat.vatRate), Modifier.weight(1f))
//                        TableCell(vat.vatAmount.toFormattedCurrency(), Modifier.weight(1f))
//                        TableCell(vat.netAmount.toFormattedCurrency(), Modifier.weight(1f))
//                        TableCell(vat.grossAmount.toFormattedCurrency(), Modifier.weight(1f))
//                    }
//                }
//            }
//        }

        // Revenue summary
        SectionCard(title = str(ReportsKeys.revenueSummary)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TableHeaderCell(str(ReportsKeys.vatRateShort), Modifier.weight(1f))
                TableHeaderCell(str(ReportsKeys.vatShort), Modifier.weight(1f))
                TableHeaderCell(str(ReportsKeys.netAmount), Modifier.weight(1f))
                TableHeaderCell(str(ReportsKeys.grossAmount), Modifier.weight(1f))
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            zBonData.vatGroups!!.forEach { vat ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TableCell(formatPercentForDisplay(vat.vatRate), Modifier.weight(1f))
                    TableCell(vat.vatAmount.toFormattedCurrency(), Modifier.weight(1f))
                    TableCell(vat.netAmount.toFormattedCurrency(), Modifier.weight(1f))
                    TableCell(vat.grossAmount.toFormattedCurrency(), Modifier.weight(1f))
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 4.dp,
            )
            LabelValueRow(
                label = str(ReportsKeys.totalRevenue),
                value = zBonData.totalRevenue.toFormattedCurrency(),
                bold = true,
            )
            // 4c-2c: receivables cleared at the till are money in the drawer, but not turnover —
            // shown on its own line, and only when there is something to show.
            if (Decimal.parseOrNull(zBonData.totalReceivableCleared)?.let { it != Decimal.ZERO } == true) {
                LabelValueRow(
                    label = str(ReportsKeys.receivableCleared),
                    value = zBonData.totalReceivableCleared.toFormattedCurrency(),
                )
            }
            zBonData.adminTip?.let { adminTip ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "(${str(ReportsKeys.ownerTipIncluded)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                    )
                    Text(
                        text = adminTip.toFormattedCurrency(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                    )
                }
            }
            LabelValueRow(
                label = str(ReportsKeys.discounts),
                value = zBonData.totalDiscounts.toFormattedCurrency(),
            )
            // Per-promotion breakdown (shared with the back office's Z-Bon panel)
            ZBonPromotionBreakdown(zBonData)
            LabelValueRow(
                label = str(ReportsKeys.cancellations),
                value = zBonData.cancelledAmount.toFormattedCurrency(),
            )
            if (zBonData.stornoBillCount > 0) {
                LabelValueRow(
                    label = "${str(ReportsKeys.refunds)} (${zBonData.stornoBillCount})",
                    value = zBonData.refundAmount.toFormattedCurrency(),
                )
            }
            if (zBonData.rebookStornoCount > 0) {
                LabelValueRow(
                    label = "${str(ReportsKeys.refundsRebook)} (${zBonData.rebookStornoCount})",
                    value = zBonData.rebookStornoAmount.toFormattedCurrency(),
                )
            }
            // Schulungsbelege are in the closing (bill.zbon_id, DSFinV-K) but not in its revenue; the
            // server reports them separately and the panel used to drop them (admin20 NOTE-B).
            if (zBonData.trainingBillCount > 0) {
                LabelValueRow(
                    label = str(ReportsKeys.trainingReceipts, zBonData.trainingBillCount),
                    value = zBonData.trainingRevenue.toFormattedCurrency(),
                )
            }
        }

        // Cash balance
        CashBalanceSection(zBonData)

        // Action buttons
        if (onPrint != null || onViewPdf != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onViewPdf != null) {
                    PosSecondaryButton(
                        text = str(ReportsKeys.downloadPdf),
                        onClick = onViewPdf,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (onPrint != null) {
                    PosSecondaryButton(
                        text = str(ReportsKeys.print),
                        onClick = onPrint,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ==================== PRIVATE COMPOSABLES ====================

@Composable
private fun CashBalanceSection(zBonData: ZBonDto.ZBonResponseDto) {
    // Geldtransit and Kassendifferenz get their own sections: neither is stated anywhere else on
    // a Z-Bon. Everything else keeps the existing Einnahmen/Ausgaben split — see
    // partitionZBonKassenbuch, which is total, so no entry is dropped.
    val sections = partitionZBonKassenbuch(zBonData.kassenbuchSummaryDto)
    val einnahmen = sections.income
    val ausgaben = sections.expenses

    SectionCard(title = str(ReportsKeys.cashBalance)) {
        LabelValueRow(
            label = str(ReportsKeys.openingBalance),
            value = zBonData.openingBalance.toFormattedCurrency(),
            bold = true,
        )
        if (sections.transit.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = str(ReportsKeys.cashTransit),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            sections.transit.forEach { entry ->
                KassenbuchEntryRow(entry)
            }
        }
        if (sections.differences.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = str(ReportsKeys.cashDifference),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            sections.differences.forEach { entry ->
                KassenbuchEntryRow(entry)
            }
        }
        if (einnahmen.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = str(ReportsKeys.income),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            einnahmen.forEach { entry ->
                KassenbuchEntryRow(entry)
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            val totalEinnahmen = einnahmen.mapNotNull { Decimal.parseOrNull(it.totalAmount) }.sum()
            LabelValueRow(
                label = str(ReportsKeys.totalIncome),
                value = totalEinnahmen.toFormattedCurrency(),
                bold = true,
            )
        }
        if (ausgaben.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = str(ReportsKeys.expenses),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            ausgaben.forEach { entry ->
                KassenbuchEntryRow(entry)
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            val totalAusgaben = ausgaben.mapNotNull { Decimal.parseOrNull(it.totalAmount) }.sum()
            LabelValueRow(
                label = str(ReportsKeys.totalExpenses),
                value = totalAusgaben.toFormattedCurrency(),
                bold = true,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(4.dp))
        LabelValueRow(
            label = str(ReportsKeys.endBalance),
            value = zBonData.closingBalance.toFormattedCurrency(),
            bold = true,
        )
    }
}

@Composable
private fun EmployeeSummaryRow(summary: ZBonDto.EmployeeZBonSummaryResponseDto) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // Employee number only: `role` is the CURRENT role, not stored on the closing, so
                    // showing it made a closed Z-Bon render differently after a role change (NOTE-4).
                    text = summary.employeeNumber,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = summary.totalRevenue.toFormattedCurrency(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        if (summary.paymentBreakdown.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                summary.paymentBreakdown.forEach { breakdown ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = breakdown.paymentName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = breakdown.amount.toFormattedCurrency(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KassenbuchEntryRow(entry: KassenbuchDto.KassenbuchGroupedSummaryDto) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f)) {
            Text(
                text = "${str(ReportsKeys.documentNumber)}:${entry.belegNr}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 70.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            PosAutoSizeText(
                text = entry.belegtext.shownBookingText(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = formatMoneyForDisplay(entry.totalAmount, LocalDisplayCurrency.current, forceSign = true),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SectionCard(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Tonal,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        body {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(PosSpacing.sm),
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                content()
            }
        }
    }
}

@Composable
private fun LabelValueRow(
    label: String,
    value: String,
    bold: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PosAutoSizeText(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun TableHeaderCell(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

@Composable
private fun TableCell(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

@Composable
private fun getVoucherSourceColor(source: VoucherSourceType): androidx.compose.ui.graphics.Color =
    when (source) {
        VoucherSourceType.INTERNAL -> MaterialTheme.colorScheme.primary
        VoucherSourceType.LEGACY -> MaterialTheme.colorScheme.secondary
        VoucherSourceType.EXTERNAL -> MaterialTheme.colorScheme.tertiary
        VoucherSourceType.PROMOTIONAL -> MaterialTheme.colorScheme.error
        VoucherSourceType.GIFT_CARD -> MaterialTheme.colorScheme.primaryContainer
    }
