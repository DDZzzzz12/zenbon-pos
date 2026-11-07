package org.example.project.core.presentation.components.picker

import androidx.compose.material3.CalendarLocale
import androidx.compose.ui.text.intl.Locale

actual fun calendarLocaleOf(languageTag: String): CalendarLocale = Locale(languageTag)
