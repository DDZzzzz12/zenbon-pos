package org.example.project.features.notifications.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.domain.dto.websocket.NotificationDto
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
 * A notification delivered twice must appear once.
 *
 * Billing requests reach the client over two paths by design: the live SSE push, and
 * `NotificationReconciler`, which replays requests parked server-side on *every* successful
 * connect (the parked copy is only dropped when the table's session ends). Both build the id
 * from the same payload — `billing_request_{tableId}_{timestamp}` — so a reconnect produced two
 * byte-identical ids. The notification list is keyed by id, so the duplicate broke the
 * `LazyColumn` key contract with `IllegalArgumentException: Key "…" was already used`, which
 * escaped composition and blanked the entire till UI until a reload.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationViewModelDuplicateIdTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
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
    fun replayedBillingRequest_isAddedOnlyOnce() =
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
            advanceUntilIdle()

            // The same parked request the reconciler replays after the live push already landed.
            repeat(2) {
                connection.emitMessage(
                    NotificationDto.BillingRequestNotificationDto(
                        timestamp = "2026-08-21T17:43:57.622556Z",
                        tableId = 27L,
                        tableNumber = "1",
                        requestingEmployeeNumber = "A001",
                    ),
                )
                advanceUntilIdle()
            }

            val notifications = viewModel.uiState.value.notifications
            assertEquals(
                1,
                notifications.size,
                "a replayed billing request must not be added twice — duplicate ids break the list key",
            )
            assertEquals(
                1,
                notifications.map { it.id }.distinct().size,
                "notification ids must stay unique",
            )
            assertEquals(
                1,
                viewModel.uiState.value.unreadCount,
                "the unread badge must count the request once, not once per delivery path",
            )
        }
}
