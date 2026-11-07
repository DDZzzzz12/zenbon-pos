package org.example.project.tse.tasks

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.mocks.MockWormStoreFactory
import org.example.project.tse.models.TseStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.*

/**
 * Unit tests for TseMaintenanceTask.
 *
 * Tests focus on task lifecycle management and behavior without actual
 * TSE hardware. Since WormAPI cannot be easily mocked, we test:
 * - Task start/stop lifecycle
 * - SIMULATION mode behavior
 * - Manual maintenance execution (which will fail without hardware)
 *
 * @since 1.0.0
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TseMaintenanceTaskTest {

    private val logger = LoggerFactory.getLogger(TseMaintenanceTaskTest::class.java)

    private lateinit var testScope: TestScope

    @BeforeEach
    fun setup() {
        logger.info("═".repeat(80))
        logger.info("Setting up test...")
        testScope = TestScope()
    }

    @AfterEach
    fun teardown() {
        logger.info("Cleaning up test...")
        testScope.cancel()
        logger.info("═".repeat(80))
    }

    // ============================================================================================
    // Start/Stop Lifecycle Tests
    // ============================================================================================

    @Test
    fun `start returns true on first call in NORMAL mode`() {
        logger.info("Test: start returns true on first call in NORMAL mode")

        // Use NORMAL mode without actual hardware detection
        val tseStatus = MockWormStoreFactory.createTseStatusForNormalModeWithoutDetection()
        val maintenanceTask = TseMaintenanceTask(tseStatus, testScope)

        val result = maintenanceTask.start()

        logger.info("  - Start result: $result")

        assertTrue(result)
    }

    @Test
    fun `start returns false if already running`() {
        logger.info("Test: start returns false if already running")

        val tseStatus = MockWormStoreFactory.createTseStatusForNormalModeWithoutDetection()
        val maintenanceTask = TseMaintenanceTask(tseStatus, testScope)

        val firstStart = maintenanceTask.start()
        val secondStart = maintenanceTask.start()

        logger.info("  - First start: $firstStart")
        logger.info("  - Second start: $secondStart")

        assertTrue(firstStart)
        assertFalse(secondStart)
    }

    @Test
    fun `start returns false in SIMULATION mode`() {
        logger.info("Test: start returns false in SIMULATION mode")

        val tseStatus = MockWormStoreFactory.createTseStatusForTesting(
            mode = TseMode.SIMULATION
        )
        val maintenanceTask = TseMaintenanceTask(tseStatus, testScope)

        val result = maintenanceTask.start()

        logger.info("  - Start result: $result")

        assertFalse(result)
    }

    @Test
    fun `stop does not throw when not running`() {
        logger.info("Test: stop does not throw when not running")

        val tseStatus = MockWormStoreFactory.createTseStatusForNormalModeWithoutDetection()
        val maintenanceTask = TseMaintenanceTask(tseStatus, testScope)

        // Should not throw
        try {
            maintenanceTask.stop()
            logger.info("  - Stop succeeded (no exception)")
        } catch (e: Exception) {
            logger.error("  - Stop threw exception: ${e.message}", e)
            fail("stop() should not throw when not running: ${e.message}")
        }
    }

    @Test
    fun `stop cancels running task`() {
        logger.info("Test: stop cancels running task")

        val tseStatus = MockWormStoreFactory.createTseStatusForNormalModeWithoutDetection()
        val maintenanceTask = TseMaintenanceTask(tseStatus, testScope)

        maintenanceTask.start()
        maintenanceTask.stop()

        logger.info("  - Task started and stopped")

        // stop() must not cancel the injected scope: it is shared with other work, and
        // a restart on a cancelled scope would launch a dead job. Restarting on the
        // SAME scope must therefore work.
        assertTrue(testScope.isActive)
        val newTask = TseMaintenanceTask(tseStatus, testScope)
        val canStartAgain = newTask.start()

        logger.info("  - Can start new task on the same scope: $canStartAgain")

        assertTrue(canStartAgain)
    }

    @Test
    fun `stopAndJoin waits for the maintenance job to terminate`() = testScope.runTest {
        logger.info("Test: stopAndJoin waits for the maintenance job to terminate")

        val tseStatus = MockWormStoreFactory.createTseStatusForNormalModeWithoutDetection()
        val maintenanceTask = TseMaintenanceTask(tseStatus, testScope)

        assertTrue(maintenanceTask.start())
        maintenanceTask.stopAndJoin()

        val running = maintenanceTask.getStandbyDetectionStatus()["isRunning"] as Boolean
        logger.info("  - isRunning after stopAndJoin: $running")
        assertFalse(running)
    }

    @Test
    fun `start returns false when the injected scope is already cancelled`() {
        logger.info("Test: start returns false when the injected scope is already cancelled")

        val tseStatus = MockWormStoreFactory.createTseStatusForNormalModeWithoutDetection()
        testScope.cancel()
        val maintenanceTask = TseMaintenanceTask(tseStatus, testScope)

        val result = maintenanceTask.start()
        logger.info("  - Start result on cancelled scope: $result")
        assertFalse(result)
    }

    // ============================================================================================
    // SIMULATION Mode Tests
    // ============================================================================================

    @Test
    fun `maintenance not started in SIMULATION mode`() {
        logger.info("Test: maintenance not started in SIMULATION mode")

        val tseStatus = MockWormStoreFactory.createTseStatusForTesting()

        logger.info("  - Mode: ${tseStatus.getMode()}")

        assertEquals(TseMode.SIMULATION, tseStatus.getMode())

        val maintenanceTask = TseMaintenanceTask(tseStatus, testScope)
        val result = maintenanceTask.start()

        logger.info("  - Start result: $result")

        assertFalse(result)
    }

    // ============================================================================================
    // Manual Maintenance Tests
    // ============================================================================================

    @Test
    fun `performManualMaintenance returns false when not connected`() = testScope.runTest {
        logger.info("Test: performManualMaintenance returns false when not connected")

        val tseStatus = MockWormStoreFactory.createTseStatusForTesting()
        val maintenanceTask = TseMaintenanceTask(tseStatus, testScope)

        // Without hardware, maintenance should fail
        val (selfTestResult, timeSyncResult) = maintenanceTask.performManualMaintenance()

        logger.info("  - Self-test result: $selfTestResult")
        logger.info("  - Time sync result: $timeSyncResult")

        assertFalse(selfTestResult)
        assertFalse(timeSyncResult)
    }

    @Test
    fun `performManualMaintenance does not throw`() = testScope.runTest {
        logger.info("Test: performManualMaintenance does not throw")

        val tseStatus = MockWormStoreFactory.createTseStatusForNormalModeWithoutDetection()
        val maintenanceTask = TseMaintenanceTask(tseStatus, testScope)

        try {
            val result = maintenanceTask.performManualMaintenance()
            logger.info("  - Manual maintenance result: $result")
        } catch (e: Exception) {
            logger.error("  - Manual maintenance threw exception: ${e.message}", e)
            fail("performManualMaintenance() should not throw: ${e.message}")
        }
    }

    // ============================================================================================
    // Configuration Tests
    // ============================================================================================

    @Test
    fun `maintenanceTask uses TseStatus configuration`() {
        logger.info("Test: maintenanceTask uses TseStatus configuration")

        val tseStatus = MockWormStoreFactory.createTseStatusForTesting(
            mode = TseMode.NORMAL,
            clientId = "CUSTOM-CLIENT",
        )
        val maintenanceTask = TseMaintenanceTask(tseStatus, testScope)

        logger.info("  - Mode: ${tseStatus.getMode()}")
        logger.info("  - Client ID: ${tseStatus.getClientId()}")

        // Verify the TseStatus configuration is preserved
        assertEquals(TseMode.NORMAL, tseStatus.getMode())
        assertEquals("CUSTOM-CLIENT", tseStatus.getClientId())

        // Task should start successfully with NORMAL mode
        val started = maintenanceTask.start()
        logger.info("  - Task started: $started")
        assertTrue(started)
    }

    // ============================================================================================
    // Task Lifecycle Edge Cases
    // ============================================================================================

    @Test
    fun `multiple start calls after stop work correctly`() {
        logger.info("Test: multiple start calls after stop work correctly")

        val tseStatus = MockWormStoreFactory.createTseStatusForNormalModeWithoutDetection()

        // Two start/stop cycles on the SAME task instance and scope — stop() cancels
        // only its own job, so a task is restartable without a fresh scope.
        val task = TseMaintenanceTask(tseStatus, testScope)
        val started1 = task.start()
        task.stop()
        logger.info("  - First cycle: started=$started1")

        val started2 = task.start()
        task.stop()
        logger.info("  - Second cycle: started=$started2")

        assertTrue(started1)
        assertTrue(started2)
    }

    @Test
    fun `task handles mode change after creation`() {
        logger.info("Test: task handles mode change after creation")

        val tseStatus = MockWormStoreFactory.createTseStatusForTesting()
        val maintenanceTask = TseMaintenanceTask(tseStatus, testScope)

        // Initially in SIMULATION mode
        val startedSimulation = maintenanceTask.start()
        logger.info("  - Started in SIMULATION mode: $startedSimulation")
        assertFalse(startedSimulation)

        // Change mode (would need new task in practice)
        tseStatus.setMode(TseMode.NORMAL)

        // Task checks mode on start
        val newScope = TestScope()
        val newTask = TseMaintenanceTask(tseStatus, newScope)
        val startedNormal = newTask.start()
        logger.info("  - Started in NORMAL mode: $startedNormal")
        assertTrue(startedNormal)

        newScope.cancel()
    }

    // ============================================================================================
    // Standby/Wake Detection Tests
    // ============================================================================================

    @Test
    fun `getStandbyDetectionStatus returns correct initial state`() {
        logger.info("Test: getStandbyDetectionStatus returns correct initial state")

        val tseStatus = MockWormStoreFactory.createTseStatusForNormalModeWithoutDetection()
        val maintenanceTask = TseMaintenanceTask(tseStatus, testScope)

        val status = maintenanceTask.getStandbyDetectionStatus()

        logger.info("  - Status: $status")

        // Before start, lastCheckTimestamp should be 0
        assertEquals(0L, status["lastCheckTimestamp"])
        assertEquals(600, status["checkIntervalSeconds"])
        assertEquals(900L, status["standbyDetectionThreshold"]) // 600 * 1.5
        assertEquals(30, status["minStandbyThresholdSeconds"])
        assertFalse(status["isRunning"] as Boolean)
    }

    @Test
    fun `notifyWakeFromStandby returns false when not connected`() = testScope.runTest {
        logger.info("Test: notifyWakeFromStandby returns false when not connected")

        val tseStatus = MockWormStoreFactory.createTseStatusForTesting(
            mode = TseMode.SIMULATION
        )
        val maintenanceTask = TseMaintenanceTask(tseStatus, testScope)

        val result = maintenanceTask.notifyWakeFromStandby()

        logger.info("  - Result: $result")

        // Without WormStore, should return false
        assertFalse(result)
    }

    @Test
    fun `notifyWakeFromStandby does not throw without hardware`() = testScope.runTest {
        logger.info("Test: notifyWakeFromStandby does not throw without hardware")

        val tseStatus = MockWormStoreFactory.createTseStatusForNormalModeWithoutDetection()
        val maintenanceTask = TseMaintenanceTask(tseStatus, testScope)

        try {
            val result = maintenanceTask.notifyWakeFromStandby()
            logger.info("  - notifyWakeFromStandby returned: $result")
            // Without WormStore instance, should return false gracefully
            assertFalse(result)
        } catch (e: Exception) {
            logger.error("  - notifyWakeFromStandby threw exception: ${e.message}", e)
            fail("notifyWakeFromStandby() should not throw: ${e.message}")
        }
    }

    @Test
    fun `getStandbyDetectionStatus shows running state after start`() {
        logger.info("Test: getStandbyDetectionStatus shows running state after start")

        val tseStatus = MockWormStoreFactory.createTseStatusForNormalModeWithoutDetection()
        val maintenanceTask = TseMaintenanceTask(tseStatus, testScope)

        // Start the task
        maintenanceTask.start()

        val status = maintenanceTask.getStandbyDetectionStatus()
        logger.info("  - Status after start: $status")

        assertTrue(status["isRunning"] as Boolean)

        maintenanceTask.stop()
    }

    @Test
    fun `standby detection thresholds are sensible`() {
        logger.info("Test: standby detection thresholds are sensible")

        val tseStatus = MockWormStoreFactory.createTseStatusForNormalModeWithoutDetection()
        val maintenanceTask = TseMaintenanceTask(tseStatus, testScope)

        val status = maintenanceTask.getStandbyDetectionStatus()

        val checkInterval = status["checkIntervalSeconds"] as Int
        val threshold = status["standbyDetectionThreshold"] as Long
        val minStandby = status["minStandbyThresholdSeconds"] as Int

        logger.info("  - Check interval: ${checkInterval}s")
        logger.info("  - Standby threshold: ${threshold}s")
        logger.info("  - Min standby: ${minStandby}s")

        // Threshold should be > check interval (allows for detection)
        assertTrue(threshold > checkInterval)

        // Min standby should be < threshold to allow detection
        assertTrue(minStandby < threshold)

        // Check interval should be reasonable (1-10 minutes)
        assertTrue(checkInterval in 60..600)

        // Min standby should be reasonable (> 10 seconds to avoid false positives)
        assertTrue(minStandby >= 10)
    }
}
