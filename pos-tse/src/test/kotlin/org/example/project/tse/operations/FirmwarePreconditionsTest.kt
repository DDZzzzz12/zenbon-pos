package org.example.project.tse.operations

import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.firmware.FirmwareApplyBundledOperation
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * A firmware update on TSE 2.0 requires an initialized device, a set time, a valid
 * certificate and no unfinished transactions. Checking them first gives an operator
 * something they can act on; WORM_ERROR_TSE_HAS_UNFINISHED_TRANSACTIONS does not.
 *
 * The checks also run before the Admin login, deliberately. There is no sense spending one
 * of the three attempts that stand between the device and a PIN lockout on an operation
 * that cannot proceed anyway.
 */
class FirmwarePreconditionsTest {

    private val tseStatus = TseStatus()

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
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

    private fun setUpAndOpenATransaction() {
        val store = tseStatus.getWormStore()
        store.tse_setup_ext(
            "SwissbitSwissbit".toByteArray(), "888888".toByteArray(),
            "54321".toByteArray(), "98765".toByteArray(), "POS-TERMINAL-1",
        )
        store.tse_updateTime(System.currentTimeMillis() / 1000)
        store.transaction_start("POS-TERMINAL-1", "data".toByteArray(), "Kassenbeleg-V1")
    }

    @Test
    fun `refuses on an uninitialized TSE 2_0 with an actionable message`() {
        connect("v2")

        val failure = assertFailsWith<PosException> {
            FirmwareApplyBundledOperation(adminPin = "12345").execute(tseStatus)
        }
        assertTrue(
            failure.message!!.contains("initial", ignoreCase = true),
            "message should name the unmet precondition: '${failure.message}'",
        )
    }

    @Test
    fun `refuses while a transaction is open`() {
        connect("v2")
        setUpAndOpenATransaction()

        val failure = assertFailsWith<PosException> {
            FirmwareApplyBundledOperation(adminPin = "54321").execute(tseStatus)
        }
        assertTrue(
            failure.message!!.contains("transaction", ignoreCase = true),
            "message should tell the operator to finish open transactions: '${failure.message}'",
        )
        assertTrue(
            failure.message!!.contains("1"),
            "naming how many are open saves the operator hunting: '${failure.message}'",
        )
    }

    @Test
    fun `the same checks apply on TSE 1_x`() {
        connect("v1")

        // The preconditions are stricter on 2.0 but none of them are wrong on 1.x, and an
        // operator gets the same actionable message either way.
        val failure = assertFailsWith<PosException> {
            FirmwareApplyBundledOperation(adminPin = "12345").execute(tseStatus)
        }
        assertTrue(failure.message!!.contains("initial", ignoreCase = true))
    }
}
