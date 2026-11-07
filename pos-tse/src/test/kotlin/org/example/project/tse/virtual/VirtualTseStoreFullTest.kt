package org.example.project.tse.virtual

import com.secureflashcard.wormapi.WormError
import com.secureflashcard.wormapi.WormException
import com.secureflashcard.wormapi.WormStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * TSE 2.0 reports STORE_FULL once free space drops below ~1 MB, not when the device is
 * physically full — so a cash register must export well before capacity is reached.
 */
class VirtualTseStoreFullTest {

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
    fun `v2 rejects transactions once free space falls below the reserve`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            setUp(store)
            val capacity = store.info().use { it.capacity() }

            VirtualTseTestSupport.setSimulatedFreeBytes(store, freeBytes = 512)

            val failure = assertFailsWormException {
                store.transaction_start(clientId, "data".toByteArray(), "Kassenbeleg-V1")
            }
            assertEquals(WormError.WORM_ERROR_STORE_FULL, failure.error())
            assertTrue(
                store.info().use { it.size() } < capacity,
                "device should NOT be physically full — the reserve is what triggers this",
            )
        }
    }

    @Test
    fun `v1 only rejects transactions when genuinely full`() {
        VirtualTseTestSupport.attachDevice("v1")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            setUp(store)

            // 512 bytes free is fine on v1: it has no reserve, unlike v2.
            VirtualTseTestSupport.setSimulatedFreeBytes(store, freeBytes = 512)

            store.transaction_start(clientId, "data".toByteArray(), "Kassenbeleg-V1")
        }
    }

    private inline fun assertFailsWormException(block: () -> Unit): WormException =
        try {
            block()
            throw AssertionError("expected a WormException but none was thrown")
        } catch (e: WormException) {
            e
        }
}
