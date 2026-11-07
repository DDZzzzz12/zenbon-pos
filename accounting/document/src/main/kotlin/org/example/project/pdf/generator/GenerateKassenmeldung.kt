/**
 * Kassenmeldung PDF generator.
 *
 * Creates an A4 PDF containing the Kassenmeldung data sheet for
 * ELSTER submission under § 146a Abs. 4 AO. Includes Betriebsstätte,
 * elektronisches Aufzeichnungssystem, and TSE sections.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.pdf.generator

import org.example.project.core.domain.dto.setup.ShopConfigurationDto
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/**
 * Generates a Kassenmeldung PDF as a byte array.
 *
 * Creates an A4 PDF with three sections: Betriebsstätte, elektronisches
 * Aufzeichnungssystem, and Technische Sicherheitseinrichtung (TSE).
 * Intended for use with ELSTER submission under § 146a Abs. 4 AO.
 *
 * @param data Aggregated Kassenmeldung response containing all required fields
 * @return PDF document bytes
 */
fun generateKassenmeldungPdfBytes(data: ShopConfigurationDto.KassenMeldungResponseDto): ByteArray {
    val output = ByteArrayOutputStream()
    val document = Document(PageSize.A4, 55f, 55f, 55f, 55f)
    PdfWriter.getInstance(document, output)
    document.open()

    val baseFont = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED)
    val baseFontBold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED)

    val colorDarkNavy = Color(26, 26, 46)
    val colorTeal = Color(0, 137, 123)
    val colorGray = Color(100, 100, 100)
    val colorLightGray = Color(240, 242, 244)

    val titleFont = Font(baseFontBold, 20f, Font.BOLD, colorDarkNavy)
    val subtitleFont = Font(baseFont, 10f, Font.NORMAL, colorGray)
    val sectionHeaderFont = Font(baseFontBold, 11f, Font.BOLD, Color.WHITE)
    val labelFont = Font(baseFont, 10f, Font.NORMAL, colorGray)
    val valueFont = Font(baseFontBold, 10f, Font.BOLD, colorDarkNavy)
    val footerFont = Font(baseFont, 8f, Font.NORMAL, colorGray)

    // ─── Title block ───────────────────────────────────────────────────────
    val title = Paragraph("Kassenmeldung — Datenblatt", titleFont)
    title.alignment = Element.ALIGN_LEFT
    title.spacingAfter = 4f
    document.add(title)

    val subtitle =
        Paragraph(
            "Daten für die Mitteilung nach § 146a Abs. 4 AO (ELSTER)",
            subtitleFont,
        )
    subtitle.alignment = Element.ALIGN_LEFT
    subtitle.spacingAfter = 16f
    document.add(subtitle)

    document.add(LineSeparator(1f, 100f, colorTeal, Element.ALIGN_CENTER, -2f))
    document.add(Paragraph(" ", labelFont))

    // ─── Section 1 — Betriebsstätte ────────────────────────────────────────
    document.add(buildSectionHeader("1.  Betriebsstätte", sectionHeaderFont, colorTeal))
    document.add(Paragraph(" ", labelFont))

    val section1Table = buildTwoColumnTable()
    addSectionRow(section1Table, "Bezeichnung Betriebsstätte", data.betriebsstaetteName, labelFont, valueFont)
    addSectionRow(section1Table, "Straße", data.betriebsstaetteStreet, labelFont, valueFont)
    addSectionRow(section1Table, "PLZ", data.betriebsstaetteZip, labelFont, valueFont)
    addSectionRow(section1Table, "Ort", data.betriebsstaetteCity, labelFont, valueFont)
    addSectionRow(section1Table, "Land", data.betriebsstaetteCountry, labelFont, valueFont)
    document.add(section1Table)
    document.add(Paragraph(" ", labelFont))

    // ─── Section 2 — Elektronisches Aufzeichnungssystem ────────────────────
    document.add(buildSectionHeader("2.  Elektronisches Aufzeichnungssystem", sectionHeaderFont, colorTeal))
    document.add(Paragraph(" ", labelFont))

    val section2Table = buildTwoColumnTable()
    addSectionRow(section2Table, "Kassen-Seriennummer", data.kasseSeriennr, labelFont, valueFont)
    addSectionRow(section2Table, "Hersteller / Marke", data.kasseBrand, labelFont, valueFont)
    addSectionRow(section2Table, "Modell", data.kasseModell, labelFont, valueFont)
    addSectionRow(section2Table, "Software", data.kasseSwBrand, labelFont, valueFont)
    addSectionRow(section2Table, "Software-Version", data.kasseSwVersion, labelFont, valueFont)
    addSectionRow(section2Table, "Art des Aufzeichnungssystems", data.artDesAufzeichnungssystems, labelFont, valueFont)
    addSectionRow(section2Table, "Anschaffung", data.anschaffung ?: "—", labelFont, valueFont)
    addSectionRow(section2Table, "Inbetriebnahme", data.inbetriebnahme, labelFont, valueFont)
    document.add(section2Table)
    document.add(Paragraph(" ", labelFont))

    // ─── Section 3 — Technische Sicherheitseinrichtung ─────────────────────
    document.add(buildSectionHeader("3.  Technische Sicherheitseinrichtung (TSE)", sectionHeaderFont, colorTeal))
    document.add(Paragraph(" ", labelFont))

    val expirationFormatted =
        data.tseCertificateExpirationDate?.let { epochSeconds ->
            runCatching {
                val date = Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDate()
                date.format(DATE_FORMATTER)
            }.getOrElse { "—" }
        } ?: "—"

    val bsiCertDisplay = data.tseBsiCertId?.let { "BSI-K-TR-$it" } ?: "—"

    val section3Table = buildTwoColumnTable()
    addSectionRow(section3Table, "TSE-Seriennummer", data.tseSerialNumberHex ?: "Nicht verbunden", labelFont, valueFont)
    addSectionRow(section3Table, "TSE-Beschreibung", data.tseDescription ?: "—", labelFont, valueFont)
    addSectionRow(section3Table, "Zertifikat gültig bis", expirationFormatted, labelFont, valueFont)
    addSectionRow(section3Table, "BSI-Zertifizierungs-ID", bsiCertDisplay, labelFont, valueFont)
    addSectionRow(section3Table, "Art / Bauform der TSE", data.tseFormFactor ?: "—", labelFont, valueFont)
    document.add(section3Table)
    if (data.tseSerialNumberHex != null) {
        document.add(
            Paragraph(
                "Die TSE-Angaben stammen von der aktuell angeschlossenen TSE. " +
                    "Prüfen Sie sie vor der Meldung an das Finanzamt.",
                footerFont,
            ),
        )
    }

    // ─── Footer ────────────────────────────────────────────────────────────
    document.add(Paragraph(" ", labelFont))
    document.add(LineSeparator(1f, 100f, colorLightGray, Element.ALIGN_CENTER, -2f))
    document.add(Paragraph(" ", labelFont))

    val today = LocalDate.now().format(DATE_FORMATTER)
    val footer = Paragraph("Erstellt am $today — POS System", footerFont)
    footer.alignment = Element.ALIGN_CENTER
    document.add(footer)

    document.close()
    return output.toByteArray()
}

/** Creates a coloured section header bar spanning the full width. */
private fun buildSectionHeader(
    text: String,
    font: Font,
    bgColor: Color,
): PdfPTable {
    val table = PdfPTable(1)
    table.widthPercentage = 100f
    table.setSpacingBefore(4f)

    val cell = PdfPCell(Phrase(text, font))
    cell.backgroundColor = bgColor
    cell.border = Rectangle.NO_BORDER
    cell.paddingTop = 7f
    cell.paddingBottom = 7f
    cell.paddingLeft = 10f
    table.addCell(cell)
    return table
}

/** Creates a two-column table (40% / 60%). */
private fun buildTwoColumnTable(): PdfPTable {
    val table = PdfPTable(2)
    table.widthPercentage = 100f
    table.setWidths(floatArrayOf(40f, 60f))
    return table
}

/** Adds a label–value row to a two-column section table. */
private fun addSectionRow(
    table: PdfPTable,
    label: String,
    value: String,
    labelFont: Font,
    valueFont: Font,
) {
    val labelCell = PdfPCell(Phrase(label, labelFont))
    labelCell.border = Rectangle.BOX
    labelCell.borderColor = Color(220, 220, 220)
    labelCell.paddingTop = 6f
    labelCell.paddingBottom = 6f
    labelCell.paddingLeft = 8f

    val valueCell = PdfPCell(Phrase(value, valueFont))
    valueCell.border = Rectangle.BOX
    valueCell.borderColor = Color(220, 220, 220)
    valueCell.paddingTop = 6f
    valueCell.paddingBottom = 6f
    valueCell.paddingLeft = 8f

    table.addCell(labelCell)
    table.addCell(valueCell)
}
