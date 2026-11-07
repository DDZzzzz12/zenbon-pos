package org.example.project.core.websocket

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Authorization notifications carry the same timestamp shape as every other notification: an
 * ISO-8601 UTC instant. They used to send `System.currentTimeMillis().toString()`, which the client
 * could only display by guessing the format.
 */
class AuthNotificationTimestampTest {
    @AfterTest
    fun tearDown() = NotificationHub.resetForTest()

    @Test
    fun `a session-expiring notification is stamped with an ISO-8601 UTC instant`() =
        runTest {
            val out = Channel<OutgoingFrame>(Channel.UNLIMITED)
            NotificationHub.addSseConnection("c1", "e1", out)

            AuthNotificationManager.sendSessionExpiring(deviceId = "d1", sessionId = "s1", remainingSeconds = 30)

            val frame = assertIs<OutgoingFrame.Event>(out.tryReceive().getOrNull())
            val timestamp = frame.event.message.timestamp
            assertFalse(timestamp.all { it.isDigit() }, "epoch millis are not an ISO instant: \"$timestamp\"")
            assertTrue(timestamp.endsWith("Z"), "the instant must be UTC: \"$timestamp\"")
            Instant.parse(timestamp)
        }
}
