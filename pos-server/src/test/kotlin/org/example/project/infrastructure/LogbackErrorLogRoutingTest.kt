package org.example.project.infrastructure

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `error.log` is fed by the ERROR_FILE appender. A logger with `additivity="false"` never reaches the
 * root logger, so each such logger that writes `application.log` must name ERROR_FILE itself; before
 * that, 16 ERROR lines were in `application.log` and none in `error.log` (RTSE-35).
 */
class LogbackErrorLogRoutingTest {
    private val loggers: List<Element> by lazy {
        val stream = javaClass.classLoader.getResourceAsStream("logback.xml") ?: error("logback.xml missing")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
        val nodes = doc.getElementsByTagName("logger")
        (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.appenderRefs(): Set<String> {
        val refs = getElementsByTagName("appender-ref")
        return (0 until refs.length).map { (refs.item(it) as Element).getAttribute("ref") }.toSet()
    }

    @Test
    fun `every non-additive logger that writes application log also writes error log`() {
        val missing = loggers.filter { it.getAttribute("additivity") == "false" && "FILE" in it.appenderRefs() && "ERROR_FILE" !in it.appenderRefs() }
        assertEquals(emptyList(), missing.map { it.getAttribute("name") })
    }

    @Test
    fun `client error and rejected logs stay out of error log`() {
        val byName = loggers.associateBy { it.getAttribute("name") }
        assertTrue("ERROR_FILE" !in byName.getValue("client-error").appenderRefs())
        assertTrue("ERROR_FILE" !in byName.getValue("rejected").appenderRefs())
    }
}
