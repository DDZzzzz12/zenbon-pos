package org.example.project.tse.virtual

import com.secureflashcard.wormapi.WormError
import com.secureflashcard.wormapi.WormException
import com.secureflashcard.wormapi.WormExportTarCallback
import com.secureflashcard.wormapi.WormStore
import com.secureflashcard.wormapi.WormUserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.AfterEach

/**
 * The v2 rules that decide which credential each operation needs. These are what make
 * an unattended, Admin-PIN-free production design possible — and what make leaving
 * TimeAdmin logged in fatal.
 */
class VirtualTseV2PreconditionsTest {

    private val adminPuk = "888888".toByteArray()
    private val adminPin = "54321".toByteArray()
    private val timeAdminPin = "98765".toByteArray()
    private val seed = "SwissbitSwissbit".toByteArray()
    private val clientId = "POS-TERMINAL-1"

    private fun setUp(store: WormStore) {
        store.tse_setup_ext(seed, adminPuk, adminPin, timeAdminPin, clientId)
        store.tse_updateTime(System.currentTimeMillis() / 1000)
    }

    /**
     * `WormExportTarCallback` is a SWIG director *class*, not a functional interface, so
     * it has to be subclassed rather than passed as a lambda — the same shape production
     * code uses in FullExportOperation.
     */
    private fun discardingCallback() = object : WormExportTarCallback() {
        override fun onNewData(data: ByteArray): Int = 0
    }

    @AfterEach
    fun tearDown() = VirtualTseTestSupport.detachDevice()

    @Test
    fun `export succeeds with no user logged in`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            setUp(store)
            store.user_logout(WormUserId.WORM_USER_ADMIN)

            // No Admin credential needed -- this is what lets backups run unattended.
            store.export_tar(discardingCallback())
        }
    }

    @Test
    fun `export is rejected while TimeAdmin is logged in`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            setUp(store)
            store.user_login(WormUserId.WORM_USER_TIME_ADMIN, timeAdminPin)

            val failure = assertFailsWith<WormException> { store.export_tar(discardingCallback()) }
            assertEquals(WormError.WORM_ERROR_NOT_AUTHORIZED, failure.error())
        }
    }

    @Test
    fun `deleting stored data requires Admin`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            setUp(store)
            store.export_tar(discardingCallback())
            store.user_logout(WormUserId.WORM_USER_ADMIN)

            val failure = assertFailsWith<WormException> { store.export_deleteStoredData() }
            assertEquals(WormError.WORM_ERROR_NOT_AUTHORIZED, failure.error())
        }
    }

    @Test
    fun `deleting stored data succeeds as Admin`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            setUp(store)
            store.export_tar(discardingCallback())
            store.user_login(WormUserId.WORM_USER_ADMIN, adminPin)

            store.export_deleteStoredData()
        }
    }

    @Test
    fun `filtered exports always fail on v2`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            setUp(store)
            // Produce an entry first: otherwise these would throw EXPORT_NO_DATA_AVAILABLE
            // and the test would pass without the v2 rule existing at all.
            store.transaction_start(clientId, "data".toByteArray(), "Kassenbeleg-V1")

            val byTime = assertFailsWith<WormException> {
                store.export_tar_filtered_time(0, Long.MAX_VALUE, clientId, discardingCallback())
            }
            assertEquals(WormError.WORM_ERROR_CMD_NOT_SUPPORTED, byTime.error())

            val byTransaction = assertFailsWith<WormException> {
                store.export_tar_filtered_transaction(0, Long.MAX_VALUE, clientId, discardingCallback())
            }
            assertEquals(WormError.WORM_ERROR_CMD_NOT_SUPPORTED, byTransaction.error())
        }
    }

    @Test
    fun `filtered exports still work on v1`() {
        VirtualTseTestSupport.attachDevice("v1")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            setUp(store)
            store.transaction_start(clientId, "data".toByteArray(), "Kassenbeleg-V1")

            store.export_tar_filtered_time(0, Long.MAX_VALUE, clientId, discardingCallback())
        }
    }

    @Test
    fun `registering a client on an uninitialized v2 device fails`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            // No login first: registration checks the initialization state before the user,
            // and on real hardware a fresh device forbids login until the PUK is changed
            // anyway. The point is the init precondition.
            val failure = assertFailsWith<WormException> { store.tse_registerClient(clientId) }
            assertEquals(WormError.WORM_ERROR_TSE_NOT_INITIALIZED, failure.error())
        }
    }

    @Test
    fun `client ids containing forbidden characters are rejected on v2`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            assertFailsWith<WormException> {
                store.tse_setup_ext(seed, adminPuk, adminPin, timeAdminPin, "POS/TERMINAL:1?")
            }
            Unit
        }
    }

    @Test
    fun `ctss toggles are no-ops on v2`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            setUp(store)

            // No-ops rather than errors, matching the hardware.
            store.tse_ctss_enable()
            store.tse_ctss_disable()
            store.tse_enableExportIfCspTestFails()
            store.tse_disableExportIfCspTestFails()
        }
    }
}
