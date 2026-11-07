package org.example.project.features.notifications

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.utils.LanguageManager
import org.example.project.features.notifications.presentation.viewmodel.NotificationUiEvent
import org.example.project.features.notifications.presentation.viewmodel.NotificationViewModel
import org.example.project.testutils.fakes.FakeKeyValueStore
import org.example.project.testutils.fakes.FakeNotificationConnection
import org.example.project.testutils.fakes.FakeOverlayEventBus
import org.example.project.testutils.fakes.FakeSyncService
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * An approval that ran on the server changed data the requesting device is looking at, so the
 * device has to be told something happened — a toast is not enough.
 *
 * E2E-2026-09-22 R3-3: after a manager approved a backdated cash-book entry, the accountant's till
 * kept showing the pre-approval list and balance even after leaving and re-entering the screen;
 * only the refresh icon brought the entry in. `APPROVAL_RESULT` raised an overlay message and
 * nothing else, so no screen could react to it. Same shape as R2-3, which was fixed for the table
 * panel by giving it an event to collect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalCompletedEventTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        // The APPROVAL_RESULT handler localises its message, and `localized()` reads a lateinit
        // registry — without this the handler throws and its own try/catch swallows it, so the
        // test hangs waiting for an event that a crash prevented (see the run that found this).
        TranslationRegistry.initialize(defaultTranslations())
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() = kotlinx.coroutines.Dispatchers.resetMain()

    private fun viewModel(connection: FakeNotificationConnection) =
        NotificationViewModel(
            notificationConnection = connection,
            languageManager = LanguageManager(),
            syncService = FakeSyncService(),
            tableStatusCacheWriter = null,
            overlayEventBus = FakeOverlayEventBus(),
            tokenStore = TokenStore(FakeKeyValueStore()),
        )

    private fun approvalResult(
        operation: String,
        approved: Boolean,
        success: Boolean,
    ) = NotificationDto.AuthorizationNotificationDto(
        timestamp = "2026-09-23T18:00:00",
        notificationType = "APPROVAL_RESULT",
        data =
            Json.encodeToString(
                AuthDto.ApprovalResultNotificationDto.serializer(),
                AuthDto.ApprovalResultNotificationDto(
                    requestId = "REQ-1",
                    operation = operation,
                    approved = approved,
                    success = success,
                    resultMessage = "done",
                ),
            ),
    )

    @Test
    fun `an approved and executed request tells the device which operation ran`() =
        runTest(dispatcher) {
            val connection = FakeNotificationConnection()
            val vm = viewModel(connection)
            // Let the view model's own message collector subscribe first: the transport flow has
            // no replay, so a message emitted before it listens is dropped and the test would
            // hang rather than fail honestly.
            runCurrent()
            // Same for ours — notificationEvents has no replay either.
            val received =
                async {
                    withTimeoutOrNull(5_000) {
                        vm.notificationEvents.first { it is NotificationUiEvent.ApprovalCompleted }
                    }
                }
            runCurrent()

            connection.emitMessage(approvalResult("BACKDATE_CASH_BOOK_ENTRY", approved = true, success = true))
            advanceUntilIdle()

            val event = assertNotNull(received.await(), "the device must learn that the approved action ran")
            assertEquals(
                "BACKDATE_CASH_BOOK_ENTRY",
                (event as NotificationUiEvent.ApprovalCompleted).operation,
                "the screen decides by operation what to reload",
            )
        }

    @Test
    fun `a denied request changes no data, so it raises no completion event`() =
        runTest(dispatcher) {
            val connection = FakeNotificationConnection()
            val vm = viewModel(connection)
            runCurrent()
            val received =
                async {
                    withTimeoutOrNull(1_000) {
                        vm.notificationEvents.first { it is NotificationUiEvent.ApprovalCompleted }
                    }
                }
            runCurrent()

            connection.emitMessage(approvalResult("BACKDATE_CASH_BOOK_ENTRY", approved = false, success = false))
            advanceUntilIdle()

            assertNull(received.await(), "a denial leaves the server data untouched; reloading would only hide the banner")
        }
}
