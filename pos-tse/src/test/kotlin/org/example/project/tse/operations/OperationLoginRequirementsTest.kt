package org.example.project.tse.operations

import com.secureflashcard.wormapi.WormUserId
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.export.FullExportOperation
import org.example.project.tse.operations.admin.firmware.FirmwareUpdateCheckOperation
import org.example.project.tse.operations.admin.selftest.SelfTestOperation
import org.example.project.tse.operations.admin.time.UpdateTimeOperation
import org.example.project.tse.operations.admin.users.ChangePinOperation
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * Each operation must require exactly what the SDK requires — no more, no less.
 *
 * Too much (a gratuitous Admin login) forces an operator to unlock TSE administration for
 * something the device permits unauthenticated, and blocks the unattended scheduled export
 * the design calls for. Too little works today only because the TSE 1.x startup sequence
 * leaves Admin logged in and v1 has no idle logout — cover that stops existing the moment
 * startup switches to TimeAdmin.
 *
 * Every test here passes a deliberately WRONG Admin PIN. That is the assertion: the operation
 * must not depend on it.
 */
class OperationLoginRequirementsTest {

    private val tseStatus = TseStatus()

    private val wrongAdminPin = "00000"

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    /** Commissions a device and leaves NOBODY logged in — the resting state after startup. */
    private fun commissionedDevice(generation: String) {
        VirtualTseTestSupport.attachDevice(generation)
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setClientId("TER-001")
        tseStatus.setTimeAdminPin("98765")
        tseStatus.setCredentialSeed("SwissbitSwissbit")
        assertTrue(tseStatus.detectHardware())
        tseStatus.getWormStore().tse_setup_ext(
            "SwissbitSwissbit".toByteArray(), "888888".toByteArray(),
            "54321".toByteArray(), "98765".toByteArray(), "TER-001",
        )
        tseStatus.getWormStore().tse_updateTime(System.currentTimeMillis() / 1000)
        runCatching { tseStatus.getWormStore().user_logout(WormUserId.WORM_USER_ADMIN) }
    }

    // --- self test: needs no login on v1, only excludes TimeAdmin on v2 ------------------

    @Test
    fun `self test needs no Admin PIN on TSE 2_0`() {
        commissionedDevice("v2")
        val result = SelfTestOperation(adminPin = wrongAdminPin, clientId = "TER-001").execute(tseStatus)
        assertTrue(result.success, "self test must not depend on the Admin PIN")
    }

    @Test
    fun `self test needs no Admin PIN on TSE 1_x`() {
        commissionedDevice("v1")
        val result = SelfTestOperation(adminPin = wrongAdminPin, clientId = "TER-001").execute(tseStatus)
        assertTrue(result.success, "self test must not depend on the Admin PIN")
    }

    // --- time sync: accepts Admin OR TimeAdmin; TimeAdmin is the storable one ------------

    @Test
    fun `time sync runs on the TimeAdmin PIN, not Admin`() {
        commissionedDevice("v2")
        val result = UpdateTimeOperation(adminPin = wrongAdminPin).execute(tseStatus)
        assertTrue(result.success, "time sync must run on the TimeAdmin PIN")
    }

    @Test
    fun `time sync does not leave TimeAdmin logged in`() {
        commissionedDevice("v2")
        UpdateTimeOperation(adminPin = wrongAdminPin).execute(tseStatus)
        tseStatus.getWormStore().info().use {
            assertEquals(
                WormUserId.WORM_USER_UNAUTHENTICATED, it.loggedInUser(),
                "a resting TimeAdmin blocks every export on TSE 2.0",
            )
        }
    }

    // --- export: export_tar only excludes TimeAdmin; Admin is NOT required ---------------

    @Test
    fun `export needs no Admin PIN on TSE 2_0`() {
        commissionedDevice("v2")
        // This is what makes an unattended scheduled export possible.
        FullExportOperation(adminPin = wrongAdminPin).execute(tseStatus)
    }

    @Test
    fun `export needs no Admin PIN on TSE 1_x`() {
        commissionedDevice("v1")
        FullExportOperation(adminPin = wrongAdminPin).execute(tseStatus)
    }

    // --- firmware CHECK states no auth requirement (apply does, and keeps its login) -----

    @Test
    fun `firmware update check needs no Admin PIN`() {
        commissionedDevice("v2")
        FirmwareUpdateCheckOperation(adminPin = wrongAdminPin).execute(tseStatus)
    }

    // --- change PIN: the SDK needs the user WHOSE PIN CHANGES logged in, not Admin -------

    @Test
    fun `changing the TimeAdmin PIN works with nobody logged in`() {
        commissionedDevice("v2")

        // user_change_pin requires that user to be logged in first. With nobody logged in,
        // this only works if the operation performs the login itself.
        val result = ChangePinOperation(
            adminPin = wrongAdminPin,
            userId = "TIME_ADMIN",
            currentPin = "98765",
            newPin = "55555",
        ).execute(tseStatus)

        assertTrue(result.success, "the operation must log in the user whose PIN is changing")
    }
}
