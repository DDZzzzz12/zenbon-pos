/**
 * Reusable template for generating German business letters following DIN 676 standards.
 *
 * Provides consistent layout and formatting for various document types including:
 * - Formal invoices (Rechnungen)
 * - General business letters (Geschäftsbriefe)
 * - Quotes/Estimates (Angebote)
 * - Payment reminders (Zahlungserinnerungen)
 * - Delivery notes (Lieferscheine)
 *
 * DIN 676 Layout Standards:
 * - A4 page size (210mm x 297mm)
 * - Return address: 45mm from top (window envelope fold-back area)
 * - Recipient address: 50mm from top, 20mm from left (window envelope compatible)
 * - Company header: Top-right corner
 * - Footer: Bank details, tax info, contact information at page bottom
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.pdf.template

import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.openpdf.text.Document
import org.openpdf.text.Element
import org.openpdf.text.Font
import org.openpdf.text.PageSize
import org.openpdf.text.Paragraph
import org.openpdf.text.Phrase
import org.openpdf.text.Rectangle
import org.openpdf.text.pdf.ColumnText
import org.openpdf.text.pdf.PdfPCell
import org.openpdf.text.pdf.PdfPTable
import org.openpdf.text.pdf.PdfPageEventHelper
import org.openpdf.text.pdf.PdfWriter
import java.io.File
import java.io.FileOutputStream

/**
 * German business letter template builder following DIN 676 standards.
 *
 * Usage example:
 * ```kotlin
 * val template = GermanBusinessLetterTemplate(
 *     outputFile = File("invoice.pdf"),
 *     title = "Rechnung ${billNumber}",
 *     subject = "Rechnung für ${customerName}",
 *     shopInfo = shopInfoDto,
 *     recipientAddress = RecipientAddress(
 *         name = "Max Mustermann",
 *         street = "Musterstraße 123",
 *         postalCode = "12345",
 *         city = "Berlin",
 *         country = "Deutschland"
 *     )
 * )
 *
 * template.generate { document, fonts ->
 *     // Add custom content here (invoice items, letter text, etc.)
 *     document.add(Paragraph("Your custom content", fonts.regular))
 * }
 * ```
 *
 * @property outputFile Target PDF file
 * @property title PDF document title
 * @property subject PDF document subject
 * @property shopInfo Shop/company information for header and footer
 * @property recipientAddress Recipient address details
 */
class GermanBusinessLetterTemplate(
    private val outputFile: File,
    private val title: String,
    private val subject: String,
    private val shopInfo: ShopInfoDto.ShopInfoResponseDto,
    private val recipientAddress: RecipientAddress,
    /**
     * The fonts every piece of content is drawn with. Injectable so the ZUGFeRD path can supply a
     * fully **embedded** set (PDF/A forbids the non-embedded base-14 Helvetica the default uses).
     * The plain path keeps the base-14 default and is unaffected.
     */
    private val fonts: LetterFonts = LetterFonts(),
    /**
     * Runs against the freshly created [PdfWriter] **before** `document.open()`. The PDF version has
     * to be raised here because it is baked into the file header the moment the document opens. Null
     * for the plain path.
     */
    private val configureWriterBeforeOpen: ((PdfWriter) -> Unit)? = null,
    /**
     * Runs against the [PdfWriter] **after** `document.open()`, once the writer body exists. The
     * OutputIntent and the embedded-file stream both write into that body, so they cannot run before
     * open. Null for the plain path.
     */
    private val configureWriterAfterOpen: ((PdfWriter) -> Unit)? = null,
) {
    /**
     * Recipient address information for the letter.
     *
     * @property name Recipient name (person or company)
     * @property street Street address
     * @property postalCode Postal/ZIP code
     * @property city City name
     * @property country Country name
     * @property customerNumber Optional customer reference number
     * @property taxId Optional customer tax identification number
     */
    data class RecipientAddress(
        val name: String,
        val companyName: String? = null,
        val street: String,
        val postalCode: String,
        val city: String,
        val country: String,
        val customerNumber: String? = null,
        val taxId: String? = null,
    )

    /**
     * Standard font set for German business letters.
     *
     * @property header Large bold font for main title (14pt)
     * @property bold Bold font for section headings (11pt)
     * @property regular Normal font for body text (10pt)
     * @property small Small font for metadata (8pt)
     * @property tiny Tiny font for footer and return address (7pt)
     */
    data class LetterFonts(
        val header: Font = Font(Font.HELVETICA, 14f, Font.BOLD),
        val bold: Font = Font(Font.HELVETICA, 11f, Font.BOLD),
        val regular: Font = Font(Font.HELVETICA, 10f, Font.NORMAL),
        val small: Font = Font(Font.HELVETICA, 8f, Font.NORMAL),
        val tiny: Font = Font(Font.HELVETICA, 7f, Font.NORMAL),
    )

    /**
     * Generates the PDF document with custom content.
     *
     * Sets up the DIN 676 layout structure and invokes the content callback
     * to add document-specific content (invoice items, letter text, etc.).
     *
     * @param contentBuilder Lambda function that adds custom content to the document.
     *                       Receives the Document and LetterFonts for content creation.
     * @throws Exception if PDF generation fails
     */
    fun generate(contentBuilder: (document: Document, fonts: LetterFonts) -> Unit) {
        // A4 page with margins: left=20mm, right=20mm, top=20mm, bottom=40mm (space for footer)
        val document = Document(PageSize.A4, 56.7f, 56.7f, 56.7f, 56.7f)

        document.use { document ->
            val writer = PdfWriter.getInstance(document, FileOutputStream(outputFile))

            // The PDF version is part of the file header and must be set before the document opens;
            // the plain path passes no configurator.
            configureWriterBeforeOpen?.invoke(writer)

            // Add page event for footer on every page with page numbers
            writer.pageEvent =
                object : PdfPageEventHelper() {
                    override fun onEndPage(
                        writer: PdfWriter,
                        document: Document,
                    ) {
                        addFooter(writer, document, fonts.tiny, writer.pageNumber)
                    }
                }

            // Set PDF metadata
            document.addTitle(title)
            document.addCreator("POS System Document Generator")
            document.addSubject(subject)
            document.addAuthor(shopInfo.shopName)

            document.open()

            // The writer body now exists — OutputIntent and embedded-file streams can be written.
            configureWriterAfterOpen?.invoke(writer)

            // Add company header (top-right)
            addCompanyHeader(document, fonts.regular)

            // Add spacing to reach return address position (~45mm from top)
            document.add(Paragraph("\n\n", fonts.small))

            // Add return address (sender - for window envelope)
            addReturnAddress(document, fonts.tiny)

            // Add recipient address (window envelope position)
            addRecipientAddress(document, fonts.regular)

            // Add spacing before main content
            document.add(Paragraph("\n\n", fonts.regular))

            // Invoke custom content builder
            contentBuilder(document, fonts)
        }
    }

    /**
     * Adds company header in top-right corner.
     */
    private fun addCompanyHeader(
        document: Document,
        font: Font,
    ) {
        val headerTable = PdfPTable(2)
        headerTable.widthPercentage = 100f
        headerTable.setWidths(floatArrayOf(1f, 1f))

        // Left cell (empty for address space)
        val leftCell = PdfPCell()
        leftCell.border = Rectangle.NO_BORDER
        headerTable.addCell(leftCell)

        // Right cell (company info)
        val companyInfo = StringBuilder()
        companyInfo.append(shopInfo.shopName)
        shopInfo.companyName?.let { companyInfo.append("\n$it") }
        shopInfo.shopAddress?.let { companyInfo.append("\n$it") }
        companyInfo.append("\n${shopInfo.zipCode ?: ""} ${shopInfo.shopCity ?: ""}")
        shopInfo.shopPhone?.let { companyInfo.append("\nTel: $it") }
        shopInfo.shopEmail?.let { companyInfo.append("\nE-Mail: $it") }

        val rightCell = PdfPCell(Phrase(companyInfo.toString(), font))
        rightCell.border = Rectangle.NO_BORDER
        rightCell.horizontalAlignment = Element.ALIGN_RIGHT
        headerTable.addCell(rightCell)

        document.add(headerTable)
    }

    /**
     * Adds return address line (sender) for window envelope visibility.
     */
    private fun addReturnAddress(
        document: Document,
        font: Font,
    ) {
        val returnAddress =
            buildString {
                append(shopInfo.shopName)
                shopInfo.shopAddress?.let { append(" • $it") }
                append(" • ${shopInfo.zipCode ?: ""} ${shopInfo.shopCity ?: ""}")
            }
        val returnAddressPara = Paragraph(returnAddress, font)
        returnAddressPara.setLeading(0f, 1.2f)
        document.add(returnAddressPara)

        // Small spacing before recipient
        document.add(Paragraph(" ", font))
    }

    /**
     * Adds recipient address (customer) in window envelope position.
     */
    private fun addRecipientAddress(
        document: Document,
        font: Font,
    ) {
        val recipientPara = Paragraph()
        recipientPara.add(Phrase("${recipientAddress.name}\n", font))
        recipientAddress.companyName?.let { name ->
            recipientPara.add(Phrase("$name\n", font))
        }
        recipientPara.add(Phrase("${recipientAddress.street}\n", font))
        recipientPara.add(Phrase("${recipientAddress.postalCode} ${recipientAddress.city}\n", font))
        recipientPara.add(Phrase(recipientAddress.country, font))
        document.add(recipientPara)
    }

    /**
     * Adds footer to each page with bank details, tax information, contact details, and page number.
     *
     * @param writer PDF writer instance
     * @param document PDF document
     * @param font Font to use for footer text
     * @param pageNumber Current page number
     */
    private fun addFooter(
        writer: PdfWriter,
        document: Document,
        font: Font,
        pageNumber: Int,
    ) {
        val footerTable = PdfPTable(2)
        footerTable.totalWidth = document.pageSize.width - document.leftMargin() - document.rightMargin()
        footerTable.setWidths(floatArrayOf(1f, 1f))

        // Left column: Bank details
        val bankDetails = StringBuilder()
        bankDetails.append("Bankverbindung:\n")
        shopInfo.shopBankName?.let { bankDetails.append("$it\n") }
        shopInfo.shopBankIBAN?.let { bankDetails.append("IBAN: $it\n") }
        shopInfo.shopBankBic?.let { bankDetails.append("BIC: $it") }

        val bankCell = PdfPCell(Phrase(bankDetails.toString(), font))
        bankCell.border = Rectangle.NO_BORDER
        bankCell.horizontalAlignment = Element.ALIGN_LEFT
        bankCell.verticalAlignment = Element.ALIGN_TOP
        footerTable.addCell(bankCell)

        // Right column: Contact info + tax number
        val shopDetails = StringBuilder()
        shopDetails.append("${shopInfo.shopName}\n")
        shopInfo.shopEmail?.let { shopDetails.append("E-Mail: $it\n") }
        shopInfo.shopWebsite?.let { shopDetails.append("Web: $it\n") }
        shopInfo.shopPhone?.let { shopDetails.append("Tel: $it\n") }
        shopInfo.shopTaxNumber?.let { shopDetails.append("Steuernummer: $it") }

        val shopCell = PdfPCell(Phrase(shopDetails.toString(), font))
        shopCell.border = Rectangle.NO_BORDER
        shopCell.horizontalAlignment = Element.ALIGN_RIGHT
        shopCell.verticalAlignment = Element.ALIGN_TOP
        footerTable.addCell(shopCell)

        // Position footer at bottom of page
        val footerY = document.bottomMargin()
        footerTable.writeSelectedRows(
            0,
            -1,
            document.leftMargin(),
            footerY,
            writer.directContent,
        )

        // Page number centered below the footer
        val pageNumberPhrase = Phrase("Seite $pageNumber", font)
        val pageNumberY = document.bottomMargin() - 40f
        val pageNumberX =
            (document.pageSize.width - document.leftMargin() - document.rightMargin()) / 2f + document.leftMargin()

        ColumnText.showTextAligned(
            writer.directContent,
            Element.ALIGN_CENTER,
            pageNumberPhrase,
            pageNumberX,
            pageNumberY,
            0f,
        )
    }
}
