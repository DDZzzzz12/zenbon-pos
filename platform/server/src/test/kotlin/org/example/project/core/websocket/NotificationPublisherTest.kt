package org.example.project.core.websocket

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationPublisherTest {
    private fun publisher() = NotificationPublisher(SseConnectionRegistry(), NotificationEventLog())

    private fun err(m: String) = NotificationDto.WebSocketErrorDto(timestamp = "t", errorCode = "E", message = m)

    @Test
    fun `broadcastToAll delivers an Event frame to a live connection`() =
        runTest {
            val pub = publisher()
            val out = Channel<OutgoingFrame>(Channel.UNLIMITED)
            pub.addSseConnection("c1", "e1", out)
            pub.broadcastToAll(err("hello"))
            val frame = out.tryReceive().getOrNull()
            assertTrue(frame is OutgoingFrame.Event)
            assertEquals("hello", (frame.event.message as NotificationDto.WebSocketErrorDto).message)
        }

    @Test
    fun `adding a second connection for the same employee evicts the first`() =
        runTest {
            val pub = publisher()
            val out1 = Channel<OutgoingFrame>(Channel.UNLIMITED)
            val out2 = Channel<OutgoingFrame>(Channel.UNLIMITED)
            pub.addSseConnection("c1", "e1", out1)
            pub.addSseConnection("c2", "e1", out2)
            assertEquals(1, pub.getActiveConnectionCount())
            assertTrue(out1.isClosedForSend)
        }

    @Test
    fun `replayFor returns broadcast events after lastId`() =
        runTest {
            val pub = publisher()
            pub.addSseConnection("c1", "e1", Channel<OutgoingFrame>(Channel.UNLIMITED))
            pub.broadcastToAll(err("a"))
            pub.broadcastToAll(err("b"))
            val out = Channel<OutgoingFrame>(Channel.UNLIMITED)
            pub.addSseConnection("c2", "e2", out)
            val replay = pub.replayFor("e2", lastEventId = 1)
            assertEquals(listOf("b"), replay.map { (it.message as NotificationDto.WebSocketErrorDto).message })
        }

    @Test
    fun `sendToEmployee only targets that employee and is replayable by them`() =
        runTest {
            val pub = publisher()
            val outA = Channel<OutgoingFrame>(Channel.UNLIMITED)
            val outB = Channel<OutgoingFrame>(Channel.UNLIMITED)
            pub.addSseConnection("cA", "eA", outA)
            pub.addSseConnection("cB", "eB", outB)
            pub.sendToEmployee("eA", err("for-A"))
            assertTrue(outA.tryReceive().getOrNull() is OutgoingFrame.Event)
            assertTrue(outB.tryReceive().isFailure)
            assertTrue(pub.replayFor("eB", 0).isEmpty())
            assertEquals(1, pub.replayFor("eA", 0).size)
        }

    @Test
    fun `removeConnection drops the connection`() =
        runTest {
            val pub = publisher()
            val out = Channel<OutgoingFrame>(Channel.UNLIMITED)
            pub.addSseConnection("c1", "e1", out)
            pub.removeConnection("c1")
            pub.broadcastToAll(err("x"))
            assertTrue(out.tryReceive().isFailure)
            assertEquals(0, pub.getActiveConnectionCount())
        }

    @Test
    fun `broadcast to a closed-channel connection deregisters it`() =
        runTest {
            val pub = NotificationPublisher(SseConnectionRegistry(), NotificationEventLog())
            val out = Channel<OutgoingFrame>(Channel.UNLIMITED)
            pub.addSseConnection("c1", "e1", out)
            out.close() // simulate a dead/half-open client whose writer channel is gone
            pub.broadcastToAll(NotificationDto.WebSocketErrorDto(timestamp = "t", errorCode = "E", message = "x"))
            assertEquals(0, pub.getActiveConnectionCount())
        }

    @Test
    fun `two order stations sharing the terminal employee keep both streams`() =
        runTest {
            val pub = publisher()
            val stationA = Channel<OutgoingFrame>(Channel.UNLIMITED)
            val stationB = Channel<OutgoingFrame>(Channel.UNLIMITED)
            pub.addSseConnection("sA", "terminal-device", stationA, deviceId = "station-a", isStation = true)
            pub.addSseConnection("sB", "terminal-device", stationB, deviceId = "station-b", isStation = true)
            assertEquals(2, pub.getActiveConnectionCount())
            assertFalse(stationA.isClosedForSend, "station B must not evict station A")
        }

    @Test
    fun `a station reconnecting from the same device replaces its own older stream`() =
        runTest {
            val pub = publisher()
            val older = Channel<OutgoingFrame>(Channel.UNLIMITED)
            val newer = Channel<OutgoingFrame>(Channel.UNLIMITED)
            pub.addSseConnection("s1", "terminal-device", older, deviceId = "station-a", isStation = true)
            pub.addSseConnection("s2", "terminal-device", newer, deviceId = "station-a", isStation = true)
            assertEquals(1, pub.getActiveConnectionCount())
            assertTrue(older.isClosedForSend)
            assertFalse(newer.isClosedForSend)
        }

    @Test
    fun `one employee logged in on two devices keeps both streams`() =
        runTest {
            // F21 regression. Superseding used to be employee-wide for every stream that was not a
            // station's, so two tills logged in as the same admin evicted each other: the evicted
            // client reconnected after its 1000 ms backoff and closed the stream that had just closed
            // it, forever. Measured on real devices: 62 streams in 80 seconds, strictly alternating.
            val pub = publisher()
            val till = Channel<OutgoingFrame>(Channel.UNLIMITED)
            val tablet = Channel<OutgoingFrame>(Channel.UNLIMITED)
            pub.addSseConnection("c1", "e1", till, deviceId = "till-1")
            pub.addSseConnection("c2", "e1", tablet, deviceId = "till-2")
            assertEquals(2, pub.getActiveConnectionCount())
            assertFalse(till.isClosedForSend, "a second device of the same employee must not evict the first")
            assertFalse(tablet.isClosedForSend)
        }

    @Test
    fun `an employee reconnecting from the same device replaces their own stale stream`() =
        runTest {
            // The case superseding exists for: a network blip leaves the previous stream registered
            // server-side, and this client's reconnect is what has to take it back out.
            val pub = publisher()
            val stale = Channel<OutgoingFrame>(Channel.UNLIMITED)
            val fresh = Channel<OutgoingFrame>(Channel.UNLIMITED)
            pub.addSseConnection("c1", "e1", stale, deviceId = "till-1")
            pub.addSseConnection("c2", "e1", fresh, deviceId = "till-1")
            assertEquals(1, pub.getActiveConnectionCount())
            assertTrue(stale.isClosedForSend)
            assertFalse(fresh.isClosedForSend)
        }

    @Test
    fun `a stream with no device id falls back to employee-wide supersede`() =
        runTest {
            // There is nothing to narrow on, so the employee-wide behaviour is all that is left.
            val pub = publisher()
            val onDevice = Channel<OutgoingFrame>(Channel.UNLIMITED)
            val deviceless = Channel<OutgoingFrame>(Channel.UNLIMITED)
            pub.addSseConnection("c1", "e1", onDevice, deviceId = "till-1")
            pub.addSseConnection("c2", "e1", deviceless, deviceId = null)
            assertEquals(1, pub.getActiveConnectionCount())
            assertTrue(onDevice.isClosedForSend, "a null device id on the new stream must fall back to evicting every stream of the employee")
        }

    @Test
    fun `sendToEmployee reaches every station stream of the shared employee`() =
        runTest {
            val pub = publisher()
            val stationA = Channel<OutgoingFrame>(Channel.UNLIMITED)
            val stationB = Channel<OutgoingFrame>(Channel.UNLIMITED)
            pub.addSseConnection("sA", "terminal-device", stationA, deviceId = "station-a", isStation = true)
            pub.addSseConnection("sB", "terminal-device", stationB, deviceId = "station-b", isStation = true)
            pub.sendToEmployee("terminal-device", err("to-all-stations"))
            assertTrue(stationA.tryReceive().getOrNull() is OutgoingFrame.Event)
            assertTrue(stationB.tryReceive().getOrNull() is OutgoingFrame.Event)
        }

    @Test
    fun `disconnecting the shared employee closes every station stream`() =
        runTest {
            // Revoking the device account (deactivation, credential reset) must stop every station.
            val pub = publisher()
            val stationA = Channel<OutgoingFrame>(Channel.UNLIMITED)
            val stationB = Channel<OutgoingFrame>(Channel.UNLIMITED)
            pub.addSseConnection("sA", "terminal-device", stationA, deviceId = "station-a", isStation = true)
            pub.addSseConnection("sB", "terminal-device", stationB, deviceId = "station-b", isStation = true)
            assertEquals(2, pub.disconnectEmployee("terminal-device"))
            assertTrue(stationA.isClosedForSend)
            assertTrue(stationB.isClosedForSend)
            assertEquals(0, pub.getActiveConnectionCount())
        }

    @Test
    fun `removing one of two station streams for a shared employee leaves the employee registered`() =
        runTest {
            val pub = publisher()
            val employeeId = "shared-emp-supersede-test"
            val before = AuthNotificationManager.getStats()["registeredEmployees"] as Int
            val stationA = Channel<OutgoingFrame>(Channel.UNLIMITED)
            val stationB = Channel<OutgoingFrame>(Channel.UNLIMITED)
            pub.addSseConnection("sA", employeeId, stationA, deviceId = "station-a", isStation = true, employeeRole = EmployeeRole.CASHIER)
            pub.addSseConnection("sB", employeeId, stationB, deviceId = "station-b", isStation = true, employeeRole = EmployeeRole.CASHIER)
            assertEquals(before + 1, AuthNotificationManager.getStats()["registeredEmployees"])

            pub.removeConnection("sA")
            assertEquals(
                before + 1,
                AuthNotificationManager.getStats()["registeredEmployees"],
                "sibling station B is still connected; the employee must stay registered",
            )

            pub.removeConnection("sB")
            assertEquals(
                before,
                AuthNotificationManager.getStats()["registeredEmployees"],
                "the last stream closing does unregister the employee",
            )
        }

    @Test
    fun `a station connecting with a blank device id falls back to employee-wide supersede`() =
        runTest {
            val pub = publisher()
            val real = Channel<OutgoingFrame>(Channel.UNLIMITED)
            val blank = Channel<OutgoingFrame>(Channel.UNLIMITED)
            pub.addSseConnection("s1", "terminal-device", real, deviceId = "station-a", isStation = true)
            pub.addSseConnection("s2", "terminal-device", blank, deviceId = "", isStation = true)
            assertEquals(1, pub.getActiveConnectionCount())
            assertTrue(real.isClosedForSend, "a blank device id on the new stream must fall back to evicting every stream of the employee")
        }

    @Test
    fun `re-registering a keyed onConnect listener replaces the previous registration`() =
        runTest {
            // HttpClientModule registers the TSE status listener at startup and after every provider
            // hot-swap; appending meant one more duplicate status frame per new stream per TSE change.
            val pub = publisher()
            var calls = 0
            repeat(3) {
                pub.replaceOnConnectListeners("tse-status") { pub.onConnect { calls++ } }
            }
            pub.addSseConnection("c1", "e1", Channel<OutgoingFrame>(Channel.UNLIMITED))
            assertEquals(1, calls)
        }

    @Test
    fun `a keyed registration that added nothing is replaced by one that adds a listener`() =
        runTest {
            // The SDK-less provider logs and registers nothing; a later provider must still end up with one.
            val pub = publisher()
            var calls = 0
            pub.replaceOnConnectListeners("tse-status") { }
            pub.replaceOnConnectListeners("tse-status") { pub.onConnect { calls++ } }
            pub.addSseConnection("c1", "e1", Channel<OutgoingFrame>(Channel.UNLIMITED))
            assertEquals(1, calls)
        }

    @Test
    fun `keyed replacement leaves listeners registered without a key alone`() =
        runTest {
            val pub = publisher()
            var unkeyed = 0
            var keyed = 0
            pub.onConnect { unkeyed++ }
            repeat(2) {
                pub.replaceOnConnectListeners("tse-status") { pub.onConnect { keyed++ } }
            }
            pub.addSseConnection("c1", "e1", Channel<OutgoingFrame>(Channel.UNLIMITED))
            assertEquals(1, unkeyed)
            assertEquals(1, keyed)
        }
}
