/**
 * Formatter for customer bill receipts.
 *
 * Produces an ESC/POS byte array containing the full receipt:
 * shop header, bill metadata, item list, payment breakdown,
 * VAT summary, the readable TSE data block, and closing message.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.formatter

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.utils.formatLocalDateTime
import org.example.project.core.utils.printedRateOfFraction
import org.example.project.printer.core.emitter.EscPosEmitter
import org.example.project.printer.core.enums.Alignment
import org.example.project.printer.core.enums.FontSize
import org.example.project.printer.core.utils.TextFormatUtils
import org.example.project.printer.core.utils.TextFormatUtils.ColumnAlign
import org.example.project.printer.core.utils.printAmount
import org.example.project.printer.core.utils.printMoney
import org.example.project.utils.formatter.ReceiptSigns
import org.example.project.utils.formatter.TseReceiptText
import org.example.project.utils.formatter.toBigDecimal
import org.example.project.utils.formatter.tseSigningNoteLines

/**
 * Formats customer bill receipts for thermal printer output.
 */
class BillFormatter : BaseDocumentFormatter() {
    /**
     * Renders TSE epoch seconds as a readable `dd.MM.yyyy HH:mm:ss` in the shop's local
     * timezone — used for the Tz.-2.7.2 "Erste Bestellung" print line.
     */
    private fun formatEpochSecondsLocal(epochSeconds: Long): String {
        val local =
            java.time.Instant
                .ofEpochSecond(epochSeconds)
                .atZone(java.time.ZoneId.systemDefault())
        return LOCAL_DATE_TIME_FORMAT.format(local)
    }

    private companion object {
        private val LOCAL_DATE_TIME_FORMAT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter
                .ofPattern("dd.MM.yyyy HH:mm:ss")
    }

    /**
     * Prints [ReceiptSigns.trainingMarking] centred and bold, the title double-size; nothing for a
     * bill that is not a Schulungsbeleg. Leaves the emitter left-aligned at normal size.
     */
    private fun EscPosEmitter.printTrainingMarking(bill: BillDto.BillResponseDto) {
        val marking = ReceiptSigns.trainingMarking(bill)
        if (marking.isEmpty()) return
        align(Alignment.CENTER)
        bold(true)
        marking.forEachIndexed { index, line ->
            fontSize(if (index == 0) FontSize.DOUBLE else FontSize.NORMAL)
            textLine(line)
        }
        fontSize(FontSize.NORMAL)
        bold(false)
        newLine()
        align(Alignment.LEFT)
    }

    /**
     * Format a bill receipt.
     *
     * @param bill Bill data to format
     * @param shopHeader Shop header data for the receipt header
     * @param language Language for date formatting
     * @return Result containing ESC/POS byte array or error
     */
    suspend fun format(
        bill: BillDto.BillResponseDto,
        shopHeader: ShopHeaderData,
        language: Language,
        bewirtungsbeleg: Boolean = false,
    ): ByteArray {
        val codePage = codePageFor(language)
        return formatDocument(codePage = codePage) {
            initialize()

            // Enable Kanji mode for CJK languages so printer interprets multi-byte chars
            if (language == Language.CHINESE) munbynChineseMode()

            // ==================== SHOP HEADER ====================
            align(Alignment.CENTER)
            fontSize(FontSize.DOUBLE)
            bold(true)
            textLine(shopHeader.shopName)

            // Shop details (centered, normal text)
            fontSize(FontSize.NORMAL)
            shopHeader.shopAddress?.let { textLine(it) }
            textLine("${shopHeader.shopCity}, ${shopHeader.zipCode ?: ""}")
            shopHeader.shopPhone?.let { textLine(it) }
            shopHeader.shopTaxNumber?.let { text(it) }
            bold(false)
            feedLines(2)
            newLine()

            // ==================== SCHULUNGSBELEG (head) ====================
            printTrainingMarking(bill)

            // ==================== BILL METADATA ====================
            align(Alignment.LEFT)
            fontSize(FontSize.NORMAL)

            text(
                TextFormatUtils.formatColumn("Tisch: ${bill.tableNumber}", 24, ColumnAlign.LEFT) +
                    TextFormatUtils.formatColumn(bill.startTime.formatLocalDateTime(Language.GERMAN), 24, ColumnAlign.RIGHT),
            )
            newLine()

            text(
                TextFormatUtils.formatColumn("Bedienung: ${bill.employeeNumber}", 24, ColumnAlign.LEFT) +
                    TextFormatUtils.formatColumn(bill.endTime.formatLocalDateTime(Language.GERMAN), 24, ColumnAlign.RIGHT),
            )
            newLine()

            text(bill.billNumber)
            newLine()

            // A Storno Beleg names itself and the Beleg it reverses, like the old correction header.
            ReceiptSigns.stornoHeading(bill).forEach { line ->
                bold(true)
                textLine(line)
                bold(false)
            }

            // Separator (double height/width, bold)
            fontSize(FontSize.DOUBLE)
            bold(true)
            separator()

            // ==================== ITEMS ====================
            fontSize(FontSize.NORMAL)

            bill.orderItems.sortedBy { it.product.productType }.forEach { item ->
                // A Storno takes the original's items back: negative, so they agree with SUMME and GESAMT.
                printOrderItem(item, MAX_CHARS_SMALL, language, price = ReceiptSigns.itemAmount(bill, item.finalPrice))
            }

            // Separator
            fontSize(FontSize.DOUBLE)
            bold(true)
            separator()

            bold(false)
            fontSize(FontSize.NORMAL)

            // SUMME
            text(totalsRow("SUMME: ", printMoney(bill.billAmount, codePage)))

            // RABATT (only when discount was applied). Signs via ReceiptSigns: a Storno's reductions
            // are stored negated and print as added back, so the paper sums to its own QR.
            if (ReceiptSigns.isNonZero(bill.discountAmount)) {
                newLine()
                text(totalsRow("RABATT", printMoney(ReceiptSigns.reduction(bill.discountAmount), codePage)))
            }

            // AKTIONEN (promotions) — a Storno prints its reversed promotion total as one line.
            // The old 4-way quarter split gave the name only MAX_CHARS_SMALL/4 (12) characters
            // including the "Aktion: " prefix, so "Rabatt Wein A" printed as "Aktion: Raba".
            // labelAmountRow lets the name take the whole line apart from the amount; only a name
            // too long to share a line with its amount wraps onto further lines.
            ReceiptSigns.promotionLines(bill).forEach { (name, amount) ->
                newLine()
                val label = name?.let { "Aktion: $it" } ?: "Aktion"
                val amountText = printMoney(amount, codePage)
                if (label.length + 1 + amountText.length <= MAX_CHARS_SMALL) {
                    text(TextFormatUtils.labelAmountRow(label, amountText, MAX_CHARS_SMALL))
                } else {
                    TextFormatUtils.wordWrap(label, MAX_CHARS_SMALL).forEach { textLine(it) }
                    text(TextFormatUtils.formatColumn(amountText, MAX_CHARS_SMALL, ColumnAlign.RIGHT))
                }
            }

            // TRINKGELD — a Storno's tip is stored negative and is printed too
            newLine()

            if (ReceiptSigns.isNonZero(bill.tips)) {
                text(totalsRow("TRINKGELD", printMoney(bill.tips, codePage)))
                newLine()
            }

            // ==================== PAYMENTS ====================
            bill.payments.forEach { payment ->
                newLine()
                text(totalsRow(payment.paymentTypeName, printMoney(ReceiptSigns.paymentAmount(bill, payment), codePage)))

                // Voucher detail
                if (payment.paymentTypeName.equals("Voucher", ignoreCase = true) ||
                    payment.paymentTypeName.equals("Gutschein", ignoreCase = true)
                ) {
                    bill.voucherRedeem?.forEach { voucher ->
                        if (toBigDecimal(voucher.redeemedAmount).compareTo(toBigDecimal(payment.amount)) == 0) {
                            newLine()
                            text(
                                TextFormatUtils.formatColumn(" ", MAX_CHARS_SMALL / 2, ColumnAlign.LEFT) +
                                    TextFormatUtils.formatColumn(
                                        "Nr: ${voucher.voucherNumber} (${voucher.voucherSource.german})",
                                        MAX_CHARS_SMALL / 2,
                                        ColumnAlign.RIGHT,
                                    ),
                            )
                        }
                    }
                }
            }

            // ==================== TOTAL ====================
            fontSize(FontSize.DOUBLE)
            bold(true)
            newLine()
            newLine()
            text(
                TextFormatUtils.formatColumn("GESAMT: ", 8, ColumnAlign.LEFT) +
                    TextFormatUtils.formatColumn(printMoney(bill.paidAmount, codePage), 16, ColumnAlign.RIGHT),
            )
            newLine()
            newLine()

            // ==================== VAT SUMMARY TABLE ====================
            fontSize(FontSize.NORMAL)

            fourColumns("MWST-Satz", "MWST", "NETTO", "BRUTTO")
            newLine()

            bill.vatDetails.forEach { vat ->
                fourColumns(
                    "${vat.vatId} ${printedRateOfFraction(vat.vatRate.toString())}",
                    printAmount(vat.vatAmount),
                    printAmount(vat.netAmount),
                    printAmount(vat.totalAmount),
                )
                newLine()
            }

            // Tz. 2.7.2 relief precondition: the FIRST Bestellung's start time must be READABLE on
            // the Beleg (not only inside the QR) — Anhang I p. 122 (round-3 TSER-3).
            bill.firstOrderTseStartTime?.let { epochSeconds ->
                align(Alignment.CENTER)
                textLine("Erste Bestellung: ${formatEpochSecondsLocal(epochSeconds)}")
            }

            // ==================== TSE DATA (readable, no QR) ====================
            // AEAO zu § 146a AO Nr. 2.4.4: TANR, Vorgangsbeginn/-ende, Signaturzähler, both serial
            // numbers and the Prüfwert, printed exactly as the TSE returned them. The signature is
            // broken across lines without dropping a character; nothing is truncated.
            bill.tseReceiptData?.let { tse ->
                newLine()
                align(Alignment.LEFT)
                fontSize(FontSize.NORMAL)
                TseReceiptText.lines(tse, MAX_CHARS_SMALL).forEach { textLine(it) }
                newLine()
            }

            // ==================== NACHSIGNIERUNG ====================
            // A bill the TSE could not sign immediately says so, and keeps saying it after the late
            // signature (tse_late_signing, V82) — otherwise it reads like one signed on time.
            bill.tseSigningNote?.let { note ->
                align(Alignment.CENTER)
                fontSize(FontSize.NORMAL)
                tseSigningNoteLines(note).forEach { textLine(it) }
                newLine()
            }

            // ==================== BEWIRTUNGSBELEG ====================
            if (bewirtungsbeleg) {
                feedLines(2)
                align(Alignment.CENTER)
                fontSize(FontSize.DOUBLE)
                bold(true)
                textLine("BEWIRTUNGSBELEG")
                fontSize(FontSize.NORMAL)
                bold(false)
                textLine("gemäß ${'\u00A7'} 4 Abs. 5 Nr. 2 EStG")
                feedLines(1)

                // Blank fields for customer to fill in
                align(Alignment.LEFT)
                textLine("Bewirtete Personen:")
                feedLines(1)
                for (i in 1..5) {
                    feedLines(1)
                    textLine(TextFormatUtils.blankLine(MAX_CHARS_SMALL, "$i. "))
                }
                feedLines(1)

                textLine("Anlass der Bewirtung:")
                feedLines(1)
                textLine(TextFormatUtils.blankLine(MAX_CHARS_SMALL))
                textLine(TextFormatUtils.blankLine(MAX_CHARS_SMALL))
                feedLines(2)

                feedLines(1)
                textLine("Ort, Datum")
                feedLines(2)
                textLine(TextFormatUtils.blankLine(MAX_CHARS_SMALL))
                feedLines(1)
                textLine("Unterschrift des Bewirtenden")
                feedLines(2)
                textLine(TextFormatUtils.blankLine(MAX_CHARS_SMALL))
            }

            // ==================== SCHULUNGSBELEG (foot) ====================
            // Repeated at the foot so a torn-off or folded slip still reads as training.
            if (bill.isTraining) {
                feedLines(1)
                printTrainingMarking(bill)
            }

            // ==================== CLOSING MESSAGE ====================
            feedLines(2)
            fontSize(FontSize.NORMAL)
            align(Alignment.CENTER)
            textLine("Vielen Dank f${'\u00FC'}r Ihren Besuch!")
            textLine("Wir freuen uns auf ein Wiedersehen.")

            // ==================== FINALIZE ====================
            feedLines(2)
            cut()
        }
    }

    /**
     * A totals row in the right half of the paper: label left, amount right-aligned at the edge.
     *
     * Byte-identical to the former label-quarter + amount-quarter layout whenever both fit, but a
     * grouped German amount (`"12.345,67 EUR"`, 13 chars) takes room from the label instead of being
     * cut to its quarter.
     */
    private fun totalsRow(
        label: String,
        amount: String,
    ): String =
        TextFormatUtils.formatColumn("", MAX_CHARS_SMALL / 2, ColumnAlign.LEFT) +
            TextFormatUtils.labelAmountRow(label, amount, MAX_CHARS_SMALL / 2)
}
