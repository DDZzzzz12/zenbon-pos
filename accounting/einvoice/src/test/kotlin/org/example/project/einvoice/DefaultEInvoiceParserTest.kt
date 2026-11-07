package org.example.project.einvoice

import org.example.project.einvoice.model.EInvoiceFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end tests over the real `corpus/` fixtures.
 *
 * Two things can only be asserted here and nowhere earlier in the module: that every outcome is a
 * *value* rather than a thrown exception, and that [EInvoiceFormat] is decided by the parser from
 * the profile URN — a mapper sees one syntax and cannot tell ZUGFeRD CII from CII-flavoured
 * XRechnung.
 */
class DefaultEInvoiceParserTest {
    private val parser = DefaultEInvoiceParser()

    private fun corpus(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/corpus/$name")).use { it.readBytes() }

    private fun parseXml(name: String) = parser.parse(corpus(name), "application/xml")

    private fun parsePdf(name: String) = parser.parse(corpus(name), "application/pdf")

    @Test
    fun `parses the CII XRechnung fixture`() {
        val result = parseXml("xrechnung-cii.xml")

        val success = assertIs<EInvoiceParseResult.Success>(result)
        assertEquals("123456XX", success.invoice.invoiceNumber)
        assertTrue(success.invoice.vatBreakdown.isNotEmpty(), "VAT breakdown must not be empty")
    }

    @Test
    fun `parses the UBL XRechnung fixture`() {
        val result = parseXml("xrechnung-ubl.xml")

        val success = assertIs<EInvoiceParseResult.Success>(result)
        assertTrue(success.invoice.vatBreakdown.isNotEmpty(), "VAT breakdown must not be empty")
        assertEquals(EInvoiceFormat.UBL, success.invoice.format)
    }

    @Test
    fun `parses a ZUGFeRD PDF by unwrapping its embedded attachment`() {
        val result = parsePdf("zugferd-sample.pdf")

        val success = assertIs<EInvoiceParseResult.Success>(result)
        assertTrue(success.invoice.invoiceNumber.isNotBlank(), "invoice number must be mapped")
    }

    /**
     * The format rule: a CII payload declaring the XRechnung guideline URN is an XRechnung, one
     * declaring any other conformant profile is ZUGFeRD. No earlier task could test this, because
     * the mappers see only the syntax.
     */
    @Test
    fun `assigns XRECHNUNG to the CII fixture and ZUGFERD to the ZUGFeRD PDF`() {
        val cii = assertIs<EInvoiceParseResult.Success>(parseXml("xrechnung-cii.xml"))
        val zugferd = assertIs<EInvoiceParseResult.Success>(parsePdf("zugferd-sample.pdf"))

        assertEquals(EInvoiceFormat.XRECHNUNG, cii.invoice.format)
        assertEquals(EInvoiceFormat.ZUGFERD, zugferd.invoice.format)
    }

    /** The everyday case: a scanned invoice is not an error, it simply carries no payload. */
    @Test
    fun `reports a plain scanned PDF as NoEInvoiceFound`() {
        assertEquals(EInvoiceParseResult.NoEInvoiceFound, parsePdf("plain-scan.pdf"))
    }

    @Test
    fun `reports a MINIMUM profile as NotConformant rather than a failure`() {
        val result = parsePdf("zugferd-minimum.pdf")

        val notConformant = assertIs<EInvoiceParseResult.NotConformant>(result)
        assertEquals("urn:factur-x.eu:1p0:minimum", notConformant.profile)
        assertTrue(
            notConformant.reason.contains("MINIMUM"),
            "reason should name the profile, was: ${notConformant.reason}",
        )
    }

    @Test
    fun `reports malformed XML as a Failure value with its cause preserved`() {
        val result = parseXml("malformed.xml")

        val failure = assertIs<EInvoiceParseResult.Failure>(result)
        assertNotNull(failure.cause, "an exception path must preserve its cause")
        assertTrue(
            failure.reason.contains("XML", ignoreCase = true),
            "reason should name the stage, was: ${failure.reason}",
        )
    }

    @Test
    fun `refuses an XXE payload as a Failure`() {
        val xxe =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE invoice [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100">
              <note>&xxe;</note>
            </rsm:CrossIndustryInvoice>
            """.trimIndent().toByteArray()

        val failure = assertIs<EInvoiceParseResult.Failure>(parser.parse(xxe, "application/xml"))
        assertNotNull(failure.cause)
    }

    /** An unrelated attachment is a normal thing to encounter — not a broken invoice. */
    @Test
    fun `reports an unrelated XML document as NoEInvoiceFound`() {
        val unrelated = """<order xmlns="urn:example:order"/>""".toByteArray()

        assertEquals(EInvoiceParseResult.NoEInvoiceFound, parser.parse(unrelated, "application/xml"))
    }

    @Test
    fun `reports a corrupt PDF as a Failure value rather than throwing`() {
        val corrupt = "%PDF-1.4\nthis is not a pdf at all".toByteArray()

        val failure = assertIs<EInvoiceParseResult.Failure>(parser.parse(corrupt, "application/pdf"))
        assertNotNull(failure.cause, "an exception path must preserve its cause")
    }

    /**
     * Deliberately hostile input: 50 000 levels of nesting. Whatever the DOM and XPath layers do
     * with it — refuse it, exhaust a stack, or map it to nothing — `parse` must hand back a value.
     */
    @Test
    fun `returns a value rather than throwing on a pathologically nested document`() {
        val depth = 50_000
        val nested =
            buildString(depth * 8) {
                append("""<rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100">""")
                repeat(depth) { append("<a>") }
                repeat(depth) { append("</a>") }
                append("</rsm:CrossIndustryInvoice>")
            }.toByteArray()

        val result = parser.parse(nested, "application/xml")

        assertTrue(
            result is EInvoiceParseResult.Failure || result is EInvoiceParseResult.NoEInvoiceFound,
            "expected a value outcome, was: $result",
        )
    }

    @Test
    fun `reports an unsupported media type as NoEInvoiceFound`() {
        assertEquals(EInvoiceParseResult.NoEInvoiceFound, parser.parse(byteArrayOf(1, 2, 3), "image/png"))
    }
}
