package org.example.project.core.websocket

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.domain.enums.websocket.DataChangeOperation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class NotificationServiceTest {
    @AfterTest
    fun tearDown() = NotificationHub.resetForTest()

    @Test
    fun `notifyDataChange delivers a data_change event to a live connection`() =
        runTest {
            val out = Channel<OutgoingFrame>(Channel.UNLIMITED)
            NotificationHub.addSseConnection("c1", "e1", out)
            NotificationService.notifyDataChange(ChangeEntityType.PRODUCT, DataChangeOperation.UPDATED, "42")
            val frame = out.tryReceive().getOrNull()
            assertTrue(frame is OutgoingFrame.Event && frame.event.message is NotificationDto.DataChangeNotificationDto)
        }
}
