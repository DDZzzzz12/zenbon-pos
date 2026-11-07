package org.example.project.securexml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Supplier-sent XML is untrusted input, and this is the only place the module parses it. An XXE
 * payload must be rejected outright rather than resolved — a parser that silently returns an empty
 * value for the entity is not safe, it just hides the read attempt.
 */
class SecureXmlTest {
    @Test
    fun `a well formed document parses`() {
        val doc = SecureXml.parse("""<a><b>x</b></a>""".toByteArray())
        assertEquals("a", doc.documentElement.localName)
    }

    @Test
    fun `an external entity declaration is rejected`() {
        val xxe =
            """
            <?xml version="1.0"?>
            <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <foo>&xxe;</foo>
            """.trimIndent().toByteArray()

        assertFailsWith<XmlSecurityException> { SecureXml.parse(xxe) }
    }

    // The billion-laughs / quadratic-blowup family is blocked by the same doctype ban, so this
    // asserts the ban rather than measuring expansion.
    @Test
    fun `a nested entity expansion document is rejected`() {
        val bomb =
            """
            <?xml version="1.0"?>
            <!DOCTYPE lolz [
              <!ENTITY lol "lol">
              <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;">
            ]>
            <lolz>&lol2;</lolz>
            """.trimIndent().toByteArray()

        assertFailsWith<XmlSecurityException> { SecureXml.parse(bomb) }
    }

    @Test
    fun `input above the size cap is rejected`() {
        val tooBig = ByteArray(SecureXml.MAX_XML_BYTES + 1) { '<'.code.toByte() }
        assertFailsWith<XmlSecurityException> { SecureXml.parse(tooBig) }
    }

    // Later tasks bind XPath by namespace URI, so namespace awareness must be on: a null
    // namespaceURI here would make every one of those lookups fail for no visible reason.
    @Test
    fun `the parser is namespace aware`() {
        val ns = "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"
        val doc = SecureXml.parse("""<rsm:CrossIndustryInvoice xmlns:rsm="$ns"/>""".toByteArray())

        assertEquals(ns, assertNotNull(doc.documentElement.namespaceURI))
        assertEquals("CrossIndustryInvoice", doc.documentElement.localName)
    }
}
