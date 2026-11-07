package org.example.project.core.presentation.strings

import org.example.project.core.domain.enums.setup.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Build gate for translation completeness.
 *
 * [TranslationRegistry.get] falls back to `key.id` when a language has no entry for a key, so a
 * string added to one language file and forgotten in the other two renders on screen as the raw
 * identifier — `floor_plan.status_occupied` instead of "Occupied". Nothing failed when that
 * happened: [TranslationRegistry.findMissingTranslations] already detects it, but its only caller
 * (`composeApp`'s `Language.kt`) merely prints a warning at startup, truncated to the first five
 * keys per language, and `adminApp` never calls it at all.
 *
 * These tests turn that existing runtime check into a compile-time-ish gate: adding a key to one
 * `*Strings.kt` without the other two now fails `:platform-api:jvmTest`.
 *
 * **Known blind spot.** [TranslationRegistry.findMissingTranslations] compares each language against
 * the *union* of keys across all languages, so a key declared in a `*Keys.kt` object but translated
 * in **no** language is invisible to it — it never enters the union. Closing that would mean
 * enumerating declared `StringKey` properties by reflection, which is JVM-only and cannot live in
 * `commonTest`. The gate therefore catches the realistic mistake (one language forgotten) rather
 * than every conceivable one.
 */
class TranslationCompletenessTest {
    @Test
    fun everySharedKeyIsTranslatedInEveryLanguage() {
        TranslationRegistry.initialize(defaultTranslations())

        val missing = TranslationRegistry.findMissingTranslations()

        assertTrue(
            missing.isEmpty(),
            buildString {
                append("Some shared string keys are not translated in every language.\n")
                append("A missing entry renders the raw key id on screen instead of text.\n")
                missing.forEach { (language, keys) ->
                    append("\n${language.name} is missing ${keys.size} key(s):\n")
                    keys.sortedBy { it.id }.forEach { append("  ${it.id}\n") }
                }
            },
        )
    }

    @Test
    fun allLanguagesCarryTheSameKeySet() {
        val translations = defaultTranslations()

        // Asserted directly off defaultTranslations() rather than through the registry singleton,
        // so this case still holds if another test in the same JVM re-initialized the registry.
        val english = translations.getValue(Language.ENGLISH).keys
        assertEquals(english, translations.getValue(Language.GERMAN).keys, "German key set differs from English")
        assertEquals(english, translations.getValue(Language.CHINESE).keys, "Chinese key set differs from English")
    }

    @Test
    fun everyLanguageInTheEnumHasATranslationMap() {
        val translations = defaultTranslations()

        // A newly added Language would otherwise produce an app in which every string is a raw id,
        // and findMissingTranslations would report the whole catalogue rather than the real cause.
        Language.entries.forEach { language ->
            assertTrue(
                translations[language]?.isNotEmpty() == true,
                "Language ${language.name} has no translations in defaultTranslations()",
            )
        }
    }
}
