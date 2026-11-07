package org.example.project.features.scheduler.presentation.components

import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.presentation.strings.getString
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * UI review follow-up 5: "Protokolleintragsbereinigung" (28 letters, no break opportunity) broke
 * mid-word in the job card and the history sheet headline on a phone. Every job name must offer a
 * line break at least every [MAX_UNBREAKABLE] characters (a space or a hyphen; a soft hyphen breaks
 * too, but Skia draws no hyphen there).
 */
class JobTypeLabelBreakTest {
    @BeforeTest
    fun initializeTranslations() {
        TranslationRegistry.initialize(defaultTranslations())
    }

    @Test
    fun everyJobNameCanWrapAtAWordOrSyllableBoundary() {
        ScheduledJobType.entries.forEach { type ->
            Language.entries.forEach { language ->
                val label = getString(jobTypeKey(type), language)
                val longest = label.split(' ', '-', '­').maxOf { it.length }
                assertTrue(longest <= MAX_UNBREAKABLE, "$type in $language: '$label' has a $longest-character run without a break")
            }
        }
    }

    private companion object {
        const val MAX_UNBREAKABLE = 20
    }
}
