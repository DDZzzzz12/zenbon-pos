package org.example.project.admin.features.update.presentation

import org.example.project.core.domain.dto.update.UpdateDto

private const val BYTES_PER_MB = 1024L * 1024L
private const val BYTES_PER_KB = 1024L

/**
 * `4.2 MB` / `812 KB` / `0 B`, one decimal for megabytes.
 *
 * Hand-rolled because `String.format` does not exist on wasm; integer tenths cannot suffer the
 * float rounding a `Double` division would add to a number the operator reads as exact.
 */
fun formatDownloadSize(bytes: Long): String {
    val value = bytes.coerceAtLeast(0)
    return when {
        value >= BYTES_PER_MB -> {
            val tenths = value * 10 / BYTES_PER_MB
            "${tenths / 10}.${tenths % 10} MB"
        }
        value >= BYTES_PER_KB -> "${value / BYTES_PER_KB} KB"
        else -> "$value B"
    }
}

/** Whole percent, 0..100, or null when the total is unknown -- the bar is then indeterminate. */
fun downloadPercent(progress: UpdateDto.DownloadProgressDto): Int? {
    val total = progress.bytesTotal?.takeIf { it > 0 } ?: return null
    return (progress.bytesDone.coerceIn(0, total) * 100 / total).toInt()
}
