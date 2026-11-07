package org.example.project.infrastructure.errors

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.error.PosErrorCode
import org.example.project.utils.errorHandling.ExceptionConverter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 4xx-below-ERROR gate for the service layer (2026-09-18 e2e finding #2). StatusPages already keeps
 * 4xx out of error.log (ErrorLoggingTest); ExceptionConverter.catchingThrows logged every
 * PosException at ERROR with a stack trace before StatusPages ever saw it.
 *
 * operation = null keeps the audit write (which needs a database) out of the test.
 */
class ExceptionConverterLogLevelTest {
    private val logger = LoggerFactory.getLogger(ExceptionConverter::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun throwThrough(error: Throwable) {
        assertFailsWith<Throwable> {
            runBlocking { ExceptionConverter.catchingThrows<Unit>(context = "testOp()") { throw error } }
        }
    }

    private fun eventsAtOrAbove(level: Level) = appender.list.filter { it.level.isGreaterOrEqual(level) }

    @Test
    fun `a 4xx PosException is not logged at ERROR and carries no stack trace`() {
        throwThrough(PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, "table occupied"))

        assertTrue(eventsAtOrAbove(Level.ERROR).isEmpty(), "got ${appender.list.map { it.level to it.formattedMessage }}")
        val rejected = appender.list.single { it.level == Level.INFO }
        assertTrue(rejected.formattedMessage.contains("VALIDATION_FAILED"))
        assertTrue(rejected.formattedMessage.contains("400"))
        assertNull(rejected.throwableProxy)
    }

    @Test
    fun `a 404 PosException is not logged at ERROR`() {
        throwThrough(PosException(PosErrorCode.CARD_TERMINAL_NOT_FOUND, HttpStatusCode.NotFound, "Terminal not found: unknown"))

        assertTrue(eventsAtOrAbove(Level.ERROR).isEmpty(), "got ${appender.list.map { it.level to it.formattedMessage }}")
    }

    @Test
    fun `a 5xx PosException is still logged at ERROR with its stack trace`() {
        throwThrough(PosException(PosErrorCode.TSE_UNREACHABLE, HttpStatusCode.ServiceUnavailable, "down"))

        val error = eventsAtOrAbove(Level.ERROR).single()
        assertEquals(Level.ERROR, error.level)
        assertNotNull(error.throwableProxy)
    }

    @Test
    fun `a non-PosException is still logged at ERROR`() {
        throwThrough(RuntimeException("boom"))

        assertEquals(1, eventsAtOrAbove(Level.ERROR).size)
    }
}
