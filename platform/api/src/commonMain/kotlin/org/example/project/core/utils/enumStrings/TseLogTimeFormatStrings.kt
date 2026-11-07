package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * The on-screen name of the TSE's internal log time format (`unixTime`, `utcTime`,
 * `utcTimeWithSeconds`, `generalizedTime`). An unknown value is returned unchanged.
 */
fun tseLogTimeFormatLabel(
    raw: String,
    language: Language,
): String =
    when (raw) {
        "unixTime" -> getString(EnumKeys.tseLogTimeUnix, language)
        "utcTime" -> getString(EnumKeys.tseLogTimeUtc, language)
        "utcTimeWithSeconds" -> getString(EnumKeys.tseLogTimeUtcSeconds, language)
        "generalizedTime" -> getString(EnumKeys.tseLogTimeGeneralized, language)
        else -> raw
    }
