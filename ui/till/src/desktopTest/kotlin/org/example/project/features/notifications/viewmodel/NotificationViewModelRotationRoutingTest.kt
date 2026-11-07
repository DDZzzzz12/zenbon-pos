package org.example.project.features.notifications.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.domain.dto.rotation.RotationDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.utils.LanguageManager
import org.example.project.features.notifications.presentation.viewmodel.NotificationViewModel
import org.example.project.features.rotation.RotationNextStore
import org.example.project.testutils.fakes.FakeNotificationConnection
import org.example.project.testutils.fakes.FakeOverlayEventBus
import org.example.project.testutils.fakes.FakeSyncService
import org.example.project.testutils.fakes.FakeTokenStore
import org.example.project.testutils.fakes.noOpTableStatusCacheWriter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationViewModelRotationRoutingTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun rotationNextUpdate_reachesTheSharedStore() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            val store = RotationNextStore()
            NotificationViewModel(
                connection,
                LanguageManager(),
                FakeSyncService(),
                noOpTableStatusCacheWriter(),
                FakeOverlayEventBus(),
                FakeTokenStore.create(),
                rotationNextStore = store,
            )
            advanceUntilIdle()

            val ref = RotationDto.EmployeeRefDto(employeeId = "e1", employeeNumber = "101", displayName = "Anna")
            connection.emitMessage(
                NotificationDto.RotationNextUpdateDto(timestamp = "t", version = 1, next = ref),
            )
            advanceUntilIdle()

            assertEquals(ref, store.next.value)
        }
}
