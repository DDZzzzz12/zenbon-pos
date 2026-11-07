/**
 * Utility functions for creating and styling PDF tables using OpenPDF.
 *
 * Provides reusable helper functions for consistent table formatting
 * across PDF generation operations, particularly for invoice layouts.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.pdf.util

import org.openpdf.text.Element
import org.openpdf.text.Font
import org.openpdf.text.Phrase
import org.openpdf.text.Rectangle
import org.openpdf.text.pdf.PdfPCell
import org.openpdf.text.pdf.PdfPTable
import java.awt.Color

/**
 * Helper function to add detail rows to the invoice details table.
 *
 * Creates a two-column row with label and value, using borderless cells
 * with consistent bottom padding.
 *
 * @param table The PdfPTable to add the row to
 * @param label The label text (left column)
 * @param value The value text (right column)
 * @param font The font to use for both cells
 */
fun addDetailRow(
    table: PdfPTable,
    label: String,
    value: String,
    font: Font,
) {
    val labelCell = PdfPCell(Phrase(label, font))
    labelCell.border = Rectangle.NO_BORDER
    labelCell.paddingBottom = 5f

    val valueCell = PdfPCell(Phrase(value, font))
    valueCell.border = Rectangle.NO_BORDER
    valueCell.paddingBottom = 5f

    table.addCell(labelCell)
    table.addCell(valueCell)
}

/**
 * Helper function to add header cells to the item table.
 *
 * Creates a styled header cell with gray background and consistent padding.
 *
 * @param table The PdfPTable to add the header to
 * @param text The header text
 * @param font The font to use for the header
 */
fun addTableHeader(
    table: PdfPTable,
    text: String,
    font: Font,
) {
    val cell = PdfPCell(Phrase(text, font))
    cell.backgroundColor = Color(230, 230, 230)
    cell.horizontalAlignment = Element.ALIGN_LEFT
    cell.paddingTop = 5f
    cell.paddingBottom = 5f
    table.addCell(cell)
}

/**
 * Helper function to create table cells with consistent styling.
 *
 * Creates a PdfPCell with specified text, font, and alignment,
 * including standard padding for visual consistency.
 *
 * @param text The text content of the cell
 * @param font The font to use for the cell
 * @param alignment The horizontal alignment (defaults to left)
 * @return Configured PdfPCell instance
 */
fun createCell(
    text: String,
    font: Font,
    alignment: Int = Element.ALIGN_LEFT,
): PdfPCell = createCell(Phrase(text, font), alignment)

/**
 * [createCell] for content assembled from several chunks — e.g. a label whose amount must not be
 * split across lines. Same padding and alignment as the text form.
 */
fun createCell(
    phrase: Phrase,
    alignment: Int = Element.ALIGN_LEFT,
): PdfPCell {
    val cell = PdfPCell(phrase)
    cell.horizontalAlignment = alignment
    cell.paddingTop = 5f
    cell.paddingBottom = 5f
    return cell
}

/**
 * Helper function to add total rows to the totals table.
 *
 * Creates a two-column row for financial totals with borderless cells,
 * right-aligned values, and consistent padding.
 *
 * @param table The PdfPTable to add the row to
 * @param label The label text (left column)
 * @param value The value text (right column, right-aligned)
 * @param font The font to use for both cells
 */
fun addTotalRow(
    table: PdfPTable,
    label: String,
    value: String,
    font: Font,
) {
    addRow(table, font, alignment = null, label, value)
}

/**
 * Helper function to add a multi-column total row to a table.
 *
 * By default the first column is left-aligned (label) and remaining columns
 * are right-aligned (values). Pass [alignment] to apply a uniform alignment
 * to all cells instead.
 *
 * @param table The PdfPTable to add the row to
 * @param font The font to use for all cells
 * @param alignment Optional uniform alignment for all cells (e.g. [Element.ALIGN_CENTER]).
 *                  When null, first column is left-aligned, rest are right-aligned.
 * @param values The cell texts — column count must match the table
 */
fun addRow(
    table: PdfPTable,
    font: Font,
    alignment: Int? = null,
    vararg values: String,
) {
    values.forEachIndexed { index, text ->
        val cell = PdfPCell(Phrase(text, font))
        cell.border = Rectangle.NO_BORDER
        cell.paddingBottom = 3f
        cell.horizontalAlignment = alignment
            ?: if (index == 0) Element.ALIGN_LEFT else Element.ALIGN_RIGHT
        table.addCell(cell)
    }
}

/**
 * Helper function to add a row where the label spans all columns except the last.
 *
 * Useful for tables with more than 2 columns where a label-value pair
 * should visually occupy just two positions (e.g. tip rows in a 3-column VAT table).
 *
 * @param table The PdfPTable to add the row to
 * @param label The label text (spans all columns except last, left-aligned)
 * @param value The value text (last column, right-aligned)
 * @param font The font to use for both cells
 */
fun addSpannedRow(
    table: PdfPTable,
    label: String,
    value: String,
    font: Font,
) {
    val labelCell = PdfPCell(Phrase(label, font))
    labelCell.border = Rectangle.NO_BORDER
    labelCell.paddingBottom = 3f
    labelCell.colspan = table.numberOfColumns - 1

    val valueCell = PdfPCell(Phrase(value, font))
    valueCell.border = Rectangle.NO_BORDER
    valueCell.horizontalAlignment = Element.ALIGN_RIGHT
    valueCell.paddingBottom = 3f

    table.addCell(labelCell)
    table.addCell(valueCell)
}
