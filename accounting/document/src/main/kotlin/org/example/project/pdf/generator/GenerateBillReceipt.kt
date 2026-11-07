/**
 * Bill receipt PDF generator.
 *
 * Creates a compact, receipt-style PDF (80 mm wide) from a bill DTO,
 * suitable for IPP printing on standard (non-thermal) printers.
 * Replicates the visual layout of an ESC/POS thermal receipt.
 *
 * @since 1.0.0
 */
package org.example.project.pdf.generator

import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.utils.formatLocalDateTime
import org.example.project.core.utils.printLabel
import org.example.project.core.utils.printedAmount
import org.example.project.core.utils.printedRate
import org.example.project.core.utils.printedRateOfFraction
import org.example.project.pdf.util.addCenteredParagraph
import org.example.project.pdf.util.addReceiptRow
import org.example.project.pdf.util.addSeparator
import org.example.project.utils.formatter.ReceiptSigns
import org.example.project.utils.formatter.TseReceiptText
import org.example.project.utils.formatter.getTimeZone
import org.example.project.utils.formatter.tseSigningNoteLines
import org.openpdf.text.Document
import org.openpdf.text.Element
import org.openpdf.text.Font
import org.openpdf.text.Paragraph
import org.openpdf.text.Phrase
import org.openpdf.text.Rectangle
import org.openpdf.text.pdf.BaseFont
import org.openpdf.text.pdf.PdfPCell
import org.openpdf.text.pdf.PdfPTable
import org.openpdf.text.pdf.PdfWriter
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * Generates a compact bill receipt PDF as a byte array.
 *
 * The PDF uses an 80 mm page width (226 pt) to simulate a thermal receipt.
 * Content includes: shop header, bill metadata, itemised list, totals,
 * VAT summary, payment information, and a closing "thank you" line.
 *
 * @param bill Bill response DTO containing items, totals, and payment data
 * @param shopHeader Shop details for the receipt header section
 * @return PDF document bytes ready for printing or streaming
 */
fun generateBillReceiptPdfBytes(
    bill: BillDto.BillResponseDto,
    shopHeader: ShopHeaderData,
): ByteArray {
    val output = ByteArrayOutputStream()
    // 80 mm width receipt format (226 points), variable height
    val pageSize = Rectangle(226f, 842f)
    val document = Document(pageSize, 10f, 10f, 10f, 10f)
    PdfWriter.getInstance(document, output)
    document.open()

    val baseFont = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED)
    val titleFont = Font(baseFont, 10f, Font.BOLD)
    val normalFont = Font(baseFont, 7f, Font.NORMAL)
    val boldFont = Font(baseFont, 7f, Font.BOLD)
    val smallFont = Font(baseFont, 6f, Font.NORMAL)
    val smallItalicFont = Font(baseFont, 6f, Font.ITALIC)

    // Shop header
    addCenteredParagraph(document, shopHeader.shopName, titleFont)
    shopHeader.shopAddress?.let { addCenteredParagraph(document, it, smallFont) }
    val cityLine = listOfNotNull(shopHeader.zipCode, shopHeader.shopCity).joinToString(" ")
    if (cityLine.isNotBlank()) addCenteredParagraph(document, cityLine, smallFont)
    shopHeader.shopPhone?.let { addCenteredParagraph(document, "Tel: $it", smallFont) }
    shopHeader.shopTaxNumber?.let { addCenteredParagraph(document, "Steuer-Nr: $it", smallFont) }

    addSeparator(document, normalFont)

    // A Schulungsbeleg says so at its head and foot, as on paper (emu20 FAIL-C).
    // Title large, notice in the body's bold size so it fits the 80 mm width on one line.
    val addTrainingMarking = {
        ReceiptSigns.trainingMarking(bill).forEachIndexed { index, line ->
            addCenteredParagraph(document, line, if (index == 0) titleFont else boldFont)
        }
    }
    addTrainingMarking()

    // Bill info
    // A Storno Beleg is headed as one and names the Beleg it reverses; a sale stays "RECHNUNG".
    val stornoHeading = ReceiptSigns.stornoHeading(bill)
    if (stornoHeading.isEmpty()) {
        addCenteredParagraph(document, "RECHNUNG", boldFont)
    } else {
        stornoHeading.forEach { addCenteredParagraph(document, it, boldFont) }
    }
    document.add(Paragraph("Nr: ${bill.billNumber}", normalFont))
    // The paper receipt's rendering (BillFormatter), not the raw ISO timestamp (emu22 NOTE-hh).
    document.add(Paragraph("Datum: ${bill.endTime.formatLocalDateTime(Language.GERMAN)}", normalFont))
    document.add(Paragraph("Tisch: ${bill.tableNumber}  Bed.: ${bill.employeeNumber}", normalFont))

    addSeparator(document, normalFont)

    // Items table
    val table = PdfPTable(2)
    table.widthPercentage = 100f
    table.setWidths(floatArrayOf(3f, 1f))

    bill.orderItems.forEach { item ->
        val qty = item.orderItemIds.size
        val itemCell = PdfPCell(Phrase("${qty}x ${item.product.productName}", normalFont))
        itemCell.border = Rectangle.NO_BORDER
        itemCell.horizontalAlignment = Element.ALIGN_LEFT
        table.addCell(itemCell)

        // A Storno takes the original's items back: negative, so they agree with Summe and Gesamt.
        val priceCell = PdfPCell(Phrase("${printedAmount(ReceiptSigns.itemAmount(bill, item.finalPrice))} €", normalFont))
        priceCell.border = Rectangle.NO_BORDER
        priceCell.horizontalAlignment = Element.ALIGN_RIGHT
        table.addCell(priceCell)

        // Extras
        item.extras?.takeIf { it.isNotEmpty() }?.forEach { extra ->
            val extraCell = PdfPCell(Phrase("  - ${extra.printLabel()}", smallFont))
            extraCell.border = Rectangle.NO_BORDER
            table.addCell(extraCell)

            val emptyCell = PdfPCell(Phrase("", smallFont))
            emptyCell.border = Rectangle.NO_BORDER
            table.addCell(emptyCell)
        }

        // Side notes
        if (!item.sideNotes.isNullOrBlank()) {
            val noteCell = PdfPCell(Phrase("  \"${item.sideNotes}\"", smallItalicFont))
            noteCell.border = Rectangle.NO_BORDER
            table.addCell(noteCell)

            val emptyCell = PdfPCell(Phrase("", smallFont))
            emptyCell.border = Rectangle.NO_BORDER
            table.addCell(emptyCell)
        }

        // Item discount
        if (item.discount > 0.0 && item.discount < 1.0) {
            val discountPercent = ((1 - item.discount) * 100).roundToInt()
            val discountCell = PdfPCell(Phrase("  -${printedRate(discountPercent.toString())} Rabatt", smallFont))
            discountCell.border = Rectangle.NO_BORDER
            table.addCell(discountCell)

            val emptyCell = PdfPCell(Phrase("", smallFont))
            emptyCell.border = Rectangle.NO_BORDER
            table.addCell(emptyCell)
        }
    }
    document.add(table)

    addSeparator(document, normalFont)

    // Totals
    val totalsTable = PdfPTable(2)
    totalsTable.widthPercentage = 100f
    totalsTable.setWidths(floatArrayOf(3f, 1f))

    addReceiptRow(totalsTable, "Summe:", "${printedAmount(bill.billAmount)} €", boldFont)

    // Bill-level discount. Signs via ReceiptSigns: a Storno's reductions are stored negated and
    // print as added back, its refunded tender as paid out — so the paper sums to its own signed data.
    if (ReceiptSigns.isNonZero(bill.discountAmount)) {
        addReceiptRow(totalsTable, "Rabatt:", "${printedAmount(ReceiptSigns.reduction(bill.discountAmount))} €", normalFont)
    }

    // Promotions — a Storno prints its reversed promotion total as one line
    ReceiptSigns.promotionLines(bill).forEach { (name, amount) ->
        addReceiptRow(totalsTable, name?.let { "Aktion: $it" } ?: "Aktion:", "${printedAmount(amount)} €", normalFont)
    }

    // Tips
    if (bill.tips.toBigDecimal().signum() != 0) {
        addReceiptRow(totalsTable, "Trinkgeld:", "${printedAmount(bill.tips)} €", normalFont)
    }

    addReceiptRow(totalsTable, "Gesamt:", "${printedAmount(bill.paidAmount)} €", boldFont)

    document.add(totalsTable)

    addSeparator(document, normalFont)

    // VAT breakdown
    val vatTable = PdfPTable(3)
    vatTable.widthPercentage = 100f
    vatTable.setWidths(floatArrayOf(1.5f, 1f, 1f))

    // VAT header
    addVatHeaderCell(vatTable, "MwSt.", smallFont)
    addVatHeaderCell(vatTable, "Netto", smallFont)
    addVatHeaderCell(vatTable, "Brutto", smallFont)

    bill.vatDetails.forEach { vat ->
        val vatPercent = printedRateOfFraction(vat.vatRate.toString())
        addVatCell(vatTable, "${vat.vatId} $vatPercent: ${printedAmount(vat.vatAmount)} €", smallFont, Element.ALIGN_LEFT)
        addVatCell(vatTable, "${printedAmount(vat.netAmount)} €", smallFont, Element.ALIGN_RIGHT)
        addVatCell(vatTable, "${printedAmount(vat.totalAmount)} €", smallFont, Element.ALIGN_RIGHT)
    }

    document.add(vatTable)

    addSeparator(document, normalFont)

    // Payment breakdown with amounts
    bill.payments.forEach { payment ->
        document.add(Paragraph("${payment.paymentTypeName}: ${printedAmount(ReceiptSigns.paymentAmount(bill, payment))} €", normalFont))

        // Voucher details
        if (payment.paymentTypeName.equals("Voucher", ignoreCase = true) ||
            payment.paymentTypeName.equals("Gutschein", ignoreCase = true)
        ) {
            bill.voucherRedeem?.forEach { voucher ->
                document.add(
                    Paragraph(
                        "  Nr: ${voucher.voucherNumber} (${printedAmount(voucher.redeemedAmount)} €)",
                        smallFont,
                    ),
                )
            }
        }
    }

    addTseBlock(document, bill, smallFont)

    document.add(Paragraph("\n", smallFont))
    addTrainingMarking()
    addCenteredParagraph(document, "Vielen Dank!", boldFont)

    document.close()
    return output.toByteArray()
}

/**
 * Renders the fiscal block §6 KassenSichV requires on the Beleg handed to the customer.
 *
 * AEAO zu § 146a AO, Nr. 2.4.4 lists the data: Transaktionsnummer, Vorgangsbeginn and -ende, the
 * serial numbers of the till (its TSE ClientID) and of the TSE, Signaturzähler and Prüfwert. § 6
 * KassenSichV would let a DSFinV-K Anhang I QR code replace them; this receipt prints them as
 * readable text instead and carries no QR. The shared [TseReceiptText] fixes the field list and
 * order for this PDF, the thermal receipt and the A4 invoice.
 *
 * The AEAO forbids rounding, truncating or altering the values the TSE returned, so they are printed
 * verbatim in a monospaced font, a value too long for the 80 mm width cut — never shortened — into
 * [RECEIPT_TSE_LINE_WIDTH]-character lines. Times are the TSE's Unix times in UTC, the form the
 * AEAO explicitly accepts, and say so in their label.
 *
 * `Erste Bestellung` stays a separate line: relying on the Tz. 2.7.2 Erleichterung (opening the
 * Kassenbeleg transaction only at billing, which this system does) requires the first Bestellung's
 * start time on the Beleg (AEAO Nr. 2.2.3.6.2).
 *
 * A bill that could not be signed immediately carries its Nachsignierung note, as on paper. One with
 * neither TSE data nor a note is genuinely unsecured (TSE outage, or SIMULATION — see BillMapper B2):
 * the failure notice is printed rather than silently omitting the block, so the receipt does not
 * read like a signed one.
 */
private fun addTseBlock(
    document: Document,
    bill: BillDto.BillResponseDto,
    smallFont: Font,
) {
    document.add(Paragraph("\n", smallFont))

    bill.firstOrderTseStartTime?.let { epochSeconds ->
        document.add(
            Paragraph("Erste Bestellung: ${formatTseEpochSeconds(epochSeconds)}", smallFont),
        )
    }

    val tse = bill.tseReceiptData
    val note = bill.tseSigningNote
    if (tse != null) {
        val monoFont = Font(Font.COURIER, smallFont.size, Font.NORMAL)
        TseReceiptText.lines(tse, RECEIPT_TSE_LINE_WIDTH).forEach { document.add(Paragraph(it, monoFont)) }
    }
    note?.let { tseSigningNoteLines(it).forEach { line -> document.add(Paragraph(line, smallFont)) } }
    if (tse == null && note == null) {
        document.add(Paragraph("TSE-Signatur: nicht verfügbar (Ausfall)", smallFont))
    }
}

/** The thermal receipt's 48 columns; Courier 6 pt × 48 = 173 pt, inside the 206 pt text width. */
private const val RECEIPT_TSE_LINE_WIDTH = 48

/**
 * Renders TSE epoch seconds as `dd.MM.yyyy HH:mm:ss` in the shop's local timezone — the same
 * rendering the thermal formatter uses, so the two receipt surfaces cannot disagree about when
 * ordering began.
 */
private fun formatTseEpochSeconds(epochSeconds: Long): String =
    Instant
        .fromEpochSeconds(epochSeconds)
        .toLocalDateTime(getTimeZone())
        .let {
            "%02d.%02d.%04d %02d:%02d:%02d".format(
                it.dayOfMonth,
                it.monthNumber,
                it.year,
                it.hour,
                it.minute,
                it.second,
            )
        }

private fun addVatHeaderCell(
    table: PdfPTable,
    text: String,
    font: Font,
) {
    val cell = PdfPCell(Phrase(text, font))
    cell.border = Rectangle.NO_BORDER
    cell.borderWidthBottom = 0.5f
    cell.horizontalAlignment = Element.ALIGN_CENTER
    table.addCell(cell)
}

private fun addVatCell(
    table: PdfPTable,
    text: String,
    font: Font,
    alignment: Int,
) {
    val cell = PdfPCell(Phrase(text, font))
    cell.border = Rectangle.NO_BORDER
    cell.horizontalAlignment = alignment
    table.addCell(cell)
}
