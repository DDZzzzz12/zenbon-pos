package org.example.project.pdf.generator

import kotlinx.datetime.LocalDateTime
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.example.project.core.domain.dto.tse.TseReceiptDataDto
import org.example.project.features.salesinvoice.domain.service.InvoiceBuyer
import org.example.project.features.salesinvoice.domain.service.InvoiceSeller
import org.example.project.features.salesinvoice.domain.service.SalesInvoicePdfAllowance
import org.example.project.features.salesinvoice.domain.service.SalesInvoicePdfData
import org.example.project.features.salesinvoice.domain.service.SalesInvoicePdfLine
import org.example.project.features.salesinvoice.domain.service.SalesInvoicePdfVatRow
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for two defects found by rendering the invoice and reading it back:
 *  - the payment-due line printed a raw `LocalDateTime.toString()` (`2026-09-02T00:00`);
 *  - the seller's name was emitted twice in the letterhead (`shopName` and `companyName` both set).
 * Both are visible-content bugs the structural/round-trip tests could not catch.
 */
class GenerateSalesInvoiceTest {
    private val sellerName = "Bella Vista Ristorante GmbH"

    private fun pdfData(
        dueDate: LocalDateTime?,
        expectsTse: Boolean = false,
    ) = SalesInvoicePdfData(
        invoiceNumber = "RG-2026-00042",
        issueDate = LocalDateTime(2026, 8, 19, 10, 0),
        serviceDate = LocalDateTime(2026, 8, 18, 20, 30),
        invoiceTypeCode = "380",
        precedingInvoiceNumber = null,
        buyer =
            InvoiceBuyer(
                name = "Steuerkanzlei Dr. Maas & Partner",
                street = "Anwaltsweg 5",
                zip = "80331",
                city = "München",
                country = "DE",
            ),
        seller =
            InvoiceSeller(
                name = sellerName,
                vatId = "DE123456789",
                taxNumber = "12/345/67890",
                street = "Hauptstraße 12",
                zip = "10115",
                city = "Berlin",
                country = "DE",
            ),
        lines =
            listOf(
                SalesInvoicePdfLine(1, "Beratung", BigDecimal.ONE, BigDecimal("100.0000"), BigDecimal("119.0000"), BigDecimal("19.00")),
            ),
        vatRows =
            listOf(
                SalesInvoicePdfVatRow(BigDecimal("19.00"), BigDecimal("100.0000"), BigDecimal("19.0000")),
            ),
        netTotal = BigDecimal("100.0000"),
        vatTotal = BigDecimal("19.0000"),
        grossTotal = BigDecimal("119.0000"),
        prepaidAmount = BigDecimal.ZERO,
        amountDue = BigDecimal("119.0000"),
        paymentNote = null,
        paymentReference = "RG-2026-00042",
        dueDate = dueDate,
        paymentTermsText = null,
        expectsTse = expectsTse,
        tse = null,
    )

    private fun textOf(pdf: ByteArray): String = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }

    @Test
    fun `the payment-due date is formatted German, not raw ISO`() {
        val text = textOf(renderSalesInvoicePdf(pdfData(LocalDateTime(2026, 9, 2, 0, 0))))

        assertTrue(text.contains("Zahlbar bis: 02.09.2026"), "expected a German date; got:\n$text")
        assertFalse(text.contains("2026-09-02T00:00"), "the raw LocalDateTime must not leak into the PDF")
    }

    @Test
    fun `the seller name is not duplicated in the letterhead`() {
        val text = textOf(renderSalesInvoicePdf(pdfData(null)))

        // The name legitimately appears in the letterhead, the return-address line and the footer:
        // three times. The duplication bug added a fourth (companyName == shopName).
        val occurrences = Regex(Regex.escape(sellerName)).findAll(text).count()
        assertEquals(3, occurrences, "seller name should appear 3×, not duplicated; got $occurrences in:\n$text")
    }

    @Test
    fun `a STANDALONE invoice prints no TSE block`() {
        // expectsTse = false: never went through the till, so no KassenSichV disclosure applies.
        val text = textOf(renderSalesInvoicePdf(pdfData(dueDate = null, expectsTse = false)))

        assertFalse(text.contains("Ausfall"), "a standalone invoice must not assert a TSE failure")
        assertFalse(text.contains("TSE-Signatur"), "a standalone invoice must print no TSE block")
    }

    /**
     * E2E 2026-09-27: on a Gutschrift the VAT line read "zzgl. 19 % USt auf" and then "-200,00 €"
     * split across lines, with the minus sign stranded — the line breaker treats "-" as a break
     * opportunity. The amount must stay one unit on one line.
     */
    @Test
    fun `a credit note keeps the negative taxable amount on one line with its minus sign`() {
        val creditNote =
            pdfData(dueDate = null).copy(
                invoiceNumber = "RG-2026-00043",
                invoiceTypeCode = "381",
                precedingInvoiceNumber = "RG-2026-00042",
                lines =
                    listOf(
                        SalesInvoicePdfLine(1, "Catering", BigDecimal.ONE, BigDecimal("-200.0000"), BigDecimal("-238.0000"), BigDecimal("19.00")),
                    ),
                vatRows = listOf(SalesInvoicePdfVatRow(BigDecimal("19.00"), BigDecimal("-200.0000"), BigDecimal("-38.0000"))),
                netTotal = BigDecimal("-200.0000"),
                vatTotal = BigDecimal("-38.0000"),
                grossTotal = BigDecimal("-238.0000"),
                amountDue = BigDecimal("-238.0000"),
                paymentReference = null,
            )

        val lines =
            textOf(renderSalesInvoicePdf(creditNote))
                .lines()
                // A no-break space is what keeps the amount together; it reads as a space.
                .map { it.replace(' ', ' ') }

        assertTrue(
            lines.any { it.contains("USt auf -200,00 €") },
            "the VAT line must carry '-200,00 €' whole; lines were:\n${lines.joinToString("\n")}",
        )
    }

    @Test
    fun `a from-bill invoice with no TSE still states the Ausfall`() {
        // expectsTse = true + tse = null: a till sale whose TSE was down must disclose the outage.
        val text = textOf(renderSalesInvoicePdf(pdfData(dueDate = null, expectsTse = true)))

        assertTrue(text.contains("Ausfall"), "a from-bill invoice must state a missing signature")
        assertTrue(text.contains("TSE-Signatur"), "the TSE block must be present for a till sale")
    }

    /** A P-384 signature (96 bytes) in Base64: 128 characters, longer than any invoice line. */
    private val signature =
        java.util.Base64
            .getEncoder()
            .encodeToString(ByteArray(96) { (it * 5 + 7).toByte() })

    private val tse =
        TseReceiptDataDto(
            transactionNumber = 4711L,
            startTime = "2026-09-01T10:00:00.000Z",
            endTime = "2026-09-01T10:00:01.000Z",
            signatureCounter = 99L,
            tseSerialNumber = "8C6B9B4D4A0C6FBA2E7C9F0D1E2A3B4C5D6E7F8091A2B3C4D5E6F708192A3B4C",
            clientSerialNumber = "TERM-7",
            signature = signature,
        )

    private fun assertFullTseBlock(text: String) {
        val lines = text.lines().map { it.trim() }
        listOf(
            "TSE-Signatur",
            "TSE-Transaktion: 4711",
            "Start (UTC): 2026-09-01T10:00:00.000Z",
            "Ende (UTC): 2026-09-01T10:00:01.000Z",
            "Signaturzähler: 99",
            "TSE-Seriennr.:",
            "Kassen-Seriennr.: TERM-7",
            "Signatur:",
        ).forEach { assertTrue(it in lines, "missing \"$it\" in the invoice:\n$text") }

        val serialAt = lines.indexOf("TSE-Seriennr.:")
        assertEquals(tse.tseSerialNumber, lines[serialAt + 1] + lines[serialAt + 2], text)
        val sigAt = lines.indexOf("Signatur:")
        assertEquals(signature, lines.subList(sigAt + 1, sigAt + 4).joinToString(""), "Prüfwert untruncated:\n$text")
        assertFalse("Ausfall" in text, "a signed sale is not an Ausfall")
    }

    @Test
    fun `a from-bill invoice prints the receipt's full TSE data as text`() {
        assertFullTseBlock(textOf(renderSalesInvoicePdf(pdfData(dueDate = null, expectsTse = true).copy(tse = tse))))
    }

    @Test
    fun `the ZUGFeRD invoice prints the same TSE block in its embedded font`() {
        val data = pdfData(dueDate = null, expectsTse = true).copy(tse = tse)
        assertFullTseBlock(textOf(renderSalesInvoiceZugferdPdf(data, "<x/>".toByteArray())))
    }

    @Test
    fun `negative control - a STANDALONE invoice still prints no TSE block`() {
        val text = textOf(renderSalesInvoicePdf(pdfData(dueDate = null, expectsTse = false)))

        listOf("TSE-Transaktion", "Signatur:", "Kassen-Seriennr.", "Start (UTC)")
            .forEach { assertFalse(it in text, "a standalone invoice has no till transaction: \"$it\"") }
    }

    /** 119.00 at 19 % with an 11.90 promotion: the bill 107.10 = net 90.00 + VAT 17.10. */
    private fun promotedData(invoiceTypeCode: String = "380"): SalesInvoicePdfData {
        val sign = if (invoiceTypeCode == "381") BigDecimal.ONE.negate() else BigDecimal.ONE
        return pdfData(dueDate = null, expectsTse = true).copy(
            invoiceTypeCode = invoiceTypeCode,
            lines =
                listOf(
                    SalesInvoicePdfLine(1, "Beratung", BigDecimal.ONE, BigDecimal("100.0000") * sign, BigDecimal("119.0000") * sign, BigDecimal("19.00")),
                ),
            vatRows = listOf(SalesInvoicePdfVatRow(BigDecimal("19.00"), BigDecimal("90.0000") * sign, BigDecimal("17.1000") * sign)),
            allowances = listOf(SalesInvoicePdfAllowance("Aktion: Happy Hour", BigDecimal("19.00"), BigDecimal("11.9000") * sign, isCharge = false)),
            netTotal = BigDecimal("90.0000") * sign,
            vatTotal = BigDecimal("17.1000") * sign,
            grossTotal = BigDecimal("107.1000") * sign,
            amountDue = BigDecimal("107.1000") * sign,
        )
    }

    @Test
    fun `a promotion prints as a Nachlass block between the lines and the totals`() {
        val text = textOf(renderSalesInvoicePdf(promotedData()))

        assertTrue(text.contains("Nachlass"), text)
        assertTrue(text.contains("Aktion: Happy Hour (19 % USt)"), text)
        assertTrue(text.contains("-11,90 €"), "the allowance takes the amount off: $text")
        assertTrue(text.indexOf("Nachlass") > text.indexOf("Beratung"), "after the lines")
        assertTrue(text.indexOf("Nachlass") < text.indexOf("Nettobetrag"), "before the totals")
        assertTrue(text.contains("90,00 €"), "Nettobetrag is the net after the promotion: $text")
        assertTrue(text.contains("107,10 €"), "Gesamtbetrag: $text")
    }

    @Test
    fun `a Gutschrift gives the Nachlass back with the opposite sign`() {
        val text = textOf(renderSalesInvoicePdf(promotedData(invoiceTypeCode = "381")))

        assertTrue(text.contains("Aktion: Happy Hour (19 % USt)"), text)
        assertTrue(text.contains("11,90 €") && !text.contains("-11,90 €"), "a reversed allowance is positive: $text")
    }

    @Test
    fun `an invoice without a promotion prints no Nachlass block`() {
        assertFalse(textOf(renderSalesInvoicePdf(pdfData(dueDate = null))).contains("Nachlass"))
    }
}
