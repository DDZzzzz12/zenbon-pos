package org.example.project.tse.virtual

import com.secureflashcard.wormapi.WormStore
import com.secureflashcard.wormapi.WormUserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * On TSE 2.0 the SDK sets a fixed Logger PIN during setup and logs Logger in
 * automatically for transactions. The ERS never learns that PIN — which is what lets
 * the production design run unattended with no stored Admin credential.
 */
class VirtualTseLoggerUserTest {

    private val adminPuk = "888888".toByteArray()
    private val adminPin = "54321".toByteArray()
    private val timeAdminPin = "98765".toByteArray()
    private val seed = "SwissbitSwissbit".toByteArray()
    private val clientId = "POS-TERMINAL-1"

    /**
     * Brings the device to a transaction-ready state on either generation.
     *
     * `tse_setup_ext` sets the time only on v2; on v1 the ERS must still do it, which
     * is the certified v1 order (AGD Figure 4 puts updateTime after initializing).
     * Admin is logged in after setup on both, and Admin may update the time.
     */
    private fun setUpDevice(store: WormStore) {
        store.tse_setup_ext(seed, adminPuk, adminPin, timeAdminPin, clientId)
        store.tse_updateTime(System.currentTimeMillis() / 1000)
    }

    @AfterEach
    fun tearDown() = VirtualTseTestSupport.detachDevice()

    @Test
    fun `transaction succeeds with no user logged in`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            setUpDevice(store)
            store.user_logout(WormUserId.WORM_USER_ADMIN)

            val response = store.transaction_start(clientId, "data".toByteArray(), "Kassenbeleg-V1")

            assertTrue(response.transactionNumber() > 0)
        }
    }

    @Test
    fun `transaction logs Logger in, evicting Admin`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            setUpDevice(store)
            store.user_login(WormUserId.WORM_USER_ADMIN, adminPin)

            store.transaction_start(clientId, "data".toByteArray(), "Kassenbeleg-V1")

            store.info().use { info ->
                assertEquals(
                    WormUserId.WORM_USER_LOGGER,
                    info.loggedInUser(),
                    "the automatic Logger login should have evicted Admin",
                )
            }
        }
    }

    @Test
    fun `v1 does not use the Logger user for transactions`() {
        VirtualTseTestSupport.attachDevice("v1")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            setUpDevice(store)
            store.user_login(WormUserId.WORM_USER_ADMIN, adminPin)

            store.transaction_start(clientId, "data".toByteArray(), "Kassenbeleg-V1")

            // Asserted behaviourally, not via loggedInUser(): real TSE 1.x always reports
            // WORM_USER_UNAUTHENTICATED regardless of the true session, so reading that field
            // here would prove nothing about hardware. Instead exercise a command that needs
            // Admin — it still works, so no Logger login evicted the Admin session.
            store.tse_ctss_enable()
        }
    }
}
