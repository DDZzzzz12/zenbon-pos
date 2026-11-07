package org.example.project.tse.operations

import com.secureflashcard.wormapi.WormUserId
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.signing.TseSignOperation
import org.example.project.tse.operations.signing.TseTransactionFinishOperation
import org.example.project.tse.operations.signing.TseTransactionStartOperation
import org.example.project.tse.virtual.VirtualTseTestSupport
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * E2E R2-6, the half of the rule that must KEEP working on the signing path: a genuine hardware
 * loss (a WormError below the SDK's `0x1000` boundary) still drops the connection, and the POS
 * signs again once the stick is back.
 *
 * [SigningKeepsConnectionOnCardAnswerTest] proves the other half. Before the virtual device could
 * fail a transaction call host-side, nothing could tell a classifier that kept every connection
 * apart from a correct one. The fault hook is [VirtualTseTestSupport.armHostFault].
 */
class SigningResetsConnectionOnHardwareLossTest {
    private val tseStatus = TseStatus()

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    /** Attaches, connects and commissions a v2 virtual TSE, so signing works. */
    private fun connectedAndCommissioned() {
        VirtualTseTestSupport.attachDevice("v2")
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setClientId(CLIENT)
        tseStatus.setTimeAdminPin("98765")
        tseStatus.setCredentialSeed("SwissbitSwissbit")
        assertTrue(tseStatus.detectHardware())
        val store = tseStatus.getWormStore()
        store.tse_setup_ext(
            "SwissbitSwissbit".toByteArray(),
            "888888".toByteArray(),
            "54321".toByteArray(),
            "98765".toByteArray(),
            CLIENT,
        )
        store.tse_updateTime(System.currentTimeMillis() / 1000)
        runCatching { store.user_logout(WormUserId.WORM_USER_ADMIN) }
    }

    private fun sign() = TseSignOperation(command = "signbill", value = RECEIPT, clientId = CLIENT).execute(tseStatus)

    private fun assertConnectionReset() {
        assertNull(tseStatus.getMountPoint(), "a lost device must clear the mount point")
        assertFalse(tseStatus.isConnected(), "a lost device must drop the connection")
    }

    @Test
    fun `a hardware loss during signing resets the connection and reports a connection failure`() {
        connectedAndCommissioned()
        VirtualTseTestSupport.armHostFault("WORM_ERROR_NO_WORM_CARD")

        val e = assertFailsWith<PosException> { sign() }

        assertEquals(PosErrorCode.TSE_CONNECTION_FAILED, e.code)
        assertConnectionReset()

        // The next sale must fail fast as "not found", not talk to a store that is gone.
        val next = assertFailsWith<PosException> { sign() }
        assertEquals(PosErrorCode.TSE_HARDWARE_NOT_FOUND, next.code)
    }

    @Test
    fun `signing recovers once the hardware fault clears, without a lost transaction number`() {
        connectedAndCommissioned()
        val before = sign()
        VirtualTseTestSupport.armHostFault("WORM_ERROR_NO_WORM_CARD")
        assertFailsWith<PosException> { sign() }

        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        assertFalse(tseStatus.isConnected(), "a device that is still gone must not be re-acquired")

        VirtualTseTestSupport.clearFault()
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        assertTrue(tseStatus.isConnected(), "the device must be re-acquired once it is back")

        val after = sign()

        assertTrue(after.success)
        assertEquals(
            before.transactionNumber + 1,
            after.transactionNumber,
            "the failed call never reached the card, so it must not have consumed a TANR",
        )
    }

    /**
     * A timeout is host-side but the request may have reached the card (M7), so it carries its
     * own code. The transaction the card already opened survives the reset: after re-acquisition
     * it can be finished under its original number instead of being orphaned.
     */
    @Test
    fun `a timeout mid-transaction resets the connection and the open transaction can still be finished`() {
        connectedAndCommissioned()
        val started = TseTransactionStartOperation(command = "signbill", clientId = CLIENT).execute(tseStatus)
        VirtualTseTestSupport.armHostFault("WORM_ERROR_TIMEOUT")

        val e =
            assertFailsWith<PosException> {
                TseTransactionFinishOperation(
                    command = "signbill",
                    transactionNumber = started.transactionNumber,
                    value = RECEIPT,
                    clientId = CLIENT,
                ).execute(tseStatus)
            }

        assertEquals(PosErrorCode.TSE_COMMUNICATION_TIMEOUT, e.code)
        assertConnectionReset()

        VirtualTseTestSupport.clearFault()
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        val finished =
            TseTransactionFinishOperation(
                command = "signbill",
                transactionNumber = started.transactionNumber,
                value = RECEIPT,
                clientId = CLIENT,
            ).execute(tseStatus)

        assertTrue(finished.success)
        assertEquals(started.transactionNumber, finished.transactionNumber)
    }

    private companion object {
        const val CLIENT = "TER-001"
        const val RECEIPT = "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar"
    }
}
