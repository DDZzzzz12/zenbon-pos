package org.example.project.features.tse

import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.tse.TseSigningQueueDto
import org.example.project.features.tse.data.service.TseSigningRetryService
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `POST /api/tse/queue/trigger` used to respond with the service's internal `ProcessingResult`,
 * which is not `@Serializable`: the batch ran, then the reply failed with 400 *"Serializer for
 * class 'ProcessingResult' is not found"*. The wire contract is
 * [TseSigningQueueDto.TriggerRetryResponseDto] — what `KtorTseQueueApiClient.triggerRetry()`
 * decodes behind the "Erneut versuchen" button.
 */
@DisplayName("TSE queue — the manual trigger answers a serializable report")
class TriggerRetryResponseTest {
    private fun result(
        processed: Int = 0,
        succeeded: Int = 0,
        failed: Int = 0,
        queued: Int = 0,
        tseAvailable: Boolean = true,
        skippedReason: String? = null,
    ) = TseSigningRetryService.ProcessingResult(
        timestamp = 0L,
        processed = processed,
        succeeded = succeeded,
        failed = failed,
        queued = queued,
        tseAvailable = tseAvailable,
        skippedReason = skippedReason,
    )

    @Test
    fun `the report round-trips through the shared DTO`() {
        val dto = result(processed = 3, succeeded = 3).toResponseDto()

        val decoded =
            Json.decodeFromString<TseSigningQueueDto.TriggerRetryResponseDto>(
                Json.encodeToString(dto),
            )

        assertEquals(dto, decoded)
        assertEquals(3, decoded.processedCount)
        assertEquals(3, decoded.successCount)
        assertTrue(decoded.success)
    }

    @Test
    fun `a cycle with failures is not reported as a success`() {
        val dto = result(processed = 2, succeeded = 1, failed = 1).toResponseDto()

        assertFalse(dto.success)
        assertEquals(1, dto.failureCount)
    }

    @Test
    fun `a skipped cycle is not a success and names the reason`() {
        val dto = result(tseAvailable = false, skippedReason = "TSE unavailable").toResponseDto()

        assertFalse(dto.success, "nothing was signed, so the button must not read as done")
        assertTrue(dto.message.contains("TSE unavailable"))
        assertEquals(0, dto.processedCount)
    }
}
