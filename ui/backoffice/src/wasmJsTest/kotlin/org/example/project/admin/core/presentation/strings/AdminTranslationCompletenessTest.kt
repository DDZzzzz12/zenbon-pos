package org.example.project.admin.core.presentation.strings

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.keys.CommonKeys
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The adminApp half of the translation build gate.
 *
 * `shared`'s `TranslationCompletenessTest` can only see the keys in [defaultTranslations]. adminApp
 * merges roughly 600 more from `AdminStringKeys` on top in [AdminTranslations.init], and those are
 * invisible to the shared test — so a key added to `EnglishAdminStrings` and forgotten in the German
 * and Chinese ones would slip through and render its raw id (e.g. `admin.staff.title`) in the panel.
 *
 * This asserts the *merged* map that the running panel actually uses.
 */
class AdminTranslationCompletenessTest {
    @Test
    fun everyAdminKeyIsTranslatedInEveryLanguage() {
        AdminTranslations.init()

        val missing = TranslationRegistry.findMissingTranslations()

        assertTrue(
            missing.isEmpty(),
            buildString {
                append("Some admin string keys are not translated in every language.\n")
                append("A missing entry renders the raw key id on screen instead of text.\n")
                missing.forEach { (language, keys) ->
                    append("\n${language.name} is missing ${keys.size} key(s):\n")
                    keys.sortedBy { it.id }.forEach { append("  ${it.id}\n") }
                }
            },
        )
    }

    @Test
    fun theMergedMapCoversEveryLanguageAndKeepsTheSharedKeys() {
        AdminTranslations.init()

        // Every language must resolve both a shared key and an admin key. Reading through the
        // registry rather than the maps is what proves the merge itself: `get` falls back to the
        // key id, so a dropped half of the merge surfaces here as an id-shaped string.
        Language.entries.forEach { language ->
            assertNotEquals(
                CommonKeys.save.id,
                TranslationRegistry.get(CommonKeys.save, language),
                "${language.name} lost the shared translations in the admin merge",
            )
            assertNotEquals(
                AdminStringKeys.FloorPlanTitle.id,
                TranslationRegistry.get(AdminStringKeys.FloorPlanTitle, language),
                "${language.name} lost the admin translations in the merge",
            )
        }
    }
}
