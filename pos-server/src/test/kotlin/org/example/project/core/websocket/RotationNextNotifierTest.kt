package org.example.project.core.websocket

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.features.employee.data.repository.EmployeeInfoRepositoryImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.data.repository.PermissionGroupRepositoryImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.data.service.RotationAssignmentServiceImpl
import org.example.project.features.order.data.service.RotationNextNotifier
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The notifier is a process-wide object, so every assertion is relative to a baseline snapshot. */
class RotationNextNotifierTest {
    @AfterEach
    fun tearDown() = NotificationHub.resetForTest()

    private fun install() =
        RotationNextNotifier.install(
            RotationAssignmentServiceImpl(
                employeeRepository = EmployeeRepositoryImpl(),
                configManager = ShopConfigurationManager,
                permissionGroups = PermissionGroupRepositoryImpl(),
                employeeInfoRepository = EmployeeInfoRepositoryImpl(),
            ),
        )

    private fun seedPoolEmployee(number: String) {
        val emp = TestData.createCashier(employeeNumber = number, username = "u_$number")
        transaction { Employee.findById(emp.id)!!.inRotationPool = true }
    }

    private suspend fun observe(): Channel<OutgoingFrame> {
        val out = Channel<OutgoingFrame>(Channel.UNLIMITED)
        NotificationHub.addSseConnection(connectionId = "obs", employeeId = "obs", outgoing = out)
        return out
    }

    private fun Channel<OutgoingFrame>.drain(): List<NotificationDto.RotationNextUpdateDto> {
        val updates = mutableListOf<NotificationDto.RotationNextUpdateDto>()
        while (true) {
            val frame = tryReceive().getOrNull() ?: break
            val msg = (frame as? OutgoingFrame.Event)?.event?.message
            if (msg is NotificationDto.RotationNextUpdateDto) updates += msg
        }
        return updates
    }

    @Test
    fun publishBroadcastsCurrentNextWithIncreasingVersion() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                seedPoolEmployee("001")
                runBlocking { TestData.setRoundRobinAssignment(true) }
                install()
                runBlocking {
                    val out = observe()
                    val base = RotationNextNotifier.snapshot().version
                    RotationNextNotifier.publishCurrent()
                    RotationNextNotifier.publishCurrent()
                    val got = out.drain()
                    assertEquals(2, got.size)
                    assertEquals(base + 1, got[0].version)
                    assertEquals(base + 2, got[1].version)
                    assertEquals("001", got[0].next?.employeeNumber)
                }
            },
        ) { _ -> }

    @Test
    fun publishWithToggleOffBroadcastsNullNext() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                seedPoolEmployee("001")
                runBlocking { TestData.setRoundRobinAssignment(false) }
                install()
                runBlocking {
                    val out = observe()
                    RotationNextNotifier.publishCurrent()
                    val got = out.drain()
                    assertEquals(1, got.size)
                    assertNull(got[0].next)
                }
            },
        ) { _ -> }

    @Test
    fun concurrentPublishesBroadcastInVersionOrder() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                seedPoolEmployee("001")
                runBlocking { TestData.setRoundRobinAssignment(true) }
                install()
                runBlocking {
                    val out = observe()
                    coroutineScope {
                        (1..20).map { async { RotationNextNotifier.publishCurrent() } }.awaitAll()
                    }
                    val versions = out.drain().map { it.version }
                    assertEquals(20, versions.size)
                    assertEquals(versions.sorted(), versions)
                    assertEquals(20, versions.toSet().size)
                }
            },
        ) { _ -> }

    @Test
    fun versionIsSeededFromTheClockSoItSurvivesARestart() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                install()
                runBlocking {
                    // A counter starting at 0 would make every post-restart event look older than what a
                    // client already holds. Seeded from boot time, it is past any 2020 epoch millisecond.
                    val version = RotationNextNotifier.snapshot().version
                    assertTrue(version > EPOCH_2020_MILLIS, "version $version was not seeded from the clock")
                }
            },
        ) { _ -> }

    @Test
    fun snapshotDoesNotBumpVersion() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                seedPoolEmployee("001")
                runBlocking { TestData.setRoundRobinAssignment(true) }
                install()
                runBlocking {
                    val a = RotationNextNotifier.snapshot()
                    val b = RotationNextNotifier.snapshot()
                    assertEquals(a.version, b.version)
                    assertNotNull(a.next)
                }
            },
        ) { _ -> }

    @Test
    fun snapshotAndPublishAgree() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                seedPoolEmployee("001")
                seedPoolEmployee("002")
                runBlocking { TestData.setRoundRobinAssignment(true) }
                install()
                runBlocking {
                    val out = observe()
                    RotationNextNotifier.publishCurrent()
                    val last = out.drain().last()
                    val snap = RotationNextNotifier.snapshot()
                    assertEquals(last.version, snap.version)
                    assertEquals(last.next, snap.next)
                    assertFalse(last.isSnapshot)
                }
            },
        ) { _ -> }

    private companion object {
        const val EPOCH_2020_MILLIS = 1_577_836_800_000L
    }
}
