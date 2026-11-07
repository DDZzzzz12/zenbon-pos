package org.example.project.features.tse

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.tse.TseHealthState
import org.example.project.features.tse.domain.provider.TseStatusBroadcaster
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TseStatusBroadcasterDiffTest {
    private val captured = mutableListOf<NotificationDto.TseStatusUpdateDto>()

    @BeforeEach
    fun setup() {
        captured.clear()
        TseStatusBroadcaster.resetState()
        TseStatusBroadcaster.broadcastSink = { captured.add(it) }
    }

    @AfterEach
    fun teardown() {
        TseStatusBroadcaster.broadcastSink = null
        TseStatusBroadcaster.resetState()
    }

    @Test
    fun `same state twice broadcasts once`() =
        runBlocking {
            TseStatusBroadcaster.broadcastIfStateChanged(connected = true, health = TseHealthState.HEALTHY, mode = "NORMAL")
            TseStatusBroadcaster.broadcastIfStateChanged(connected = true, health = TseHealthState.HEALTHY, mode = "NORMAL")
            assertEquals(1, captured.size)
        }

    @Test
    fun `ops change triggers broadcast even when connected mode health unchanged`() =
        runBlocking {
            TseStatusBroadcaster.broadcastIfStateChanged(
                connected = true,
                health = TseHealthState.MAINTENANCE,
                mode = "NORMAL",
                ops = setOf("scheduled-selftest"),
            )
            TseStatusBroadcaster.broadcastIfStateChanged(
                connected = true,
                health = TseHealthState.MAINTENANCE,
                mode = "NORMAL",
                ops = setOf("scheduled-timesync"),
            )
            assertEquals(2, captured.size)
            assertEquals(setOf("scheduled-selftest"), captured[0].maintenanceOps)
            assertEquals(setOf("scheduled-timesync"), captured[1].maintenanceOps)
        }

    @Test
    fun `health transition MAINTENANCE to HEALTHY broadcasts`() =
        runBlocking {
            TseStatusBroadcaster.broadcastIfStateChanged(
                connected = true,
                health = TseHealthState.MAINTENANCE,
                mode = "NORMAL",
                ops = setOf("scheduled-timesync"),
            )
            TseStatusBroadcaster.broadcastIfStateChanged(connected = true, health = TseHealthState.HEALTHY, mode = "NORMAL")
            assertEquals(2, captured.size)
            assertEquals(TseHealthState.MAINTENANCE, captured[0].health)
            assertEquals(TseHealthState.HEALTHY, captured[1].health)
        }

    @Test
    fun `back-compat healthy boolean is true iff health is HEALTHY`() =
        runBlocking {
            TseStatusBroadcaster.broadcastIfStateChanged(connected = true, health = TseHealthState.HEALTHY, mode = "NORMAL")
            TseStatusBroadcaster.broadcastIfStateChanged(
                connected = true,
                health = TseHealthState.MAINTENANCE,
                mode = "NORMAL",
                ops = setOf("scheduled-selftest"),
            )
            TseStatusBroadcaster.broadcastIfStateChanged(connected = true, health = TseHealthState.DEGRADED, mode = "NORMAL")
            assertEquals(3, captured.size)
            assertTrue(captured[0].healthy)
            assertFalse(captured[1].healthy)
            assertFalse(captured[2].healthy)
        }
}
