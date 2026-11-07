package org.example.project.features.tse

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.dto.tse.TseSigningQueueDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.tse.data.provider.local.LocalTseSelfTest
import org.example.project.features.tse.data.provider.local.TsePowerLossRecovery
import org.example.project.features.tse.data.service.TseSigningRetryService
import org.example.project.features.tse.domain.service.TseSigningQueueService
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.example.project.features.tse.domain.dto.TseSigningQueueDto as ServerQueueDto

/**
 * F50 (real Swissbit TSE): after an unplug/replug the device answers again with
 * `passedSelfTest=false`, `validTime=false`. Re-acquiring the mount point did not repair that, the
 * sweeper waited for a healthy check, and bills stayed PENDING until an operator ran the self test
 * and time update by hand. [TsePowerLossRecovery] runs those two steps from the health tick.
 */
@DisplayName("TSE power-loss recovery — self test + time update after a replug")
class TsePowerLossRecoveryTest {
    /** Records the steps in order. `Dispatchers.Unconfined` runs the launched recovery in place. */
    private class Harness(
        var failWith: Exception? = null,
    ) {
        val steps = mutableListOf<String>()
        var clock = 0L
        val recovery =
            TsePowerLossRecovery(
                selfTest = {
                    steps += "selfTest"
                    failWith?.let { throw it }
                },
                updateTime = {
                    steps += "updateTime"
                    failWith?.let { throw it }
                },
                scope = CoroutineScope(Dispatchers.Unconfined),
                backoff = 60.seconds,
                nowMillis = { clock },
            )
    }

    @Test
    fun `a replugged TSE gets a self test then a time update, once per backoff window`() {
        val h = Harness()

        assertTrue(h.recovery.onHealthResult(TseMode.NORMAL, replugged(), inMaintenance = false))
        assertEquals(listOf("selfTest", "updateTime"), h.steps, "self test first: it invalidates the clock")

        h.clock = 59_000L
        assertFalse(h.recovery.onHealthResult(TseMode.NORMAL, replugged(), inMaintenance = false))
        assertEquals(2, h.steps.size, "a second health tick inside the backoff must not run again")

        h.clock = 60_000L
        assertTrue(h.recovery.onHealthResult(TseMode.NORMAL, replugged(), inMaintenance = false))
        assertEquals(4, h.steps.size, "after the backoff a still-broken TSE is retried")
    }

    @Test
    fun `only the time update runs when the self test is still valid`() {
        val h = Harness()

        h.recovery.onHealthResult(TseMode.NORMAL, replugged().copy(passedSelfTest = true), inMaintenance = false)

        assertEquals(listOf("updateTime"), h.steps)
    }

    @Test
    fun `nothing runs in maintenance, uncommissioned, non-NORMAL, unreachable or healthy`() {
        val h = Harness()

        assertFalse(h.recovery.onHealthResult(TseMode.NORMAL, replugged(), inMaintenance = true))
        assertFalse(h.recovery.onHealthResult(TseMode.NORMAL, replugged().copy(initialized = false), inMaintenance = false))
        assertFalse(h.recovery.onHealthResult(TseMode.SIMULATION, replugged(), inMaintenance = false))
        assertFalse(h.recovery.onHealthResult(TseMode.DISABLED, replugged(), inMaintenance = false))
        assertFalse(h.recovery.onHealthResult(TseMode.NORMAL, null, inMaintenance = false))
        assertFalse(h.recovery.onHealthResult(TseMode.NORMAL, healthy(), inMaintenance = false))

        assertTrue(h.steps.isEmpty(), "none of these may touch the device: ${h.steps}")
    }

    @Test
    fun `a rejected TimeAdmin PIN stops all further attempts`() {
        val h = Harness(failWith = pinRejected())

        h.recovery.onHealthResult(TseMode.NORMAL, replugged(), inMaintenance = false)
        assertEquals(listOf("selfTest"), h.steps)

        h.clock = 10 * 60_000L
        assertFalse(
            h.recovery.onHealthResult(TseMode.NORMAL, replugged(), inMaintenance = false),
            "retrying a rejected PIN unattended walks the device towards lockout",
        )
        assertEquals(1, h.steps.size)
    }

    @Test
    fun `a healthy result re-arms recovery after an auth stop`() {
        val h = Harness(failWith = pinRejected())
        h.recovery.onHealthResult(TseMode.NORMAL, replugged(), inMaintenance = false)

        h.failWith = null
        h.recovery.onHealthResult(TseMode.NORMAL, healthy(), inMaintenance = false)
        h.clock = 10 * 60_000L

        assertTrue(h.recovery.onHealthResult(TseMode.NORMAL, replugged(), inMaintenance = false))
        assertEquals(listOf("selfTest", "selfTest", "updateTime"), h.steps)
    }

    @Test
    fun `any other failure is retried after the backoff`() {
        val h =
            Harness(
                failWith =
                    PosException(
                        code = PosErrorCode.TSE_NOT_OPERATIONAL,
                        httpStatus = HttpStatusCode.ServiceUnavailable,
                        message = "TSE not operational: WORM_ERROR_TIMEOUT",
                    ),
            )
        h.recovery.onHealthResult(TseMode.NORMAL, replugged(), inMaintenance = false)
        h.clock = 60_000L

        assertTrue(h.recovery.onHealthResult(TseMode.NORMAL, replugged(), inMaintenance = false))
    }

    /**
     * The production wiring: the recovery's self test is [LocalTseSelfTest], which resolves the
     * registered client itself. Found on a real Swissbit TSE 1.x (2026-10-10): the recovery passed
     * no client, the driver had none after a plug-in during runtime, and every attempt failed.
     */
    private class ClientHarness(
        var client: String?,
        tseV2: Boolean = false,
    ) {
        val steps = mutableListOf<String>()
        var clock = 0L
        var maintenanceStarts = 0
        val recovery =
            TsePowerLossRecovery(
                selfTest = {
                    LocalTseSelfTest(
                        resolveClient = { client },
                        isTseV2 = { tseV2 },
                        execute = { clientId, _ ->
                            steps += "selfTest($clientId)"
                            TseDto.SelfTestResultDto(success = true, message = "ok")
                        },
                    ).run(force = false)
                },
                updateTime = { steps += "updateTime" },
                scope = CoroutineScope(Dispatchers.Unconfined),
                backoff = 60.seconds,
                nowMillis = { clock },
                afterRecovery = { maintenanceStarts++ },
            )
    }

    @Test
    fun `a replugged TSE 1x is self-tested with the registered client, then its time is set`() {
        val h = ClientHarness(client = "TER-003")

        assertTrue(h.recovery.onHealthResult(TseMode.NORMAL, replugged(), inMaintenance = false))

        assertEquals(listOf("selfTest(TER-003)", "updateTime"), h.steps)
        assertEquals(1, h.maintenanceStarts, "a successful recovery hands over to the scheduled maintenance")
    }

    @Test
    fun `with no registered terminal nothing reaches the device, and registering one lets the next attempt through`() {
        val h = ClientHarness(client = null)

        h.recovery.onHealthResult(TseMode.NORMAL, replugged(), inMaintenance = false)
        assertTrue(h.steps.isEmpty(), "no self test without a registered client, and no time update after it: ${h.steps}")
        assertEquals(0, h.maintenanceStarts)

        h.client = "TER-003"
        h.clock = 60_000L
        assertTrue(h.recovery.onHealthResult(TseMode.NORMAL, replugged(), inMaintenance = false), "not a permanent stop")
        assertEquals(listOf("selfTest(TER-003)", "updateTime"), h.steps)
    }

    @Test
    fun `a replugged TSE 2_0 needs no client`() {
        val h = ClientHarness(client = null, tseV2 = true)

        h.recovery.onHealthResult(TseMode.NORMAL, replugged(), inMaintenance = false)

        assertEquals(listOf("selfTest(null)", "updateTime"), h.steps)
    }

    @Test
    fun `a failed recovery does not start the scheduled maintenance`() {
        var maintenanceStarts = 0
        val recovery =
            TsePowerLossRecovery(
                selfTest = {},
                updateTime = { throw pinRejected() },
                scope = CoroutineScope(Dispatchers.Unconfined),
                nowMillis = { 0L },
                afterRecovery = { maintenanceStarts++ },
            )

        recovery.onHealthResult(TseMode.NORMAL, replugged(), inMaintenance = false)

        assertEquals(0, maintenanceStarts, "its TimeAdmin login would retry a PIN the TSE just rejected")
    }

    @Test
    fun `the sweeper drains the queue once recovery has repaired the TSE`() =
        runBlocking {
            val device = FakeDevice()
            val recovery =
                TsePowerLossRecovery(
                    selfTest = { device.passedSelfTest = true },
                    updateTime = { device.validTime = true },
                    scope = CoroutineScope(Dispatchers.Unconfined),
                    nowMillis = { 0L },
                )
            val queue = HealthGatedQueue(device, recovery)
            queue.queued = 3
            val sweeper = TseSigningRetryService(queue)

            val first = sweeper.triggerManualRetry()
            assertFalse(first.tseAvailable, "the replugged TSE is not healthy on the tick that finds it")

            val second = sweeper.triggerManualRetry()
            assertTrue(second.tseAvailable, "recovery must have made the next tick healthy")
            assertEquals(3, second.succeeded, "the PENDING backlog is signed")
            assertEquals(0, queue.queued)
        }

    @Test
    fun `negative control - without recovery the replugged TSE never drains`() =
        runBlocking {
            val device = FakeDevice()
            val queue = HealthGatedQueue(device, recovery = null)
            queue.queued = 3
            val sweeper = TseSigningRetryService(queue)

            repeat(5) { sweeper.triggerManualRetry() }

            assertEquals(3, queue.queued, "this is the F50 deadlock the recovery exists to break")
        }

    private class FakeDevice {
        var passedSelfTest = false
        var validTime = false

        fun health() = replugged().copy(passedSelfTest = passedSelfTest, validTime = validTime, healthy = passedSelfTest && validTime)
    }

    /** `isTseAvailable` mirrors `LocalTseSignProvider.checkHealth`: read the device, hand it to recovery. */
    private class HealthGatedQueue(
        private val device: FakeDevice,
        private val recovery: TsePowerLossRecovery?,
    ) : TseSigningQueueService {
        var queued = 0

        override suspend fun isTseAvailable(): Boolean {
            val result = device.health()
            recovery?.onHealthResult(TseMode.NORMAL, result, inMaintenance = false)
            return result.healthy
        }

        override suspend fun processBatch(
            batchSize: Int,
            ignoreBackoff: Boolean,
        ): ServerQueueDto.BatchProcessingResultDto {
            val n = minOf(queued, batchSize)
            queued -= n
            return ServerQueueDto.BatchProcessingResultDto(
                processedCount = n,
                successCount = n,
                failureCount = 0,
                queuedForRetryCount = 0,
                skippedCount = 0,
                processingTimeMs = 1L,
                errors = emptyList(),
            )
        }

        override suspend fun enqueue(
            referenceId: String,
            entityType: TseEntityType,
            processType: String,
            processData: String,
            clientId: String,
            reason: TseLateSigningReason,
            reasonText: String,
            openTseTransactionNumber: Long?,
        ): TseSigningQueueDto.QueueEntryResponseDto = throw NotImplementedError()

        override suspend fun processEntry(queueEntryId: Long): TseSigningQueueDto.QueueEntryResponseDto = throw NotImplementedError()

        override suspend fun getQueueStatus(): TseSigningQueueDto.QueueStatusDto = throw NotImplementedError()

        override suspend fun getEntry(queueEntryId: Long): TseSigningQueueDto.QueueEntryResponseDto = throw NotImplementedError()

        override suspend fun listEntries(
            status: TseSigningStatus?,
            entityType: TseEntityType?,
            limit: Int,
            offset: Int,
        ): List<TseSigningQueueDto.QueueEntryResponseDto> = throw NotImplementedError()

        override suspend fun retryEntry(queueEntryId: Long): TseSigningQueueDto.QueueEntryResponseDto = throw NotImplementedError()

        override suspend fun cancelEntry(
            queueEntryId: Long,
            employeeId: String,
            reason: String,
        ): TseSigningQueueDto.QueueEntryResponseDto = throw NotImplementedError()

        override suspend fun cleanupOldEntries(olderThanDays: Int): Int = throw NotImplementedError()
    }

    private companion object {
        /** What the real TSE answered after the replug in F50. */
        fun replugged() =
            TseDto.HealthCheckResultDto(
                healthy = false,
                initialized = true,
                validTime = false,
                passedSelfTest = false,
                remainingSignatures = 19_000_000L,
                availableCapacity = 1_000_000L,
            )

        fun healthy() = replugged().copy(healthy = true, validTime = true, passedSelfTest = true)

        /** The admin path's shape for a refused TimeAdmin login (see `TseAdminUnlock.isPinRejection`). */
        fun pinRejected() =
            PosException(
                code = PosErrorCode.TSE_OPERATION_REJECTED,
                httpStatus = HttpStatusCode.UnprocessableEntity,
                message = "TSE rejected the operation: WORM_ERROR_AUTHENTICATION_FAILED",
            )
    }
}
