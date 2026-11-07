package org.example.project.features.notifications.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.tse.TseHealthState
import org.example.project.core.network.NotificationConnection
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.NotificationState
import org.example.project.core.presentation.components.notification.OverlayEvent
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.NotificationKeys
import org.example.project.core.utils.LanguageManager
import org.example.project.features.notifications.presentation.state.TseConnectionState
import org.example.project.features.notifications.presentation.viewmodel.NotificationViewModel
import org.example.project.testutils.fakes.FakeNotificationConnection
import org.example.project.testutils.fakes.FakeOverlayEventBus
import org.example.project.testutils.fakes.FakeSyncService
import org.example.project.testutils.fakes.FakeTokenStore
import org.example.project.testutils.fakes.noOpTableStatusCacheWriter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TSE status reaches a NotificationViewModel that subscribes after the server's push.
 *
 * The server sends TSE status only on change and once per new stream, and a new view model is created
 * on every MainScreen mount while the process-wide stream stays open — so the footer read
 * "TSE Unbekannt" until the next change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationViewModelTseStatusTest {
    private val testDispatcher = StandardTestDispatcher()
    private val languageManager = LanguageManager()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        TranslationRegistry.initialize(defaultTranslations())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun status(
        mode: String,
        connected: Boolean = true,
        health: TseHealthState = TseHealthState.HEALTHY,
        daysUntilCertExpiry: Long? = null,
        firmwareUpdateAvailable: Boolean = false,
    ) = NotificationDto.TseStatusUpdateDto(
        timestamp = "2026-09-14T10:00:00Z",
        connected = connected,
        healthy = health == TseHealthState.HEALTHY,
        health = health,
        mode = mode,
        daysUntilCertExpiry = daysUntilCertExpiry,
        firmwareUpdateAvailable = firmwareUpdateAvailable,
    )

    private fun viewModel(
        connection: NotificationConnection,
        bus: FakeOverlayEventBus,
        tokenStore: TokenStore = FakeTokenStore.create(),
    ) = NotificationViewModel(connection, languageManager, FakeSyncService(), noOpTableStatusCacheWriter(), bus, tokenStore)

    /** The overlays [bus] was asked to show whose message is [text]. */
    private fun shownWithMessage(
        bus: FakeOverlayEventBus,
        text: String,
    ): List<NotificationState> =
        bus.history
            .filterIsInstance<OverlayEvent.Enqueue>()
            .map { it.state }
            .filter { it.message == text }

    private val language get() = languageManager.currentLanguage.value

    /** The "TSE deactivated" warnings [bus] was asked to show, in the language the view model uses. */
    private fun disabledWarnings(bus: FakeOverlayEventBus) = shownWithMessage(bus, getString(NotificationKeys.tseDisabledWarning, language))

    private fun simulationWarnings(bus: FakeOverlayEventBus) = shownWithMessage(bus, getString(NotificationKeys.tseSimulationWarning, language))

    private fun certificateWarnings(
        bus: FakeOverlayEventBus,
        daysLeft: Long,
    ) = shownWithMessage(bus, getString(NotificationKeys.tseCertExpiryWarning, language, daysLeft))

    private fun firmwareWarnings(bus: FakeOverlayEventBus) = shownWithMessage(bus, getString(NotificationKeys.tseFirmwareUpdateMessage, language))

    private fun connectedToasts(bus: FakeOverlayEventBus) = shownWithMessage(bus, getString(NotificationKeys.tseConnected, language))

    private fun station() = FakeTokenStore.create().apply { stationRole = "ORDER_STATION" }

    @Test
    fun aViewModelCreatedAfterTheStatusArrived_seedsTheFooterFromTheConnection() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            // Delivered to an earlier consumer: nobody is subscribed now, so the flow drops it.
            connection.emitMessage(status("NORMAL"))

            val viewModel = viewModel(connection, FakeOverlayEventBus())
            advanceUntilIdle()

            assertEquals(TseConnectionState.Connected, viewModel.uiState.value.tseConnectionState)
        }

    @Test
    fun seedingIsSilent_noOverlayForAStatusAnEarlierConsumerAlreadyAnnounced() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            connection.emitMessage(status("SIMULATION"))
            val bus = FakeOverlayEventBus()

            viewModel(connection, bus)
            advanceUntilIdle()

            assertTrue(bus.history.filterIsInstance<OverlayEvent.Enqueue>().isEmpty(), "seeding must not replay warnings: ${bus.history}")
        }

    @Test
    fun aStatusArrivingAfterConstruction_updatesTheFooter() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()

            val viewModel = viewModel(connection, FakeOverlayEventBus())
            // observeMessages()'s launch is only queued at construction time under this dispatcher;
            // run it now so the collector is actually subscribed before we emit.
            runCurrent()

            connection.emitMessage(status("NORMAL"))
            advanceUntilIdle()

            // Confirms live updates still reach the footer after the seed is in place. The
            // subscribe-before-seed-read production ordering itself is pinned below, where an
            // Unconfined dispatcher lets the launch run inline the way Dispatchers.Main.immediate
            // does in production.
            assertEquals(TseConnectionState.Connected, viewModel.uiState.value.tseConnectionState)
        }

    /**
     * A [NotificationConnection] whose [latestTseStatus] getter reads as null but, as a side effect
     * of being read, emits [statusToDeliver] on [messages] — modelling a status that arrives exactly
     * in the window between subscribing to [messages] and reading the seed snapshot.
     */
    private class SeedReadTriggersLiveStatusConnection(
        private val statusToDeliver: NotificationDto.TseStatusUpdateDto,
    ) : NotificationConnection {
        override val connectionState: StateFlow<NotificationConnection.ConnectionState> =
            MutableStateFlow(NotificationConnection.ConnectionState.Disconnected)

        private val _messages = MutableSharedFlow<NotificationDto.NotificationMessage>(replay = 0, extraBufferCapacity = 64)
        override val messages: SharedFlow<NotificationDto.NotificationMessage> = _messages

        override val errors: SharedFlow<NotificationConnection.NotificationConnectionError> =
            MutableSharedFlow<NotificationConnection.NotificationConnectionError>(replay = 1)

        override val latestTseStatus: StateFlow<NotificationDto.TseStatusUpdateDto?>
            get() {
                _messages.tryEmit(statusToDeliver)
                return MutableStateFlow(null)
            }

        override suspend fun connect(endpoint: String) {}

        override suspend fun disconnect() {}

        override fun closeIfSessionEnded() {}

        override fun resetReconnectAttempts() {}

        override fun requestImmediateReconnect() {}

        override fun clearErrors() {}

        override fun dispose() {}
    }

    @Test
    fun subscribingToMessagesBeforeReadingTheSeed_catchesAStatusThatArrivesInTheGap() =
        runTest {
            // An Unconfined Main dispatcher runs a launched coroutine inline, the way
            // Dispatchers.Main.immediate runs observeMessages() in production — unlike the
            // StandardTestDispatcher above, where the launch is only queued until runCurrent().
            Dispatchers.setMain(UnconfinedTestDispatcher())
            try {
                val connection = SeedReadTriggersLiveStatusConnection(status("NORMAL"))
                val viewModel = viewModel(connection, FakeOverlayEventBus())

                // Subscribe-first catches it: observeMessages() is already collecting by the time
                // the seed read triggers the emission. Seed-first would drop it — nothing would be
                // subscribed to `messages` yet when the emission happened.
                assertEquals(TseConnectionState.Connected, viewModel.uiState.value.tseConnectionState)
            } finally {
                Dispatchers.resetMain()
                Dispatchers.setMain(testDispatcher)
            }
        }

    @Test
    fun aFreshViewModel_firstDisabledStatus_raisesNoModal() =
        runTest(testDispatcher) {
            // Every Main mount, reconnect and station start used to open with this manual-dismiss modal:
            // the server re-sends the status on each new stream and there was no previous state.
            val connection = FakeNotificationConnection()
            val bus = FakeOverlayEventBus()
            viewModel(connection, bus)
            advanceUntilIdle()

            connection.emitMessage(status("DISABLED"))
            advanceUntilIdle()

            assertTrue(disabledWarnings(bus).isEmpty())
        }

    @Test
    fun normalThenDisabled_raisesTheWarningOnce_evenWhenTheStatusIsSentAgain() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            val bus = FakeOverlayEventBus()
            viewModel(connection, bus)
            advanceUntilIdle()

            connection.emitMessage(status("NORMAL"))
            advanceUntilIdle()
            connection.emitMessage(status("DISABLED"))
            advanceUntilIdle()
            // The on-connect status after a reconnect: same mode, not a transition.
            connection.emitMessage(status("DISABLED"))
            advanceUntilIdle()

            val warnings = disabledWarnings(bus)
            assertEquals(1, warnings.size)
            assertEquals(DisplayMode.DIALOG, warnings.single().displayMode)
        }

    @Test
    fun aRemountedViewModel_doesNotRepeatTheWarningThePreviousInstanceShowed() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            val firstBus = FakeOverlayEventBus()
            viewModel(connection, firstBus)
            advanceUntilIdle()
            connection.emitMessage(status("NORMAL"))
            advanceUntilIdle()
            connection.emitMessage(status("DISABLED"))
            advanceUntilIdle()

            // MainScreen re-mounts: a new view model on the same process-wide connection, which then
            // receives the server's on-connect status again.
            val secondBus = FakeOverlayEventBus()
            viewModel(connection, secondBus)
            advanceUntilIdle()
            connection.emitMessage(status("DISABLED"))
            advanceUntilIdle()

            assertEquals(1, disabledWarnings(firstBus).size)
            assertTrue(disabledWarnings(secondBus).isEmpty(), "the new instance seeds DISABLED and stays silent")
        }

    @Test
    fun aStationPrincipal_getsTheDisabledTransitionAsAToast_neverAModal() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            val bus = FakeOverlayEventBus()
            viewModel(connection, bus, tokenStore = station())
            advanceUntilIdle()

            connection.emitMessage(status("NORMAL"))
            advanceUntilIdle()
            connection.emitMessage(status("DISABLED"))
            advanceUntilIdle()

            val warnings = disabledWarnings(bus)
            assertEquals(1, warnings.size)
            assertEquals(DisplayMode.TOAST, warnings.single().displayMode)
        }

    @Test
    fun aLiveStatusThatMapsToUnknown_isStillKnown_soDisabledAfterItWarns() =
        runTest(testDispatcher) {
            // A live message can also map to Unknown. "Never received" and "received Unknown" differ: a change
            // out of the latter into DISABLED is a real transition.
            val connection = FakeNotificationConnection()
            val bus = FakeOverlayEventBus()
            val viewModel = viewModel(connection, bus)
            advanceUntilIdle()

            connection.emitMessage(status("NORMAL", health = TseHealthState.UNREACHABLE))
            advanceUntilIdle()
            assertEquals(TseConnectionState.Unknown, viewModel.uiState.value.tseConnectionState, "fixture must map to Unknown")
            connection.emitMessage(status("DISABLED"))
            advanceUntilIdle()

            val warnings = disabledWarnings(bus)
            assertEquals(1, warnings.size)
            assertEquals(DisplayMode.DIALOG, warnings.single().displayMode)
        }

    @Test
    fun aFreshViewModel_firstSimulationStatus_raisesNoWarning() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            val bus = FakeOverlayEventBus()
            viewModel(connection, bus)
            advanceUntilIdle()

            connection.emitMessage(status("SIMULATION"))
            advanceUntilIdle()

            assertTrue(simulationWarnings(bus).isEmpty())
        }

    @Test
    fun normalThenSimulation_warnsOnce_andNeitherAResentStatusNorARemountRepeatsIt() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            val firstBus = FakeOverlayEventBus()
            viewModel(connection, firstBus)
            advanceUntilIdle()
            connection.emitMessage(status("NORMAL"))
            advanceUntilIdle()
            connection.emitMessage(status("SIMULATION"))
            advanceUntilIdle()
            connection.emitMessage(status("SIMULATION"))
            advanceUntilIdle()

            val secondBus = FakeOverlayEventBus()
            viewModel(connection, secondBus)
            advanceUntilIdle()
            connection.emitMessage(status("SIMULATION"))
            advanceUntilIdle()

            val warnings = simulationWarnings(firstBus)
            assertEquals(1, warnings.size)
            assertEquals(DisplayMode.DIALOG, warnings.single().displayMode)
            assertTrue(simulationWarnings(secondBus).isEmpty(), "the new instance seeds SIMULATION and stays silent")
        }

    @Test
    fun aStationPrincipal_getsTheSimulationTransitionAsAToast() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            val bus = FakeOverlayEventBus()
            viewModel(connection, bus, tokenStore = station())
            advanceUntilIdle()

            connection.emitMessage(status("NORMAL"))
            advanceUntilIdle()
            connection.emitMessage(status("SIMULATION"))
            advanceUntilIdle()

            val warning = simulationWarnings(bus).single()
            assertEquals(DisplayMode.TOAST, warning.displayMode)
            assertEquals(5_000L, warning.autoDismissMs)
        }

    @Test
    fun aCertificateWarningResentAfterAReconnect_isShownOnce() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            val bus = FakeOverlayEventBus()
            viewModel(connection, bus)
            advanceUntilIdle()

            connection.emitMessage(status("NORMAL", daysUntilCertExpiry = 10))
            advanceUntilIdle()
            // The on-connect status after a reconnect carries the same warning.
            connection.emitMessage(status("NORMAL", daysUntilCertExpiry = 10))
            advanceUntilIdle()

            val warnings = certificateWarnings(bus, daysLeft = 10)
            assertEquals(1, warnings.size)
            assertEquals(DisplayMode.DIALOG, warnings.single().displayMode)
        }

    @Test
    fun aCertificateWarningWithNewContent_isShownAgain() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            val bus = FakeOverlayEventBus()
            viewModel(connection, bus)
            advanceUntilIdle()

            connection.emitMessage(status("NORMAL", daysUntilCertExpiry = 10))
            advanceUntilIdle()
            connection.emitMessage(status("NORMAL", daysUntilCertExpiry = 9))
            advanceUntilIdle()

            assertEquals(1, certificateWarnings(bus, daysLeft = 10).size)
            assertEquals(1, certificateWarnings(bus, daysLeft = 9).size)
        }

    @Test
    fun aFirmwareUpdateResentAfterAReconnect_isRaisedAgain() =
        runTest(testDispatcher) {
            // Unlike the certificate warning, not deduplicated: the duty to install the update does not lapse
            // because someone closed the dialog, so every status that carries it raises it.
            val connection = FakeNotificationConnection()
            val bus = FakeOverlayEventBus()
            viewModel(connection, bus)
            advanceUntilIdle()

            connection.emitMessage(status("NORMAL", firmwareUpdateAvailable = true))
            advanceUntilIdle()
            connection.emitMessage(status("NORMAL", firmwareUpdateAvailable = true))
            advanceUntilIdle()

            val warnings = firmwareWarnings(bus)
            assertEquals(2, warnings.size)
            assertTrue(warnings.all { it.displayMode == DisplayMode.DIALOG })
        }

    @Test
    fun aStationPrincipal_getsCertificateAndFirmwareWarningsAsToasts() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            val bus = FakeOverlayEventBus()
            viewModel(connection, bus, tokenStore = station())
            advanceUntilIdle()

            connection.emitMessage(status("NORMAL", daysUntilCertExpiry = 10, firmwareUpdateAvailable = true))
            advanceUntilIdle()

            for (warning in listOf(certificateWarnings(bus, daysLeft = 10).single(), firmwareWarnings(bus).single())) {
                assertEquals(DisplayMode.TOAST, warning.displayMode)
                assertEquals(5_000L, warning.autoDismissMs)
            }
        }

    @Test
    fun theConnectedToast_isShownOnlyOnARealTransitionIntoConnected() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            val bus = FakeOverlayEventBus()
            viewModel(connection, bus)
            advanceUntilIdle()

            connection.emitMessage(status("NORMAL"))
            advanceUntilIdle()
            assertTrue(connectedToasts(bus).isEmpty(), "the first status is this client learning the state, not a change")

            connection.emitMessage(status("NORMAL", connected = false))
            advanceUntilIdle()
            connection.emitMessage(status("NORMAL"))
            advanceUntilIdle()
            // The on-connect status after a reconnect: still Connected.
            connection.emitMessage(status("NORMAL"))
            advanceUntilIdle()

            assertEquals(1, connectedToasts(bus).size)
        }

    @Test
    fun anExpiredCertificateWarningResentAfterAReconnect_isShownOnce() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            val bus = FakeOverlayEventBus()
            viewModel(connection, bus)
            advanceUntilIdle()

            connection.emitMessage(status("NORMAL", daysUntilCertExpiry = 0))
            advanceUntilIdle()
            connection.emitMessage(status("NORMAL", daysUntilCertExpiry = 0))
            advanceUntilIdle()

            val warnings = shownWithMessage(bus, getString(NotificationKeys.tseCertExpiredWarning, language))
            assertEquals(1, warnings.size)
            assertEquals(DisplayMode.DIALOG, warnings.single().displayMode)
        }

    @Test
    fun aRecoveryWithAnAlreadyRaisedCertificateWarning_stillShowsTheConnectedToast() =
        runTest(testDispatcher) {
            // The certificate warning replaces the "TSE connected" toast only when it is actually shown. Once it is
            // deduplicated, Connected → offline → Connected with the same day count must still announce the recovery.
            val connection = FakeNotificationConnection()
            val bus = FakeOverlayEventBus()
            viewModel(connection, bus)
            advanceUntilIdle()

            connection.emitMessage(status("NORMAL", daysUntilCertExpiry = 10))
            advanceUntilIdle()
            connection.emitMessage(status("NORMAL", connected = false, daysUntilCertExpiry = 10))
            advanceUntilIdle()
            connection.emitMessage(status("NORMAL", daysUntilCertExpiry = 10))
            advanceUntilIdle()

            assertEquals(1, certificateWarnings(bus, daysLeft = 10).size)
            assertEquals(1, connectedToasts(bus).size)
        }
}
