package org.example.project.securexml

import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.namespace.NamespaceContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The standard EN 16931 CII namespace URIs (UN/CEFACT), used only to prove [XPathReader] binds by
 * URI rather than by prefix text, and to read the real conformant corpus fixture below. `:platform-secure-xml`
 * is a leaf module with no knowledge of any concrete XML syntax, so this fixture is local to the
 * test rather than borrowed from a consumer such as `:accounting-einvoice`'s CII/UBL namespace table — these
 * are public standard identifiers, not something specific to that module.
 */
private object TestNamespaces {
    const val RSM = "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"
    const val RAM = "urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100"

    fun ciiContext(): NamespaceContext =
        object : NamespaceContext {
            private val byPrefix = mapOf("rsm" to RSM, "ram" to RAM)

            override fun getNamespaceURI(prefix: String?): String = byPrefix[prefix] ?: ""

            override fun getPrefix(namespaceURI: String?): String? = byPrefix.entries.firstOrNull { it.value == namespaceURI }?.key

            override fun getPrefixes(namespaceURI: String?): MutableIterator<String> =
                byPrefix
                    .filterValues { it == namespaceURI }
                    .keys
                    .toMutableList()
                    .iterator()
        }
}

/**
 * Senders pick their own prefixes, so the reader must bind by namespace URI. This document uses
 * deliberately wrong-looking prefixes to prove the reader never matches on the prefix text.
 */
class XPathReaderTest {
    private val doc =
        SecureXml.parse(
            """
            <weird:CrossIndustryInvoice xmlns:weird="${TestNamespaces.RSM}"
                                        xmlns:odd="${TestNamespaces.RAM}">
              <weird:ExchangedDocument><odd:ID>RE-1</odd:ID></weird:ExchangedDocument>
              <odd:Amount>100.1234</odd:Amount>
            </weird:CrossIndustryInvoice>
            """.trimIndent().toByteArray(),
        )

    private val reader = XPathReader(TestNamespaces.ciiContext())

    @Test
    fun `reads a string by namespace not by prefix`() {
        assertEquals("RE-1", reader.string(doc, "/rsm:CrossIndustryInvoice/rsm:ExchangedDocument/ram:ID"))
    }

    @Test
    fun `a missing path yields null rather than an empty string`() {
        assertNull(reader.string(doc, "/rsm:CrossIndustryInvoice/ram:Nonexistent"))
    }

    // Money must never round-trip through Double, and the scale the sender wrote is meaningful.
    @Test
    fun `decimals keep the scale they were written with`() {
        val amount = reader.decimal(doc, "/rsm:CrossIndustryInvoice/ram:Amount")
        assertEquals(BigDecimal("100.1234"), amount)
        assertEquals(4, amount!!.scale())
    }

    @Test
    fun `a present but unparseable decimal throws with the raw value and expression`() {
        val garbled =
            SecureXml.parse(
                """
                <weird:CrossIndustryInvoice xmlns:weird="${TestNamespaces.RSM}"
                                            xmlns:odd="${TestNamespaces.RAM}">
                  <odd:BasisAmount>1.234,56</odd:BasisAmount>
                </weird:CrossIndustryInvoice>
                """.trimIndent().toByteArray(),
            )

        val exception =
            assertFailsWith<XmlValueException> {
                reader.decimal(garbled, "/rsm:CrossIndustryInvoice/ram:BasisAmount")
            }

        assertTrue(exception.message!!.contains("1.234,56"))
        assertTrue(exception.message!!.contains("/rsm:CrossIndustryInvoice/ram:BasisAmount"))
    }

    @Test
    fun `an absent decimal element still returns null rather than throwing`() {
        assertNull(reader.decimal(doc, "/rsm:CrossIndustryInvoice/ram:Nonexistent"))
    }

    @Test
    fun `a present but blank decimal element returns null, not a throw`() {
        val blank =
            SecureXml.parse(
                """
                <weird:CrossIndustryInvoice xmlns:weird="${TestNamespaces.RSM}"
                                            xmlns:odd="${TestNamespaces.RAM}">
                  <odd:BasisAmount/>
                </weird:CrossIndustryInvoice>
                """.trimIndent().toByteArray(),
            )

        assertNull(reader.decimal(blank, "/rsm:CrossIndustryInvoice/ram:BasisAmount"))
    }

    @Test
    fun `elements returns every matching node in document order`() {
        val siblings =
            SecureXml.parse(
                """
                <weird:CrossIndustryInvoice xmlns:weird="${TestNamespaces.RSM}"
                                            xmlns:odd="${TestNamespaces.RAM}">
                  <odd:Line><odd:ID>1</odd:ID></odd:Line>
                  <odd:Line><odd:ID>2</odd:ID></odd:Line>
                  <odd:Line><odd:ID>3</odd:ID></odd:Line>
                </weird:CrossIndustryInvoice>
                """.trimIndent().toByteArray(),
            )

        val lines = reader.elements(siblings, "/rsm:CrossIndustryInvoice/ram:Line")

        assertEquals(3, lines.size)
        assertEquals(listOf("1", "2", "3"), lines.map { reader.string(it, "ram:ID") })
    }

    @Test
    fun `attribute reads an attribute off a matched element and returns null when absent`() {
        val withAttribute =
            SecureXml.parse(
                """
                <weird:CrossIndustryInvoice xmlns:weird="${TestNamespaces.RSM}"
                                            xmlns:odd="${TestNamespaces.RAM}">
                  <odd:BilledQuantity unitCode="C62">5</odd:BilledQuantity>
                  <odd:NoAttribute>7</odd:NoAttribute>
                </weird:CrossIndustryInvoice>
                """.trimIndent().toByteArray(),
            )

        assertEquals(
            "C62",
            reader.attribute(withAttribute, "/rsm:CrossIndustryInvoice/ram:BilledQuantity", "unitCode"),
        )
        assertNull(reader.attribute(withAttribute, "/rsm:CrossIndustryInvoice/ram:NoAttribute", "unitCode"))
    }

    @Test
    fun `reads the invoice number from a real conformant corpus file`() {
        val bytes =
            checkNotNull(javaClass.getResourceAsStream("/corpus/xrechnung-cii.xml")) {
                "corpus fixture missing"
            }.readBytes()
        val corpusDoc = SecureXml.parse(bytes)

        assertEquals(
            "123456XX",
            reader.string(corpusDoc, "/rsm:CrossIndustryInvoice/rsm:ExchangedDocument/ram:ID"),
        )
    }

    /**
     * `XPath` (and the `XPathFactory` that produces it) is not thread-safe in the JDK, and this
     * reader is meant to be held as a shared singleton by the CII/UBL mappers. This test drives many
     * threads through one shared [XPathReader] instance at once and asserts every read comes back
     * correct with no exception escaping.
     *
     * Each thread parses its **own** `Document` from identical bytes rather than sharing one `doc`
     * across threads. This matches production: a mapper singleton is shared across concurrent
     * uploads, but each upload parses its own file, so no two threads ever hand the same `Document`
     * to the reader at once. It also matters for what this test can actually prove — an earlier
     * version of this test shared one `Document` across all threads and kept failing intermittently
     * even after [XPathReader] was made thread-safe, because the JDK's default DOM implementation
     * (Xerces) is independently not safe for concurrent reads: it lazily computes and caches internal
     * node state without synchronization. That is a real hazard, but it is a hazard in sharing a
     * `Document` across threads, not in [XPathReader] — which does not own or control the `Document`
     * it is handed. Parsing per-thread isolates the race this test exists to catch: [XPathReader]'s
     * own thread-safety, independent of that separate, orthogonal DOM concern.
     *
     * This is inherently probabilistic, not deterministic: a data race on a shared, non-thread-safe
     * `XPath` does not fail on every run, only when two threads' internal state collides during the
     * same window. A [CountDownLatch] lines every thread up at the starting gate to maximize the odds
     * of that collision, and the iteration count is set high enough (a few hundred evaluations spread
     * across several threads) to make the race very likely to surface if the implementation ever
     * regresses to a single shared `XPath` field — but a clean run of this test is evidence of safety,
     * not proof of it.
     */
    @Test
    fun `concurrent reads through one shared reader all return the correct value`() {
        val threadCount = 16
        val iterationsPerThread = 25
        val pool = Executors.newFixedThreadPool(threadCount)
        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)
        val mismatches = AtomicInteger(0)
        val exceptions = AtomicInteger(0)

        try {
            val futures =
                (1..threadCount).map {
                    pool.submit {
                        readyLatch.countDown()
                        startLatch.await()
                        val threadLocalDoc =
                            SecureXml.parse(
                                """
                                <weird:CrossIndustryInvoice xmlns:weird="${TestNamespaces.RSM}"
                                                            xmlns:odd="${TestNamespaces.RAM}">
                                  <weird:ExchangedDocument><odd:ID>RE-1</odd:ID></weird:ExchangedDocument>
                                  <odd:Amount>100.1234</odd:Amount>
                                </weird:CrossIndustryInvoice>
                                """.trimIndent().toByteArray(),
                            )
                        repeat(iterationsPerThread) {
                            try {
                                val value =
                                    reader.string(
                                        threadLocalDoc,
                                        "/rsm:CrossIndustryInvoice/rsm:ExchangedDocument/ram:ID",
                                    )
                                if (value != "RE-1") mismatches.incrementAndGet()
                            } catch (_: Exception) {
                                exceptions.incrementAndGet()
                            }
                        }
                    }
                }

            readyLatch.await()
            startLatch.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }

        assertEquals(0, exceptions.get(), "no read should throw when shared across threads")
        assertTrue(mismatches.get() == 0, "every one of ${threadCount * iterationsPerThread} reads must return RE-1")
    }
}
