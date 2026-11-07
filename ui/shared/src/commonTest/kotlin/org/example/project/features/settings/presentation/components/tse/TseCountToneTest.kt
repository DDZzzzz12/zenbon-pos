package org.example.project.features.settings.presentation.components.tse

import kotlin.test.Test
import kotlin.test.assertEquals

class TseCountToneTest {
    @Test
    fun failedZeroIsNeutral() {
        assertEquals(TseCountTone.Neutral, tseCountTone(failure = true, count = 0))
    }

    @Test
    fun failedPositiveIsAlert() {
        assertEquals(TseCountTone.Alert, tseCountTone(failure = true, count = 3))
    }

    @Test
    fun nonFailureCountIsNeutral() {
        assertEquals(TseCountTone.Neutral, tseCountTone(failure = false, count = 5))
    }
}
