package org.example.project.core.utils

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.NotificationKeys
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * E-3 (emulator pass 2026-09-27): after a crash Android restarted the sticky
 * `PosNotificationService` in a fresh process with no Activity, so the Activity's
 * `LanguageProvider` never ran and the service's foreground notification crashed on the
 * uninitialized `lateinit` registry. `initializeAppTranslations()` is what `PosApplication` and the
 * service now call; it must make the service's lookups work and must be safe to call repeatedly.
 */
class InitializeAppTranslationsTest {
    @AfterTest
    fun restore() = TranslationRegistry.initialize(defaultTranslations())

    @Test
    fun `the service's notification text resolves after the app-level init, without any Compose tree`() {
        initializeAppTranslations()

        assertTrue(TranslationRegistry.isInitialized)
        val title = getString(NotificationKeys.systemConnectionActive, Language.GERMAN)
        assertNotEquals(NotificationKeys.systemConnectionActive.id, title, "resolved to the raw key id")
    }

    @Test
    fun `a second call is a no-op and does not replace maps already installed`() {
        initializeAppTranslations()
        val probe = StringKey("init_test.probe")
        TranslationRegistry.initialize(mapOf(Language.GERMAN to mapOf(probe to "installed")))

        initializeAppTranslations()

        assertEquals("installed", TranslationRegistry.get(probe, Language.GERMAN))
    }
}
