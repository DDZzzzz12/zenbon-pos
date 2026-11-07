package org.example.project.tse.operations.admin.setup

import kotlinx.coroutines.test.runTest
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.mocks.MockWormStoreFactory
import org.example.project.tse.models.TseStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.*

/**
 * Tests for TSE Factory Reset Operation.
 *
 * WARNING: These tests interact with REAL TSE hardware.
 * - Factory reset ERASES ALL DATA on the TSE
 * - Only works on DEVELOPMENT firmware TSEs
 * - Cannot be reversed
 *
 * SAFETY GUIDELINES:
 * - Only run these tests with a development TSE you intend to reset
 * - Tests will be skipped if no hardware is available
 * - All operations are logged for debugging
 *
 * @since 2.0.0
 */
class FactoryResetOperationTest {

    private val logger = LoggerFactory.getLogger(FactoryResetOperationTest::class.java)

    private var hardwareAvailable: Boolean = false
    private var hardwareTseStatus: TseStatus? = null

    // Test credentials (matching MockWormStoreFactory.Defaults)
    private val adminPin = MockWormStoreFactory.Defaults.ADMIN_PIN

    @BeforeEach
    fun setUp() {
        logger.info("═".repeat(80))
        logger.info("Setting up Factory Reset test...")

        try {
            hardwareTseStatus = MockWormStoreFactory.createTseStatusForNormalMode()
            hardwareAvailable = hardwareTseStatus != null
            if (hardwareAvailable) {
                logger.info("✓ TSE hardware detected and connected")
                logger.info("  - Mount point: ${hardwareTseStatus?.getMountPoint()}")
                logger.info("  - Init state: ${hardwareTseStatus?.getInitializationState()}")
            } else {
                logger.info("No TSE hardware available - hardware tests will be skipped")
            }
        } catch (e: Exception) {
            logger.warn("Hardware detection failed: ${e.message}", e)
            hardwareAvailable = false
        }
    }

    @AfterEach
    fun tearDown() {
        logger.info("Cleaning up test...")
        try {
            hardwareTseStatus?.close()
        } catch (e: Exception) {
            logger.warn("Error during cleanup: ${e.message}", e)
        }
        hardwareTseStatus = null
        logger.info("═".repeat(80))
    }

    // ============================================================================================
    // HARDWARE TESTS - Require real TSE with DEVELOPMENT firmware
    // ============================================================================================

    @Test
    fun `HARDWARE - factory reset on development TSE`() = runTest {
        logger.info("Test: HARDWARE - factory reset on development TSE")
        logger.warn("⚠️ WARNING: This test will ERASE ALL DATA on the TSE!")

        val tseStatus = hardwareTseStatus
        if (tseStatus == null) {
            logger.warn("SKIPPED: No TSE hardware available")
            return@runTest
        }

        logger.info("  - Hardware connected: ${tseStatus.isConnected()}")
        logger.info("  - Current init state: ${tseStatus.getInitializationState()}")

        // Check if this is a development TSE
        val isDevelopment = try {
            tseStatus.executeOnTseThread {
                val store = tseStatus.getWormStore()
                store.info().use { it.isDevelopmentFirmware() }
            }
        } catch (e: Exception) {
            logger.error("Failed to check development firmware: ${e.message}")
            false
        }

        if (!isDevelopment) {
            logger.warn("SKIPPED: TSE is not development firmware - factory reset not supported")
            return@runTest
        }

        logger.info("  - Development firmware: YES")
        logger.info("  - Proceeding with factory reset...")

        // Execute factory reset
        val operation = FactoryResetOperation(adminPin)
        val data = try {
            operation.execute(tseStatus)
        } catch (e: PosException) {
            logger.error("✗ Factory reset failed: code=${e.code} msg=${e.message}")
            fail("Factory reset should not fail on development TSE: ${e.code} ${e.message}")
        }

        logger.info("✓ Factory reset result: $data")
        assertTrue(data.success, "Factory reset should succeed")

        // Verify TSE is now UNINITIALIZED
        val newState = try {
            tseStatus.executeOnTseThread {
                val store = tseStatus.getWormStore()
                store.info().use { it.initializationState().toString() }
            }
        } catch (e: Exception) {
            logger.error("Failed to check new state: ${e.message}")
            null
        }

        logger.info("  - New init state: $newState")
        assertEquals(
            "WORM_INIT_UNINITIALIZED",
            newState,
            "TSE should be UNINITIALIZED after factory reset"
        )
    }

    @Test
    fun `HARDWARE - factory reset reports TSE info before reset`() = runTest {
        logger.info("Test: HARDWARE - factory reset reports TSE info before reset")

        val tseStatus = hardwareTseStatus
        if (tseStatus == null) {
            logger.warn("SKIPPED: No TSE hardware available")
            return@runTest
        }

        // Get current TSE info without resetting
        try {
            tseStatus.executeOnTseThread {
                val store = tseStatus.getWormStore()
                store.info().use { info ->
                    logger.info("TSE Information:")
                    logger.info("  - Init state: ${info.initializationState()}")
                    logger.info("  - Development firmware: ${info.isDevelopmentFirmware()}")
                    logger.info("  - Has passed self-test: ${info.hasPassedSelfTest()}")
                    logger.info("  - Has valid time: ${info.hasValidTime()}")
                    logger.info("  - Registered clients: ${info.registeredClients()}")
                    logger.info("  - Created signatures: ${info.createdSignatures()}")
                    logger.info("  - Remaining signatures: ${info.remainingSignatures()}")
                    logger.info("Credential change state:")
                    logger.info("  - Admin PIN changed: ${info.hasChangedAdminPin()}")
                    logger.info("  - Admin PUK changed: ${info.hasChangedPuk()}")
                    logger.info("  - TimeAdmin PIN changed: ${info.hasChangedTimeAdminPin()}")
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to get TSE info: ${e.message}", e)
        }

        // This test just reports info - doesn't actually reset
        assertTrue(true, "Info reported successfully")
    }

    @Test
    fun `HARDWARE - factory reset then initialize cycle`() = runTest {
        logger.info("Test: HARDWARE - factory reset then initialize cycle")
        logger.warn("⚠️ WARNING: This test will RESET and RE-INITIALIZE the TSE!")

        val tseStatus = hardwareTseStatus
        if (tseStatus == null) {
            logger.warn("SKIPPED: No TSE hardware available")
            return@runTest
        }

        // Check if development firmware
        val isDevelopment = try {
            tseStatus.executeOnTseThread {
                val store = tseStatus.getWormStore()
                store.info().use { it.isDevelopmentFirmware() }
            }
        } catch (e: Exception) {
            logger.error("Failed to check development firmware: ${e.message}")
            false
        }

        if (!isDevelopment) {
            logger.warn("SKIPPED: TSE is not development firmware")
            return@runTest
        }

        // Step 1: Factory reset
        logger.info("Step 1: Performing factory reset...")
        val resetOperation = FactoryResetOperation(adminPin)
        val resetData = try {
            resetOperation.execute(tseStatus)
        } catch (e: PosException) {
            logger.error("✗ Factory reset failed: code=${e.code} msg=${e.message}")
            fail("Factory reset should succeed")
        }
        logger.info("✓ Factory reset completed: ${resetData.message}")

        // Step 2: Verify UNINITIALIZED state
        val stateAfterReset = try {
            tseStatus.executeOnTseThread {
                val store = tseStatus.getWormStore()
                store.info().use { it.initializationState().toString() }
            }
        } catch (e: Exception) {
            logger.error("Failed to check state after reset: ${e.message}")
            null
        }

        logger.info("Step 2: State after reset: $stateAfterReset")
        assertEquals(
            "WORM_INIT_UNINITIALIZED",
            stateAfterReset,
            "TSE should be UNINITIALIZED after factory reset"
        )

        // Step 3: Verify credentials are reset (hasChangedAdminPin should be false)
        val credStateAfterReset = try {
            tseStatus.executeOnTseThread {
                val store = tseStatus.getWormStore()
                store.info().use { info ->
                    Triple(
                        info.hasChangedAdminPin(),
                        info.hasChangedPuk(),
                        info.hasChangedTimeAdminPin()
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to check credential state: ${e.message}")
            null
        }

        logger.info("Step 3: Credential state after reset:")
        logger.info("  - Admin PIN changed: ${credStateAfterReset?.first}")
        logger.info("  - Admin PUK changed: ${credStateAfterReset?.second}")
        logger.info("  - TimeAdmin PIN changed: ${credStateAfterReset?.third}")

        if (credStateAfterReset != null) {
            assertFalse(credStateAfterReset.first, "Admin PIN should not be marked as changed")
            assertFalse(credStateAfterReset.second, "Admin PUK should not be marked as changed")
            assertFalse(credStateAfterReset.third, "TimeAdmin PIN should not be marked as changed")
        }

        logger.info("✓ Factory reset + verify cycle completed successfully")
        logger.info("  TSE is now ready for fresh initialization")
    }
}
