/**
 * Eigenbeleg (self-issued receipt) PDF generator.
 *
 * Creates a simple A4 PDF document for Kassenbuch entries that do not have
 * an external invoice attached. Used for audit compliance — every Kassenbuch
 * entry must have a linked document.
 *
 * Layout:
 * - Shop name and address at top
 * - Title: "Eigenbeleg Nr. {belegNr}"
 * - Date, entry type, amount, description
 * - Signature area placeholder
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.pdf.generator

import org.example.project.pdf.util.addDetailRow
import org.openpdf.text.Document
import org.openpdf.text.Element
import org.openpdf.text.Font
import org.openpdf.text.PageSize
import org.openpdf.text.Paragraph
import org.openpdf.text.pdf.BaseFont
import org.openpdf.text.pdf.PdfPTable
import org.openpdf.text.pdf.PdfWriter
import org.openpdf.text.pdf.draw.LineSeparator
import java.awt.Color
import java.io.ByteArrayOutputStream

/**
 * Generates an Eigenbeleg (self-issued receipt) PDF as a byte array.
 *
 * The PDF uses an A4 page format with a clean, professional layout suitable
 * for German accounting audit requirements (GoBD compliance).
 *
 * When [correctsBelegNr] is non-blank the document is rendered as a
 * Storno-Eigenbeleg: the title changes to "Storno-Eigenbeleg Nr. $belegNr"
 * and two additional detail rows are appended ("Storniert:" and "Grund:").
 *
 * @param belegNr Sequential booking number for the Kassenbuch entry
 * @param date Formatted German date string (e.g., "28.02.2026")
 * @param entryType German label for the entry type (e.g., "Privatentnahme", "Bareinnahme")
 * @param amount Formatted amount with currency (e.g., "100,00 EUR")
 * @param description Belegtext/purpose of the entry
 * @param shopName Name of the shop/business
 * @param shopAddress Optional shop address line
 * @param correctsBelegNr Beleg-Nr. of the original entry being reversed (null for ordinary Eigenbeleg)
 * @param correctsDate Booking date of the original entry (rendered alongside [correctsBelegNr])
 * @param reason Mandatory correction reason (GoBD); rendered in the "Grund:" row
 * @return PDF document bytes
 */
@Suppress("DefaultLocale")
fun generateEigenbelegPdfBytes(
    belegNr: String,
    date: String,
    entryType: String,
    amount: String,
    description: String,
    shopName: String,
    shopAddress: String?,
    correctsBelegNr: String? = null,
    correctsDate: String? = null,
    reason: String? = null,
): ByteArray {
    val output = ByteArrayOutputStream()
    val document = Document(PageSize.A4, 50f, 50f, 50f, 50f)
    PdfWriter.getInstance(document, output)
    document.open()

    val baseFont = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED)
    val baseFontBold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED)
    val titleFont = Font(baseFontBold, 18f, Font.BOLD)
    val headerFont = Font(baseFontBold, 11f, Font.BOLD)
    val normalFont = Font(baseFont, 10f, Font.NORMAL)
    val labelFont = Font(baseFontBold, 10f, Font.BOLD)
    val smallFont = Font(baseFont, 8f, Font.NORMAL, Color.GRAY)

    // Shop header
    val shopParagraph = Paragraph(shopName, headerFont)
    shopParagraph.alignment = Element.ALIGN_LEFT
    document.add(shopParagraph)

    if (!shopAddress.isNullOrBlank()) {
        val addressParagraph = Paragraph(shopAddress, normalFont)
        addressParagraph.alignment = Element.ALIGN_LEFT
        document.add(addressParagraph)
    }

    // Separator line
    document.add(Paragraph(" ", normalFont))
    document.add(LineSeparator(1f, 100f, Color.DARK_GRAY, Element.ALIGN_CENTER, -2f))
    document.add(Paragraph(" ", normalFont))

    // Title
    val isCorrection = !correctsBelegNr.isNullOrBlank()
    val titleText = if (isCorrection) "Storno-Eigenbeleg Nr. $belegNr" else "Eigenbeleg Nr. $belegNr"
    val title = Paragraph(titleText, titleFont)
    title.alignment = Element.ALIGN_CENTER
    title.spacingAfter = 20f
    document.add(title)

    // Details table
    val detailsTable = PdfPTable(2)
    detailsTable.widthPercentage = 80f
    detailsTable.setWidths(floatArrayOf(1.5f, 3f))
    detailsTable.setSpacingBefore(10f)
    detailsTable.setSpacingAfter(20f)

    addDetailRow(detailsTable, "Datum:", date, labelFont)
    addDetailRow(detailsTable, "Belegart:", entryType, normalFont)
    addDetailRow(detailsTable, "Betrag:", amount, labelFont)
    addDetailRow(detailsTable, "Verwendungszweck:", description, normalFont)

    if (isCorrection) {
        val storniert =
            buildString {
                append("Beleg-Nr. ")
                append(correctsBelegNr)
                if (!correctsDate.isNullOrBlank()) {
                    append(" vom ")
                    append(correctsDate)
                }
            }
        addDetailRow(detailsTable, "Storniert:", storniert, labelFont)
        addDetailRow(detailsTable, "Grund:", reason ?: "", normalFont)
    }

    document.add(detailsTable)

    // Separator
    document.add(LineSeparator(0.5f, 80f, Color.LIGHT_GRAY, Element.ALIGN_CENTER, -2f))
    document.add(Paragraph(" ", normalFont))
    document.add(Paragraph(" ", normalFont))

    // Footer note
    val footerNote =
        Paragraph(
            "Dieser Eigenbeleg wurde automatisch vom System erstellt.",
            smallFont,
        )
    footerNote.spacingBefore = 30f
    footerNote.alignment = Element.ALIGN_CENTER
    document.add(footerNote)

    document.close()
    return output.toByteArray()
}
