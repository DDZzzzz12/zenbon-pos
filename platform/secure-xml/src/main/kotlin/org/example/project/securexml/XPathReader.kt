package org.example.project.securexml

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.math.BigDecimal
import javax.xml.namespace.NamespaceContext
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * A thin namespace-aware read helper. The prefixes in the expressions are the ones bound by the
 * supplied [NamespaceContext], not whatever the document happens to use.
 *
 * Absent and blank are both reported as null: an e-invoice that writes `<ram:ID/>` has not supplied
 * an invoice number, and treating that as `""` would push an empty string into the database.
 *
 * The JDK's `XPath` and `XPathFactory` are not thread-safe. This class is intended to be held as a
 * shared singleton by a syntax mapper (e.g. `private val read = XPathReader(...)` at `object`
 * level), so it builds one `XPath` per thread internally — each thread gets its own instance, and a
 * caller may safely keep one `XPathReader` in an `object` shared across concurrent uploads.
 */
class XPathReader(
    private val namespaces: NamespaceContext,
) {
    // XPathFactory is also not thread-safe, so it is built fresh inside the initializer, per thread,
    // rather than once in the constructor.
    private val threadLocalXPath =
        ThreadLocal.withInitial {
            XPathFactory.newInstance().newXPath().apply { namespaceContext = namespaces }
        }
    private val xpath get() = threadLocalXPath.get()

    fun string(
        node: Node,
        expression: String,
    ): String? = (xpath.evaluate(expression, node, XPathConstants.STRING) as String).trim().ifBlank { null }

    /**
     * Never parses through `Double`; the scale the sender wrote is preserved.
     *
     * Absent stays `null`; present-but-unparseable throws [XmlValueException]. Those are different
     * failure modes and collapsing them into one `null` is a real defect: a mapper that falls back to
     * `?: BigDecimal.ZERO` would turn a garbled amount (e.g. a German decimal comma, `1.234,56`) into
     * a silent zero in a fiscal document, indistinguishable from the field never having been sent.
     */
    fun decimal(
        node: Node,
        expression: String,
    ): BigDecimal? {
        val raw = string(node, expression) ?: return null
        return runCatching { BigDecimal(raw) }.getOrElse { throw XmlValueException(expression, raw, it) }
    }

    /**
     * Reads via [Element.getAttribute], which is not namespace-aware: it finds unprefixed attributes
     * only. That matches the intended call sites (`unitCode` on `BilledQuantity`, `format` on
     * `DateTimeString`, both unprefixed in real instances), but a namespaced attribute such as
     * `xsi:type` would not be found.
     */
    fun attribute(
        node: Node,
        expression: String,
        attribute: String,
    ): String? = (element(node, expression))?.getAttribute(attribute)?.trim()?.ifBlank { null }

    fun element(
        node: Node,
        expression: String,
    ): Element? = xpath.evaluate(expression, node, XPathConstants.NODE) as? Element

    /** Silently drops any matched node that is not an [Element] (e.g. text/comment nodes). */
    fun elements(
        node: Node,
        expression: String,
    ): List<Element> {
        val list = xpath.evaluate(expression, node, XPathConstants.NODESET) as NodeList
        return (0 until list.length).mapNotNull { list.item(it) as? Element }
    }
}
