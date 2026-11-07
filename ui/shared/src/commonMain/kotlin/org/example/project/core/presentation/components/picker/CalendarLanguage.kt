package org.example.project.core.presentation.components.picker

import androidx.compose.material3.CalendarLocale
import org.example.project.core.domain.enums.setup.Language

/**
 * The BCP 47 tag the calendar is formatted in: the **UI language**, never the browser's or device's
 * locale. Material's pickers otherwise take month names, weekday letters and the month/year header from
 * the platform locale, so a German back office opened in a Chinese browser showed `十月` above German
 * fields (UI-P1-08 follow-up).
 *
 * The region is Germany for every language: the shop is German, and where the platform derives the
 * first day of the week from the given locale (JVM, Android) the region decides it, so an English or
 * Chinese UI keeps Monday-first weeks instead of the US/CN Sunday-first week. In the browser Material
 * takes the first weekday from the browser locale regardless; only the captions follow this tag there.
 */
fun calendarLanguageTag(language: Language): String =
    when (language) {
        Language.GERMAN -> "de-DE"
        Language.ENGLISH -> "en-DE"
        Language.CHINESE -> "zh-DE"
    }

/** The platform's [CalendarLocale] for [languageTag]: `java.util.Locale` on JVM/Android, Compose's `Locale` on wasm. */
expect fun calendarLocaleOf(languageTag: String): CalendarLocale

/** The [CalendarLocale] for the UI [language]; pass it to `DatePickerState(locale = ...)`. */
fun calendarLocaleFor(language: Language): CalendarLocale = calendarLocaleOf(calendarLanguageTag(language))
