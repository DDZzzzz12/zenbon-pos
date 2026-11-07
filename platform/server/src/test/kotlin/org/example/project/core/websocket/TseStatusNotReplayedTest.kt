package org.example.project.core.websocket

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.example.project.core.domain.dto.websocket.NotificationDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TSE status is current state, not history.
 *
 * Every connection is pushed the live status on connect (`TseStatusBroadcaster.sendCurrentStatusTo`),
 * so a buffered status frame can only replay a mode the device has already moved past — which is what
 * raised the false mode-change warning on a cold-starting till (E2E-2026-09-15 N4).
 */
class TseStatusNotReplayedTest {
    private val log = NotificationEventLog()

    private fun publisher() = NotificationPublisher(SseConnectionRegistry(), log)

    private fun tseStatus(mode: String) =
        NotificationDto.TseStatusUpdateDto(
            timestamp = "t",
            connected = true,
            healthy = true,
            mode = mode,
        )

    private fun err(m: String) = NotificationDto.WebSocketErrorDto(timestamp = "t", errorCode = "E", message = m)

    /** Everything the replay buffer still holds, regardless of targeting. */
    private fun buffered() = log.replay(afterId = 0) { true }.map { it.message }

    @Test
    fun `a broadcast TSE status frame is not retained for replay`() =
        runTest {
            val pub = publisher()
            pub.addSseConnection("c1", "e1", Channel<OutgoingFrame>(Channel.UNLIMITED))

            pub.broadcastToAll(tseStatus("SIMULATION"))

            assertTrue(
                buffered().none { it is NotificationDto.TseStatusUpdateDto },
                "TSE status must not sit in the replay buffer; buffer held ${buffered()}",
            )
        }

    @Test
    fun `a non-TSE broadcast in the same sequence still replays`() =
        runTest {
            val pub = publisher()
            pub.addSseConnection("c1", "e1", Channel<OutgoingFrame>(Channel.UNLIMITED))

            pub.broadcastToAll(err("before"))
            pub.broadcastToAll(tseStatus("SIMULATION"))
            pub.broadcastToAll(err("after"))

            assertEquals(
                listOf("before", "after"),
                buffered().filterIsInstance<NotificationDto.WebSocketErrorDto>().map { it.message },
                "only the TSE frame may be dropped — ordinary broadcasts must still replay",
            )
            assertTrue(
                buffered().none { it is NotificationDto.TseStatusUpdateDto },
                "TSE status must not sit in the replay buffer; buffer held ${buffered()}",
            )
        }

    @Test
    fun `a live connection still receives the TSE status frame`() =
        runTest {
            val pub = publisher()
            val out = Channel<OutgoingFrame>(Channel.UNLIMITED)
            pub.addSseConnection("c1", "e1", out)

            pub.broadcastToAll(tseStatus("NORMAL"))

            val delivered =
                generateSequence { out.tryReceive().getOrNull() }
                    .filterIsInstance<OutgoingFrame.Event>()
                    .map { it.event.message }
                    .filterIsInstance<NotificationDto.TseStatusUpdateDto>()
                    .map { it.mode }
                    .toList()
            assertEquals(listOf("NORMAL"), delivered, "dropping the frame from the buffer must not stop live delivery")
        }
}
