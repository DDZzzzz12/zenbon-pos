/**
 * Order receipt PDF generator.
 *
 * Creates a compact, receipt-style PDF (80 mm wide) from an order DTO,
 * suitable for IPP printing on standard (non-thermal) printers.
 * Typically used for kitchen/bar order tickets.
 *
 * @since 1.0.0
 */
package org.example.project.pdf.generator

import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.pdf.util.addCenteredParagraph
import org.example.project.pdf.util.addSeparator
import org.openpdf.text.Document
import org.openpdf.text.Font
import org.openpdf.text.Paragraph
import org.openpdf.text.Rectangle
import org.openpdf.text.pdf.BaseFont
import org.openpdf.text.pdf.PdfWriter
import java.io.ByteArrayOutputStream

/**
 * Generates a compact order receipt PDF as a byte array.
 *
 * The PDF uses an 80 mm page width (226 pt) to simulate a thermal receipt.
 * Content includes: order header (number, table, time) and itemised list
 * with optional side notes.
 *
 * @param order Order response DTO containing items and metadata
 * @return PDF document bytes ready for printing or streaming
 */
fun generateOrderReceiptPdfBytes(
    order: OrderDto.OrderResponseDto,
    takeAway: Boolean = false,
): ByteArray {
    val output = ByteArrayOutputStream()
    val pageSize = Rectangle(226f, 842f)
    val document = Document(pageSize, 10f, 10f, 10f, 10f)
    PdfWriter.getInstance(document, output)
    document.open()

    val baseFont = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED)
    val titleFont = Font(baseFont, 10f, Font.BOLD)
    val normalFont = Font(baseFont, 7f, Font.NORMAL)
    val boldFont = Font(baseFont, 7f, Font.BOLD)

    // Order header
    if (takeAway) {
        addCenteredParagraph(document, "--- MITNEHMEN ---", titleFont)
    }
    addCenteredParagraph(document, "BESTELLUNG", titleFont)
    document.add(Paragraph("Bestell-Nr: ${order.orderNumber}", normalFont))
    document.add(Paragraph("Tisch: ${order.tableNumber}", boldFont))
    document.add(Paragraph("Zeit: ${order.orderTime}", normalFont))

    addSeparator(document, normalFont)

    // Items
    order.orderItems.forEach { item ->
        val qty = item.orderItemIds.size
        val line = "${qty}x ${item.product.productName}"
        document.add(Paragraph(line, normalFont))
        item.sideNotes?.let { notes ->
            document.add(Paragraph("  >> $notes", Font(baseFont, 6f, Font.ITALIC)))
        }
    }

    addSeparator(document, normalFont)
    document.close()

    return output.toByteArray()
}
