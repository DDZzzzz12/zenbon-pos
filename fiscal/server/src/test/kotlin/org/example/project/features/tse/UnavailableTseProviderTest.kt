package org.example.project.features.tse

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseFailureBehavior
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.tse.data.provider.unavailable.UnavailableTseAdminProvider
import org.example.project.features.tse.data.provider.unavailable.UnavailableTseSignProvider
import org.example.project.features.tse.domain.TseSignOutcome
import org.example.project.features.tse.domain.TseSigningOutcomeResolver
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the fallback used when LOCAL connection mode is configured but the Swissbit
 * TSE SDK is absent.
 *
 * The contract under test is not just "it throws" — it is that the thrown error flows
 * through the existing outage machinery so a missing SDK behaves exactly like an
 * offline TSE (queue for Nachsignieren, or reject), and never like a working simulator.
 */
class UnavailableTseProviderTest {
    @Test
    fun `sign operations throw TSE_UNREACHABLE rather than crashing`() =
        runTest {
            val provider = UnavailableTseSignProvider()

            val error =
                runCatching {
                    provider.quickSign(processType = "Kassenbeleg-V1", processData = "data", clientId = "POS-1")
                }.exceptionOrNull()

            assertTrue(error is PosException, "expected PosException, got $error")
            assertEquals(PosErrorCode.TSE_UNREACHABLE, error.code)
            assertEquals(HttpStatusCode.FailedDependency, error.httpStatus)
            assertTrue(
                error.message.orEmpty().contains("native/WormAPI.jar"),
                "error must name the expected SDK path so the operator can act on it",
            )
        }

    @Test
    fun `every signing entry point fails rather than silently returning a result`() =
        runTest {
            val provider = UnavailableTseSignProvider()

            val failures =
                listOf<suspend () -> Any>(
                    { provider.quickSign("Kassenbeleg-V1", "d", "POS-1") },
                    { provider.startTransaction("Bestellung-V1", "POS-1") },
                    { provider.updateTransaction(1L, "d", "POS-1") },
                    { provider.finishTransaction(1L, "d", "POS-1", "Kassenbeleg-V1") },
                ).map { runCatching { it() }.exceptionOrNull() }

            assertTrue(
                failures.all { it is PosException },
                "a signing path that returned normally would emit an unsigned receipt as if signed",
            )
        }

    @Test
    fun `never reports a simulated signature`() {
        val provider = UnavailableTseSignProvider()

        // Reporting SIMULATION here would let TseSigningOutcomeResolver stamp
        // TseSigningStatus.SIMULATION on real receipts — fake signatures on live sales.
        assertEquals("UNAVAILABLE", provider.getCachedMode())
        assertFalse(provider.isConnected())
    }

    @Test
    fun `health probes report unreachable instead of throwing`() =
        runTest {
            val sign = UnavailableTseSignProvider()
            val admin = UnavailableTseAdminProvider()

            assertNull(sign.checkHealth())
            assertNull(sign.restartConnection())
            assertFalse(admin.ping())
            assertFalse(admin.isInitialized())
            assertFalse(admin.setMode(mode = "NORMAL", clientId = "POS-1"))
        }

    @Test
    fun `admin commands throw TSE_UNREACHABLE`() =
        runTest {
            val admin = UnavailableTseAdminProvider()

            val error = runCatching { admin.getStatus() }.exceptionOrNull()

            assertTrue(error is PosException, "expected PosException, got $error")
            assertEquals(PosErrorCode.TSE_UNREACHABLE, error.code)
        }

    @Test
    fun `missing SDK queues for Nachsignieren under QUEUE behavior`() =
        runTest {
            val provider = UnavailableTseSignProvider()
            val error = runCatching { provider.quickSign("Kassenbeleg-V1", "d", "POS-1") }.exceptionOrNull()

            val decision =
                TseSigningOutcomeResolver.resolve(
                    mode = TseMode.NORMAL,
                    failureBehavior = TseFailureBehavior.QUEUE,
                    outcome = TseSignOutcome.Failed(error as PosException),
                )

            assertEquals(TseSigningStatus.QUEUED, decision.status)
            assertTrue(decision.enqueue, "transaction must be queued for late signing")
            assertNull(decision.rethrow, "QUEUE keeps the shop trading during a TSE outage")
        }

    @Test
    fun `missing SDK rejects the transaction under FAIL behavior`() =
        runTest {
            val provider = UnavailableTseSignProvider()
            val error = runCatching { provider.quickSign("Kassenbeleg-V1", "d", "POS-1") }.exceptionOrNull()

            val decision =
                TseSigningOutcomeResolver.resolve(
                    mode = TseMode.NORMAL,
                    failureBehavior = TseFailureBehavior.FAIL,
                    outcome = TseSignOutcome.Failed(error as PosException),
                )

            assertEquals(TseSigningStatus.FAILED, decision.status)
            assertFalse(decision.enqueue)
            assertTrue(decision.rethrow is PosException, "FAIL must surface the error to the caller")
        }
}
