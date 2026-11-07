/**
 * Reference print PDF generator.
 *
 * Creates a compact, receipt-style PDF (80 mm wide) from order items,
 * suitable for IPP printing on standard (non-thermal) printers.
 * Used as a pre-bill reference printout showing open table items.
 *
 * @since 1.0.0
 */
package org.example.project.pdf.generator

import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.core.utils.printLabel
import org.example.project.core.utils.printedAmount
import org.example.project.pdf.util.addCenteredParagraph
import org.example.project.pdf.util.addSeparator
import org.openpdf.text.Document
import org.openpdf.text.Element
import org.openpdf.text.Font
import org.openpdf.text.Phrase
import org.openpdf.text.Rectangle
import org.openpdf.text.pdf.BaseFont
import org.openpdf.text.pdf.PdfPCell
import org.openpdf.text.pdf.PdfPTable
import org.openpdf.text.pdf.PdfWriter
import java.io.ByteArrayOutputStream
import java.math.BigDecimal

/**
 * Generates a compact reference print PDF as a byte array.
 *
 * The PDF uses an 80 mm page width (226 pt) to simulate a thermal receipt.
 * Content includes: shop header, table/employee metadata, itemised list,
 * subtotal, and VAT breakdown grouped by VAT rate.
 *
 * @param shopHeader Shop details for the receipt header section
 * @param tableNumber Table number string for display
 * @param employeeNumber Employee number string for display
 * @param dateTime Formatted date/time string for display
 * @param items List of order item response DTOs to render
 * @return PDF document bytes ready for printing or streaming
 */
fun generateReferencePrintPdfBytes(
    shopHeader: ShopHeaderData,
    tableNumber: String,
    employeeNumber: String,
    dateTime: String,
    items: List<OrderItemDto.OrderItemResponseDto>,
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

    // Metadata: 2-column table (datetime left, table right; empty left, employee right)
    val metaTable = PdfPTable(2)
    metaTable.widthPercentage = 100f

    val dateCell = PdfPCell(Phrase(dateTime, normalFont))
    dateCell.border = Rectangle.NO_BORDER
    dateCell.horizontalAlignment = Element.ALIGN_LEFT
    metaTable.addCell(dateCell)

    val tableCell = PdfPCell(Phrase("Tisch: $tableNumber", normalFont))
    tableCell.border = Rectangle.NO_BORDER
    tableCell.horizontalAlignment = Element.ALIGN_RIGHT
    metaTable.addCell(tableCell)

    val emptyMetaCell = PdfPCell(Phrase("", normalFont))
    emptyMetaCell.border = Rectangle.NO_BORDER
    metaTable.addCell(emptyMetaCell)

    val employeeCell = PdfPCell(Phrase("Bedienung: $employeeNumber", normalFont))
    employeeCell.border = Rectangle.NO_BORDER
    employeeCell.horizontalAlignment = Element.ALIGN_RIGHT
    metaTable.addCell(employeeCell)

    document.add(metaTable)

    addSeparator(document, normalFont)

    // Items table: 2-column (3:1 ratio)
    val itemsTable = PdfPTable(2)
    itemsTable.widthPercentage = 100f
    itemsTable.setWidths(floatArrayOf(3f, 1f))

    items.forEach { item ->
        val qty = item.orderItemIds.size
        val itemCell = PdfPCell(Phrase("${qty}x ${item.product.productName}", normalFont))
        itemCell.border = Rectangle.NO_BORDER
        itemCell.horizontalAlignment = Element.ALIGN_LEFT
        itemsTable.addCell(itemCell)

        val priceCell = PdfPCell(Phrase("${printedAmount(item.finalPrice)} EUR", normalFont))
        priceCell.border = Rectangle.NO_BORDER
        priceCell.horizontalAlignment = Element.ALIGN_RIGHT
        itemsTable.addCell(priceCell)

        // Extras indented
        item.extras?.takeIf { it.isNotEmpty() }?.forEach { extra ->
            val extraCell = PdfPCell(Phrase("  - ${extra.printLabel()}", smallFont))
            extraCell.border = Rectangle.NO_BORDER
            itemsTable.addCell(extraCell)

            val emptyCell = PdfPCell(Phrase("", smallFont))
            emptyCell.border = Rectangle.NO_BORDER
            itemsTable.addCell(emptyCell)
        }

        // Side notes indented italic
        if (!item.sideNotes.isNullOrBlank()) {
            val noteCell = PdfPCell(Phrase("  ${item.sideNotes}", smallItalicFont))
            noteCell.border = Rectangle.NO_BORDER
            itemsTable.addCell(noteCell)

            val emptyCell = PdfPCell(Phrase("", smallFont))
            emptyCell.border = Rectangle.NO_BORDER
            itemsTable.addCell(emptyCell)
        }
    }
    document.add(itemsTable)

    addSeparator(document, normalFont)

    // Subtotal — summed in BigDecimal (money is never a Double) and printed in German form whatever
    // the JVM locale, like the receipt and Z-Bon.
    val total = items.fold(BigDecimal.ZERO) { acc, item -> acc + (item.finalPrice.toBigDecimalOrNull() ?: BigDecimal.ZERO) }
    val totalFormatted = printedAmount(total.toPlainString())

    val totalsTable = PdfPTable(2)
    totalsTable.widthPercentage = 100f
    totalsTable.setWidths(floatArrayOf(3f, 1f))

    val sumLabelCell = PdfPCell(Phrase("SUMME:", boldFont))
    sumLabelCell.border = Rectangle.NO_BORDER
    sumLabelCell.horizontalAlignment = Element.ALIGN_RIGHT
    totalsTable.addCell(sumLabelCell)

    val sumValueCell = PdfPCell(Phrase("$totalFormatted EUR", boldFont))
    sumValueCell.border = Rectangle.NO_BORDER
    sumValueCell.horizontalAlignment = Element.ALIGN_RIGHT
    totalsTable.addCell(sumValueCell)

    document.add(totalsTable)

//    addSeparator(document, normalFont)

//    // VAT breakdown grouped by vatRate
//    val vatTable = PdfPTable(4)
//    vatTable.widthPercentage = 100f
//    vatTable.setWidths(floatArrayOf(1.5f, 1f, 1f, 1f))
//
//    // Header row
//    addReferencePrintVatHeaderCell(vatTable, "MWST-Satz", smallFont)
//    addReferencePrintVatHeaderCell(vatTable, "MWST", smallFont)
//    addReferencePrintVatHeaderCell(vatTable, "NETTO", smallFont)
//    addReferencePrintVatHeaderCell(vatTable, "BRUTTO", smallFont)
//
//    // Group items by vatRate
//    val vatGroups = items.groupBy { it.vatRate }
//    vatGroups.forEach { (vatRate, groupItems) ->
//        val brutto = groupItems.sumOf { it.finalPrice.toDoubleOrNull() ?: 0.0 }
//        val vatFactor = vatRate
//        val vatAmount = brutto - (brutto / (1.0 + vatFactor))
//        val netto = brutto - vatAmount
//
//        val vatPercent = vatRate.toPercentage()
//        addReferencePrintVatDataCell(vatTable, "$vatPercent%", smallFont, Element.ALIGN_LEFT)
//        addReferencePrintVatDataCell(vatTable, "%.2f".format(vatAmount), smallFont, Element.ALIGN_RIGHT)
//        addReferencePrintVatDataCell(vatTable, "%.2f".format(netto), smallFont, Element.ALIGN_RIGHT)
//        addReferencePrintVatDataCell(vatTable, "%.2f".format(brutto), smallFont, Element.ALIGN_RIGHT)
//    }
//
//    document.add(vatTable)

    document.close()
    return output.toByteArray()
}

private fun addReferencePrintVatHeaderCell(
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

private fun addReferencePrintVatDataCell(
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
