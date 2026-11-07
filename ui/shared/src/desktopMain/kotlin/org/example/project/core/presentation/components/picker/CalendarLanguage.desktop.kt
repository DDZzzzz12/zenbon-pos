package org.example.project.core.presentation.components.picker

import androidx.compose.material3.CalendarLocale
import java.util.Locale

actual fun calendarLocaleOf(languageTag: String): CalendarLocale = Locale.forLanguageTag(languageTag)
