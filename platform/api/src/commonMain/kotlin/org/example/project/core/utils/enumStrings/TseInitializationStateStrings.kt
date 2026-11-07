package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * The on-screen name of a Swissbit `WormInitializationState` as the server sends it
 * (`WORM_INIT_INITIALIZED`). An unknown value is returned unchanged.
 */
fun tseInitializationStateLabel(
    raw: String,
    language: Language,
): String =
    when (raw) {
        "WORM_INIT_INITIALIZED" -> getString(EnumKeys.tseInitInitialized, language)
        "WORM_INIT_UNINITIALIZED" -> getString(EnumKeys.tseInitUninitialized, language)
        "WORM_INIT_DECOMMISSIONED" -> getString(EnumKeys.tseInitDecommissioned, language)
        else -> raw
    }
