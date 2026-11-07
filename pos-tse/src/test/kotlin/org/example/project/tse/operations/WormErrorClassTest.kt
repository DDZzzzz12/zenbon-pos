package org.example.project.tse.operations

import com.secureflashcard.wormapi.WormError
import com.secureflashcard.wormapi.WormException
import org.example.project.core.error.PosErrorCode
import org.example.project.tse.operations.signing.WormErrorClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WormErrorClassTest {
    @Test
    fun `host-side errors reset the connection`() {
        for (e in listOf(WormError.WORM_ERROR_IO, WormError.WORM_ERROR_NO_WORM_CARD, WormError.WORM_ERROR_TSE_NOT_FOUND, WormError.WORM_ERROR_CONNECTION_FAILED)) {
            val c = WormErrorClass.of(e)
            assertEquals(WormErrorClass.CONNECTION, c, "$e")
            assertTrue(c.resetsConnection, "$e")
            assertEquals(PosErrorCode.TSE_CONNECTION_FAILED, c.code)
        }
    }

    @Test
    fun `a timeout means the request may have reached the device`() {
        for (e in listOf(WormError.WORM_ERROR_TIMEOUT, WormError.WORM_ERROR_RESPONSE_MISSING, WormError.WORM_ERROR_NETWORK_TIMEOUT)) {
            val c = WormErrorClass.of(e)
            assertEquals(WormErrorClass.COMMUNICATION_TIMEOUT, c, "$e")
            assertTrue(c.resetsConnection)
            assertEquals(PosErrorCode.TSE_COMMUNICATION_TIMEOUT, c.code)
        }
    }

    @Test
    fun `a card that is not ready to sign keeps its connection`() {
        val notReady =
            listOf(
                WormError.WORM_ERROR_TSE_NOT_INITIALIZED,
                WormError.WORM_ERROR_TSE_DECOMMISSIONED,
                WormError.WORM_ERROR_CLIENT_NOT_REGISTERED,
                WormError.WORM_ERROR_CERTIFICATE_EXPIRED,
                WormError.WORM_ERROR_WRONG_STATE_NEEDS_PIN_CHANGE,
                WormError.WORM_ERROR_WRONG_STATE_NEEDS_PUK_CHANGE,
                WormError.WORM_ERROR_WRONG_STATE_NEEDS_ACTIVE_CTSS,
                // Stale-TSE preconditions (R2-6 fix review): recovery retries these once, but
                // when remediation fails they must still surface as NOT_OPERATIONAL, not REJECTED.
                WormError.WORM_ERROR_NO_TIME_SET,
                WormError.WORM_ERROR_WRONG_STATE_NEEDS_SELF_TEST,
                WormError.WORM_ERROR_WRONG_STATE_NEEDS_SELF_TEST_PASSED,
            )
        for (e in notReady) {
            val c = WormErrorClass.of(e)
            assertEquals(WormErrorClass.NOT_OPERATIONAL, c, "$e")
            assertFalse(c.resetsConnection, "$e")
            assertEquals(PosErrorCode.TSE_NOT_OPERATIONAL, c.code)
        }
    }

    @Test
    fun `a transaction that is not open is reported as itself`() {
        for (e in listOf(WormError.WORM_ERROR_TRANSACTION_NOT_STARTED, WormError.WORM_ERROR_NO_TRANSACTION_IN_PROGRESS)) {
            val c = WormErrorClass.of(e)
            assertEquals(WormErrorClass.TRANSACTION_NOT_OPEN, c, "$e")
            assertFalse(c.resetsConnection)
            assertEquals(PosErrorCode.TSE_TRANSACTION_NOT_OPEN, c.code)
        }
    }

    @Test
    fun `any other answer from the card is a rejection, not lost hardware`() {
        val c = WormErrorClass.of(WormError.WORM_ERROR_STORE_FULL)
        assertEquals(WormErrorClass.REJECTED, c)
        assertFalse(c.resetsConnection)
        assertEquals(PosErrorCode.TSE_OPERATION_REJECTED, c.code)
    }

    @Test
    fun `no new incremental export data is an answer about the data, not a lost device`() {
        // F40 / RT2-B1: host-range code 12, but the TSE that says it is connected and healthy.
        val c = WormErrorClass.of(WormError.WORM_ERROR_INCREMENTAL_EXPORT_NO_DATA)
        assertEquals(WormErrorClass.REJECTED, c)
        assertFalse(c.resetsConnection)
        assertTrue(WormErrorClass.isNoNewData(WormException(WormError.WORM_ERROR_INCREMENTAL_EXPORT_NO_DATA.swigValue())))
        assertFalse(WormErrorClass.isNoNewData(WormException(WormError.WORM_ERROR_IO.swigValue())))
        assertFalse(WormErrorClass.isNoNewData(WormException("no code")))
    }

    @Test
    fun `a message-only WormException carries no code and counts as a connection failure`() {
        assertEquals(WormErrorClass.CONNECTION, WormErrorClass.of(WormException("no code")))
    }

    @Test
    fun `messageFor pins the wire text fiscal records quote`() {
        assertEquals(
            "Failed to connect to TSE hardware",
            WormErrorClass.CONNECTION.messageFor(WormException(WormError.WORM_ERROR_IO.swigValue())),
        )
        assertTrue(
            WormErrorClass.NOT_OPERATIONAL.messageFor(
                WormException(WormError.WORM_ERROR_TSE_NOT_INITIALIZED.swigValue()),
            ).contains("WORM_ERROR_TSE_NOT_INITIALIZED"),
        )
    }
}
