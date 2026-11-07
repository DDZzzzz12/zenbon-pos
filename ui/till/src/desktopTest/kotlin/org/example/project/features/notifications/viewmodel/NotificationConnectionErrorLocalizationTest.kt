package org.example.project.features.notifications.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.network.NotificationConnection
import org.example.project.core.presentation.components.notification.OverlayEvent
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.NotificationKeys
import org.example.project.core.utils.LanguageManager
import org.example.project.features.notifications.presentation.NotificationConnectionErrorKeys
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every notification-stream failure must reach the operator in their own language.
 *
 * A station once showed a modal titled "Authentifizierungsfehler" whose body read
 * "Authentication failed: Unable to refresh access token" — the title came from a StringKey, the
 * body was an English literal built in the SSE transport and carried through the ViewModel as a
 * String. The fix makes the transport report a typed reason instead, so the only way to produce a
 * body is to resolve a key. These tests pin that: the dialog text must equal the translation of
 * the mapped key, and must never contain the developer wording.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationConnectionErrorLocalizationTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        TranslationRegistry.initialize(defaultTranslations())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The overlay text produced for [error], in the default (German) UI language. */
    private fun shownMessageFor(failure: NotificationConnection.NotificationConnectionError): String {
        var shown: String? = null
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            val bus = FakeOverlayEventBus()
            NotificationViewModel(
                connection,
                LanguageManager(),
                FakeSyncService(),
                noOpTableStatusCacheWriter(),
                bus,
                FakeTokenStore.create(),
            )
            advanceUntilIdle()

            connection.emitError(failure)
            advanceUntilIdle()

            val enqueued = bus.history.filterIsInstance<OverlayEvent.Enqueue>()
            assertTrue(enqueued.isNotEmpty(), "the failure must surface to the operator at all")
            shown = enqueued.last().state.message
        }
        return requireNotNull(shown) { "no overlay was enqueued for $failure" }
    }

    @Test
    fun tokenRenewalFailure_showsTheTranslatedText_notDeveloperEnglish() {
        val shown =
            shownMessageFor(
                NotificationConnection.NotificationConnectionError.AuthenticationFailed(
                    NotificationConnection.AuthFailureReason.TOKEN_RENEWAL_FAILED,
                ),
            )

        assertEquals(
            getString(NotificationKeys.authErrorSessionRenewalFailed, Language.GERMAN),
            shown,
            "the body must be the resolved translation, not a String carried up from the transport",
        )
        assertFalse(
            shown.contains("Authentication failed") || shown.contains("refresh access token"),
            "developer English must never appear under a localized title: was \"$shown\"",
        )
    }

    @Test
    fun missingSession_hasItsOwnText() {
        val shown =
            shownMessageFor(
                NotificationConnection.NotificationConnectionError.AuthenticationFailed(
                    NotificationConnection.AuthFailureReason.NO_ACCESS_TOKEN,
                ),
            )

        assertEquals(
            getString(NotificationKeys.authErrorNoSession, Language.GERMAN),
            shown,
            "'no session at all' and 'session could not be renewed' are different situations for the operator",
        )
    }

    @Test
    fun connectionFailures_areLocalizedToo() {
        val lost =
            shownMessageFor(
                NotificationConnection.NotificationConnectionError.ConnectionFailed(
                    NotificationConnection.ConnectionFailureReason.STREAM_LOST,
                ),
            )
        val exhausted =
            shownMessageFor(
                NotificationConnection.NotificationConnectionError.ConnectionFailed(
                    NotificationConnection.ConnectionFailureReason.RETRIES_EXHAUSTED,
                ),
            )

        assertEquals(getString(NotificationKeys.connectionErrorStreamLost, Language.GERMAN), lost)
        assertEquals(getString(NotificationKeys.connectionErrorRetriesExhausted, Language.GERMAN), exhausted)
    }

    @Test
    fun messageLevelFailures_showAGenericLocalizedWarning_notTheExceptionText() {
        val shown =
            shownMessageFor(
                NotificationConnection.NotificationConnectionError.DeserializationFailed(
                    "Unexpected JSON token at offset 42",
                ),
            )

        assertEquals(
            getString(NotificationKeys.messageProcessingError, Language.GERMAN),
            shown,
            "an exception text is a log line, not something to render at the till",
        )
        assertFalse(shown.contains("JSON"), "the raw parser message must not leak into the overlay")
    }

    @Test
    fun everyReasonMapsToADistinctTranslatedKey() {
        val authTexts =
            NotificationConnection.AuthFailureReason.entries.map {
                getString(NotificationConnectionErrorKeys.messageKey(it), Language.GERMAN)
            }
        val connectionTexts =
            NotificationConnection.ConnectionFailureReason.entries.map {
                getString(NotificationConnectionErrorKeys.messageKey(it), Language.GERMAN)
            }

        // getString falls back to the raw key id when a translation is missing; a key id contains
        // a '.' and no spaces, which is what this catches for a reason added without translations.
        (authTexts + connectionTexts).forEach { text ->
            assertTrue(text.contains(' '), "untranslated key rendered as its id: \"$text\"")
        }
        assertEquals(authTexts.size, authTexts.distinct().size, "each auth reason needs its own wording")
        assertEquals(
            connectionTexts.size,
            connectionTexts.distinct().size,
            "each connection reason needs its own wording",
        )
    }
}
