package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * The on-screen name of a VAT class code (`STANDARD_19`, `REDUCED_7`, `ZERO`). The server sends
 * the code as a free string, so an unknown (operator-created) class is returned unchanged.
 */
fun vatClassLabel(
    raw: String,
    language: Language,
): String =
    when (raw) {
        "STANDARD_19" -> getString(EnumKeys.vatClassStandard, language)
        "REDUCED_7" -> getString(EnumKeys.vatClassReduced, language)
        "ZERO" -> getString(EnumKeys.vatClassZero, language)
        else -> raw
    }
