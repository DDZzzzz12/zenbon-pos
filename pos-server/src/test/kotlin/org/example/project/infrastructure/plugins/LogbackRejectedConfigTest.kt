package org.example.project.infrastructure.plugins

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `rejected.log` holds why a request was refused (4xx). It must stay out of `error.log` — a 4xx is
 * the client's outcome, not a server fault (E2E-2026-09-15 N12) — and out of the 7-day
 * `application.log`, so a burst of expired-token 401s cannot push real history out.
 */
class LogbackRejectedConfigTest {
    private val xml: String =
        checkNotNull(javaClass.getResource("/logback.xml")) { "logback.xml is not on the test classpath" }.readText()

    private fun appenderBlock(): String {
        val match = Regex("""<appender name="REJECTED_FILE".*?</appender>""", RegexOption.DOT_MATCHES_ALL).find(xml)
        return assertNotNull(match, "logback.xml has no REJECTED_FILE appender").value
    }

    private fun loggerBlock(): String {
        val match = Regex("""<logger name="rejected"[^>]*>.*?</logger>""", RegexOption.DOT_MATCHES_ALL).find(xml)
        return assertNotNull(match, "logback.xml has no logger named 'rejected'").value
    }

    @Test
    fun `the appender writes JSON to rejected log and keeps thirty days under a 200MB cap`() {
        val appender = appenderBlock()
        assertTrue(appender.contains("net.logstash.logback.encoder.LogstashEncoder"), "must be structured JSON")
        assertTrue(appender.contains("<file>\${LOG_DIR:-logs}/rejected.log</file>"))
        assertTrue(appender.contains("<maxHistory>30</maxHistory>"))
        assertTrue(appender.contains("<totalSizeCap>200MB</totalSizeCap>"))
    }

    @Test
    fun `the appender carries the MDC keys the search reads`() {
        val appender = appenderBlock()
        listOf("traceId", "code", "statusCode", "method", "path").forEach { key ->
            assertTrue(appender.contains("<includeMdcKeyName>$key</includeMdcKeyName>"), "missing MDC key $key")
        }
    }

    @Test
    fun `the rejected logger does not cascade into error or application logs`() {
        val logger = loggerBlock()
        assertTrue(logger.contains("additivity=\"false\""))
        assertTrue(logger.contains("level=\"INFO\""))
        assertTrue(logger.contains("ref=\"REJECTED_FILE\""))
        assertFalse(logger.contains("ERROR_FILE"), "a 4xx must never reach error.log")
        assertFalse(logger.contains("ref=\"FILE\""), "a 4xx must never reach the 7-day application.log")
    }
}
