package org.example.project.features.tse

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseFailureBehavior
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.tse.domain.TseSignOutcome
import org.example.project.features.tse.domain.TseSigningOutcomeResolver
import org.example.project.infrastructure.errors.PosException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class TseSigningOutcomeResolverTest {
    private val err = PosException(PosErrorCode.TSE_UNREACHABLE, HttpStatusCode.ServiceUnavailable, "down")

    @Test
    fun `NORMAL signed - SIGNED, no enqueue, no rethrow`() {
        val d = TseSigningOutcomeResolver.resolve(TseMode.NORMAL, TseFailureBehavior.QUEUE, TseSignOutcome.Signed)
        assertEquals(TseSigningStatus.SIGNED, d.status)
        assertEquals(false, d.enqueue)
        assertNull(d.rethrow)
    }

    @Test
    fun `SIMULATION signed - SIMULATION`() {
        val d = TseSigningOutcomeResolver.resolve(TseMode.SIMULATION, TseFailureBehavior.QUEUE, TseSignOutcome.Signed)
        assertEquals(TseSigningStatus.SIMULATION, d.status)
        assertEquals(false, d.enqueue)
        assertNull(d.rethrow)
    }

    @Test
    fun `NoSignature - DISABLED regardless of mode`() {
        for (mode in TseMode.entries) {
            val d = TseSigningOutcomeResolver.resolve(mode, TseFailureBehavior.QUEUE, TseSignOutcome.NoSignature)
            assertEquals(TseSigningStatus.DISABLED, d.status)
            assertEquals(false, d.enqueue)
            assertNull(d.rethrow)
        }
    }

    @Test
    fun `Failed + QUEUE - QUEUED + enqueue, no rethrow`() {
        val d = TseSigningOutcomeResolver.resolve(TseMode.NORMAL, TseFailureBehavior.QUEUE, TseSignOutcome.Failed(err))
        assertEquals(TseSigningStatus.QUEUED, d.status)
        assertEquals(true, d.enqueue)
        assertNull(d.rethrow)
    }

    @Test
    fun `Failed + FAIL - FAILED + rethrow, no enqueue`() {
        val d = TseSigningOutcomeResolver.resolve(TseMode.NORMAL, TseFailureBehavior.FAIL, TseSignOutcome.Failed(err))
        assertEquals(TseSigningStatus.FAILED, d.status)
        assertEquals(false, d.enqueue)
        assertSame(err, d.rethrow)
    }
}
