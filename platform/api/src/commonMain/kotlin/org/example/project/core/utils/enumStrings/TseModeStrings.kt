package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * Returns the localized name for this TseMode.
 *
 * @param language The target language for localization
 * @return Localized display name for the TSE mode
 */
fun TseMode.getName(language: Language): String =
    when (this) {
        TseMode.SIMULATION -> getString(EnumKeys.tseModeSimulation, language)
        TseMode.DISABLED -> getString(EnumKeys.tseModeDisabled, language)
        TseMode.NORMAL -> getString(EnumKeys.tseModeNormal, language)
    }

/**
 * A mode as it arrives on the wire (`TseStatusDto.mode`, e.g. "DISABLED") as the operator reads it.
 * An unrecognized value is shown as sent rather than folded into a wrong label.
 */
fun localizedTseModeName(
    mode: String,
    language: Language,
): String = TseMode.entries.firstOrNull { it.name == mode.uppercase() }?.getName(language) ?: mode
