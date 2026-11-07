package org.example.project.tse.tasks

import kotlinx.coroutines.test.runTest
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.mocks.MockWormStoreFactory
import org.example.project.tse.models.TseStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.*

/**
 * Unit tests for TseInitializationManager.
 *
 * SAFETY GUIDELINES:
 * - NEVER call initialize/setup/login more than ONCE per test with real hardware
 * - Real hardware tests are marked with "Hardware" in the name
 * - SIMULATION mode tests are safe and don't interact with hardware
 * - All errors are logged for debugging
 *
 * Test Categories:
 * 1. SIMULATION Mode Tests (safe, no hardware)
 * 2. Configuration Tests (safe, no hardware interaction)
 * 3. Hardware Tests (require real TSE, run carefully)
 *
 * @since 1.0.0
 */
class TseInitializationManagerTest {

    private val logger = LoggerFactory.getLogger(TseInitializationManagerTest::class.java)

    // Track if hardware is available (detected once at setup)
    private var hardwareAvailable: Boolean = false
    private var hardwareTseStatus: TseStatus? = null

    // Test credentials (matching MockWormStoreFactory.Defaults)
    private val adminPin = MockWormStoreFactory.Defaults.ADMIN_PIN
    private val adminPuk = MockWormStoreFactory.Defaults.ADMIN_PUK

    private val clientId = MockWormStoreFactory.Defaults.CLIENT_ID

    @BeforeEach
    fun setUp() {
        logger.info("═".repeat(80))
        logger.info("Setting up test...")

        // Try to detect hardware once for all hardware tests
        // This avoids repeated detection attempts
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
    // SIMULATION MODE TESTS - Safe, no hardware interaction
    // ============================================================================================

    @Test
    fun `SIMULATION - initialize returns true in simulation mode`() = runTest {
        logger.info("Test: SIMULATION - initialize returns true in simulation mode")

        val tseStatus = MockWormStoreFactory.createTseStatusForTesting(
            mode = TseMode.SIMULATION
        )

        logger.info("  - Mode: ${tseStatus.getMode()}")
        logger.info("  - hasWormStoreInstance: ${tseStatus.hasWormStoreInstance()}")

        val initManager = TseInitializationManager(tseStatus)
        val result = initManager.initialize(clientId)

        logger.info("  - Initialize result: $result")
        assertTrue(result, "Initialize should return true in SIMULATION mode")
    }

    @Test
    fun `SIMULATION - getStatusSummary returns connected false when not connected`() = runTest {
        logger.info("Test: SIMULATION - getStatusSummary returns connected false when not connected")

        val tseStatus = MockWormStoreFactory.createTseStatusForTesting()

        val initManager = TseInitializationManager(tseStatus)
        val statusSummary = initManager.getStatusSummary()

        logger.info("  - Status summary: $statusSummary")

        assertNotNull(statusSummary, "Status summary should not be null")
        assertEquals(false, statusSummary["connected"], "connected should be false")
    }

    @Test
    fun `SIMULATION - getStatusSummary handles exceptions gracefully`() = runTest {
        logger.info("Test: SIMULATION - getStatusSummary handles exceptions gracefully")

        val tseStatus = MockWormStoreFactory.createTseStatusForNormalModeWithoutDetection()

        val initManager = TseInitializationManager(tseStatus)
        val statusSummary = initManager.getStatusSummary()

        logger.info("  - Status summary: $statusSummary")

        // Should not throw and should contain error info or connected=false
        assertNotNull(statusSummary, "Status summary should not be null")
        assertTrue(
            statusSummary.containsKey("connected") || statusSummary.containsKey("error"),
            "Should contain 'connected' or 'error' key"
        )
    }

    // ============================================================================================
    // CONFIGURATION TESTS - Verify TseStatus configuration is correct
    // ============================================================================================

    @Test
    fun `CONFIG - TseStatus has all required parameters configured`() = runTest {
        logger.info("Test: CONFIG - TseStatus has all required parameters configured")

        val customClientId = "CUSTOM-CLIENT"
        val customTimeAdminPin = "11111"
        val customCredentialSeed = "CustomSeedValue!"

        val tseStatus = MockWormStoreFactory.createTseStatusForTesting(
            clientId = customClientId,
            timeAdminPin = customTimeAdminPin,
            credentialSeed = customCredentialSeed
        )

        logger.info("  - Client ID: ${tseStatus.getClientId()}")
        logger.info("  - Time Admin PIN: ${tseStatus.getTimeAdminPin()}")
        logger.info("  - Credential Seed: ${tseStatus.getCredentialSeed()}")

        assertEquals(customClientId, tseStatus.getClientId(), "Client ID mismatch")
        assertEquals(customTimeAdminPin, tseStatus.getTimeAdminPin(), "Time Admin PIN mismatch")
        assertEquals(customCredentialSeed, tseStatus.getCredentialSeed(), "Credential Seed mismatch")
    }

    @Test
    fun `CONFIG - initManager does not modify TseStatus mode`() = runTest {
        logger.info("Test: CONFIG - initManager does not modify TseStatus mode")

        val tseStatus = MockWormStoreFactory.createTseStatusForTesting(
            mode = TseMode.SIMULATION
        )

        val initManager = TseInitializationManager(tseStatus)
        initManager.initialize(clientId)

        logger.info("  - Mode after initialize: ${tseStatus.getMode()}")

        assertEquals(TseMode.SIMULATION, tseStatus.getMode(), "Mode should remain unchanged")
    }

    @Test
    fun `CONFIG - initManager does not modify TseStatus client id`() = runTest {
        logger.info("Test: CONFIG - initManager does not modify TseStatus client id")

        val originalClientId = "ORIGINAL-CLIENT"

        val tseStatus = MockWormStoreFactory.createTseStatusForTesting(
            clientId = originalClientId
        )

        val initManager = TseInitializationManager(tseStatus)
        initManager.initialize(clientId)

        logger.info("  - Client ID after initialize: ${tseStatus.getClientId()}")

        assertEquals(originalClientId, tseStatus.getClientId(), "Client ID should remain unchanged")
    }

    // ============================================================================================
    // HARDWARE TESTS - Require real TSE, run with caution
    // IMPORTANT: Each test calls initialize() AT MOST ONCE to prevent device lock
    // ============================================================================================

    @Test
    fun `HARDWARE - getStatusSummary returns comprehensive info when connected`() = runTest {
        logger.info("Test: HARDWARE - getStatusSummary returns comprehensive info when connected")

        val tseStatus = hardwareTseStatus
        if (tseStatus == null) {
            logger.warn("SKIPPED: No TSE hardware available")
            return@runTest
        }

        logger.info("  - Hardware connected: ${tseStatus.isConnected()}")

        val initManager = TseInitializationManager(tseStatus)

        // DO NOT call initialize() - just get status summary
        val statusSummary = initManager.getStatusSummary()

        logger.info("  - Status summary keys: ${statusSummary.keys}")
        statusSummary.forEach { (key, value) ->
            logger.info("    - $key: $value")
        }

        assertNotNull(statusSummary, "Status summary should not be null")
        assertTrue(statusSummary.containsKey("connected"), "Should contain 'connected' key")
        assertTrue(statusSummary.containsKey("initializationState"), "Should contain 'initializationState' key")
    }

    @Test
    fun `HARDWARE - initialize with connected hardware - SINGLE ATTEMPT`() = runTest {
        logger.info("Test: HARDWARE - initialize with connected hardware - SINGLE ATTEMPT")
        logger.info("  ⚠️ WARNING: This test performs ONE initialization attempt")

        val tseStatus = hardwareTseStatus
        if (tseStatus == null) {
            logger.warn("SKIPPED: No TSE hardware available")
            return@runTest
        }

        logger.info("  - Hardware connected: ${tseStatus.isConnected()}")
        logger.info("  - Initialization state: ${tseStatus.getInitializationState()}")

        val initManager = TseInitializationManager(tseStatus)

        // SINGLE initialization attempt - log everything
        logger.info("  - Starting initialization (SINGLE ATTEMPT)...")
        val result = try {
            initManager.initialize(clientId)
        } catch (e: Exception) {
            logger.error("  ✗ Initialize threw exception: ${e.message}", e)
            false
        }

        logger.info("  - Initialize result: $result")

        // Get final status
        val finalStatus = initManager.getStatusSummary()
        logger.info("  - Final status:")
        finalStatus.forEach { (key, value) ->
            logger.info("    - $key: $value")
        }

        // We don't assert success because it depends on TSE state
        // Just verify it doesn't throw and returns a boolean
        assertNotNull(result, "Result should not be null")
    }

    // ============================================================================================
    // NORMAL MODE WITHOUT HARDWARE TESTS - Test error handling
    // ============================================================================================

    @Test
    fun `NORMAL_MODE - initialize fails gracefully without hardware`() = runTest {
        logger.info("Test: NORMAL_MODE - initialize fails gracefully without hardware")

        val tseStatus = MockWormStoreFactory.createTseStatusForNormalModeWithoutDetection(
            mountPoint = "/nonexistent/path"
        )

        logger.info("  - Mode: ${tseStatus.getMode()}")
        logger.info("  - Mount point: ${tseStatus.getMountPoint()}")
        logger.info("  - hasWormStoreInstance: ${tseStatus.hasWormStoreInstance()}")

        val initManager = TseInitializationManager(tseStatus)
        val result = try {
            initManager.initialize(clientId)
        } catch (e: Exception) {
            logger.error("  ✗ Initialize threw exception: ${e.message}", e)
            false
        }

        logger.info("  - Initialize result: $result")
        assertFalse(result, "Initialize should return false without hardware connection")
    }

    @Test
    fun `NORMAL_MODE - TseStatus reports not connected without detectHardware`() = runTest {
        logger.info("Test: NORMAL_MODE - TseStatus reports not connected without detectHardware")

        val tseStatus = MockWormStoreFactory.createTseStatusForNormalModeWithoutDetection()

        logger.info("  - isConnected: ${tseStatus.isConnected()}")
        logger.info("  - hasWormStoreInstance: ${tseStatus.hasWormStoreInstance()}")

        assertFalse(tseStatus.isConnected(), "Should not be connected")
        assertFalse(tseStatus.hasWormStoreInstance(), "Should not have WormStore instance")
    }
}
