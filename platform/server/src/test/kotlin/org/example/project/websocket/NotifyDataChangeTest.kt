package org.example.project.websocket

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.domain.enums.websocket.DataChangeOperation
import org.example.project.core.websocket.NotificationHub
import org.example.project.core.websocket.NotificationService
import org.example.project.core.websocket.OutgoingFrame
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the `notifyDataChange` contract:
 * - `seq` is forwarded into the constructed [NotificationDto.DataChangeNotificationDto]
 * - the hint is broadcast to ALL connections, INCLUDING the originator's.
 *
 * `excludeEmployeeId` is retained on the signature for source compatibility but is
 * deliberately NOT applied to data-change hints (7e5e0d5d3, "deliver data-change hints
 * to the originator too"): the originating client needs its own hint to apply the change
 * to its local SQLDelight cache, which the API response does not touch. Excluding it left
 * that cache stale until the next full sync.
 */
class NotifyDataChangeTest {
    @AfterTest
    fun tearDown() = NotificationHub.resetForTest()

    // -------------------------------------------------------------------------
    // Helper to receive all currently queued frames from a channel
    // -------------------------------------------------------------------------
    private fun Channel<OutgoingFrame>.drainDataChangeDtos(): List<NotificationDto.DataChangeNotificationDto> {
        val result = mutableListOf<NotificationDto.DataChangeNotificationDto>()
        while (true) {
            val frame = tryReceive().getOrNull() ?: break
            val msg = (frame as? OutgoingFrame.Event)?.event?.message
            if (msg is NotificationDto.DataChangeNotificationDto) result.add(msg)
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Core contract: seq and entityType/entityId survive the round-trip
    // -------------------------------------------------------------------------

    @Test
    fun `notifyDataChange passes seq and entity fields into the DTO delivered to connections`() =
        runTest {
            val outOther = Channel<OutgoingFrame>(Channel.UNLIMITED)
            NotificationHub.addSseConnection("c-other", "emp-other", outOther)

            NotificationService.notifyDataChange(
                entityType = ChangeEntityType.PRODUCT,
                operation = DataChangeOperation.UPDATED,
                entityId = "42",
                seq = 7L,
                excludeEmployeeId = "emp-7",
            )

            val dtos = outOther.drainDataChangeDtos()
            assertEquals(1, dtos.size, "emp-other should receive exactly one data_change frame")
            val dto = dtos.single()
            assertEquals(ChangeEntityType.PRODUCT, dto.entityType)
            assertEquals(DataChangeOperation.UPDATED, dto.operation)
            assertEquals("42", dto.entityId)
            assertEquals(7L, dto.seq)
        }

    // -------------------------------------------------------------------------
    // No exclusion: originator receives its own hint, and so does everyone else
    // -------------------------------------------------------------------------

    @Test
    fun `notifyDataChange delivers to the originating employee connection as well as others`() =
        runTest {
            val outOriginator = Channel<OutgoingFrame>(Channel.UNLIMITED)
            val outOther = Channel<OutgoingFrame>(Channel.UNLIMITED)

            NotificationHub.addSseConnection("c-emp7", "emp-7", outOriginator)
            NotificationHub.addSseConnection("c-other", "emp-other", outOther)

            NotificationService.notifyDataChange(
                entityType = ChangeEntityType.PRODUCT,
                operation = DataChangeOperation.UPDATED,
                entityId = "42",
                seq = 7L,
                excludeEmployeeId = "emp-7",
            )

            // The other employee receives the notification
            val otherFrames = outOther.drainDataChangeDtos()
            assertEquals(1, otherFrames.size, "emp-other should receive the broadcast")

            // ...and so does the originator: it needs the hint to refresh its own local cache.
            val originatorFrames = outOriginator.drainDataChangeDtos()
            assertEquals(1, originatorFrames.size, "emp-7 (originator) must receive its own write notification")
        }

    // -------------------------------------------------------------------------
    // Null exclude: all connections receive when excludeEmployeeId is null
    // -------------------------------------------------------------------------

    @Test
    fun `notifyDataChange with null excludeEmployeeId delivers to all connections`() =
        runTest {
            val out1 = Channel<OutgoingFrame>(Channel.UNLIMITED)
            val out2 = Channel<OutgoingFrame>(Channel.UNLIMITED)

            NotificationHub.addSseConnection("c1", "emp-1", out1)
            NotificationHub.addSseConnection("c2", "emp-2", out2)

            NotificationService.notifyDataChange(
                entityType = ChangeEntityType.PRODUCT,
                operation = DataChangeOperation.CREATED,
                entityId = "99",
                excludeEmployeeId = null,
            )

            assertEquals(1, out1.drainDataChangeDtos().size, "emp-1 should receive when no exclusion")
            assertEquals(1, out2.drainDataChangeDtos().size, "emp-2 should receive when no exclusion")
        }

    // -------------------------------------------------------------------------
    // Long overload: forwards seq and excludeEmployeeId correctly
    // -------------------------------------------------------------------------

    @Test
    fun `Long-entityId overload forwards seq and entityId to String overload`() =
        runTest {
            val outOriginator = Channel<OutgoingFrame>(Channel.UNLIMITED)
            val outOther = Channel<OutgoingFrame>(Channel.UNLIMITED)

            NotificationHub.addSseConnection("c-originator", "emp-author", outOriginator)
            NotificationHub.addSseConnection("c-observer", "emp-observer", outOther)

            NotificationService.notifyDataChange(
                entityType = ChangeEntityType.PAYMENT_TYPE,
                operation = DataChangeOperation.DELETED,
                entityId = 5L,
                seq = 3L,
                excludeEmployeeId = "emp-author",
            )

            // Observer receives correct DTO
            val observerDtos = outOther.drainDataChangeDtos()
            assertEquals(1, observerDtos.size)
            val dto = observerDtos.single()
            assertEquals("5", dto.entityId)
            assertEquals(3L, dto.seq)
            assertEquals(ChangeEntityType.PAYMENT_TYPE, dto.entityType)

            // Originator included, same as the String overload
            assertEquals(1, outOriginator.drainDataChangeDtos().size, "originator must receive via Long overload")
        }
}
