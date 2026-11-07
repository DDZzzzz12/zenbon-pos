package org.example.project.tse.mocks

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.models.TseStatus
import org.example.project.tse.utils.TseLocator
import org.slf4j.LoggerFactory

/**
 * Factory object for creating test configurations for TSE testing.
 *
 * Since WormAPI classes cannot be easily mocked (final classes, JNI dependencies),
 * this factory focuses on creating properly configured TseStatus instances
 * for SIMULATION mode testing and safe NORMAL mode testing.
 *
 * IMPORTANT: When testing with real hardware:
 * - NEVER call initialize/setup/login more than once per test
 * - Always log errors for debugging
 * - Use SIMULATION mode for unit tests without hardware
 *
 * @since 1.0.0
 */
object MockWormStoreFactory {

    private val logger = LoggerFactory.getLogger(MockWormStoreFactory::class.java)

    /**
     * Default test values for TSE configuration.
     *
     * These match the default development TSE values (Swissbit sample device).
     */
    object Defaults {
        const val MOUNT_POINT = "/media/tse"
        const val CLIENT_ID = "TEST-TSE-CLIENT"
        const val ADMIN_PIN = "12345"
        const val ADMIN_PUK = "123456"
        const val TIME_ADMIN_PIN = "12345"
        const val CREDENTIAL_SEED = "SwissbitSwissbit"
    }

    /**
     * Create a TseStatus configured for SIMULATION mode testing.
     *
     * This creates a real TseStatus instance configured for SIMULATION mode,
     * which allows testing of signing operations without actual TSE hardware.
     *
     * Safe to use for unit tests - no hardware interaction.
     *
     * @param mode Operating mode (default: SIMULATION)
     * @param mountPoint TSE mount point path
     * @param clientId Client identifier for TSE operations
     * @param timeAdminPin Time Admin PIN for time operations
     * @param credentialSeed Credential seed for TSE initialization
     * @return Configured TseStatus instance
     */
    fun createTseStatusForTesting(
        mode: TseMode = TseMode.SIMULATION,
        mountPoint: String = Defaults.MOUNT_POINT,
        clientId: String = Defaults.CLIENT_ID,
        timeAdminPin: String = Defaults.TIME_ADMIN_PIN,
        credentialSeed: String = Defaults.CREDENTIAL_SEED
    ): TseStatus {
        logger.info("Creating TseStatus for testing:")
        logger.info("  - Mode: $mode")
        logger.info("  - Mount Point: $mountPoint")
        logger.info("  - Client ID: $clientId")
        logger.info("  - Credentials: configured")

        val tseStatus = TseStatus()
        tseStatus.setMode(mode)
        tseStatus.setMountPoint(mountPoint)
        tseStatus.setClientId(clientId)
        tseStatus.setTimeAdminPin(timeAdminPin)
        tseStatus.setCredentialSeed(credentialSeed)

        logger.info("TseStatus created successfully (mode: $mode, hardware not connected)")
        return tseStatus
    }

    /**
     * Create a TseStatus configured for NORMAL mode with hardware detection.
     *
     * CAUTION: This method connects to real TSE hardware!
     * - Returns null if no TSE hardware found (does not throw)
     * - Automatically detects hardware and establishes connection
     * - Use sparingly to avoid hardware wear
     *
     * @param clientId Client identifier for TSE operations
     * @param timeAdminPin Time Admin PIN for time operations
     * @param credentialSeed Credential seed for TSE initialization
     * @return Configured TseStatus with hardware connection, or null if no hardware found
     */
    fun createTseStatusForNormalMode(
        clientId: String = Defaults.CLIENT_ID,
        timeAdminPin: String = Defaults.TIME_ADMIN_PIN,
        credentialSeed: String = Defaults.CREDENTIAL_SEED
    ): TseStatus? {
        logger.info("Creating TseStatus for NORMAL mode testing...")
        logger.info("  - Client ID: $clientId")
        logger.info("  - Credentials: configured")

        // Find and validate TSE hardware
        val mountPoint = try {
            TseLocator.findAndValidateTse()
        } catch (e: Exception) {
            logger.error("Error during TSE detection: ${e.message}", e)
            null
        }

        if (mountPoint == null) {
            logger.warn("No TSE hardware found - returning null")
            logger.warn("  Searched locations: /mnt/*, /mount/*, /media/*/*")
            return null
        }

        logger.info("TSE hardware found at: $mountPoint")

        // Create TseStatus with all parameters
        val tseStatus = TseStatus()
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setMountPoint(mountPoint)
        tseStatus.setClientId(clientId)
        tseStatus.setTimeAdminPin(timeAdminPin)
        tseStatus.setCredentialSeed(credentialSeed)

        // Detect hardware to establish connection
        val connected = runBlocking {
            try {
                tseStatus.detectHardware()
            } catch (e: Exception) {
                logger.error("Failed to connect to TSE hardware: ${e.message}", e)
                false
            }
        }

        if (!connected) {
            logger.error("Failed to establish connection to TSE at: $mountPoint")
            return null
        }

        logger.info("TseStatus created and connected to hardware at: $mountPoint")
        logger.info("  - Connection state: ${tseStatus.isConnected()}")
        logger.info("  - Initialization state: ${tseStatus.getInitializationState()}")

        return tseStatus
    }

    /**
     * Create a TseStatus for NORMAL mode WITHOUT detecting hardware.
     *
     * Use this when you want to test behavior before hardware detection,
     * or when you need to control when detectHardware() is called.
     *
     * @param mountPoint TSE mount point path (must be valid for later detection)
     * @param clientId Client identifier for TSE operations
     * @param timeAdminPin Time Admin PIN for time operations
     * @param credentialSeed Credential seed for TSE initialization
     * @return Configured TseStatus without hardware connection
     */
    fun createTseStatusForNormalModeWithoutDetection(
        mountPoint: String = Defaults.MOUNT_POINT,
        clientId: String = Defaults.CLIENT_ID,
        timeAdminPin: String = Defaults.TIME_ADMIN_PIN,
        credentialSeed: String = Defaults.CREDENTIAL_SEED
    ): TseStatus {
        logger.info("Creating TseStatus for NORMAL mode (without hardware detection):")
        logger.info("  - Mount Point: $mountPoint")
        logger.info("  - Client ID: $clientId")

        val tseStatus = TseStatus()
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setMountPoint(mountPoint)
        tseStatus.setClientId(clientId)
        tseStatus.setTimeAdminPin(timeAdminPin)
        tseStatus.setCredentialSeed(credentialSeed)

        logger.info("TseStatus created (NORMAL mode, hardware NOT connected yet)")
        return tseStatus
    }
}
