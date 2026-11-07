/**
 * Time and date formatting utilities for the application.
 *
 * Provides language-aware formatting for LocalDateTime and LocalDate.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.utils

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atTime
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.enums.setup.Language

/**
 * An ISO local date-time as a date and minute: `27.09.2026, 10:05` (German), `27 Sep 2026, 10:05`
 * (English), `2026年09月27日 10:05` (Chinese). Unparseable input is returned unchanged.
 *
 * Pure string assembly. It used a `byUnicodePattern` whose English `MMM` month name the
 * kotlinx-datetime builder rejects as locale-dependent, so every English call threw.
 */
fun String.formatLocalDateTime(language: Language): String {
    val dateTime = runCatching { LocalDateTime.parse(this) }.getOrNull() ?: return this
    val time = formatHourMinute(dateTime.hour, dateTime.minute)
    return when (language) {
        Language.GERMAN -> "${dateTime.date.toString().formatShortDate(language)}, $time"
        Language.ENGLISH -> {
            val day = dateTime.day.toString().padStart(2, '0')
            "$day ${englishMonthAbbreviation(dateTime.month.number)} ${dateTime.year}, $time"
        }
        Language.CHINESE -> "${dateTime.date.toString().formatShortDate(language)} $time"
    }
}

private val ENGLISH_MONTH_ABBREVIATIONS =
    listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** `1` → `Jan`; [monthNumber] is 1-based, as `Month.number` is. */
private fun englishMonthAbbreviation(monthNumber: Int): String = ENGLISH_MONTH_ABBREVIATIONS[monthNumber - 1]

fun String.formatReadableDateTime(
    language: Language,
    withTime: Boolean = true,
    withSeconds: Boolean = false,
    showTime: Boolean = true,
): String {
    val dateTime = if (withTime) LocalDateTime.parse(this) else LocalDate.parse(this).atTime(0, 0)

    val day = dateTime.day
    val year = dateTime.year
    val hour = dateTime.hour.toString().padStart(2, '0')
    val minute = dateTime.minute.toString().padStart(2, '0')
    val second = dateTime.second.toString().padStart(2, '0')

    val monthName =
        when (language) {
            Language.GERMAN ->
                listOf(
                    "Jan",
                    "Feb",
                    "Mär",
                    "Apr",
                    "Mai",
                    "Jun",
                    "Jul",
                    "Aug",
                    "Sep",
                    "Okt",
                    "Nov",
                    "Dez",
                )[dateTime.month.number - 1]
            Language.ENGLISH -> englishMonthAbbreviation(dateTime.month.number)
            Language.CHINESE -> "${dateTime.month.number}月"
        }

    return when (language) {
        Language.GERMAN, Language.ENGLISH ->
            "$day $monthName $year" + if (withTime && showTime) ", $hour:$minute" else "" + if (withSeconds) ", $second" else ""
        Language.CHINESE ->
            "${year}年${dateTime.month.number}月${day}日 " +
                if (withTime && showTime) "$hour:$minute" else "" + if (withSeconds) ", $second" else ""
    }
}

/**
 * Parses an ISO-8601 Instant string (e.g. `2026-06-15T14:30:00Z`), converts it to the
 * device's local time zone, and formats it as `dd.MM.yyyy HH:mm`.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
fun String.formatInstantDateTimeShort(): String {
    val local =
        kotlin.time.Instant
            .parse(this)
            .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
    val day = local.day.toString().padStart(2, '0')
    val month =
        local.month.number
            .toString()
            .padStart(2, '0')
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "$day.$month.${local.year} $hour:$minute"
}

/**
 * Formats a server-side ISO LocalDateTime string (no zone, no offset — already
 * in the shop's local time) as `dd.MM.yyyy HH:mm`. Use this for fields the
 * server sends as naked `LocalDateTime.toString()` — Z-Bon times, gap times,
 * Kassenbuch entries, etc.
 */
fun String.formatLocalDateTimeShort(): String {
    val local = kotlinx.datetime.LocalDateTime.parse(this)
    val day = local.day.toString().padStart(2, '0')
    val month =
        local.month.number
            .toString()
            .padStart(2, '0')
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "$day.$month.${local.year} $hour:$minute"
}

fun String.formatTimeHHmmss(): String =
    try {
        val dt = LocalDateTime.parse(this)
        "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}:${dt.second.toString().padStart(2, '0')}"
    } catch (_: Exception) {
        takeLast(8)
    }

fun String.formatShortDate(language: Language): String {
    val dateStr = if (contains("T")) take(10) else this
    return try {
        val date = LocalDate.parse(dateStr)
        val day = date.day.toString().padStart(2, '0')
        val month =
            date.month.number
                .toString()
                .padStart(2, '0')
        val year = date.year
        when (language) {
            Language.GERMAN, Language.ENGLISH -> "$day.$month.$year"
            Language.CHINESE -> "${year}年${month}月${day}日"
        }
    } catch (_: Exception) {
        dateStr
    }
}

private val DOTTED_DATE = Regex("""^(\d{1,2})\.(\d{1,2})\.(\d{4})$""")
private val ISO_LIKE_DATE = Regex("""^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})$""")
private val CHINESE_DATE = Regex("""^(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日?$""")

/**
 * The inverse of [formatShortDate]: a typed date as a [LocalDate], or null when it is not one.
 *
 * Accepts what [formatShortDate] prints in every language (`01.03.2026`, `2026年03月01日`) with or
 * without zero padding, plus a pasted ISO date (`2026-03-01`, also `2026/03/01`). Deliberately not
 * language-bound: an operator pasting a German date into the Chinese UI still means that date, and
 * the two shapes cannot be confused (the year leads in one and trails in the other). A two-digit year
 * or an impossible day (`31.02.2026`) is null rather than guessed.
 */
fun parseShortDate(text: String): LocalDate? {
    val trimmed = text.trim()
    val (year, month, day) =
        DOTTED_DATE.matchEntire(trimmed)?.destructured?.let { (d, m, y) -> Triple(y, m, d) }
            ?: ISO_LIKE_DATE.matchEntire(trimmed)?.destructured?.let { (y, m, d) -> Triple(y, m, d) }
            ?: CHINESE_DATE.matchEntire(trimmed)?.destructured?.let { (y, m, d) -> Triple(y, m, d) }
            ?: return null
    return runCatching { LocalDate(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()
}

/**
 * What a date field hands its form for the text the operator typed: blank stays blank, a date in any
 * shape [parseShortDate] accepts becomes the ISO wire value (`2026-03-01`), and anything else is passed
 * through trimmed — so the form's own "is this an ISO date?" check sees the bad input and refuses it,
 * instead of quietly keeping the last good date while the field shows something else.
 */
fun dateWireValue(typed: String): String {
    if (typed.isBlank()) return ""
    return parseShortDate(typed)?.toString() ?: typed.trim()
}

/**
 * The text a date field shows for its form value: an ISO date (a date-time is cut to its date) in the
 * short date of [language] (`01.03.2026`, `2026年03月01日`); anything else — blank, or the bad text the
 * field passed through — exactly as it is, so nothing is rewritten under the operator's cursor.
 */
fun dateInputText(
    value: String,
    language: Language,
): String {
    val iso = value.trim().substringBefore('T')
    val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return value
    return date.toString().formatShortDate(language)
}

/** True when [value] is blank or an ISO `yyyy-MM-dd` date: what an optional date field may hold at save. */
fun isBlankOrIsoDate(value: String): Boolean = value.isBlank() || runCatching { LocalDate.parse(value.trim()) }.isSuccess

/**
 * An ISO year-month (`2026-03`), or a full ISO date or date-time, as month and year: `03.2026`
 * (German, English) or `2026年03月` (Chinese). Unparseable input is returned unchanged.
 */
fun String.formatYearMonth(language: Language): String {
    val match = Regex("""^(\d{4})-(\d{2})(?:$|[-T].*)""").find(this) ?: return this
    val (year, month) = match.destructured
    if (month.toInt() !in 1..12) return this
    return when (language) {
        Language.GERMAN, Language.ENGLISH -> "$month.$year"
        Language.CHINESE -> "${year}年${month}月"
    }
}

/**
 * A server-sent ISO date range (`2026-10-01 to 2026-10-02`, or `2026-10-01..2026-10-02`) as two
 * [formatShortDate]s joined by an en dash: `01.10.2026 – 02.10.2026`, `2026年10月01日 – 2026年10月02日`.
 *
 * The DSFinV-K validation result carries its range as one English-joined string; showing it raw put
 * an English "to" and ISO dates into a German screen. Input that is not exactly two parseable dates
 * is returned unchanged, so a caller never shows less than the server sent.
 */
fun String.formatIsoDateRange(language: Language): String {
    val parts = split(" to ", "..").map { it.trim() }
    if (parts.size != 2) return this
    val dates = parts.map { runCatching { LocalDate.parse(it.substringBefore('T')) }.getOrNull() ?: return this }
    return "${dates[0].toString().formatShortDate(language)} – ${dates[1].toString().formatShortDate(language)}"
}

/**
 * The date-and-time sibling of [formatShortDate]: an ISO local date-time
 * (`2026-09-26T23:56:11.694604`) as `26.09.2026 23:56`, or `2026年09月26日 23:56` in Chinese.
 *
 * A date-only input renders as [formatShortDate] would, and an unparseable one is returned
 * unchanged, so a caller never shows less than the server sent. Pure string assembly — no
 * `String.format`, no `byUnicodePattern` (whose `MMM` month names throw on every platform) — so it
 * is safe on wasm.
 */
fun String.formatShortDateTime(language: Language): String {
    if (!contains('T')) return formatShortDate(language)
    val dateTime = runCatching { LocalDateTime.parse(this) }.getOrNull() ?: return this
    val time = formatHourMinute(dateTime.hour, dateTime.minute)
    return "${dateTime.date.toString().formatShortDate(language)} $time"
}

/**
 * [formatShortDateTime] for an epoch-millisecond timestamp (scheduler last/next run): converted to
 * [timeZone] first, so it reads `02.10.2026 17:59` like every other date-time on screen rather than
 * the ISO `2026-10-02 17:59` the scheduler cards printed.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
fun Long.formatEpochMillisShortDateTime(
    language: Language,
    timeZone: kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.currentSystemDefault(),
): String =
    kotlin.time.Instant
        .fromEpochMilliseconds(this)
        .toLocalDateTime(timeZone)
        .toString()
        .formatShortDateTime(language)

/**
 * [formatShortDateTime] for an ISO-8601 **instant** (`2026-09-27T01:00:42.688084035Z`), converted to
 * [timeZone]. Input that is not an instant (a local ISO date-time some older server builds wrote)
 * falls through to [formatShortDateTime], which in turn returns unparseable input unchanged.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
fun String.formatInstantShortDateTime(
    language: Language,
    timeZone: kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.currentSystemDefault(),
): String {
    val instant = runCatching { kotlin.time.Instant.parse(this) }.getOrNull() ?: return formatShortDateTime(language)
    return instant.toLocalDateTime(timeZone).toString().formatShortDateTime(language)
}

/**
 * [formatInstantShortDateTime] keeping the **seconds**: `03.10.2026 12:01:38`. For TSE process
 * start/end, where the second is part of the fiscal record (admin20 NOTE-D: the bill panel's TSE block
 * showed the raw `2026-10-03T10:01:38.000Z`). This is **local** time; the printed receipt shows the same
 * instants in UTC, so the screen also shows the raw UTC value next to it.
 * Unparseable input is returned unchanged.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
fun String.formatInstantShortDateTimeSeconds(
    language: Language,
    timeZone: kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.currentSystemDefault(),
): String {
    val local =
        runCatching {
            kotlin.time.Instant
                .parse(this)
                .toLocalDateTime(timeZone)
        }.getOrNull()
            ?: runCatching { LocalDateTime.parse(this) }.getOrNull()
            ?: return this
    val seconds = local.second.toString().padStart(2, '0')
    return "${local.date.toString().formatShortDate(language)} ${formatHourMinute(local.hour, local.minute)}:$seconds"
}

/**
 * Formats a wall-clock hour/minute pair as zero-padded `HH:mm`.
 *
 * This is a data string, not display text — reservation time slots are stored and compared in this
 * form — so it carries no language parameter.
 */
fun formatHourMinute(
    hour: Int,
    minute: Int,
): String = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
