/**
 * Shared utility functions for receipt-style PDF generation.
 *
 * Provides common helpers used by both bill and order receipt generators
 * for consistent formatting of narrow (80 mm) receipt PDFs.
 *
 * @since 1.0.0
 */
package org.example.project.pdf.util

import org.openpdf.text.Document
import org.openpdf.text.Element
import org.openpdf.text.Font
import org.openpdf.text.Paragraph
import org.openpdf.text.Phrase
import org.openpdf.text.Rectangle
import org.openpdf.text.pdf.PdfPCell
import org.openpdf.text.pdf.PdfPTable

/**
 * Adds a centered paragraph to the document.
 *
 * @param document The PDF document to add the paragraph to
 * @param text The text content
 * @param font The font to use
 */
internal fun addCenteredParagraph(
    document: Document,
    text: String,
    font: Font,
) {
    val p = Paragraph(text, font)
    p.alignment = Element.ALIGN_CENTER
    document.add(p)
}

/**
 * Adds a dashed separator line to the document.
 *
 * @param document The PDF document to add the separator to
 * @param font The font to use for the dashes
 */
internal fun addSeparator(
    document: Document,
    font: Font,
) {
    val p = Paragraph("--------------------------------", font)
    p.alignment = Element.ALIGN_CENTER
    document.add(p)
}

/**
 * Adds a two-column row to a receipt table (label left, value right).
 *
 * @param table The PdfPTable to add the row to
 * @param label The label text (left column)
 * @param value The value text (right column)
 * @param font The font to use for both cells
 */
internal fun addReceiptRow(
    table: PdfPTable,
    label: String,
    value: String,
    font: Font,
) {
    val labelCell = PdfPCell(Phrase(label, font))
    labelCell.border = Rectangle.NO_BORDER
    labelCell.horizontalAlignment = Element.ALIGN_LEFT
    table.addCell(labelCell)

    val valueCell = PdfPCell(Phrase(value, font))
    valueCell.border = Rectangle.NO_BORDER
    valueCell.horizontalAlignment = Element.ALIGN_RIGHT
    table.addCell(valueCell)
}
