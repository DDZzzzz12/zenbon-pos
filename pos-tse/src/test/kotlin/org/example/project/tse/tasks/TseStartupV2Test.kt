package org.example.project.tse.tasks

import com.secureflashcard.wormapi.WormExportTarCallback
import com.secureflashcard.wormapi.WormUserId
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.models.TseStatus
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * Startup on an already-initialized device must not need an Admin credential, and must
 * not leave TimeAdmin as the resting user. This is the path that runs on every boot.
 */
class TseStartupV2Test {

    private val tseStatus = TseStatus()

    private fun discardingCallback() = object : WormExportTarCallback() {
        override fun onNewData(data: ByteArray): Int = 0
    }

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    private fun connectAndSetUp(generation: String) {
        VirtualTseTestSupport.attachDevice(generation)
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setClientId("POS-TERMINAL-1")
        tseStatus.setTimeAdminPin("98765")
        tseStatus.setCredentialSeed("SwissbitSwissbit")
        assertTrue(tseStatus.detectHardware())
        TseInitializationManager(tseStatus)
            .setUp(
                adminPin = "54321", adminPuk = "888888",
                credentialSeed = "SwissbitSwissbit", clientId = "POS-TERMINAL-1",
            )
    }

    @Test
    fun `startup on an initialized TSE 2_0 leaves nobody logged in`() {
        connectAndSetUp("v2")

        val ok = TseInitializationManager(tseStatus)
            .initialize(clientId = "POS-TERMINAL-1")

        assertTrue(ok, "startup on an already-initialized device should succeed")
        tseStatus.getWormStore().info().use { info ->
            assertEquals(
                WormUserId.WORM_USER_UNAUTHENTICATED,
                info.loggedInUser(),
                "startup must log TimeAdmin out again",
            )
            assertTrue(info.hasValidTime())
        }
    }

    @Test
    fun `after startup the device can still export`() {
        connectAndSetUp("v2")

        TseInitializationManager(tseStatus)
            .initialize(clientId = "POS-TERMINAL-1")

        // Would fail with NOT_AUTHORIZED if TimeAdmin were left logged in.
        tseStatus.getWormStore().export_tar(discardingCallback())
    }

    @Test
    fun `startup on an initialized TSE 1_x still works`() {
        connectAndSetUp("v1")

        val ok = TseInitializationManager(tseStatus)
            .initialize(clientId = "POS-TERMINAL-1")

        assertTrue(ok)
    }
}
