package org.example.project.admin.features.dashboard.sections

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.dashboard.FakeTseQueueApiClient
import org.example.project.admin.features.dashboard.FakeTseSettingsApiClient
import org.example.project.admin.features.dashboard.presentation.sections.TseHealthViewModel
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.dto.tse.TseSigningQueueDto
import org.example.project.core.network.error.NetworkApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun health(healthy: Boolean = true) =
    TseDto.HealthCheckResultDto(
        healthy = healthy,
        initialized = true,
        validTime = true,
        passedSelfTest = true,
        remainingSignatures = 1_000L,
        availableCapacity = 1_000L,
    )

private fun queueStatus(
    failed: Long = 0,
    queued: Long = 0,
) = TseSigningQueueDto.QueueStatusDto(
    totalEntries = failed + queued,
    pendingCount = 0,
    processingCount = 0,
    queuedCount = queued,
    signedCount = 0,
    failedCount = failed,
    cancelledCount = 0,
    tseAvailable = true,
    retryServiceRunning = true,
    oldestPendingAge = null,
    averageRetryCount = 0.0,
)

class TseHealthViewModelTest {
    @Test
    fun healthyWithEmptyQueue_isOperational() =
        runTest {
            val vm =
                TseHealthViewModel(
                    FakeTseSettingsApiClient { health(healthy = true) },
                    FakeTseQueueApiClient { queueStatus() },
                )
            vm.refresh()
            assertTrue(vm.state.operational)
            assertEquals(0L, vm.state.unsignedCount)
            assertFalse(vm.state.failed)
        }

    @Test
    fun unsignedBacklog_isNotOperational() =
        runTest {
            val vm =
                TseHealthViewModel(
                    FakeTseSettingsApiClient { health(healthy = true) },
                    FakeTseQueueApiClient { queueStatus(failed = 2, queued = 3) },
                )
            vm.refresh()
            assertEquals(5L, vm.state.unsignedCount)
            assertFalse(vm.state.operational)
        }

    @Test
    fun unhealthyDevice_isNotOperational() =
        runTest {
            val vm =
                TseHealthViewModel(
                    FakeTseSettingsApiClient { health(healthy = false) },
                    FakeTseQueueApiClient { queueStatus() },
                )
            vm.refresh()
            assertFalse(vm.state.operational)
        }

    @Test
    fun failedLoad_setsFailed() =
        runTest {
            val vm =
                TseHealthViewModel(
                    FakeTseSettingsApiClient { throw NetworkApiException(RuntimeException("down")) },
                    FakeTseQueueApiClient { queueStatus() },
                )
            vm.refresh()
            assertTrue(vm.state.failed)
            assertFalse(vm.state.isLoading)
        }
}
