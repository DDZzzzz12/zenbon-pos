package org.example.project.tse.virtual

import com.secureflashcard.wormapi.WormStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.example.project.tse.utils.Tr03153LogMessageParser
import org.junit.jupiter.api.AfterEach

/**
 * Round-trip: the virtual device now emits real TR-03151 DER log messages, so a transaction
 * signed on it must decode back — through the production [Tr03153LogMessageParser] — to exactly
 * the values the device reported natively in its [com.secureflashcard.wormapi.WormTransactionResponse].
 *
 * This is the fixture §9 of the TSE 2.0 design requires ("round-trip tests against virtual-device
 * entries") and the prerequisite for software-side filtered exports on TSE 2.0 (§7.1): those read
 * `logTime` and `transactionNumber` out of the DER, since `WormEntry` exposes neither.
 */
class VirtualTseLogMessageRoundTripTest {

    private val adminPuk = "888888".toByteArray()
    private val adminPin = "54321".toByteArray()
    private val timeAdminPin = "98765".toByteArray()
    private val seed = "SwissbitSwissbit".toByteArray()
    private val clientId = "POS-TERMINAL-1"

    private fun setUp(store: WormStore) {
        store.tse_setup_ext(seed, adminPuk, adminPin, timeAdminPin, clientId)
        store.tse_updateTime(System.currentTimeMillis() / 1000)
    }

    @AfterEach
    fun tearDown() = VirtualTseTestSupport.detachDevice()

    @Test
    fun `a v1 transaction entry decodes back to its native values (ASN 1 version 2)`() {
        roundTrip(generation = "v1", expectedVersion = 2)
    }

    @Test
    fun `a v2 transaction entry decodes back to its native values (ASN 1 version 3)`() {
        roundTrip(generation = "v2", expectedVersion = 3)
    }

    private fun roundTrip(generation: String, expectedVersion: Int) {
        VirtualTseTestSupport.attachDevice(generation)
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            setUp(store)
            val response = store.transaction_start(clientId, "Beleg^42.00_1".toByteArray(), "Kassenbeleg-V1")

            val parsed = Tr03153LogMessageParser.parse(store.lastEntry().readLogMessage())
            assertNotNull(parsed, "the virtual device must now emit parseable DER")

            assertEquals(expectedVersion, parsed.version, "ASN.1 version follows the generation")
            assertEquals("StartTransaction", parsed.operationType)
            assertEquals(clientId, parsed.clientId)
            assertEquals("Kassenbeleg-V1", parsed.processType)
            // The decoded fields must equal what the device reported natively — that is the round-trip.
            assertEquals(response.transactionNumber(), parsed.transactionNumber)
            assertEquals(response.signatureCounter(), parsed.signatureCounter)
            assertEquals(response.logTime(), parsed.logTime)
        }
    }
}
