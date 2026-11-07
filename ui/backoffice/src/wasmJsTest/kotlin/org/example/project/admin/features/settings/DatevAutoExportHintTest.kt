package org.example.project.admin.features.settings

import org.example.project.core.domain.enums.setting.DatevAutoCadence.MONTHLY
import org.example.project.core.domain.enums.setting.DatevAutoCadence.OFF
import org.example.project.core.domain.enums.setting.DatevAutoCadence.WEEKLY
import org.example.project.ui.screens.settings.components.datevAutoExportHasNoCadence
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatevAutoExportHintTest {
    @Test
    fun hint_shown_when_enabled_and_every_cadence_is_off() {
        assertTrue(datevAutoExportHasNoCadence(true, OFF, OFF, OFF))
    }

    @Test
    fun hint_hidden_when_any_cadence_is_chosen() {
        assertFalse(datevAutoExportHasNoCadence(true, WEEKLY, OFF, OFF))
        assertFalse(datevAutoExportHasNoCadence(true, OFF, OFF, MONTHLY))
    }

    @Test
    fun hint_hidden_when_switch_is_off() {
        assertFalse(datevAutoExportHasNoCadence(false, OFF, OFF, OFF))
    }
}
