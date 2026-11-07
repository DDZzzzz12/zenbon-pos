package org.example.project.einvoice.detect

import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class EInvoicePayloadLocatorTest {
    private fun corpus(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/corpus/$name")) { "missing corpus fixture: $name" }
            .use { it.readBytes() }

    @Test
    fun `bare xml is returned unchanged`() {
        val xml = """<a/>""".toByteArray()
        val found = EInvoicePayloadLocator.locate(xml, "application/xml")
        assertNotNull(found)
        assertTrue(found.decodeToString().contains("<a/>"))
    }

    /** The XML branch must hand back the caller's own array, not a defensive copy of it. */
    @Test
    fun `the xml branch does not copy the input`() {
        val xml = """<a/>""".toByteArray()
        assertSame(xml, EInvoicePayloadLocator.locate(xml, "text/xml"))
    }

    @Test
    fun `an embedded CII payload is extracted from a ZUGFeRD pdf`() {
        val found = EInvoicePayloadLocator.locate(corpus("zugferd-sample.pdf"), "application/pdf")
        assertNotNull(found, "expected an embedded XML attachment")
        assertTrue(found.decodeToString().contains("CrossIndustryInvoice"))
    }

    @Test
    fun `the MINIMUM profile pdf also yields its payload`() {
        val found = EInvoicePayloadLocator.locate(corpus("zugferd-minimum.pdf"), "application/pdf")
        assertNotNull(found, "expected an embedded XML attachment")
        assertContains(found.decodeToString(), "urn:factur-x.eu:1p0:minimum")
    }

    // A scanned invoice is a PDF with no attachment. That is the common case, not an error.
    @Test
    fun `a pdf without an attachment yields nothing`() {
        assertNull(EInvoicePayloadLocator.locate(corpus("plain-scan.pdf"), "application/pdf"))
    }

    @Test
    fun `a media type that is neither xml nor pdf yields nothing`() {
        // Deliberately real PDF bytes under a wrong media type: nothing may be opened, so the
        // attachment inside must NOT come back.
        assertNull(EInvoicePayloadLocator.locate(corpus("zugferd-sample.pdf"), "image/jpeg"))
    }

    /**
     * A name tree is allowed to nest its entries under `/Kids`, and some producers do. Our corpus
     * PDFs are all flat, so this case can only be covered by building the PDF here.
     */
    @Test
    fun `an attachment nested under kids is still found`() {
        val pdf = pdfWithNestedAttachment("factur-x.xml", NESTED_PAYLOAD)
        val found = EInvoicePayloadLocator.locate(pdf, "application/pdf")
        assertNotNull(found, "expected the nested attachment to be found")
        assertEquals(NESTED_PAYLOAD, found.decodeToString())
    }

    /** An unconventional filename is still accepted, because the payload can still be EN 16931. */
    @Test
    fun `an xml attachment under an unconventional name is still found`() {
        val pdf = pdfWithNestedAttachment("supplier-export.xml", NESTED_PAYLOAD)
        assertEquals(NESTED_PAYLOAD, EInvoicePayloadLocator.locate(pdf, "application/pdf")?.decodeToString())
    }

    /** A non-XML attachment is not an e-invoice payload. */
    @Test
    fun `a non-xml attachment is ignored`() {
        val pdf = pdfWithNestedAttachment("terms.txt", "not xml")
        assertNull(EInvoicePayloadLocator.locate(pdf, "application/pdf"))
    }

    /** The conventional name wins even when another xml attachment sorts earlier. */
    @Test
    fun `the conventional attachment name is preferred`() {
        val pdf =
            pdfWithAttachments(
                mapOf("aaa-other.xml" to "<other/>", "factur-x.xml" to NESTED_PAYLOAD),
            )
        assertEquals(NESTED_PAYLOAD, EInvoicePayloadLocator.locate(pdf, "application/pdf")?.decodeToString())
    }

    /**
     * A PDF that cannot be opened is a broken file, not a scan without an attachment. Reporting it
     * as "no e-invoice here" would silently drop an invoice the supplier did send.
     */
    @Test
    fun `a corrupt pdf is reported, not silently treated as attachment-free`() {
        val corrupt = "%PDF-1.7\nthis is not a pdf body".toByteArray()
        try {
            EInvoicePayloadLocator.locate(corrupt, "application/pdf")
            fail("expected EInvoicePayloadException")
        } catch (e: EInvoicePayloadException) {
            assertContains(e.message.orEmpty(), "PDF")
        }
    }

    /** Same reasoning: too large is a distinct, reportable condition, not an absent payload. */
    @Test
    fun `an oversized pdf is reported rather than silently skipped`() {
        val huge = ByteArray(EInvoicePayloadLocator.MAX_PDF_BYTES + 1)
        try {
            EInvoicePayloadLocator.locate(huge, "application/pdf")
            fail("expected EInvoicePayloadException")
        } catch (e: EInvoicePayloadException) {
            assertContains(e.message.orEmpty(), "exceeds")
        }
    }

    // --- fixtures built here, because the committed corpus cannot express them -------------------

    private fun pdfWithAttachments(
        attachments: Map<String, String>,
        nested: Boolean = false,
    ): ByteArray =
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val specs =
                attachments.mapValues { (name, body) ->
                    val bytes = body.toByteArray()
                    PDComplexFileSpecification().apply {
                        file = name
                        fileUnicode = name
                        embeddedFile =
                            PDEmbeddedFile(doc, ByteArrayInputStream(bytes), COSName.FLATE_DECODE).apply {
                                subtype = "text/xml"
                                size = bytes.size
                            }
                    }
                }
            val root = PDEmbeddedFilesNameTreeNode()
            if (nested) {
                val kid =
                    PDEmbeddedFilesNameTreeNode().apply {
                        this.names = specs
                        // /Limits is required of a non-root name-tree node by the PDF spec, and the
                        // setters are private in PDFBox 3, so it is written straight to the COS
                        // dictionary. Nothing under test reads it; it is here so the fixture is a
                        // structurally valid tree rather than a shape only our own reader accepts.
                        getCOSObject().setItem(
                            COSName.LIMITS,
                            COSArray().apply {
                                add(COSString(specs.keys.min()))
                                add(COSString(specs.keys.max()))
                            },
                        )
                    }
                root.kids = listOf(kid)
            } else {
                root.names = specs
            }
            doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply { embeddedFiles = root }
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }

    private fun pdfWithNestedAttachment(
        name: String,
        body: String,
    ): ByteArray = pdfWithAttachments(mapOf(name to body), nested = true)

    private companion object {
        const val NESTED_PAYLOAD = "<rsm:CrossIndustryInvoice/>"
    }
}
