package org.example.project.tse.operations

import com.secureflashcard.wormapi.WormStore
import org.example.project.tse.mocks.MockWormStoreFactory
import org.example.project.tse.utils.TseLocator
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Test that verifies TSE transaction signing does NOT require user login.
 *
 * VERIFIED RESULT (2025-01-30):
 * Transaction signing (transaction_start, transaction_finish) works WITHOUT
 * logging in as Admin or TimeAdmin. The TSE only requires that:
 * - The TSE is INITIALIZED
 * - The client ID is registered
 * - The CTSS interface is enabled
 *
 * This means the TseSignOperation implementation can skip the user_login() call
 * for better performance.
 *
 * Requirements to run this test:
 * - TSE hardware must be connected and INITIALIZED
 * - Set TSE_MOUNT_POINT environment variable (e.g., /media/tse)
 *
 * Run with:
 * TSE_MOUNT_POINT=/media/tse ./gradlew :server-tse:test --tests "*.TseSignLoginRequirementTest"
 */
@EnabledIfEnvironmentVariable(named = "TSE_MOUNT_POINT", matches = ".+")
class TseSignLoginRequirementTest {

    private val logger = LoggerFactory.getLogger(TseSignLoginRequirementTest::class.java)

    private lateinit var mountPoint: String
    private lateinit var clientId: String

    @BeforeEach
    fun setup() {
        mountPoint = TseLocator.findAndValidateTse() ?: MockWormStoreFactory.Defaults.MOUNT_POINT
        clientId = MockWormStoreFactory.Defaults.CLIENT_ID

        logger.info("═══════════════════════════════════════════════════════════════════════════")
        logger.info("TSE Sign Login Requirement Test")
        logger.info("═══════════════════════════════════════════════════════════════════════════")
        logger.info("Mount Point: $mountPoint")
        logger.info("Client ID: $clientId")
    }

    /**
     * VERIFIED: Transaction signing works WITHOUT admin login.
     *
     * This test proves that an INITIALIZED TSE allows transaction signing
     * without an active user login session. The login is NOT required
     * for transaction_start() and transaction_finish() operations.
     */
    @Test
    fun `VERIFIED - signing works WITHOUT admin login`() {
        logger.info("─".repeat(80))
        logger.info("TEST: Signing WITHOUT Login (VERIFIED TO WORK)")
        logger.info("─".repeat(80))

        WormStore(mountPoint).use { store ->
            // Check TSE state first
            store.info().use { info ->
                logger.info("TSE State:")
                logger.info("  - Initialization: ${info.initializationState()}")
                logger.info("  - Has Valid Time: ${info.hasValidTime()}")
                logger.info("  - Passed Self Test: ${info.hasPassedSelfTest()}")
            }

            // Sign WITHOUT logging in - this works!
            logger.info("Starting transaction WITHOUT login...")

            val startResp = store.transaction_start(
                clientId,
                ByteArray(0),
                "Kassenbeleg-V1"
            )

            val txNumber = startResp.transactionNumber()
            logger.info("✓ transaction_start SUCCEEDED without login!")
            logger.info("  - Transaction Number: $txNumber")
            logger.info("  - Log Time: ${startResp.logTime()}")

            // Finish the transaction - also works without login
            logger.info("Finishing transaction WITHOUT login...")
            val finishResp = store.transaction_finish(
                clientId,
                txNumber,
                "test-data-no-login".toByteArray(Charsets.UTF_8),
                "Kassenbeleg-V1"
            )

            logger.info("✓ transaction_finish SUCCEEDED without login!")
            logger.info("  - Transaction Number: ${finishResp.transactionNumber()}")
            logger.info("  - Signature Counter: ${finishResp.signatureCounter()}")
            logger.info("  - Signature: ${Base64.getEncoder().encodeToString(finishResp.signature())}")

            logger.info("═══════════════════════════════════════════════════════════════════════════")
            logger.info("CONCLUSION: Admin login is NOT required for transaction signing!")
            logger.info("═══════════════════════════════════════════════════════════════════════════")
        }
    }
}
