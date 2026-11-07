package org.example.project.pdf.zugferd

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.apache.pdfbox.Loader
import org.example.project.einvoice.DefaultEInvoiceParser
import org.example.project.einvoice.EInvoiceParseResult
import org.example.project.einvoice.model.EInvoice
import org.example.project.einvoice.model.EInvoiceFormat
import org.example.project.einvoice.model.EInvoiceLine
import org.example.project.einvoice.model.EInvoiceParty
import org.example.project.einvoice.model.EInvoiceVatBreakdown
import org.example.project.einvoice.syntax.CiiInvoiceWriter
import org.example.project.features.salesinvoice.domain.service.InvoiceBuyer
import org.example.project.features.salesinvoice.domain.service.InvoiceSeller
import org.example.project.features.salesinvoice.domain.service.SalesInvoicePdfData
import org.example.project.features.salesinvoice.domain.service.SalesInvoicePdfLine
import org.example.project.features.salesinvoice.domain.service.SalesInvoicePdfVatRow
import org.example.project.pdf.generator.renderSalesInvoicePdf
import org.example.project.pdf.generator.renderSalesInvoiceZugferdPdf
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The correctness gate for ZUGFeRD packaging (spec decision C): a PDF produced by
 * [renderSalesInvoiceZugferdPdf] must round-trip back through **our own** e-invoice parser to the
 * same invoice number. That proves the machine-readable CII payload is embedded, named and parseable
 * — the whole point of the container. Strict PDF/A-3 conformance is a separate, manual veraPDF gate.
 */
class ZugferdPackagerTest {
    private val ciiSample =
        EInvoice(
            format = EInvoiceFormat.ZUGFERD,
            invoiceNumber = INVOICE_NUMBER,
            issueDate = LocalDate(2026, 8, 18),
            currency = "EUR",
            seller =
                EInvoiceParty(
                    name = "Muster GmbH",
                    vatId = "DE123456789",
                    taxNumber = "12/345/67890",
                    street = "Musterstraße 1",
                    zip = "10115",
                    city = "Berlin",
                    country = "DE",
                ),
            buyer =
                EInvoiceParty(
                    name = "Kanzlei Maas",
                    vatId = "DE999999999",
                    street = "Anwaltsweg 5",
                    zip = "80331",
                    city = "München",
                    country = "DE",
                ),
            vatBreakdown =
                listOf(
                    EInvoiceVatBreakdown(
                        categoryCode = "S",
                        rate = BigDecimal("19.00"),
                        taxableAmount = BigDecimal("100.00"),
                        vatAmount = BigDecimal("19.00"),
                    ),
                ),
            lines =
                listOf(
                    EInvoiceLine(
                        lineId = "1",
                        itemName = "Beratung",
                        quantity = BigDecimal("1"),
                        unitCode = "HUR",
                        netUnitPrice = BigDecimal("100.00"),
                        lineNetAmount = BigDecimal("100.00"),
                        vatCategoryCode = "S",
                        vatRate = BigDecimal("19.00"),
                    ),
                ),
            deliveryDate = LocalDate(2026, 8, 18),
            invoiceTypeCode = "380",
            lineNetTotal = BigDecimal("100.00"),
            taxExclusiveTotal = BigDecimal("100.00"),
            taxTotal = BigDecimal("19.00"),
            taxInclusiveTotal = BigDecimal("119.00"),
            amountDue = BigDecimal("119.00"),
            prepaidAmount = BigDecimal("0.00"),
        )

    private fun pdfData() =
        SalesInvoicePdfData(
            invoiceNumber = INVOICE_NUMBER,
            issueDate = LocalDateTime(2026, 8, 18, 10, 0),
            serviceDate = LocalDateTime(2026, 8, 18, 10, 0),
            invoiceTypeCode = "380",
            precedingInvoiceNumber = null,
            buyer = InvoiceBuyer(name = "Kanzlei Maas", street = "Anwaltsweg 5", zip = "80331", city = "München"),
            seller =
                InvoiceSeller(
                    name = "Muster GmbH",
                    vatId = "DE123456789",
                    taxNumber = "12/345/67890",
                    street = "Musterstraße 1",
                    zip = "10115",
                    city = "Berlin",
                    country = "DE",
                ),
            lines =
                listOf(
                    SalesInvoicePdfLine(
                        lineNo = 1,
                        itemName = "Beratung",
                        quantity = BigDecimal.ONE,
                        netUnitPrice = BigDecimal("100.0000"),
                        grossAmount = BigDecimal("119.0000"),
                        vatRatePercent = BigDecimal("19.00"),
                    ),
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
            paymentReference = INVOICE_NUMBER,
            dueDate = null,
            paymentTermsText = null,
            expectsTse = false,
            tse = null,
        )

    @Test
    fun `the generated ZUGFeRD PDF round-trips through the e-invoice parser`() {
        val ciiXml = CiiInvoiceWriter.write(ciiSample)

        val pdfBytes = renderSalesInvoiceZugferdPdf(pdfData(), ciiXml)

        assertEquals("%PDF", pdfBytes.copyOfRange(0, 4).decodeToString(), "must be a real PDF")

        val result = DefaultEInvoiceParser().parse(pdfBytes, "application/pdf")

        assertTrue(
            result is EInvoiceParseResult.Success,
            "the parser must accept the embedded CII; got ${result::class.simpleName}: $result",
        )
        assertEquals(
            INVOICE_NUMBER,
            (result as EInvoiceParseResult.Success).invoice.invoiceNumber,
            "the embedded payload must be the invoice we packaged",
        )
    }

    @Test
    fun `the embedded file is named factur-x_xml`() {
        val ciiXml = CiiInvoiceWriter.write(ciiSample)

        val pdfBytes = renderSalesInvoiceZugferdPdf(pdfData(), ciiXml)

        Loader.loadPDF(pdfBytes).use { pdf ->
            val names =
                pdf.documentCatalog.names
                    ?.embeddedFiles
                    ?.names
            assertNotNull(names, "the PDF must carry an embedded-files name tree")
            assertTrue(
                names.keys.contains(ZugferdPackager.FACTUR_X_FILENAME),
                "the CII must be embedded as ${ZugferdPackager.FACTUR_X_FILENAME}, found: ${names.keys}",
            )
        }
    }

    @Test
    fun `the embedded file is tagged AFRelationship Alternative`() {
        val ciiXml = CiiInvoiceWriter.write(ciiSample)

        val pdfBytes = renderSalesInvoiceZugferdPdf(pdfData(), ciiXml)

        Loader.loadPDF(pdfBytes).use { pdf ->
            val fileSpec =
                pdf.documentCatalog.names
                    ?.embeddedFiles
                    ?.names
                    ?.get(ZugferdPackager.FACTUR_X_FILENAME)
            assertNotNull(fileSpec, "the factur-x.xml file spec must be present")
            assertEquals(
                "Alternative",
                fileSpec.cosObject.getNameAsString("AFRelationship"),
                "ZUGFeRD/Factur-X mandates AFRelationship = Alternative for the embedded CII",
            )
        }
    }

    @Test
    fun `the XMP declares PDF-A part 3 and the ZUGFeRD schema`() {
        val ciiXml = CiiInvoiceWriter.write(ciiSample)

        val pdfBytes = renderSalesInvoiceZugferdPdf(pdfData(), ciiXml)

        val xmp =
            Loader.loadPDF(pdfBytes).use { pdf ->
                pdf.documentCatalog.metadata
                    ?.exportXMPMetadata()
                    ?.readBytes()
                    ?.decodeToString()
            }
        assertNotNull(xmp, "a ZUGFeRD PDF/A must carry an XMP metadata stream")
        assertTrue(xmp.contains("<pdfaid:part>3</pdfaid:part>"), "XMP must declare PDF/A part 3")
        assertTrue(xmp.contains("urn:factur-x:pdfa:CrossIndustryDocument:invoice:1p0#"), "XMP must carry the fx: schema")
        assertTrue(xmp.contains("EN 16931"), "XMP must declare the EN 16931 conformance level")
    }

    @Test
    fun `the plain path stays a bare PDF with no embedded invoice`() {
        val pdfBytes = renderSalesInvoicePdf(pdfData())

        Loader.loadPDF(pdfBytes).use { pdf ->
            val embedded = pdf.documentCatalog.names?.embeddedFiles
            assertTrue(embedded == null, "the plain renderer must not embed a factur-x.xml")
        }
    }

    private companion object {
        const val INVOICE_NUMBER = "RG-2026-00001"
    }
}
