package org.example.project.tse.operations

import com.secureflashcard.wormapi.WormUserId
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.signing.TseSignOperation
import org.example.project.tse.operations.signing.TseTransactionFinishOperation
import org.example.project.tse.virtual.VirtualTseTestSupport
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** E2E R2-6: an answer from the card must never clear the mount point. */
class SigningKeepsConnectionOnCardAnswerTest {
    private val tseStatus = TseStatus()

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    private fun connected(commission: Boolean) {
        VirtualTseTestSupport.attachDevice("v2")
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setClientId("TER-001")
        tseStatus.setTimeAdminPin("98765")
        tseStatus.setCredentialSeed("SwissbitSwissbit")
        assertTrue(tseStatus.detectHardware())
        if (!commission) return
        val store = tseStatus.getWormStore()
        store.tse_setup_ext(
            "SwissbitSwissbit".toByteArray(),
            "888888".toByteArray(),
            "54321".toByteArray(),
            "98765".toByteArray(),
            "TER-001",
        )
        store.tse_updateTime(System.currentTimeMillis() / 1000)
        runCatching { store.user_logout(WormUserId.WORM_USER_ADMIN) }
    }

    private fun assertConnectionKept() {
        assertEquals(VirtualTseTestSupport.MOUNT_POINT, tseStatus.getMountPoint(), "the mount point must survive a card answer")
        assertTrue(tseStatus.isConnected(), "the connection must survive a card answer")
    }

    @Test
    fun `an uncommissioned TSE is not operational and keeps its mount point`() {
        connected(commission = false)
        val e =
            assertFailsWith<PosException> {
                TseSignOperation(command = "signbill", value = RECEIPT, clientId = "TER-001").execute(tseStatus)
            }
        assertEquals(PosErrorCode.TSE_NOT_OPERATIONAL, e.code)
        assertConnectionKept()
    }

    @Test
    fun `an unregistered client is not operational and keeps its mount point`() {
        connected(commission = true)
        val e =
            assertFailsWith<PosException> {
                TseSignOperation(command = "signbill", value = RECEIPT, clientId = "TER-999").execute(tseStatus)
            }
        assertEquals(PosErrorCode.TSE_NOT_OPERATIONAL, e.code)
        assertConnectionKept()
    }

    @Test
    fun `finishing a transaction that is not open reports it as such`() {
        connected(commission = true)
        val e =
            assertFailsWith<PosException> {
                TseTransactionFinishOperation(
                    command = "signbill",
                    transactionNumber = 999_999L,
                    value = RECEIPT,
                    clientId = "TER-001",
                ).execute(tseStatus)
            }
        assertEquals(PosErrorCode.TSE_TRANSACTION_NOT_OPEN, e.code)
        assertConnectionKept()
    }

    private companion object {
        const val RECEIPT = "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar"
    }
}
