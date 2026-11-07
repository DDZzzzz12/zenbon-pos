package org.example.project.securexml

import org.w3c.dom.Document
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.FactoryConfigurationError
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * The single entry point for turning bytes into a DOM.
 *
 * Every document this parses arrived from a third party — a supplier's e-invoice, a bank's
 * statement — so the parser is configured to refuse doctypes and external entities outright.
 * Resolving them would expose the local filesystem and outbound network to whoever sent the file.
 * No consuming module may construct its own [DocumentBuilderFactory] — a second one would silently
 * bypass this hardening, and each consumer carries a test that fails the build if one appears.
 *
 * Every failure path — oversized input, hostile input, malformed input, and a JAXP implementation
 * that will not accept the hardening (including an absent factory or an unsupported feature,
 * attribute or flag) — leaves this object as an [XmlSecurityException]. Callers therefore never
 * have to catch a JAXP type to know the bytes were refused. Errors that say nothing about the input
 * or the parser, such as an exhausted heap, are deliberately left to propagate.
 */
object SecureXml {
    /** Generous for an invoice — real ones are kilobytes — but bounded, so a hostile file cannot exhaust memory. */
    const val MAX_XML_BYTES: Int = 16 * 1024 * 1024

    /**
     * Replaces the JAXP default handler, which writes `[Fatal Error] …` straight to stderr on every
     * rejection. That output bypasses the application's logger, cannot be suppressed by log
     * configuration, and lets whoever sent the invoice decide what lands in the process's stderr.
     *
     * Nothing is swallowed: `error` and `fatalError` are rethrown, so the [SAXParseException] still
     * reaches [parse] and still drives the typed refusal. Only the printing is removed.
     */
    private val SILENT_ERROR_HANDLER =
        object : ErrorHandler {
            override fun warning(exception: SAXParseException) = Unit

            override fun error(exception: SAXParseException): Unit = throw exception

            override fun fatalError(exception: SAXParseException): Unit = throw exception
        }

    private const val DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"
    private const val EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities"
    private const val EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities"
    private const val LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd"

    fun parse(bytes: ByteArray): Document {
        if (bytes.size > MAX_XML_BYTES) {
            throw XmlSecurityException("XML exceeds ${MAX_XML_BYTES}B limit: ${bytes.size}B")
        }
        val builder =
            try {
                hardenedFactory().newDocumentBuilder().apply { setErrorHandler(SILENT_ERROR_HANDLER) }
            } catch (e: ParserConfigurationException) {
                throw XmlSecurityException("Cannot build a hardened XML parser: ${e.message}", e)
            }

        return try {
            builder.parse(ByteArrayInputStream(bytes))
        } catch (e: SAXException) {
            // disallow-doctype-decl surfaces as a SAXParseException, so a rejected doctype and
            // genuinely malformed XML arrive here the same way. Both are refusals to parse
            // untrusted input.
            throw XmlSecurityException("XML rejected: ${e.message}", e)
        } catch (e: IOException) {
            // Unreachable for a ByteArrayInputStream, but the signature declares it. Wrapping it
            // keeps the promise that this object only ever throws XmlSecurityException, so a
            // caller's catch block cannot be silently incomplete.
            throw XmlSecurityException("XML could not be read: ${e.message}", e)
        }
    }

    /**
     * A new empty namespace-aware DOM, built from the same hardened factory [parse] uses. Lets the
     * CII writer (`:accounting-einvoice`) construct a document without a second, unhardened
     * [DocumentBuilderFactory] of its own.
     */
    fun newDocument(): Document =
        try {
            hardenedFactory().newDocumentBuilder().newDocument()
        } catch (e: ParserConfigurationException) {
            throw XmlSecurityException("Cannot build a hardened XML parser: ${e.message}", e)
        }

    /**
     * Serialises a DOM built via [newDocument] to UTF-8 bytes with an XML declaration.
     *
     * Uses [TransformerFactory], which is not one of the parser factories the module guard forbids:
     * it writes a DOM out rather than reading untrusted bytes in, so it is not an XXE surface. It is
     * still configured to disable external access, because a `Transformer` obtained from a
     * misconfigured factory could otherwise resolve a stylesheet reference.
     */
    fun serialize(document: Document): ByteArray {
        val factory = TransformerFactory.newInstance()
        try {
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        } catch (e: IllegalArgumentException) {
            // Not every JAXP implementation recognises these attributes; serialisation does not
            // read untrusted bytes, so an implementation that lacks them is not disqualified.
        }
        val transformer = factory.newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        val out = ByteArrayOutputStream()
        transformer.transform(DOMSource(document), StreamResult(out))
        return out.toByteArray()
    }

    /**
     * A factory that refuses doctypes and external references. Every switch here is load-bearing;
     * a JAXP implementation that rejects one is not safe enough to parse supplier input, so the
     * refusal is surfaced rather than swallowed.
     */
    private fun hardenedFactory(): DocumentBuilderFactory {
        val factory =
            try {
                DocumentBuilderFactory.newInstance()
            } catch (e: FactoryConfigurationError) {
                // An Error, not an Exception, so it would otherwise sail past every catch below.
                throw XmlSecurityException("No XML parser factory is available: ${e.message}", e)
            }
        factory.setSecureFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setSecureFeature(DISALLOW_DOCTYPE, true)
        factory.setSecureFeature(EXTERNAL_GENERAL_ENTITIES, false)
        factory.setSecureFeature(EXTERNAL_PARAMETER_ENTITIES, false)
        factory.setSecureFeature(LOAD_EXTERNAL_DTD, false)
        factory.setSecureAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setSecureAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        factory.setSecureFlags()
        return factory
    }

    /**
     * The three switches that are plain properties rather than named features. `setXIncludeAware` is
     * documented to throw on an implementation that does not override it, so these are wrapped for
     * the same reason as their `setFeature` siblings — an unsupported switch must refuse loudly, not
     * leave the parser quietly under-hardened.
     */
    private fun DocumentBuilderFactory.setSecureFlags() {
        try {
            this.isXIncludeAware = false
            this.isExpandEntityReferences = false
            // Later stages bind CII/UBL elements by namespace URI, so this must stay on.
            this.isNamespaceAware = true
        } catch (e: UnsupportedOperationException) {
            throw XmlSecurityException("XML parser does not support the required parser flags", e)
        }
    }

    private fun DocumentBuilderFactory.setSecureFeature(
        name: String,
        value: Boolean,
    ) {
        try {
            this.setFeature(name, value)
        } catch (e: ParserConfigurationException) {
            throw XmlSecurityException("XML parser does not support the required feature $name", e)
        }
    }

    private fun DocumentBuilderFactory.setSecureAttribute(
        name: String,
        value: String,
    ) {
        try {
            this.setAttribute(name, value)
        } catch (e: IllegalArgumentException) {
            throw XmlSecurityException("XML parser does not recognise the required attribute $name", e)
        }
    }
}
