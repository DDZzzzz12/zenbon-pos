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
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.domain.enums.websocket.DataChangeOperation
import org.example.project.core.presentation.components.notification.DisplayMode
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
import kotlin.test.assertTrue

/**
 * Verifies that a live `data_change` notification applies a TARGETED single-entry
 * delta via [org.example.project.core.data.domain.service.SyncService.applyChangeDelta]
 * and never enqueues a blocking [DisplayMode.DIALOG] loading notification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataChangeHandlerTest {
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
    fun dataChange_appliesSingleEntryDelta_andEnqueuesNoDialog() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            val sync = FakeSyncService()
            val overlayBus = FakeOverlayEventBus()
            val viewModel =
                NotificationViewModel(
                    connection,
                    LanguageManager(),
                    sync,
                    noOpTableStatusCacheWriter(),
                    overlayBus,
                    FakeTokenStore.create(),
                )
            advanceUntilIdle()

            connection.emitMessage(
                NotificationDto.DataChangeNotificationDto(
                    timestamp = "t",
                    entityType = ChangeEntityType.PRODUCT,
                    operation = DataChangeOperation.UPDATED,
                    entityId = "42",
                    seq = 0L,
                ),
            )
            advanceUntilIdle()

            assertEquals(1, sync.appliedDeltas.size, "exactly one delta batch should be applied")
            val entry = sync.appliedDeltas.single().single()
            assertEquals(ChangeEntityType.PRODUCT, entry.entityType)
            assertEquals(LogOperation.UPDATE, entry.operation)
            assertEquals("42", entry.entityId)

            assertTrue(
                overlayBus.history.none {
                    it is org.example.project.core.presentation.components.notification.OverlayEvent.Enqueue &&
                        it.state.displayMode == DisplayMode.DIALOG
                },
                "data_change must not enqueue a blocking DIALOG notification",
            )
        }
}
