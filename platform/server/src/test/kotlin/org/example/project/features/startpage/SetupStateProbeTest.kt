package org.example.project.features.startpage

import kotlinx.coroutines.test.runTest
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.dto.setup.SetupReadinessDto
import org.example.project.features.setup.domain.service.InitialSetupService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupStateProbeTest {
    @Test
    fun `re-checks while uninitialised, then remembers initialised without asking again`() =
        runTest {
            val service = ScriptedSetupService(answers = ArrayDeque(listOf(false, true)))
            val probe = SetupStateProbe(service)

            assertFalse(probe.isInitialized())
            assertTrue(probe.isInitialized())
            repeat(3) { assertTrue(probe.isInitialized()) }
            assertEquals(2, service.calls, "the service is consulted only until it first says yes")
        }

    /** Answers [checkInitializationStatus] from [answers] in order; everything else is out of scope. */
    private class ScriptedSetupService(
        private val answers: ArrayDeque<Boolean>,
    ) : InitialSetupService {
        var calls = 0

        override suspend fun checkInitializationStatus(): InitialSetupDto.InitialSetupStatusDto {
            calls++
            return InitialSetupDto.InitialSetupStatusDto(isInitialized = answers.removeFirst())
        }

        override suspend fun resetSystemData(resetRequest: InitialSetupDto.ResetSystemRequestDto) = error("unused")

        override suspend fun executeSetupStep(setupRequest: InitialSetupDto.InitialSetupRequestDto<*>) = error("unused")

        override suspend fun executeCompleteSetup(request: InitialSetupDto.CompleteSetupRequestDto) = error("unused")

        override suspend fun checkReadiness(): SetupReadinessDto = error("unused")
    }
}
