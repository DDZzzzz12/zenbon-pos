package org.example.project.admin.core.presentation.format

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.utils.formatShortDate

/**
 * An ISO date or date-time from the server (`2026-03-01`, `2026-03-01T00:00`) as the operator's short
 * date — `01.03.2026`, or `2026年03月01日` in Chinese. A null or blank value is an empty string, so a
 * missing date stays a blank cell rather than the word "null".
 *
 * Display only: anything that compares or sends a date keeps the ISO string.
 */
fun String?.asDisplayDay(language: Language): String = this?.takeIf { it.isNotBlank() }?.formatShortDate(language).orEmpty()
