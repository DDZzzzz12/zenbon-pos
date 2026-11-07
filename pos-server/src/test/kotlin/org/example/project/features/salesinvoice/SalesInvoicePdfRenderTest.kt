package org.example.project.features.salesinvoice

import kotlinx.datetime.LocalDateTime
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.example.project.features.salesinvoice.domain.service.InvoiceBuyer
import org.example.project.features.salesinvoice.domain.service.InvoiceSeller
import org.example.project.features.salesinvoice.domain.service.SalesInvoicePdfData
import org.example.project.features.salesinvoice.domain.service.SalesInvoicePdfLine
import org.example.project.features.salesinvoice.domain.service.SalesInvoicePdfVatRow
import org.example.project.pdf.generator.renderSalesInvoicePdf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertTrue

class SalesInvoicePdfRenderTest {
    private fun data() =
        SalesInvoicePdfData(
            invoiceNumber = "RG-2027-00001",
            issueDate = LocalDateTime(2027, 1, 5, 10, 0),
            serviceDate = LocalDateTime(2026, 8, 16, 19, 30),
            invoiceTypeCode = "380",
            precedingInvoiceNumber = null,
            buyer =
                InvoiceBuyer(name = "Musterfirma GmbH", street = "Industriestr. 5", zip = "10117", city = "Berlin"),
            seller =
                InvoiceSeller(
                    name = "Zum Goldenen Hirschen",
                    vatId = "DE123456789",
                    taxNumber = "12/345/67890",
                    street = "Hauptstr. 1",
                    zip = "10115",
                    city = "Berlin",
                    country = "DE",
                ),
            lines =
                listOf(
                    SalesInvoicePdfLine(
                        lineNo = 1,
                        itemName = "Wiener Schnitzel",
                        quantity = BigDecimal.ONE,
                        netUnitPrice = BigDecimal("20.0000"),
                        grossAmount = BigDecimal("23.8000"),
                        vatRatePercent = BigDecimal("19.00"),
                    ),
                ),
            vatRows =
                listOf(
                    SalesInvoicePdfVatRow(BigDecimal("19.00"), BigDecimal("20.0000"), BigDecimal("3.8000")),
                ),
            netTotal = BigDecimal("20.0000"),
            vatTotal = BigDecimal("3.8000"),
            grossTotal = BigDecimal("23.8000"),
            prepaidAmount = BigDecimal("23.8000"),
            amountDue = BigDecimal.ZERO,
            paymentNote = "Betrag dankend erhalten am 16.08.2026, Beleg RE-260816-7",
            paymentReference = "RG-2027-00001",
            dueDate = LocalDateTime(2027, 2, 5, 0, 0),
            paymentTermsText = "Zahlbar innerhalb von 14 Tagen",
            // A from-bill invoice: it ties back to a Kassenbeleg, so an absent TSE is an Ausfall.
            expectsTse = true,
            tse = null,
        )

    private fun textOf(bytes: ByteArray): String = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }

    @Test
    fun `the PDF carries the invoice number, the Leistungszeitpunkt and the seller tax id`() {
        val text = textOf(renderSalesInvoicePdf(data()))

        assertTrue(text.contains("RG-2027-00001"), "the §14 invoice number must appear")
        assertTrue(text.contains("16.08.2026"), "the Leistungszeitpunkt must appear (§14 Abs. 4 Nr. 6)")
        assertTrue(text.contains("DE123456789"), "the seller's USt-IdNr must appear (§14 Abs. 4 Nr. 2)")
        assertTrue(text.contains("Musterfirma GmbH"), "the buyer must appear")
    }

    @Test
    fun `the payment note is printed so the invoice ties back to the Kassenbeleg`() {
        val text = textOf(renderSalesInvoicePdf(data()))

        assertTrue(text.contains("RE-260816-7"), "the receipt number is the auditor's link to the Z-Bon")
    }

    @Test
    fun `an already-paid invoice shows the gross total and the rate in percent units`() {
        val text = textOf(renderSalesInvoicePdf(data()))

        assertTrue(text.contains("19 %"), "the VAT rate must print in percent units, got:\n$text")
        assertTrue(text.contains("23,80"), "the gross total must be printed in German form")
    }

    @Test
    fun `a Gutschrift is labelled as one and cites what it reverses`() {
        val text =
            textOf(
                renderSalesInvoicePdf(
                    data().copy(invoiceTypeCode = "381", precedingInvoiceNumber = "RG-2027-00001"),
                ),
            )

        assertTrue(text.contains("GUTSCHRIFT") || text.contains("Gutschrift"), "a 381 must not be titled Rechnung")
        assertTrue(text.contains("Bezug"), "BT-25 makes the correction traceable")
    }

    @Test
    fun `a STANDALONE invoice prints the Verwendungszweck and the Zahlungsziel`() {
        val text = textOf(renderSalesInvoicePdf(data()))

        assertTrue(text.contains("Verwendungszweck: RG-2027-00001"), "the invoice number is the payment reference")
        assertTrue(text.contains("Zahlbar bis"), "the due date must be printed")
        assertTrue(text.contains("Zahlbar innerhalb von 14 Tagen"), "the payment terms must be printed")
    }

    @Test
    fun `the footer carries the shop's bank details so the payer knows where to pay`() {
        val seller =
            data().seller.copy(
                bankName = "Sparkasse Berlin",
                bankIban = "DE02120300000000202051",
                bankBic = "BYLADEM1001",
                email = "rechnung@hirschen.example",
            )
        val text = textOf(renderSalesInvoicePdf(data().copy(seller = seller)))

        assertTrue(text.contains("Sparkasse Berlin"), "bank name missing from the footer:\n$text")
        assertTrue(text.contains("IBAN: DE02120300000000202051"), "IBAN missing from the footer:\n$text")
        assertTrue(text.contains("BIC: BYLADEM1001"), "BIC missing from the footer:\n$text")
        assertTrue(text.contains("rechnung@hirschen.example"), "contact e-mail missing from the footer:\n$text")
    }

    @Test
    fun `a line row prints unit price and total on the same gross basis`() {
        val twoUnits =
            SalesInvoicePdfLine(
                lineNo = 1,
                itemName = "Wiener Schnitzel",
                quantity = BigDecimal("2"),
                netUnitPrice = BigDecimal("16.8067"),
                grossAmount = BigDecimal("40.0000"),
                vatRatePercent = BigDecimal("19.00"),
                grossUnitPrice = BigDecimal("20.0000"),
            )
        val text = textOf(renderSalesInvoicePdf(data().copy(lines = listOf(twoUnits))))

        // The header cell wraps, so the words arrive on separate lines.
        assertTrue(text.contains("Einzelpreis") && text.contains("brutto"), "the column must name its basis:\n$text")
        assertTrue(text.contains("2 19 % 20,00 € 40,00 €"), "Menge, USt, Einzelpreis and Gesamt must agree in one row:\n$text")
        assertTrue(text.contains("20,00 €"), "2 × 20,00 € = 40,00 € must read true on the page:\n$text")
        assertTrue(text.contains("40,00 €"))
        assertTrue(!text.contains("16,81 €"), "a net unit price beside a gross total does not add up:\n$text")
    }

    @Test
    fun `a Gutschrift row carries the sign of its total`() {
        val storno =
            SalesInvoicePdfLine(
                lineNo = 1,
                itemName = "Raummiete",
                quantity = BigDecimal.ONE,
                netUnitPrice = BigDecimal("200.0000"),
                grossAmount = BigDecimal("-238.0000"),
                vatRatePercent = BigDecimal("19.00"),
                grossUnitPrice = BigDecimal("238.0000"),
            )
        assertTrue(storno.displayGrossUnitPrice.compareTo(BigDecimal("-238")) == 0)
    }

    @Test
    fun `a bill signed by no TSE states the outage rather than omitting the block`() {
        val text = textOf(renderSalesInvoicePdf(data()))

        assertTrue(
            text.contains("nicht verfügbar") || text.contains("Ausfall"),
            "a missing signature must be stated, not silently absent",
        )
    }
}
