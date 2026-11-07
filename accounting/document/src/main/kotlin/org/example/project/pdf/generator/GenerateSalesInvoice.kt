package org.example.project.pdf.generator

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.features.salesinvoice.domain.service.SalesInvoicePdfData
import org.example.project.pdf.template.GermanBusinessLetterTemplate
import org.example.project.pdf.util.addDetailRow
import org.example.project.pdf.util.addTableHeader
import org.example.project.pdf.util.createCell
import org.example.project.pdf.zugferd.ZugferdPackager
import org.example.project.utils.formatter.TseReceiptText
import org.openpdf.text.Chunk
import org.openpdf.text.Document
import org.openpdf.text.Element
import org.openpdf.text.Font
import org.openpdf.text.Paragraph
import org.openpdf.text.Phrase
import org.openpdf.text.Rectangle
import org.openpdf.text.pdf.PdfPCell
import org.openpdf.text.pdf.PdfPTable
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Renders an outgoing invoice from **its own snapshot**.
 *
 * Deliberately not a renderer over live bill data — an earlier revision rendered from the live
 * bill and that path was deleted. A reprint two years from now must show what was issued, not what
 * the bill, the products and `shop_info` say then. Every value here comes from
 * [SalesInvoicePdfData], which the caller reads off `sales_invoice` and its children.
 *
 * @return the PDF bytes; the caller archives them and serves that file forever after.
 */
fun renderSalesInvoicePdf(data: SalesInvoicePdfData): ByteArray {
    val tempFile = File.createTempFile("ar_invoice_${data.invoiceNumber}_", ".pdf")
    try {
        buildInvoiceTemplate(tempFile, data).generate(salesInvoiceContent(data))
        return tempFile.readBytes()
    } finally {
        tempFile.delete()
    }
}

/**
 * The **ZUGFeRD/Factur-X** sibling of [renderSalesInvoicePdf]: the same visible invoice, wrapped as
 * a PDF/A-3-shaped container with [ciiXml] embedded as `factur-x.xml` so a machine can book it.
 *
 * The visible content is byte-for-byte the same drawing code as the plain path — only the writer is
 * configured for ZUGFeRD and the fonts are embedded (PDF/A forbids the base-14 Helvetica the plain
 * path uses). [ciiXml] is the EN 16931 CII produced upstream and is embedded verbatim.
 *
 * @return the PDF bytes; a human reads them and the `:accounting-einvoice` parser books the embedded payload.
 */
fun renderSalesInvoiceZugferdPdf(
    data: SalesInvoicePdfData,
    ciiXml: ByteArray,
): ByteArray {
    val tempFile = File.createTempFile("ar_zugferd_${data.invoiceNumber}_", ".pdf")
    try {
        val documentTitle = titleFor(data.invoiceTypeCode)
        val template =
            buildInvoiceTemplate(
                outputFile = tempFile,
                data = data,
                fonts = ZugferdPackager.embeddedLetterFonts(),
                configureWriterBeforeOpen = ZugferdPackager.configureBeforeOpen(),
                configureWriterAfterOpen =
                    ZugferdPackager.configureAfterOpen(
                        ciiXml = ciiXml,
                        title = "$documentTitle ${data.invoiceNumber}",
                        author = data.seller.name,
                    ),
            )
        template.generate(salesInvoiceContent(data))
        return tempFile.readBytes()
    } finally {
        tempFile.delete()
    }
}

/**
 * Builds the letter template for both paths. The plain path passes neither embedded [fonts] nor a
 * writer configurator and is therefore identical to before; the ZUGFeRD path supplies embedded
 * fonts and the PDF/A-3 writer configuration.
 */
private fun buildInvoiceTemplate(
    outputFile: File,
    data: SalesInvoicePdfData,
    fonts: GermanBusinessLetterTemplate.LetterFonts = GermanBusinessLetterTemplate.LetterFonts(),
    configureWriterBeforeOpen: ((org.openpdf.text.pdf.PdfWriter) -> Unit)? = null,
    configureWriterAfterOpen: ((org.openpdf.text.pdf.PdfWriter) -> Unit)? = null,
): GermanBusinessLetterTemplate {
    val documentTitle = titleFor(data.invoiceTypeCode)
    return GermanBusinessLetterTemplate(
        outputFile = outputFile,
        title = "$documentTitle ${data.invoiceNumber}",
        subject = "$documentTitle für ${data.buyer.name}",
        shopInfo = data.seller.toLetterheadDto(),
        recipientAddress =
            GermanBusinessLetterTemplate.RecipientAddress(
                name = data.buyer.name,
                street = data.buyer.street.orEmpty(),
                postalCode = data.buyer.zip.orEmpty(),
                city = data.buyer.city.orEmpty(),
                country = data.buyer.country.orEmpty(),
                taxId = data.buyer.vatId,
            ),
        fonts = fonts,
        configureWriterBeforeOpen = configureWriterBeforeOpen,
        configureWriterAfterOpen = configureWriterAfterOpen,
    )
}

/**
 * The invoice's visible content, drawn identically for the plain and the ZUGFeRD paths — the machine
 * payload changes the container, never what the reader sees.
 */
private fun salesInvoiceContent(data: SalesInvoicePdfData): (document: Document, fonts: GermanBusinessLetterTemplate.LetterFonts) -> Unit {
    val documentTitle = titleFor(data.invoiceTypeCode)
    return { document, fonts ->
        // ---- Title and the §14 reference block ------------------------------------------------
        val header = PdfPTable(2)
        header.widthPercentage = 100f
        header.setWidths(floatArrayOf(3f, 2f))
        header.addCell(
            PdfPCell(Phrase(documentTitle.uppercase(), fonts.header)).apply {
                border = Rectangle.NO_BORDER
                verticalAlignment = Element.ALIGN_BOTTOM
                paddingBottom = 5f
            },
        )

        val details = PdfPTable(2)
        details.widthPercentage = 100f
        addDetailRow(details, "Rechnungsnummer:", data.invoiceNumber, fonts.small)
        addDetailRow(details, "Rechnungsdatum:", data.issueDate.germanDate(), fonts.small)
        // §14 Abs. 4 Nr. 6 — printed separately because for a POS invoice it is NOT the issue date.
        addDetailRow(details, "Leistungsdatum:", data.serviceDate.germanDate(), fonts.small)
        data.precedingInvoiceNumber?.let {
            // BT-25: what makes a Gutschrift or a correction traceable to what it reverses.
            addDetailRow(details, "Bezug:", it, fonts.small)
        }
        data.seller.vatId?.let { addDetailRow(details, "USt-IdNr.:", it, fonts.small) }
        data.seller.taxNumber?.let { addDetailRow(details, "Steuernummer:", it, fonts.small) }
        header.addCell(PdfPCell(details).apply { border = Rectangle.NO_BORDER })
        document.add(header)
        document.add(Paragraph("\n", fonts.regular))

        // ---- Items ----------------------------------------------------------------------------
        // Every amount in a row is on ONE basis — gross, the price the line was sold at — so
        // "Menge × Einzelpreis = Gesamt" holds on the page. The net amounts and the VAT per rate
        // (§14 Abs. 4 Nr. 7/8 UStG) follow in the totals block; the USt column ties each line to
        // its rate. An earlier revision printed a net unit price beside a gross line total.
        val items = PdfPTable(6)
        items.widthPercentage = 100f
        items.setWidths(floatArrayOf(0.6f, 3.3f, 0.8f, 0.7f, 1.3f, 1.3f))
        items.setSpacingBefore(10f)
        items.setSpacingAfter(10f)
        addTableHeader(items, "Pos.", fonts.bold)
        addTableHeader(items, "Bezeichnung", fonts.bold)
        addTableHeader(items, "Menge", fonts.bold)
        addTableHeader(items, "USt", fonts.bold)
        addTableHeader(items, "Einzelpreis brutto", fonts.bold)
        addTableHeader(items, "Gesamt brutto", fonts.bold)
        for (line in data.lines) {
            items.addCell(createCell(line.lineNo.toString(), fonts.regular))
            items.addCell(createCell(line.itemName, fonts.regular))
            items.addCell(createCell(line.quantity.stripTrailingZeros().toPlainString(), fonts.regular, Element.ALIGN_CENTER))
            items.addCell(createCell("${line.vatRatePercent.stripTrailingZeros().toPlainString()} %", fonts.regular, Element.ALIGN_CENTER))
            items.addCell(createCell(line.displayGrossUnitPrice.euro(), fonts.regular, Element.ALIGN_RIGHT))
            items.addCell(createCell(line.grossAmount.euro(), fonts.regular, Element.ALIGN_RIGHT))
        }
        document.add(items)

        // ---- Nachlass: promotions as EN 16931 document-level allowances (BG-20) ----------------
        // Below the lines and on their gross basis, so "Σ Gesamt brutto − Nachlass = Gesamtbetrag"
        // reads true on the page; the VAT rows below have already absorbed it. Absent on an invoice
        // without one, which therefore prints exactly as before.
        allowanceBlock(data, fonts)?.let { document.add(it) }

        // ---- VAT breakdown and totals -----------------------------------------------------------
        val totals = PdfPTable(2)
        totals.widthPercentage = 45f
        // The label column carries "zzgl. 19 % USt auf -200,00 €"; at an even split a credit note's
        // longer amount no longer fit on one line. The amounts' own column needs less.
        totals.setWidths(floatArrayOf(1.7f, 1f))
        totals.horizontalAlignment = Element.ALIGN_RIGHT
        totals.addCell(createCell("Nettobetrag", fonts.regular))
        totals.addCell(createCell(data.netTotal.euro(), fonts.regular, Element.ALIGN_RIGHT))
        for (row in data.vatRows) {
            val rate = row.vatRatePercent.stripTrailingZeros().toPlainString()
            val vatLabel =
                Phrase("zzgl. $rate % USt auf ", fonts.regular).apply {
                    add(unbreakable(row.taxableAmount.euro(), fonts.regular))
                }
            totals.addCell(createCell(vatLabel))
            totals.addCell(createCell(row.vatAmount.euro(), fonts.regular, Element.ALIGN_RIGHT))
        }
        totals.addCell(createCell("Gesamtbetrag", fonts.bold))
        totals.addCell(createCell(data.grossTotal.euro(), fonts.bold, Element.ALIGN_RIGHT))
        if (data.prepaidAmount.signum() != 0) {
            totals.addCell(createCell("Bereits bezahlt", fonts.regular))
            totals.addCell(createCell(data.prepaidAmount.euro(), fonts.regular, Element.ALIGN_RIGHT))
            totals.addCell(createCell("Offener Betrag", fonts.bold))
            totals.addCell(createCell(data.amountDue.euro(), fonts.bold, Element.ALIGN_RIGHT))
        }
        document.add(totals)
        document.add(Paragraph("\n", fonts.regular))

        // ---- The link back to the Kassenbeleg ---------------------------------------------------
        data.paymentNote?.let { document.add(Paragraph(it, fonts.regular)) }

        // ---- Verwendungszweck and Zahlungsziel (STANDALONE invoices) ----------------------------
        data.paymentReference?.let { document.add(Paragraph("Verwendungszweck: $it", fonts.regular)) }
        if (data.dueDate != null || data.paymentTermsText != null) {
            val terms =
                buildList {
                    data.dueDate?.let { add("Zahlbar bis: ${it.germanDate()}") }
                    data.paymentTermsText?.takeIf { t -> t.isNotBlank() }?.let { add(it) }
                }.joinToString(" · ")
            document.add(Paragraph(terms, fonts.regular))
        }

        // ---- TSE disclosure (§6 KassenSichV) ----------------------------------------------------
        // Omitted entirely for a STANDALONE invoice, which never went through the till.
        tseDisclosure(data)?.let { disclosure ->
            document.add(Paragraph("\n", fonts.regular))
            // Kept whole: a signature block cut by a page break is unreadable as evidence (rt30 INV-04).
            document.add(Paragraph(disclosure, fonts.small).apply { keepTogether = true })
        }
    }
}

/**
 * One row per allowance: its reason and rate, and the amount it takes off (negative on an invoice,
 * positive on the Gutschrift that reverses it). A charge adds instead. Null when there is none.
 */
private fun allowanceBlock(
    data: SalesInvoicePdfData,
    fonts: GermanBusinessLetterTemplate.LetterFonts,
): PdfPTable? {
    if (data.allowances.isEmpty()) return null
    val block = PdfPTable(2)
    block.widthPercentage = 100f
    block.setWidths(floatArrayOf(5.4f, 1.3f))
    block.setSpacingAfter(10f)
    addTableHeader(block, "Nachlass", fonts.bold)
    addTableHeader(block, "Betrag brutto", fonts.bold)
    for (allowance in data.allowances) {
        val rate = allowance.vatRatePercent.stripTrailingZeros().toPlainString()
        val effect = if (allowance.isCharge) allowance.grossAmount else allowance.grossAmount.negate()
        block.addCell(createCell("${allowance.reason} ($rate % USt)", fonts.regular))
        block.addCell(createCell(effect.euro(), fonts.regular, Element.ALIGN_RIGHT))
    }
    return block
}

/** `380` Rechnung, `381` Gutschrift, `384` Korrektur — a 381 titled "Rechnung" says the opposite of what it does. */
private fun titleFor(invoiceTypeCode: String): String =
    when (invoiceTypeCode) {
        "381" -> "Gutschrift"
        "384" -> "Rechnungskorrektur"
        else -> "Rechnung"
    }

/**
 * The signature block of the till sale this invoice documents — or the statement that there is none,
 * or nothing at all.
 *
 * The block is the receipt's readable TSE data (AEAO zu § 146a AO, Nr. 2.4.4) — TANR, start and end
 * in UTC, Signaturzähler, TSE and till serial numbers and the full Prüfwert — through the same
 * [TseReceiptText] the receipts use, so a Rechnung handed over instead of the receipt carries the
 * same §6 KassenSichV data, verbatim and untruncated. No QR is printed.
 *
 * For a from-bill invoice an absent block is *printed*, never silently dropped: the receipt printed
 * the same Ausfall disclosure, and a missing signature is otherwise indistinguishable from a
 * signature nobody looked for. But a STANDALONE invoice ([SalesInvoicePdfData.expectsTse] `false`)
 * never went through the till — KassenSichV does not apply — so it returns `null` and prints no TSE
 * block: an "Ausfall" line there would assert a failure that never happened.
 */
private fun tseDisclosure(data: SalesInvoicePdfData): String? {
    val tse =
        data.tse
            ?: return if (data.expectsTse) {
                "TSE-Signatur: nicht verfügbar (Ausfall der technischen Sicherheitseinrichtung zum Zeitpunkt des Geschäftsvorfalls)."
            } else {
                null
            }
    return (listOf("TSE-Signatur") + TseReceiptText.lines(tse, INVOICE_TSE_LINE_WIDTH)).joinToString("\n")
}

/**
 * Characters per TSE line on the A4 invoice. The invoice font is the embedded proportional one
 * (PDF/A-3 for ZUGFeRD forbids an unembedded Courier), so the cut must hold even for the widest
 * glyph: 56 × a Helvetica `W` at 8 pt (7.6 pt) = 423 pt, inside the DIN 676 text width (≈ 468 pt).
 * A line openpdf had to break itself could split a value at a point we did not choose.
 */
private const val INVOICE_TSE_LINE_WIDTH = 56

/**
 * The letterhead the template needs, built from the invoice's frozen seller block rather than live
 * `shop_info` — a shop that moves must not rewrite the address on a 2027 invoice.
 */
private fun org.example.project.features.salesinvoice.domain.service.InvoiceSeller.toLetterheadDto() =
    ShopInfoDto.ShopInfoResponseDto(
        shopId = 1L,
        shopName = name,
        // The AR seller snapshot carries a single legal name; leaving companyName null keeps the
        // letterhead from printing that name twice (the template prints both when they differ).
        companyName = null,
        shopAddress = street,
        shopCity = city,
        zipCode = zip?.toIntOrNull(),
        shopCountry = country,
        shopTaxNumber = taxNumber,
        ustIdNr = vatId,
        // The footer's "Bankverbindung" and contact lines — without these the payer is not told
        // where to pay.
        shopPhone = phone,
        shopEmail = email,
        shopWebsite = website,
        shopBankName = bankName,
        shopBankIBAN = bankIban,
        shopBankBic = bankBic,
        // Not shown on the letterhead; the DTO requires it and the invoice's own dates are what the
        // document prints.
        createAt = "",
    )

/**
 * An amount the line breaker must keep whole. OpenPDF treats "-" as a break opportunity, so on a
 * Gutschrift "auf -200,00 €" wrapped as "auf -" / "200,00 €", stranding the minus sign (E2E
 * 2026-09-27). A split character that never splits keeps the amount one unit; the break before it
 * stays available, so a narrow cell wraps as "auf" / "-200,00 €".
 */
private fun unbreakable(
    text: String,
    font: Font,
): Chunk = Chunk(text, font).setSplitCharacter { _, _, _, _, _ -> false }

/** German money form, straight from `BigDecimal` — never through a `Double`. */
private fun BigDecimal.euro(): String {
    val fixed = setScale(2, RoundingMode.HALF_UP).toPlainString()
    return fixed.replace('.', ',') + " €"
}

private fun LocalDateTime.germanDate(): String {
    val day = date.dayOfMonth.toString().padStart(2, '0')
    val month = date.monthNumber.toString().padStart(2, '0')
    return "$day.$month.${date.year}"
}
