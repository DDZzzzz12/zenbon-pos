package org.example.project.features.scheduler

import org.example.project.core.domain.enums.scheduler.JobStatus
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.core.domain.enums.scheduler.TriggerSource
import org.example.project.core.presentation.strings.keys.SchedulerKeys
import org.example.project.core.presentation.strings.translations.ChineseFeaturesStrings
import org.example.project.core.presentation.strings.translations.EnglishFeaturesStrings
import org.example.project.core.presentation.strings.translations.GermanFeaturesStrings
import org.example.project.features.scheduler.presentation.components.formatJobDuration
import org.example.project.features.scheduler.presentation.components.jobTypeKey
import org.example.project.features.scheduler.presentation.components.statusKey
import org.example.project.features.scheduler.presentation.components.triggerKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SchedulerLabelsTest {
    private val languages =
        listOf(EnglishFeaturesStrings.all, GermanFeaturesStrings.all, ChineseFeaturesStrings.all)

    @Test
    fun durationUnderASecondIsNotZero() {
        assertEquals("< 1 s", formatJobDuration(0))
        assertEquals("< 1 s", formatJobDuration(999))
    }

    @Test
    fun durationInSecondsAndMinutes() {
        assertEquals("1 s", formatJobDuration(1000))
        assertEquals("42 s", formatJobDuration(42_500))
        assertEquals("1 min 5 s", formatJobDuration(65_000))
        assertEquals("2 min", formatJobDuration(120_000))
    }

    @Test
    fun everyJobTypeStatusAndTriggerHasAKeyWithAllThreeTranslations() {
        val keys =
            ScheduledJobType.entries.map(::jobTypeKey) +
                JobStatus.entries.map(::statusKey) +
                TriggerSource.entries.map(::triggerKey) +
                listOf(SchedulerKeys.stateActive, SchedulerKeys.stateInactive)
        assertEquals(keys.size, keys.toSet().size, "keys must be distinct")
        for (language in languages) {
            for (key in keys) assertNotNull(language[key], "missing translation for ${key.id}")
        }
    }

    @Test
    fun triggerSourcesReadAsWordsNotEnumNames() {
        assertEquals("Manual", EnglishFeaturesStrings.all[triggerKey(TriggerSource.MANUAL)])
        assertEquals("Geplant", GermanFeaturesStrings.all[triggerKey(TriggerSource.SCHEDULED)])
    }
}
