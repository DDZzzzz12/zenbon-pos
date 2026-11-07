package org.example.project.features.issuereport.data.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.RandomAccessFile

/**
 * The last [maxLines] WARN/ERROR lines of the current `application.log`, each capped at
 * [maxLineChars].
 *
 * `application.log` is written by logback's `LogstashEncoder`: one JSON object per line with a
 * top-level `"level"` field. A line is kept only when it parses as a JSON object whose `level` is
 * `WARN` or `ERROR` — a substring match would also keep an INFO line whose *message* mentions
 * "ERROR", and INFO is where routine lines such as login attempts (with usernames) live. Lines that
 * do not parse (the partial first line of the read window, a non-JSON line) are dropped.
 *
 * The level is decided on the whole line; the [maxLineChars] cut happens afterwards, because the
 * encoder writes `message` before `level` and a cut-first filter would lose the field it needs.
 *
 * Reads only the last [scanBytes] of the file (seeks back from the end), so a multi-gigabyte log
 * costs a fixed amount of memory. Returns null when there is no readable log or no WARN/ERROR line
 * in that window.
 */
class LogTail(
    private val logDir: () -> File,
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxLineChars: Int = DEFAULT_MAX_LINE_CHARS,
    private val scanBytes: Long = DEFAULT_SCAN_BYTES,
) {
    fun read(): String? {
        val file = File(logDir(), "application.log")
        if (!file.isFile || !file.canRead()) return null
        val lines =
            readWindow(file)
                .lineSequence()
                .filter { isWarnOrError(it) }
                .toList()
                .takeLast(maxLines)
        return lines.joinToString("\n") { it.take(maxLineChars) }.ifEmpty { null }
    }

    private fun readWindow(file: File): String =
        RandomAccessFile(file, "r").use { raf ->
            val start = maxOf(0L, raf.length() - scanBytes)
            raf.seek(start)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.readFully(bytes)
            val decoded = String(bytes, Charsets.UTF_8)
            // A seek into the middle of the file lands mid-line (possibly mid-character): drop that
            // partial first line. Without a newline the whole window is one partial line.
            if (start > 0L) decoded.substringAfter('\n', missingDelimiterValue = "") else decoded
        }

    private fun isWarnOrError(line: String): Boolean {
        if (line.isBlank()) return false
        val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: return false
        val level = (obj["level"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        return level in KEPT_LEVELS
    }

    companion object {
        const val DEFAULT_MAX_LINES = 50
        const val DEFAULT_MAX_LINE_CHARS = 500

        /** How much of the file's end is searched; WARN/ERROR lines are sparse among INFO lines. */
        const val DEFAULT_SCAN_BYTES = 4L * 1024 * 1024

        private val KEPT_LEVELS = setOf("WARN", "ERROR")
        private val json = Json { ignoreUnknownKeys = true }

        /** Production wiring: `LOG_DIR` is set by `LogDirectoryInitializer` before logback loads. */
        fun default(): LogTail = LogTail(logDir = { File(System.getProperty("LOG_DIR") ?: "logs") })
    }
}
