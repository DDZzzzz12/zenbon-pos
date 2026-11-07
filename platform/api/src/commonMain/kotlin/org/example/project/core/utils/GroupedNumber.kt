package org.example.project.core.utils

import org.example.project.core.domain.enums.setup.Language
import kotlin.math.abs

/** An integer with thousands separators: `20.000.000` (German), `20,000,000` otherwise. */
fun Long.formatGrouped(language: Language): String {
    val separator = if (language == Language.GERMAN) "." else ","
    val grouped =
        abs(this)
            .toString()
            .reversed()
            .chunked(3)
            .joinToString(separator)
            .reversed()
    return if (this < 0) "-$grouped" else grouped
}
