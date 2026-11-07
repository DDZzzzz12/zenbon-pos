package org.example.project.einvoice.detect

import org.example.project.einvoice.xml.XmlNamespaces
import org.example.project.securexml.SecureXml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EInvoiceSyntaxDetectorTest {
    private fun rootOf(xml: String) = SecureXml.parse(xml.toByteArray())

    private fun corpusDoc(resourceName: String) =
        SecureXml.parse(
            checkNotNull(javaClass.getResourceAsStream("/corpus/$resourceName")) {
                "corpus fixture missing: $resourceName"
            }.readBytes(),
        )

    @Test
    fun `detects CII by root namespace`() {
        val doc = rootOf("""<rsm:CrossIndustryInvoice xmlns:rsm="${XmlNamespaces.RSM}"/>""")
        assertEquals(EInvoiceSyntax.CII, EInvoiceSyntaxDetector.detect(doc))
    }

    @Test
    fun `detects UBL invoice by root namespace`() {
        val doc = rootOf("""<Invoice xmlns="${XmlNamespaces.UBL_INVOICE}"/>""")
        assertEquals(EInvoiceSyntax.UBL, EInvoiceSyntaxDetector.detect(doc))
    }

    @Test
    fun `detects a UBL credit note as UBL`() {
        val doc = rootOf("""<CreditNote xmlns="${XmlNamespaces.UBL_CREDIT_NOTE}"/>""")
        assertEquals(EInvoiceSyntax.UBL, EInvoiceSyntaxDetector.detect(doc))
    }

    // An arbitrary XML attachment is not an e-invoice; that is a normal outcome, not an error.
    @Test
    fun `unrelated xml is not a recognised syntax`() {
        val doc = rootOf("""<order xmlns="urn:example:order"/>""")
        assertNull(EInvoiceSyntaxDetector.detect(doc))
    }

    // A UBL file whose author forgot the namespace entirely is not a conformant UBL invoice.
    @Test
    fun `a namespace-less root is not a recognised syntax`() {
        val doc = rootOf("""<Invoice/>""")
        assertNull(EInvoiceSyntaxDetector.detect(doc))
    }

    @Test
    fun `detects CII against the real xrechnung corpus fixture`() {
        val doc = corpusDoc("xrechnung-cii.xml")
        assertEquals(EInvoiceSyntax.CII, EInvoiceSyntaxDetector.detect(doc))
    }

    @Test
    fun `detects UBL against the real xrechnung corpus fixture`() {
        val doc = corpusDoc("xrechnung-ubl.xml")
        assertEquals(EInvoiceSyntax.UBL, EInvoiceSyntaxDetector.detect(doc))
    }
}
