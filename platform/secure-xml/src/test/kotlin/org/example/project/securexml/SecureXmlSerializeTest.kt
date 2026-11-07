package org.example.project.securexml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The CII writer (a later task in `:accounting-einvoice`) must build and serialise a DOM without constructing
 * its own [javax.xml.parsers.DocumentBuilderFactory] — that would be a second, unhardened parser
 * factory. Routing document creation and serialisation through [SecureXml] keeps the single-factory
 * invariant [SingleXmlParserFactoryTest] enforces.
 */
class SecureXmlSerializeTest {
    @Test
    fun `newDocument returns an empty document`() {
        val doc = SecureXml.newDocument()

        assertNotNull(doc)
        assertEquals(null, doc.documentElement)
    }

    @Test
    fun `a document built via newDocument round-trips through serialize and parse`() {
        val doc = SecureXml.newDocument()
        val root = doc.createElementNS("urn:x", "a")
        val child = doc.createElementNS("urn:x", "b")
        child.textContent = "1"
        root.appendChild(child)
        doc.appendChild(root)

        val bytes = SecureXml.serialize(doc)
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(text.contains("<b>1</b>"), "expected serialized output to contain <b>1</b>, was: $text")

        val reparsed = SecureXml.parse(bytes)
        assertEquals("a", reparsed.documentElement.localName)
        assertEquals("urn:x", reparsed.documentElement.namespaceURI)

        // [INDENT] inserts whitespace text nodes between elements, so look the child up by name
        // rather than assuming it is documentElement.firstChild.
        val reparsedChild = reparsed.documentElement.getElementsByTagNameNS("urn:x", "b").item(0)
        assertNotNull(reparsedChild)
        assertEquals("b", reparsedChild.localName)
        assertEquals("1", reparsedChild.textContent)
    }
}
