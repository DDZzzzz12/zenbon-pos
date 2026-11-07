/**
 * Z-Bon (end-of-day report) PDF generator.
 *
 * Creates a full A4 PDF replicating the ESC/POS ZBonFormatter layout.
 * Sections mirror the thermal printer output:
 * 1. Header (Z-BON / TAGESABSCHLUSS + shop name)
 * 2. Report info (Z-Bon Nr, Created, Employee Nr)
 * 3. Period (Von/Bis)
 * 4. Payment methods (Zahlungsarten)
 * 5. VAT summary table (MwSt%, MwSt, Netto, Brutto)
 * 6. Employee tips (if any) and the owner tip on its own line
 * 7. Vouchers issued/redeemed (if any)
 * 8. Revenue summary (Gesamtumsatz, Stornierungen)
 * 9. Cash balance (Anfangsbestand, Einnahmen, Ausgaben, Endbestand)
 * 10. Footer
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.pdf.generator

import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.utils.formatLocalDateTime
import org.example.project.core.utils.partitionZBonKassenbuch
import org.example.project.core.utils.printedAmount
import org.example.project.core.utils.printedRate
import org.example.project.core.utils.zBonDeductionAmount
import org.example.project.core.utils.zBonTipSections
import org.example.project.pdf.util.addRow
import org.example.project.pdf.util.addTotalRow
import org.openpdf.text.Document
import org.openpdf.text.Element
import org.openpdf.text.Font
import org.openpdf.text.PageSize
import org.openpdf.text.Paragraph
import org.openpdf.text.Phrase
import org.openpdf.text.Rectangle
import org.openpdf.text.pdf.BaseFont
import org.openpdf.text.pdf.PdfPCell
import org.openpdf.text.pdf.PdfPTable
import org.openpdf.text.pdf.PdfWriter
import org.openpdf.text.pdf.draw.LineSeparator
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Generates a Z-Bon end-of-day report PDF as a byte array.
 *
 * The PDF uses an A4 page format with the same data structure as the
 * ESC/POS thermal printer output, suitable for digital archival and
 * linking to Kassenbuch entries.
 *
 * @param zBon Complete Z-Bon response DTO containing all report data
 * @return PDF document bytes
 */
fun generateZBonReportPdfBytes(zBon: ZBonDto.ZBonResponseDto): ByteArray {
    val output = ByteArrayOutputStream()
    val document = Document(PageSize.A4, 40f, 40f, 40f, 40f)
    PdfWriter.getInstance(document, output)
    document.open()

    val baseFont = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED)
    val baseFontBold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED)
    val titleFont = Font(baseFontBold, 18f, Font.BOLD)
    val subtitleFont = Font(baseFontBold, 14f, Font.BOLD)
    val sectionFont = Font(baseFontBold, 11f, Font.BOLD)
    val normalFont = Font(baseFont, 9f, Font.NORMAL)
    val boldFont = Font(baseFontBold, 9f, Font.BOLD)
    val smallFont = Font(baseFont, 8f, Font.NORMAL, Color.GRAY)
    val headerGray = Color(230, 230, 230)

    // ==================== HEADER ====================
    val title = Paragraph("Z-BON", titleFont)
    title.alignment = Element.ALIGN_CENTER
    document.add(title)

    val subtitle = Paragraph("TAGESABSCHLUSS", subtitleFont)
    subtitle.alignment = Element.ALIGN_CENTER
    subtitle.spacingAfter = 5f
    document.add(subtitle)

    val shopName = Paragraph(zBon.shopName, sectionFont)
    shopName.alignment = Element.ALIGN_CENTER
    document.add(shopName)

    val shopAddress = Paragraph("${zBon.shopAddress}, ${zBon.shopZipCode} ${zBon.shopCity}", normalFont)
    shopAddress.alignment = Element.ALIGN_CENTER
    document.add(shopAddress)

    val shopCountry = Paragraph(zBon.shopCountry, normalFont)
    shopCountry.alignment = Element.ALIGN_CENTER
    document.add(shopCountry)

    if (zBon.shopTaxNumber.isNotBlank()) {
        val taxNumber = Paragraph("St.-Nr.: ${zBon.shopTaxNumber}", normalFont)
        taxNumber.alignment = Element.ALIGN_CENTER
        taxNumber.spacingAfter = 10f
        document.add(taxNumber)
    } else {
        shopCountry.spacingAfter = 10f
    }

    addSeparatorLine(document)

    // ==================== REPORT INFORMATION ====================
    val infoTable = PdfPTable(2)
    infoTable.widthPercentage = 100f
    infoTable.setWidths(floatArrayOf(1.5f, 3f))
    infoTable.setSpacingBefore(5f)
    infoTable.setSpacingAfter(5f)

    addInfoRow(infoTable, "Z-Bon Nr:", "#${zBon.zbonNummer}", boldFont, normalFont)
    addInfoRow(infoTable, "Erstellt:", zBon.createdAt.formatLocalDateTime(Language.GERMAN), boldFont, normalFont)
    addInfoRow(infoTable, "Mitarbeiter-Nr.:", zBon.employeeNumber, boldFont, normalFont)

    document.add(infoTable)
    addSeparatorLine(document)

    // ==================== PERIOD ====================
    addSectionHeader(document, "ABRECHNUNGSZEITRAUM", sectionFont)

    val periodTable = PdfPTable(2)
    periodTable.widthPercentage = 100f
    periodTable.setWidths(floatArrayOf(1f, 3f))
    periodTable.setSpacingAfter(5f)

    addInfoRow(periodTable, "Von:", zBon.startTime.formatLocalDateTime(Language.GERMAN), boldFont, normalFont)
    addInfoRow(periodTable, "Bis:", zBon.endTime.formatLocalDateTime(Language.GERMAN), boldFont, normalFont)

    document.add(periodTable)
    addSeparatorLine(document)

    // ==================== EMPLOYEE REVENUE ====================
    val employeeSummaries = zBon.employeeSummaries ?: emptyList()

    if (employeeSummaries.isNotEmpty()) {
        addSectionHeader(document, "UMSATZ PRO MITARBEITER", sectionFont)

        employeeSummaries.forEach { emp ->
            val empRow = PdfPTable(2)
            empRow.widthPercentage = 100f
            // Employee number only: `role` is the employee's CURRENT role, not stored on the closing,
            // so a re-rendered PDF would drift from the original after a role change (emu16 NOTE-4).
            addTotalRow(
                empRow,
                emp.employeeNumber,
                "${printedAmount(emp.totalRevenue)} EUR",
                normalFont,
            )
            document.add(empRow)

            // Payment breakdown per employee
            emp.paymentBreakdown.forEach { breakdown ->
                val breakdownRow = PdfPTable(2)
                breakdownRow.widthPercentage = 100f
                addTotalRow(breakdownRow, "  ${breakdown.paymentName}", "${printedAmount(breakdown.amount)} ${breakdown.currency}", smallFont)
                document.add(breakdownRow)
            }
        }
        // The owner tip is in Gesamtumsatz but in no employee's row: listed on its own so the rows
        // add up to the total (admin17 NOTE-15).
        zBonTipSections(zBon).ownerTip?.let { ownerTip ->
            val ownerTipRow = PdfPTable(2)
            ownerTipRow.widthPercentage = 100f
            addTotalRow(ownerTipRow, "Inhabertrinkgeld", "${printedAmount(ownerTip)} EUR", normalFont)
            document.add(ownerTipRow)
        }

        addSpacing(document)
        addSeparatorLine(document)
    }

    // ==================== EMPLOYEE TIPS ====================
    // Employee tips (not revenue) with a total that sums exactly the listed lines; the owner tip is
    // revenue, so it stands on its own line and is never added to that total.
    val tips = zBonTipSections(zBon)
    if (!tips.isEmpty) {
        addSectionHeader(document, "TRINKGELD PRO MITARBEITER", sectionFont)

        tips.employeeTips.forEach { emp ->
            val tipRow = PdfPTable(2)
            tipRow.widthPercentage = 100f
            addTotalRow(tipRow, emp.employeeNumber, "${printedAmount(emp.amount)} EUR", normalFont)
            document.add(tipRow)
        }

        tips.employeeTipTotal?.let { totalTips ->
            addSpacing(document)
            val tipsRow = PdfPTable(2)
            tipsRow.widthPercentage = 100f
            addTotalRow(tipsRow, "Trinkgeld gesamt:", "${printedAmount(totalTips)} EUR", boldFont)
            document.add(tipsRow)
        }

        tips.ownerTip?.let { ownerTip ->
            val ownerRow = PdfPTable(2)
            ownerRow.widthPercentage = 100f
            addTotalRow(ownerRow, "Trinkgeld Betrieb (im Umsatz enthalten):", "${printedAmount(ownerTip)} EUR", normalFont)
            document.add(ownerRow)
        }

        addSpacing(document)
        addSeparatorLine(document)
    }

    // ==================== VOUCHERS ISSUED ====================
    zBon.vouchers?.takeIf { it.isNotEmpty() }?.let { vouchers ->
        addSectionHeader(document, "GUTSCHEINE VERKAUFT (${vouchers.size})", sectionFont)

        vouchers.forEach { voucher ->
            val voucherRow = PdfPTable(2)
            voucherRow.widthPercentage = 100f
            addTotalRow(voucherRow, voucher.voucherNumber, "${printedAmount(voucher.originalValue)} EUR", normalFont)
            document.add(voucherRow)

            val detailPara = Paragraph("  Erstellt von: ${voucher.employeeNumber}", smallFont)
            document.add(detailPara)
        }

        val totalCreated = vouchers.sumOf { it.originalValue.toBigDecimalValue() }
        addSpacing(document)
        val totalRow = PdfPTable(2)
        totalRow.widthPercentage = 100f
        addTotalRow(totalRow, "Gesamt:", "${totalCreated.toMoneyString()} EUR", boldFont)
        document.add(totalRow)
        addSpacing(document)
        addSeparatorLine(document)
    }

    // ==================== VOUCHERS REDEEMED ====================
    zBon.voucherRedeems?.takeIf { it.isNotEmpty() }?.let { redeems ->
        addSectionHeader(document, "GUTSCHEINE EINGELÖST (${redeems.size})", sectionFont)

        redeems.forEach { redeem ->
            val redeemRow = PdfPTable(2)
            redeemRow.widthPercentage = 100f
            addTotalRow(
                redeemRow,
                "${redeem.voucherNumber} (${redeem.voucherSource.german})",
                "${printedAmount(redeem.redeemedAmount)} EUR",
                normalFont,
            )
            document.add(redeemRow)

            val detailPara =
                Paragraph(
                    "  ${redeem.redeemedAt.formatLocalDateTime(Language.GERMAN)}",
                    smallFont,
                )
            document.add(detailPara)
        }

        val totalRedeemed = redeems.sumOf { it.redeemedAmount.toBigDecimalValue() }
        addSpacing(document)
        val totalRow = PdfPTable(2)
        totalRow.widthPercentage = 100f
        addTotalRow(totalRow, "Gesamt:", "${totalRedeemed.toMoneyString()} EUR", boldFont)
        document.add(totalRow)
        addSpacing(document)
        addSeparatorLine(document)
    }

    // ==================== PAYMENT METHODS ====================
    addSectionHeader(document, "ZAHLUNGSARTEN", sectionFont)

    zBon.paymentSummary?.forEach { payment ->
        val paymentRow = PdfPTable(2)
        paymentRow.widthPercentage = 100f
        addTotalRow(paymentRow, payment.paymentName, "${printedAmount(payment.amount)} ${payment.currency}", normalFont)
        document.add(paymentRow)
    }
    addSpacing(document)
    addSeparatorLine(document)

    // ==================== REVENUE SUMMARY ====================
    addSectionHeader(document, "UMSATZZUSAMMENFASSUNG", sectionFont)

    // VAT table within revenue summary
    val vatTable = PdfPTable(4)
    vatTable.widthPercentage = 100f
    vatTable.setWidths(floatArrayOf(1f, 1.5f, 1.5f, 1.5f))

    // Header row
    listOf("MWST%", "MWST", "NETTO", "BRUTTO").forEach { header ->
        val cell = PdfPCell(Phrase(header, boldFont))
        cell.backgroundColor = headerGray
        cell.horizontalAlignment = Element.ALIGN_CENTER
        cell.paddingTop = 4f
        cell.paddingBottom = 4f
        vatTable.addCell(cell)
    }

    zBon.vatGroups?.forEach { vat ->
        addRow(
            vatTable,
            normalFont,
            Element.ALIGN_CENTER,
            printedRate(vat.vatRate),
            printedAmount(vat.vatAmount),
            printedAmount(vat.netAmount),
            printedAmount(vat.grossAmount),
        )
    }

    document.add(vatTable)
    addSpacing(document)

    val revTable = PdfPTable(2)
    revTable.widthPercentage = 100f
    addTotalRow(revTable, "Gesamtumsatz:", "${printedAmount(zBon.totalRevenue)} EUR", boldFont)

    zBon.adminTip?.let { adminTip ->
        addTotalRow(revTable, "  (inkl. Inhabertrinkgeld:", "${printedAmount(adminTip)} EUR)", normalFont)
    }

    addTotalRow(revTable, "Rabatte:", "${printedAmount(zBonDeductionAmount(zBon.totalDiscounts))} EUR", normalFont)
    // Per-promotion breakdown
    zBon.promotionBreakdown?.takeIf { it.isNotEmpty() }?.let { promotions ->
        addSpacing(document)
        val promoHeader = Paragraph("AKTIONEN", sectionFont)
        promoHeader.spacingBefore = 3f
        promoHeader.spacingAfter = 3f
        document.add(promoHeader)

        val promoTable = PdfPTable(3)
        promoTable.widthPercentage = 100f
        promoTable.setWidths(floatArrayOf(2.5f, 1f, 1.5f))

        listOf("Aktion", "Anzahl", "Betrag").forEach { header ->
            val cell = PdfPCell(Phrase(header, boldFont))
            cell.backgroundColor = headerGray
            cell.horizontalAlignment = Element.ALIGN_CENTER
            cell.paddingTop = 4f
            cell.paddingBottom = 4f
            promoTable.addCell(cell)
        }

        promotions.forEach { promo ->
            addRow(
                promoTable,
                normalFont,
                null,
                promo.promotionName,
                "${promo.timesApplied}×",
                "${promo.totalDeducted.toBigDecimalValue().toMoneyString()} EUR",
            )
        }

        document.add(promoTable)

        val promoTotalRow = PdfPTable(2)
        promoTotalRow.widthPercentage = 100f
        addTotalRow(promoTotalRow, "Aktionen gesamt:", "${printedAmount(zBon.totalPromotions)} EUR", boldFont)
        document.add(promoTotalRow)
    }
    addTotalRow(revTable, "Stornierungen:", "${printedAmount(zBonDeductionAmount(zBon.cancelledAmount))} EUR", normalFont)
    if (zBon.stornoBillCount > 0) {
        addTotalRow(revTable, "Rückerstattungen (${zBon.stornoBillCount}):", "${printedAmount(zBonDeductionAmount(zBon.refundAmount))} EUR", normalFont)
    }
    if (zBon.rebookStornoCount > 0) {
        addTotalRow(
            revTable,
            "  davon Zahlartwechsel (${zBon.rebookStornoCount}):",
            "${printedAmount(zBonDeductionAmount(zBon.rebookStornoAmount))} EUR",
            normalFont,
        )
    }
    // Schulungsbelege: in the closing, not in its revenue — the same line the till shows (emu21 NOTE-bb).
    if (zBon.trainingBillCount > 0) {
        addTotalRow(revTable, "Schulungsbelege (${zBon.trainingBillCount}, kein Umsatz):", "${printedAmount(zBon.trainingRevenue)} EUR", normalFont)
    }
    document.add(revTable)
    addSpacing(document)
    addSeparatorLine(document)

    // ==================== CASH BALANCE ====================
    addSectionHeader(document, "KASSENSTAND", sectionFont)

    val balTable = PdfPTable(2)
    balTable.widthPercentage = 100f
    addTotalRow(balTable, "Anfangsbestand:", "${printedAmount(zBon.openingBalance)} EUR", boldFont)
    document.add(balTable)
    addSpacing(document)

    // Geldtransit and Kassendifferenz get their own sections: neither is stated anywhere else on
    // a Z-Bon. Everything else keeps the Einnahmen/Ausgaben split — partitionZBonKassenbuch is
    // total, so an ordinary cash payout is never dropped from the closing document.
    val sections = partitionZBonKassenbuch(zBon.kassenbuchSummaryDto)
    val einnahmen = sections.income
    val ausgaben = sections.expenses

    // GELDTRANSIT
    if (sections.transit.isNotEmpty()) {
        val transitHeader = Paragraph("GELDTRANSIT", sectionFont)
        transitHeader.spacingBefore = 3f
        transitHeader.spacingAfter = 3f
        document.add(transitHeader)

        val transitTable = PdfPTable(3)
        transitTable.widthPercentage = 100f
        transitTable.setWidths(floatArrayOf(1f, 3f, 1.5f))
        sections.transit.forEach { entry ->
            addRow(
                transitTable,
                normalFont,
                null,
                "B-Nr:${entry.belegNr}",
                entry.belegtext,
                "${entry.totalAmount.toBigDecimal().toMoneyString()} EUR",
            )
        }
        document.add(transitTable)
        addSpacing(document)
    }

    // KASSENDIFFERENZ
    if (sections.differences.isNotEmpty()) {
        val diffHeader = Paragraph("KASSENDIFFERENZ", sectionFont)
        diffHeader.spacingBefore = 3f
        diffHeader.spacingAfter = 3f
        document.add(diffHeader)

        val diffTable = PdfPTable(3)
        diffTable.widthPercentage = 100f
        diffTable.setWidths(floatArrayOf(1f, 3f, 1.5f))
        sections.differences.forEach { entry ->
            addRow(
                diffTable,
                normalFont,
                null,
                "B-Nr:${entry.belegNr}",
                entry.belegtext,
                "${entry.totalAmount.toBigDecimal().toMoneyString()} EUR",
            )
        }
        document.add(diffTable)
        addSpacing(document)
    }

    // EINNAHMEN (Income)
    if (einnahmen.isNotEmpty()) {
        val einnahmenHeader = Paragraph("EINNAHMEN", sectionFont)
        einnahmenHeader.spacingBefore = 3f
        einnahmenHeader.spacingAfter = 3f
        document.add(einnahmenHeader)

        val einTable = PdfPTable(3)
        einTable.widthPercentage = 100f
        einTable.setWidths(floatArrayOf(1f, 3f, 1.5f))

        einnahmen.forEach { entry ->
            addRow(einTable, normalFont, null, "B-Nr:${entry.belegNr}", entry.belegtext, "${entry.totalAmount.toBigDecimal().toMoneyString()} EUR")
        }
        document.add(einTable)

        val totalEinnahmen = einnahmen.sumOf { it.totalAmount.toBigDecimal() }
        val einTotalRow = PdfPTable(2)
        einTotalRow.widthPercentage = 100f
        addTotalRow(einTotalRow, "Summe Einnahmen:", "${totalEinnahmen.toMoneyString()} EUR", boldFont)
        document.add(einTotalRow)
        addSpacing(document)
    }

    // AUSGABEN (Expenses)
    if (ausgaben.isNotEmpty()) {
        val ausgabenHeader = Paragraph("AUSGABEN", sectionFont)
        ausgabenHeader.spacingBefore = 3f
        ausgabenHeader.spacingAfter = 3f
        document.add(ausgabenHeader)

        val ausTable = PdfPTable(3)
        ausTable.widthPercentage = 100f
        ausTable.setWidths(floatArrayOf(1f, 3f, 1.5f))

        ausgaben.forEach { entry ->
            addRow(ausTable, normalFont, null, "B-Nr:${entry.belegNr}", entry.belegtext, "${entry.totalAmount.toBigDecimal().toMoneyString()} EUR")
        }
        document.add(ausTable)

        val totalAusgaben = ausgaben.sumOf { it.totalAmount.toBigDecimal() }
        val ausTotalRow = PdfPTable(2)
        ausTotalRow.widthPercentage = 100f
        addTotalRow(ausTotalRow, "Summe Ausgaben:", "${totalAusgaben.toMoneyString()} EUR", boldFont)
        document.add(ausTotalRow)
        addSpacing(document)
    }

    // Closing balance
    addSeparatorLine(document)
    val closingTable = PdfPTable(2)
    closingTable.widthPercentage = 100f
    closingTable.setSpacingBefore(5f)
    addTotalRow(closingTable, "Endbestand:", "${printedAmount(zBon.closingBalance)} EUR", boldFont)
    document.add(closingTable)

    // ==================== FOOTER ====================
    addSpacing(document)
    addSeparatorLine(document)
    val footer = Paragraph("* * * ENDE DES Z-BONS * * *", boldFont)
    footer.alignment = Element.ALIGN_CENTER
    footer.spacingBefore = 10f
    document.add(footer)

    document.close()
    return output.toByteArray()
}

/**
 * Adds a section header paragraph to the document.
 */
private fun addSectionHeader(
    document: Document,
    text: String,
    font: Font,
) {
    val header = Paragraph(text, font)
    header.spacingBefore = 8f
    header.spacingAfter = 5f
    document.add(header)
}

/**
 * Adds a horizontal separator line to the document.
 */
private fun addSeparatorLine(document: Document) {
    document.add(LineSeparator(0.5f, 100f, Color.GRAY, Element.ALIGN_CENTER, -2f))
}

/**
 * Adds a small vertical spacer to the document.
 */
private fun addSpacing(document: Document) {
    document.add(Paragraph(" ", Font(BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED), 4f)))
}

/**
 * Adds a two-column info row with label and value using separate fonts.
 */
private fun addInfoRow(
    table: PdfPTable,
    label: String,
    value: String,
    labelFont: Font,
    valueFont: Font,
) {
    val labelCell = PdfPCell(Phrase(label, labelFont))
    labelCell.border = Rectangle.NO_BORDER
    labelCell.paddingBottom = 3f
    table.addCell(labelCell)

    val valueCell = PdfPCell(Phrase(value, valueFont))
    valueCell.border = Rectangle.NO_BORDER
    valueCell.paddingBottom = 3f
    table.addCell(valueCell)
}

/**
 * Converts a String to BigDecimal with 2 decimal places.
 */
private fun String.toBigDecimalValue(): BigDecimal = BigDecimal(this.replace(",", ".")).setScale(2, RoundingMode.HALF_UP)

/** A money amount as printed on the Z-Bon PDF: German form (`1.234,50`), whatever the JVM locale. */
private fun BigDecimal.toMoneyString(): String = printedAmount(toPlainString())
