package org.example.project.tse.models

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.example.project.tse.mocks.MockWormStoreFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MaintenanceStateTest {

    private lateinit var tseStatus: TseStatus
    private lateinit var appender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun setup() {
        tseStatus = MockWormStoreFactory.createTseStatusForNormalModeWithoutDetection()
        appender = ListAppender<ILoggingEvent>().also { it.start() }
        val maintenanceLogger = LoggerFactory.getLogger("org.example.project.tse.models.MaintenanceState") as Logger
        maintenanceLogger.addAppender(appender)
        maintenanceLogger.level = Level.DEBUG
    }

    @AfterEach
    fun teardown() {
        val maintenanceLogger = LoggerFactory.getLogger("org.example.project.tse.models.MaintenanceState") as Logger
        maintenanceLogger.detachAppender(appender)
    }

    @Test
    fun `withMaintenance toggles isInMaintenance true during the block`() {
        assertFalse(tseStatus.isInMaintenance(), "precondition: not in maintenance")
        tseStatus.withMaintenance("test-op") {
            assertTrue(tseStatus.isInMaintenance(), "should be in maintenance inside block")
            assertEquals(setOf("test-op"), tseStatus.currentMaintenanceOps())
        }
        assertFalse(tseStatus.isInMaintenance(), "should clear after block returns")
        assertTrue(tseStatus.currentMaintenanceOps().isEmpty())
    }

    @Test
    fun `withMaintenance returns the block's value`() {
        val result = tseStatus.withMaintenance("test-op") { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `overlapping ops compose — outer stays in maintenance until inner exits`() {
        tseStatus.withMaintenance("outer", maxDuration = 2.seconds) {
            assertTrue(tseStatus.isInMaintenance())
            tseStatus.withMaintenance("inner", maxDuration = 2.seconds) {
                assertEquals(setOf("outer", "inner"), tseStatus.currentMaintenanceOps())
            }
            assertTrue(tseStatus.isInMaintenance())
            assertEquals(setOf("outer"), tseStatus.currentMaintenanceOps())
        }
        assertFalse(tseStatus.isInMaintenance())
    }

    @Test
    fun `exception in block still clears the slot`() {
        val ex = assertThrowsHelper(IllegalStateException::class.java) {
            tseStatus.withMaintenance("throws") { throw IllegalStateException("boom") }
        }
        assertEquals("boom", ex.message)
        assertFalse(tseStatus.isInMaintenance(), "slot must clear via try/finally even on throw")
    }

    @Test
    fun `watchdog force-clears stuck slot and logs error`() {
        val holdThread = Thread {
            tseStatus.withMaintenance("stuck-op", maxDuration = 50.milliseconds) {
                Thread.sleep(500)
            }
        }
        holdThread.start()
        Thread.sleep(200)
        assertFalse(
            tseStatus.isInMaintenance(),
            "watchdog should have force-cleared the slot before holdThread finishes"
        )
        val errorLogs = appender.list.filter { it.level == Level.ERROR }
        assertTrue(
            errorLogs.any { it.formattedMessage.contains("force-clearing") && it.formattedMessage.contains("stuck-op") },
            "expected ERROR log mentioning 'force-clearing' and 'stuck-op'; got: ${errorLogs.map { it.formattedMessage }}"
        )
        holdThread.join()
    }
}

private fun <T : Throwable> assertThrowsHelper(type: Class<T>, block: () -> Unit): T {
    try { block() } catch (e: Throwable) {
        if (type.isInstance(e)) @Suppress("UNCHECKED_CAST") return e as T
        throw AssertionError("expected ${type.name} but got ${e::class.java.name}", e)
    }
    throw AssertionError("expected ${type.name} but no exception thrown")
}
