package org.example.project.core.utils

import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/**
 * A human-readable file size — `"512 B"`, `"1.5 KB"`, `"2 GB"`.
 *
 * Multiplatform on purpose: this replaced three copies, two in composeApp (`java.text.DecimalFormat`
 * plus `java.lang.Math`, both JVM-only) and one in adminApp that used integer division and so
 * rendered 1536 bytes as `"1 KB"` where the others said `"1.5 KB"`.
 *
 * No thousands separator: the previous `DecimalFormat("#,##0.#")` would group only past 1024 TB, and
 * a locale-dependent separator is not worth a locale dependency here.
 */
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"

    val units = listOf("B", "KB", "MB", "GB", "TB")
    val group = min((log10(bytes.toDouble()) / log10(1024.0)).toInt(), units.lastIndex)
    val scaled = bytes / 1024.0.pow(group)
    val rounded = round(scaled * 10) / 10

    val number =
        if (rounded % 1.0 == 0.0) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    return "$number ${units[group]}"
}
