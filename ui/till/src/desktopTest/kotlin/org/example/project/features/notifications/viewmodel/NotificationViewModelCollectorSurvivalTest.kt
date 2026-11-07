package org.example.project.features.notifications.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.translations.ChineseCommonStrings
import org.example.project.core.presentation.strings.translations.ChineseDashboardStrings
import org.example.project.core.presentation.strings.translations.ChineseFeaturesStrings
import org.example.project.core.presentation.strings.translations.ChineseRulesStrings
import org.example.project.core.presentation.strings.translations.ChineseSettingsStrings
import org.example.project.core.presentation.strings.translations.EnglishCommonStrings
import org.example.project.core.presentation.strings.translations.EnglishDashboardStrings
import org.example.project.core.presentation.strings.translations.EnglishFeaturesStrings
import org.example.project.core.presentation.strings.translations.EnglishRulesStrings
import org.example.project.core.presentation.strings.translations.EnglishSettingsStrings
import org.example.project.core.presentation.strings.translations.GermanCommonStrings
import org.example.project.core.presentation.strings.translations.GermanDashboardStrings
import org.example.project.core.presentation.strings.translations.GermanFeaturesStrings
import org.example.project.core.presentation.strings.translations.GermanRulesStrings
import org.example.project.core.presentation.strings.translations.GermanSettingsStrings
import org.example.project.core.utils.LanguageManager
import org.example.project.features.notifications.presentation.viewmodel.NotificationUiEvent
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

/**
 * Verifies that the [NotificationViewModel] message collector loop stays alive
 * across a sequence of consecutive incoming messages.
 *
 * `observeMessages()` wraps each `handleWebSocketMessage(...)` call in a try/catch
 * (defensive) so one bad event can never cancel the `messages.collect { ... }` loop.
 * No *current* message can trigger a synchronous handler throw, however: every
 * handler either dispatches its work via `viewModelScope.launch { ... }` (a
 * SupervisorJob child whose failure is isolated) or guards its body with an internal
 * try/catch, and `LanguageManager` is a final class whose lookups never throw. So
 * this test cannot assert "survival after a synchronous throw" — instead it verifies
 * the strictly weaker but honest property of **collector-loop liveness**: when N
 * valid messages are emitted in sequence, the collector processes *all* of them and
 * their per-message effects all occur (here, one [NotificationUiEvent.TableStatusChanged]
 * per [NotificationDto.TableStatusUpdateDto]).
 *
 * Production code is deliberately NOT modified to make a handler throwable — that
 * would be over-engineering for the sake of a test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationViewModelCollectorSurvivalTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // The handlers resolve localized strings via TranslationRegistry, which is
        // normally initialized by LanguageProvider in the composition. Initialize it
        // here so handleTableStatusUpdate can emit its UI event instead of throwing.
        TranslationRegistry.initialize(
            mapOf(
                Language.GERMAN to GermanCommonStrings.all + GermanDashboardStrings.all +
                    GermanSettingsStrings.all + GermanFeaturesStrings.all + GermanRulesStrings.all,
                Language.ENGLISH to EnglishCommonStrings.all + EnglishDashboardStrings.all +
                    EnglishSettingsStrings.all + EnglishFeaturesStrings.all + EnglishRulesStrings.all,
                Language.CHINESE to ChineseCommonStrings.all + ChineseDashboardStrings.all +
                    ChineseSettingsStrings.all + ChineseFeaturesStrings.all + ChineseRulesStrings.all,
            ),
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun collectorLoop_survivesAcrossConsecutiveMessages() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            val viewModel =
                NotificationViewModel(
                    connection,
                    LanguageManager(),
                    FakeSyncService(),
                    noOpTableStatusCacheWriter(),
                    FakeOverlayEventBus(),
                    FakeTokenStore.create(),
                )

            // Let init() subscribe its collectors (observeMessages, observeErrors, etc.).
            advanceUntilIdle()

            val received = mutableListOf<NotificationUiEvent>()
            val collectJob =
                launch {
                    viewModel.notificationEvents.toList(received)
                }
            advanceUntilIdle()

            val count = 5
            repeat(count) { i ->
                connection.emitMessage(
                    NotificationDto.TableStatusUpdateDto(
                        timestamp = "t$i",
                        tableId = i.toLong(),
                        tableNumber = "T$i",
                        status = TableStatus.OCCUPIED.name,
                    ),
                )
                // Drain after each emit so the collector is proven to advance message-by-message.
                advanceUntilIdle()
            }

            collectJob.cancel()

            val tableEvents = received.filterIsInstance<NotificationUiEvent.TableStatusChanged>()
            assertEquals(
                count,
                tableEvents.size,
                "collector loop must process every emitted message, not stop after the first",
            )
            assertEquals(
                (0 until count).map { it.toLong() },
                tableEvents.map { it.tableId },
                "each emitted table-status message must produce its own UI event in order",
            )
        }
}
