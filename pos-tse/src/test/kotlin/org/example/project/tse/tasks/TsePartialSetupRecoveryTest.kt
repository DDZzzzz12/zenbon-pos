package org.example.project.tse.tasks

import com.secureflashcard.wormapi.WormInitializationState
import com.secureflashcard.wormapi.WormStore
import com.secureflashcard.wormapi.WormUserId
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.models.TseStatus
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * A device whose credentials were changed but which was never initialized — e.g. setup
 * lost power midway. `tse_setup_ext` must NOT be called here: it would apply the initial
 * credentials, which no longer work, and repeated PUK failures can brick the TSE.
 */
class TsePartialSetupRecoveryTest {

    private val tseStatus = TseStatus()

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    /** Changes the credentials but stops short of initializing — a partial setup. */
    private fun partiallyConfigure(store: WormStore) {
        store.user_deriveInitialCredentials("SwissbitSwissbit".toByteArray()).use {
            store.user_change_puk(it.adminPuk(), "888888".toByteArray())
            store.user_login(WormUserId.WORM_USER_ADMIN, it.adminPin())
            store.user_change_pin(WormUserId.WORM_USER_ADMIN, it.adminPin(), "54321".toByteArray())
            store.user_login(WormUserId.WORM_USER_TIME_ADMIN, it.timeAdminPin())
            store.user_change_pin(WormUserId.WORM_USER_TIME_ADMIN, it.timeAdminPin(), "98765".toByteArray())
        }
    }

    private fun connect(generation: String) {
        VirtualTseTestSupport.attachDevice(generation)
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setClientId("POS-TERMINAL-1")
        tseStatus.setTimeAdminPin("98765")
        tseStatus.setCredentialSeed("SwissbitSwissbit")
        assertTrue(tseStatus.detectHardware())
    }

    @Test
    fun `recovers a partially configured TSE 2_0 without calling setup_ext`() {
        connect("v2")
        partiallyConfigure(tseStatus.getWormStore())

        val ok = TseInitializationManager(tseStatus)
            .setUp(adminPin = "54321", adminPuk = "888888", credentialSeed = "SwissbitSwissbit", clientId = "POS-TERMINAL-1")

        assertTrue(ok, "recovery should complete the setup with the already-changed credentials")
        assertEquals(WormInitializationState.WORM_INIT_INITIALIZED, tseStatus.getInitializationState())
        tseStatus.getWormStore().info().use { info ->
            assertEquals(1L, info.registeredClients(), "the client must end up registered")
            assertTrue(info.hasValidTime())
        }
    }

    @Test
    fun `a recovered TSE 2_0 can sign`() {
        connect("v2")
        partiallyConfigure(tseStatus.getWormStore())

        TseInitializationManager(tseStatus)
            .setUp(adminPin = "54321", adminPuk = "888888", credentialSeed = "SwissbitSwissbit", clientId = "POS-TERMINAL-1")

        tseStatus.getWormStore()
            .transaction_start("POS-TERMINAL-1", "data".toByteArray(), "Kassenbeleg-V1")
    }
}
