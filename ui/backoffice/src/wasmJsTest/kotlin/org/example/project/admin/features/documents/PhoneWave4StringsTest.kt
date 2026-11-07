package org.example.project.admin.features.documents

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.presentation.strings.translations.ChineseAdminStrings
import org.example.project.admin.core.presentation.strings.translations.EnglishAdminStrings
import org.example.project.admin.core.presentation.strings.translations.GermanAdminStrings
import kotlin.test.Test
import kotlin.test.assertTrue

/** An untranslated key renders as its id on screen, with nothing failing at compile time. */
class PhoneWave4StringsTest {
    private val keys =
        listOf(
            AdminStringKeys.PhonePanePdf,
            AdminStringKeys.PhonePaneData,
            AdminStringKeys.DocumentOpenInTab,
            AdminStringKeys.FloorPlanZoomIn,
            AdminStringKeys.FloorPlanZoomOut,
            AdminStringKeys.FloorPlanZoomFit,
            AdminStringKeys.FloorPlanSelection,
        )

    @Test
    fun `every wave 4 key is translated in all three languages`() {
        val missing =
            keys.flatMap { key ->
                listOf(
                    "de" to GermanAdminStrings.strings[key],
                    "en" to EnglishAdminStrings.strings[key],
                    "zh" to ChineseAdminStrings.strings[key],
                ).filter { it.second.isNullOrBlank() }.map { "${it.first}: ${key.id}" }
            }
        assertTrue(missing.isEmpty(), "Untranslated wave 4 keys: $missing")
    }
}
