package org.example.project.features.tse

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.tse.TseSigningQueueDto
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.tse.data.service.TseSigningRetryService
import org.example.project.features.tse.domain.service.TseSigningQueueService
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.features.tse.domain.dto.TseSigningQueueDto as ServerQueueDto

/**
 * Phase D verification: the existing periodic retry service ("Nachsignieren") actually processes
 * the signing queue (it is NOT a no-op) when the TSE is available. This guards the behavioral
 * contract that makes Task D1's lifecycle fix meaningful: because a NORMAL-but-offline TSE now
 * stays NORMAL (instead of being auto-switched to DISABLED, which the loop skips), QUEUED entries
 * are swept on reconnect.
 *
 * Scope note: [TseSigningRetryService.executeRetryTask]'s mode gate reads the Koin-backed
 * `ShopConfigurationManager` (only DISABLED is skipped). Driving that private scheduled path would
 * require bootstrapping Koin + a config store, which would be a brittle integration test. This test
 * instead drives [TseSigningRetryService.triggerManualRetry], which shares the exact same
 * `processBatch` Nachsignieren path as the scheduled loop, against a recording fake — asserting the
 * queue is actually processed and entries become SIGNED. The "DISABLED skips / NORMAL does not"
 * gate is exercised in production by Task D1 keeping the mode NORMAL.
 */
class TseSigningRetryServiceNachsignierenTest {
    /** Recording fake: holds QUEUED entries; processBatch "signs" them and records the call. */
    private class RecordingQueueService : TseSigningQueueService {
        val processBatchCalls = AtomicInteger(0)
        private val queued = AtomicInteger(0)
        val signed = AtomicInteger(0)
        var tseAvailable: Boolean = true

        fun enqueueFake(count: Int) {
            queued.addAndGet(count)
        }

        override suspend fun isTseAvailable(): Boolean = tseAvailable

        override suspend fun processBatch(
            batchSize: Int,
            ignoreBackoff: Boolean,
        ): ServerQueueDto.BatchProcessingResultDto {
            processBatchCalls.incrementAndGet()
            val toProcess = minOf(queued.get(), batchSize)
            queued.addAndGet(-toProcess)
            signed.addAndGet(toProcess)
            return ServerQueueDto.BatchProcessingResultDto(
                processedCount = toProcess,
                successCount = toProcess,
                failureCount = 0,
                queuedForRetryCount = 0,
                skippedCount = 0,
                processingTimeMs = 1L,
                errors = emptyList(),
            )
        }

        // Unused by this test.
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

    @Test
    fun `retry service processes the queue and signs entries when TSE is available`() =
        runBlocking {
            val fake = RecordingQueueService()
            fake.enqueueFake(3)
            val service = TseSigningRetryService(fake)

            val result = service.triggerManualRetry()

            assertTrue(fake.processBatchCalls.get() >= 1, "processBatch must be invoked by the retry path")
            assertEquals(3, result.succeeded, "all 3 QUEUED entries must be signed (Nachsignieren)")
            assertEquals(3, fake.signed.get())
            assertTrue(result.tseAvailable, "TSE was available, so the cycle must not be skipped")
        }

    @Test
    fun `retry service skips processing when TSE is unavailable`() =
        runBlocking {
            val fake = RecordingQueueService()
            fake.enqueueFake(2)
            fake.tseAvailable = false
            val service = TseSigningRetryService(fake)

            val result = service.triggerManualRetry()

            assertEquals(0, fake.processBatchCalls.get(), "processBatch must NOT run when TSE unavailable")
            assertEquals(0, result.succeeded)
            assertTrue(!result.tseAvailable)
        }
}
