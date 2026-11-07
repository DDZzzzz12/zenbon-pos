package org.example.project.tse.tasks

import com.secureflashcard.wormapi.WormUserId
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.models.TseStatus
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * The production shape: nobody has called `setClientId`.
 *
 * Every other startup test seeds it, which is precisely why this went unnoticed — pos-server
 * never set it, and the config route never populated the field the driver reads it from. On
 * TSE 2.0 the resulting null reached `tse_runSelfTest(String)`, whose SWIG wrapper rejects a
 * null jstring before the SDK sees it. That is a NullPointerException, not a WormException,
 * so the `catch (WormException)` around the call did not catch it and the server failed to
 * boot.
 *
 * TSE 2.0 ignores the client entirely and ships a no-arg overload for the purpose; TSE 1.x
 * genuinely needs a registered client and must say so in words an operator can act on.
 */
class StartupWithNoClientIdTest {

    private val tseStatus = TseStatus()

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    /** Attaches and commissions a device, deliberately never calling setClientId. */
    private fun commissionWithoutSettingClientId(generation: String) {
        VirtualTseTestSupport.attachDevice(generation)
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setTimeAdminPin("98765")
        assertTrue(tseStatus.detectHardware())
        tseStatus.getWormStore().tse_setup_ext(
            "SwissbitSwissbit".toByteArray(), "888888".toByteArray(),
            "54321".toByteArray(), "98765".toByteArray(), "TER-001",
        )
        runCatching { tseStatus.getWormStore().user_logout(WormUserId.WORM_USER_ADMIN) }
    }

    @Test
    fun `TSE 2_0 starts up with no client id configured`() {
        commissionWithoutSettingClientId("v2")

        assertTrue(
            TseInitializationManager(tseStatus).initialize(clientId = null),
            "startup must not depend on a client ID that nothing populates on TSE 2.0",
        )
    }

    @Test
    fun `the self test runs on TSE 2_0 without a client id`() {
        commissionWithoutSettingClientId("v2")

        // Directly, not via startup: this is the call that used to throw.
        tseStatus.runSelfTestOnDevice(clientId = null)

        tseStatus.getWormStore().info().use {
            assertTrue(it.hasPassedSelfTest(), "the self test must actually have run")
        }
    }

    @Test
    fun `TSE 1_x says what is missing instead of throwing an NPE`() {
        commissionWithoutSettingClientId("v1")

        val failure = assertFailsWith<IllegalStateException> {
            tseStatus.runSelfTestOnDevice(clientId = null)
        }
        assertTrue(
            failure.message!!.contains("client ID", ignoreCase = true),
            "the message must name what is missing: '${failure.message}'",
        )
    }

    @Test
    fun `a configured client id is still used`() {
        commissionWithoutSettingClientId("v1")
        tseStatus.setClientId("TER-001")

        // TSE 1.x requires the registered client, so this proves the id is really passed
        // through rather than dropped in favour of the no-arg overload.
        tseStatus.runSelfTestOnDevice()

        tseStatus.getWormStore().info().use {
            assertTrue(it.hasPassedSelfTest())
        }
    }
}
