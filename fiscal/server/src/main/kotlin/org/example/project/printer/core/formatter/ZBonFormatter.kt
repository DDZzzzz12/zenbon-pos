/**
 * Formatter for Z-Bon (end-of-day report) documents.
 *
 * Produces an ESC/POS byte array containing the complete Z-Bon report:
 * header, period, payment methods, VAT summary, employee tips,
 * vouchers issued/redeemed, revenue summary, cash balance with
 * Kassenbuch entries (Einnahmen/Ausgaben), and closing footer.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.formatter

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.utils.formatLocalDateTime
import org.example.project.core.utils.partitionZBonKassenbuch
import org.example.project.core.utils.printedRate
import org.example.project.core.utils.zBonDeductionAmount
import org.example.project.core.utils.zBonTipSections
import org.example.project.printer.core.enums.Alignment
import org.example.project.printer.core.enums.FontSize
import org.example.project.printer.core.utils.TextFormatUtils
import org.example.project.printer.core.utils.TextFormatUtils.ColumnAlign
import org.example.project.printer.core.utils.printAmount
import org.example.project.printer.core.utils.printMoney
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Formats Z-Bon end-of-day reports for thermal printer output.
 */
class ZBonFormatter : BaseDocumentFormatter() {
    /**
     * Format a Z-Bon report.
     *
     * @param zBon Z-Bon report data
     * @param language Language for date formatting
     * @return Result containing ESC/POS byte array or error
     */
    suspend fun format(
        zBon: ZBonDto.ZBonResponseDto,
        language: Language,
    ): ByteArray =
        formatDocument {
            initialize()

            // ==================== HEADER ====================
            align(Alignment.CENTER)
            fontSize(FontSize.DOUBLE)
            bold(true)
            textLine("Z-BON")
            textLine("TAGESABSCHLUSS")
            fontSize(FontSize.NORMAL)
            bold(false)
            newLine()

            // Shop info
            bold(true)
            textLine(zBon.shopName)
            bold(false)
            textLine("${zBon.shopAddress}, ${zBon.shopZipCode} ${zBon.shopCity}")
            textLine(zBon.shopCountry)
            if (zBon.shopTaxNumber.isNotBlank()) {
                textLine("St.-Nr.: ${zBon.shopTaxNumber}")
            }
            newLine()

            // ==================== REPORT INFORMATION ====================
            align(Alignment.LEFT)
            twoColumns("Z-Bon Nr:", "#${zBon.zbonNummer}")
            newLine()
            twoColumns("Erstellt:", zBon.createdAt.formatLocalDateTime(Language.GERMAN))
            newLine()
            twoColumns("Mitarbeiter-Nr.:", zBon.employeeNumber)
            newLine()
            newLine()

            // ==================== PERIOD ====================
            sectionHeader("ABRECHNUNGSZEITRAUM")

            text(
                TextFormatUtils.formatColumn("Von:", MAX_CHARS_SMALL / 3, ColumnAlign.LEFT) +
                    TextFormatUtils.formatColumn(
                        zBon.startTime.formatLocalDateTime(Language.GERMAN),
                        MAX_CHARS_SMALL * 2 / 3,
                        ColumnAlign.LEFT,
                    ),
            )
            newLine()
            text(
                TextFormatUtils.formatColumn("Bis:", MAX_CHARS_SMALL / 3, ColumnAlign.LEFT) +
                    TextFormatUtils.formatColumn(
                        zBon.endTime.formatLocalDateTime(Language.GERMAN),
                        MAX_CHARS_SMALL * 2 / 3,
                        ColumnAlign.LEFT,
                    ),
            )
            newLine()
            newLine()

            // ==================== EMPLOYEE REVENUE ====================
            zBon.employeeSummaries?.takeIf { it.isNotEmpty() }?.let { summaries ->
                sectionHeader("UMSATZ PRO MITARBEITER")

                summaries.forEach { emp ->
                    // Employee number only: `role` is the employee's CURRENT role, joined live at read
                    // time and not stored on the closing, so a reprint after a role change would no
                    // longer match the original paper (emu16 NOTE-4).
                    text(
                        TextFormatUtils.formatColumn(
                            emp.employeeNumber,
                            3 * MAX_CHARS_SMALL / 4,
                            ColumnAlign.LEFT,
                        ) +
                            TextFormatUtils.formatColumn(
                                printMoney(emp.totalRevenue),
                                MAX_CHARS_SMALL / 4,
                                ColumnAlign.RIGHT,
                            ),
                    )
                    newLine()

                    // Payment breakdown per employee
                    emp.paymentBreakdown.forEach { breakdown ->
                        text(
                            TextFormatUtils.formatColumn(
                                "  ${breakdown.paymentName}",
                                3 * MAX_CHARS_SMALL / 4,
                                ColumnAlign.LEFT,
                            ) +
                                TextFormatUtils.formatColumn(
                                    "${printAmount(breakdown.amount)} ${breakdown.currency}",
                                    MAX_CHARS_SMALL / 4,
                                    ColumnAlign.RIGHT,
                                ),
                        )
                        newLine()
                    }
                }
                // The owner tip is in Gesamtumsatz but in no employee's row: listed on its own so the
                // rows add up to the total (admin17 NOTE-15).
                zBonTipSections(zBon).ownerTip?.let { ownerTip ->
                    text(
                        TextFormatUtils.formatColumn(
                            "Inhabertrinkgeld",
                            3 * MAX_CHARS_SMALL / 4,
                            ColumnAlign.LEFT,
                        ) +
                            TextFormatUtils.formatColumn(
                                printMoney(ownerTip),
                                MAX_CHARS_SMALL / 4,
                                ColumnAlign.RIGHT,
                            ),
                    )
                    newLine()
                }
                newLine()
            }

            // ==================== EMPLOYEE TIPS ====================
            // Employee tips (not revenue) with a total that sums exactly the listed lines; the owner
            // tip is revenue, so it stands on its own line and is never added to that total.
            val tips = zBonTipSections(zBon)
            if (!tips.isEmpty) {
                sectionHeader("TRINKGELD PRO MITARBEITER")

                tips.employeeTips.forEach { emp ->
                    twoColumns(emp.employeeNumber, printMoney(emp.amount))
                    newLine()
                }

                tips.employeeTipTotal?.let { totalTips ->
                    separator()
                    bold(true)
                    twoColumns("Trinkgeld gesamt:", printMoney(totalTips))
                    bold(false)
                    newLine()
                }

                tips.ownerTip?.let { ownerTip ->
                    // Label and amount on two lines: together they exceed the paper width, and a
                    // cut "(im Umsatz enthalten)" is exactly the part that says this is revenue.
                    textLine("Trinkgeld Betrieb (im Umsatz enthalten):")
                    text(TextFormatUtils.formatColumn(printMoney(ownerTip), MAX_CHARS_SMALL, ColumnAlign.RIGHT))
                    newLine()
                }

                newLine()
            }

            // ==================== VOUCHERS ISSUED ====================
            zBon.vouchers?.takeIf { it.isNotEmpty() }?.let { vouchers ->
                sectionHeader("GUTSCHEINE VERKAUFT (${vouchers.size})")

                vouchers.forEach { voucher ->
                    text(
                        TextFormatUtils.formatColumn(
                            voucher.voucherNumber,
                            MAX_CHARS_SMALL * 2 / 3,
                            ColumnAlign.LEFT,
                        ) +
                            TextFormatUtils.formatColumn(
                                printMoney(voucher.originalValue),
                                MAX_CHARS_SMALL / 3,
                                ColumnAlign.RIGHT,
                            ),
                    )
                    newLine()
                    textLine(
                        TextFormatUtils.formatColumn(
                            "  Erstellt von: ${voucher.employeeNumber}",
                            MAX_CHARS_SMALL,
                            ColumnAlign.LEFT,
                        ),
                    )
                }

                // Total
                val totalCreated = vouchers.sumOf { it.originalValue.toBigDecimalValue() }
                separator()
                bold(true)
                twoColumns("Gesamt:", printMoney(totalCreated))
                bold(false)
                newLine()
                newLine()
            }

            // ==================== VOUCHERS REDEEMED ====================
            zBon.voucherRedeems?.takeIf { it.isNotEmpty() }?.let { redeems ->
                bold(true)
                text("GUTSCHEINE EINGELÖST (${redeems.size})")
                bold(false)
                newLine()
                separator()

                redeems.forEach { redeem ->
                    text(
                        TextFormatUtils.formatColumn(
                            "${redeem.voucherNumber} (${redeem.voucherSource.german})",
                            MAX_CHARS_SMALL * 2 / 3,
                            ColumnAlign.LEFT,
                        ) +
                            TextFormatUtils.formatColumn(
                                printMoney(redeem.redeemedAmount),
                                MAX_CHARS_SMALL / 3,
                                ColumnAlign.RIGHT,
                            ),
                    )
                    newLine()
                    textLine(
                        TextFormatUtils.formatColumn(
                            "  ${redeem.redeemedAt.formatLocalDateTime(Language.GERMAN)}",
                            MAX_CHARS_SMALL,
                            ColumnAlign.LEFT,
                        ),
                    )
                }

                // Total
                val totalRedeemed = redeems.sumOf { it.redeemedAmount.toBigDecimalValue() }
                separator()
                bold(true)
                twoColumns("Gesamt:", printMoney(totalRedeemed))
                bold(false)
                newLine()
                newLine()
            }

            // ==================== PAYMENT METHODS ====================
            separator(MAX_CHARS_SMALL)
            newLine()

            sectionHeader("ZAHLUNGSARTEN")

            zBon.paymentSummary?.forEach { payment ->
                twoColumns(payment.paymentName, "${printAmount(payment.amount)} ${payment.currency}")
                newLine()
            }
            newLine()

            // ==================== REVENUE SUMMARY ====================
            bold(true)
            sectionHeader("UMSATZZUSAMMENFASSUNG")
            bold(false)
            newLine()

            // VAT table within revenue summary
            fourColumns("MWST%", "MWST", "NETTO", "BRUTTO")
            newLine()

            zBon.vatGroups?.forEach { vat ->
                fourColumns(printedRate(vat.vatRate), printAmount(vat.vatAmount), printAmount(vat.netAmount), printAmount(vat.grossAmount))
                newLine()
            }

            separator()

            twoColumns("Gesamtumsatz:", printMoney(zBon.totalRevenue))
            newLine()

            // Admin tip subsection
            zBon.adminTip?.let { adminTip ->
                text(
                    TextFormatUtils.formatColumn(
                        "  (inkl. Inhabertrinkgeld:",
                        3 * MAX_CHARS_SMALL / 4,
                        ColumnAlign.LEFT,
                    ) +
                        TextFormatUtils.formatColumn(
                            "${printMoney(adminTip)})",
                            MAX_CHARS_SMALL / 4,
                            ColumnAlign.RIGHT,
                        ),
                )
                newLine()
            }

            twoColumns("Rabatte:", printMoney(zBonDeductionAmount(zBon.totalDiscounts)))
            newLine()
            // Per-promotion breakdown
            zBon.promotionBreakdown?.takeIf { it.isNotEmpty() }?.let { promotions ->
                newLine()
                bold(true)
                text("AKTIONEN")
                bold(false)
                newLine()
                separator()

                promotions.forEach { promo ->
                    text(
                        TextFormatUtils.formatColumn(
                            promo.promotionName,
                            MAX_CHARS_SMALL / 2,
                            ColumnAlign.LEFT,
                        ) +
                            TextFormatUtils.formatColumn(
                                "${promo.timesApplied}×",
                                MAX_CHARS_SMALL / 4,
                                ColumnAlign.CENTER,
                            ) +
                            TextFormatUtils.formatColumn(
                                printMoney(promo.totalDeducted),
                                MAX_CHARS_SMALL / 4,
                                ColumnAlign.RIGHT,
                            ),
                    )
                    newLine()
                }

                bold(true)
                twoColumns("Aktionen gesamt:", printMoney(zBon.totalPromotions))
                bold(false)
                newLine()
            }
            twoColumns("Stornierungen:", printMoney(zBonDeductionAmount(zBon.cancelledAmount)))
            newLine()
            if (zBon.stornoBillCount > 0) {
                twoColumns("Rückerstattungen (${zBon.stornoBillCount}):", printMoney(zBonDeductionAmount(zBon.refundAmount)))
                newLine()
            }
            if (zBon.rebookStornoCount > 0) {
                // The label is longer than half the line, so an even split would cut its count off.
                text(
                    TextFormatUtils.labelAmountRow(
                        "  davon Zahlartwechsel (${zBon.rebookStornoCount}):",
                        printMoney(zBonDeductionAmount(zBon.rebookStornoAmount)),
                        MAX_CHARS_SMALL,
                    ),
                )
                newLine()
            }
            // Schulungsbelege belong to the closing but not to its revenue; the till and adminApp show
            // them as their own line, and the paper did not (emu21 NOTE-bb). Display only.
            if (zBon.trainingBillCount > 0) {
                text(
                    TextFormatUtils.labelAmountRow(
                        "Schulungsbelege (${zBon.trainingBillCount}, kein Umsatz):",
                        printMoney(zBon.trainingRevenue),
                        MAX_CHARS_SMALL,
                    ),
                )
                newLine()
            }
            newLine()

            // ==================== CASH BALANCE ====================
            sectionHeader("KASSENSTAND")

            twoColumns("Anfangsbestand:", printMoney(zBon.openingBalance))
            newLine()
            newLine()

            // Split kassenbuch entries
            // Geldtransit and Kassendifferenz get their own sections: neither is stated anywhere
            // else on a Z-Bon. The printed Z-Bon must agree with the PDF of the same closing, so
            // both draw their sections from the same total partition.
            val sections = partitionZBonKassenbuch(zBon.kassenbuchSummaryDto)
            val einnahmen = sections.income
            val ausgaben = sections.expenses

            // ---- GELDTRANSIT ----
            if (sections.transit.isNotEmpty()) {
                bold(true)
                textLine("GELDTRANSIT")
                bold(false)
                sections.transit.forEach { entry ->
                    text(kassenbuchEntryLine(entry))
                    newLine()
                }
                newLine()
            }

            // ---- KASSENDIFFERENZ ----
            if (sections.differences.isNotEmpty()) {
                bold(true)
                textLine("KASSENDIFFERENZ")
                bold(false)
                sections.differences.forEach { entry ->
                    text(kassenbuchEntryLine(entry))
                    newLine()
                }
                newLine()
            }

            // ---- EINNAHMEN (Income) ----
            if (einnahmen.isNotEmpty()) {
                bold(true)
                textLine("EINNAHMEN")
                bold(false)

                einnahmen.forEach { entry ->
                    text(kassenbuchEntryLine(entry))
                    newLine()
                }

                val totalEinnahmen = einnahmen.sumOf { it.totalAmount.toBigDecimal() }
                separator()
                bold(true)
                twoColumns("Summe Einnahmen:", printMoney(totalEinnahmen))
                bold(false)
                newLine()
                newLine()
            }

            // ---- AUSGABEN (Expenses) ----
            if (ausgaben.isNotEmpty()) {
                bold(true)
                textLine("AUSGABEN")
                bold(false)

                ausgaben.forEach { entry ->
                    text(kassenbuchEntryLine(entry))
                    newLine()
                }

                val totalAusgaben = ausgaben.sumOf { it.totalAmount.toBigDecimal() }
                separator()
                bold(true)
                twoColumns("Summe Ausgaben:", printMoney(totalAusgaben))
                bold(false)
                newLine()
                newLine()
            }

            // ---- ENDBESTAND (Closing Balance) ----
            separator()
            bold(true)
            twoColumns("Endbestand:", printMoney(zBon.closingBalance))
            bold(false)
            newLine()

            // ==================== FOOTER ====================
            newLine()
            bold(true)
            separator(MAX_CHARS_SMALL)
            align(Alignment.CENTER)
            textLine("* * * ENDE DES Z-BONS * * *")
            newLine()

            // ==================== FINALIZE ====================
            feedLines(2)
            cut()
        }

    /**
     * Converts a String to BigDecimal with 2 decimal places.
     */
    private fun String.toBigDecimalValue(): BigDecimal = BigDecimal(this.replace(",", ".")).setScale(2, RoundingMode.HALF_UP)

    /**
     * One GELDTRANSIT/KASSENDIFFERENZ/EINNAHMEN/AUSGABEN row: the Beleg number, its text and the
     * amount, right-aligned. A fixed-width 10-char column for "B-Nr:$belegNr" used to cut the
     * number off after 5 digits (e.g. "2026-10-0001" became "2026-"), running straight into the
     * belegtext that followed with no separator — on the Z-Bon's own aggregate entry this read as
     * the nonsensical "B-Nr:2026-ZBon Nr. 1". [TextFormatUtils.labelAmountRow] keeps the number and
     * text together as one label and only ever trims from the end of that combined text, so the
     * Beleg number itself is never the part that goes missing.
     */
    private fun kassenbuchEntryLine(entry: KassenbuchDto.KassenbuchGroupedSummaryDto): String =
        TextFormatUtils.labelAmountRow(
            "B-Nr:${entry.belegNr} ${entry.belegtext}",
            printMoney(entry.totalAmount.toBigDecimal()),
            MAX_CHARS_SMALL,
        )
}
