package org.example.project.admin.features.promotion.presentation

/**
 * The wire carries an ISO datetime (`PromotionServiceImpl` calls `LocalDateTime.parse`), the form
 * edits a bare date. These four helpers are the only place that gap is bridged.
 */
private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

internal fun isIsoDate(value: String): Boolean = ISO_DATE.matches(value)

internal fun datePartOf(isoDateTime: String): String = isoDateTime.take(10)

internal fun startOfDay(date: String): String = "${date}T00:00:00"

internal fun endOfDay(date: String): String = "${date}T23:59:59"
