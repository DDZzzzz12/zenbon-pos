package org.example.project.features.tse

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.core.error.PosErrorCode
import org.example.project.features.tse.domain.TseSignOutcome
import org.example.project.features.tse.domain.toLateSigningCause
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TseLateSigningCausesTest {
    @Test
    fun `an unreachable TSE is TSE_UNAVAILABLE and the text keeps the code`() {
        val (reason, text) =
            TseSignOutcome
                .Failed(PosException(PosErrorCode.TSE_HARDWARE_NOT_FOUND, HttpStatusCode.ServiceUnavailable, "TSE hardware not found"))
                .toLateSigningCause()

        assertEquals(TseLateSigningReason.TSE_UNAVAILABLE, reason)
        assertEquals("TSE_HARDWARE_NOT_FOUND: TSE hardware not found", text)
    }

    @Test
    fun `an error the TSE answered with is TSE_ERROR`() {
        val (reason, _) =
            PosException(PosErrorCode.TSE_TRANSACTION_FINISH_FAILED, HttpStatusCode.InternalServerError, "finish failed")
                .toLateSigningCause()

        assertEquals(TseLateSigningReason.TSE_ERROR, reason)
    }

    @Test
    fun `a non-PosException names its class`() {
        val (reason, text) = IllegalStateException("boom").toLateSigningCause()

        assertEquals(TseLateSigningReason.TSE_ERROR, reason)
        assertEquals("IllegalStateException: boom", text)
    }

    @Test
    fun `a queued outcome without a failure says no signature came back`() {
        val (reason, text) = TseSignOutcome.NoSignature.toLateSigningCause()

        assertEquals(TseLateSigningReason.TSE_ERROR, reason)
        assertEquals("Keine Signatur erhalten", text)
    }

    @Test
    fun `a TSE that is connected but not operational gets its own reason`() {
        val (reason, text) =
            PosException(
                PosErrorCode.TSE_NOT_OPERATIONAL,
                HttpStatusCode.ServiceUnavailable,
                "TSE not operational: WORM_ERROR_TSE_NOT_INITIALIZED",
            ).toLateSigningCause()
        assertEquals(TseLateSigningReason.TSE_NOT_OPERATIONAL, reason)
        assertEquals("TSE_NOT_OPERATIONAL: TSE not operational: WORM_ERROR_TSE_NOT_INITIALIZED", text)
    }

    @Test
    fun `a transaction that is not open is a TSE error, not an outage`() {
        val (reason, _) =
            PosException(PosErrorCode.TSE_TRANSACTION_NOT_OPEN, HttpStatusCode.Conflict, "x").toLateSigningCause()
        assertEquals(TseLateSigningReason.TSE_ERROR, reason)
    }
}
