package org.example.project.tse.tasks

import com.secureflashcard.wormapi.WormUserId
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.models.TseStatus
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * Restart on an already-commissioned TSE 1.x must not need the Admin PIN — otherwise
 * removing the stored credential would stop every existing installation from starting
 * unattended, which is the entire installed base. TSE 2.0 already runs startupV2(), which
 * needs no Admin.
 */
class TseStartupV1NoAdminTest {

    private val tseStatus = TseStatus()

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    @Test
    fun `restart on a commissioned TSE 1_x succeeds with a wrong admin pin`() {
        VirtualTseTestSupport.attachDevice("v1")
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setClientId("TER-001")
        tseStatus.setTimeAdminPin("98765")
        tseStatus.setCredentialSeed("SwissbitSwissbit")
        assertTrue(tseStatus.detectHardware())

        // Commission it, then simulate a restart: nobody logged in.
        tseStatus.getWormStore().tse_setup_ext(
            "SwissbitSwissbit".toByteArray(), "888888".toByteArray(),
            "54321".toByteArray(), "98765".toByteArray(), "TER-001",
        )
        runCatching { tseStatus.getWormStore().user_logout(WormUserId.WORM_USER_ADMIN) }

        // No Admin credential is even available to pass any more.
        val ok = TseInitializationManager(tseStatus).initialize(clientId = "TER-001")

        assertTrue(ok, "TSE 1.x startup must not require the Admin PIN")
        tseStatus.getWormStore().info().use {
            assertTrue(it.hasValidTime(), "the time must be set at startup")
        }
    }
}
