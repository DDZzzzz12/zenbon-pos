package org.example.project.ui.screens.settings.components

import org.example.project.core.domain.enums.setting.DatevAutoCadence

/**
 * True when the master switch is on but all three cadences are OFF. That is by design (the job is
 * inactive without a cadence) but reads as "enabled" on screen, so the section explains it.
 */
fun datevAutoExportHasNoCadence(
    enabled: Boolean,
    kassenbuch: DatevAutoCadence,
    apLedger: DatevAutoCadence,
    buchungsstapel: DatevAutoCadence,
): Boolean = enabled && listOf(kassenbuch, apLedger, buchungsstapel).all { it == DatevAutoCadence.OFF }
