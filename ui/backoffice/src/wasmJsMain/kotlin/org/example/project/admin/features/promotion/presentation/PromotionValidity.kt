package org.example.project.admin.features.promotion.presentation

/**
 * The validity line of a promotion row: "01.11.2026 – 30.11.2026", or [unlimited] ("unbefristet")
 * when the promotion has no end. Takes already formatted days, so it stays free of language lookups.
 */
fun promotionValidityText(
    fromDay: String,
    untilDay: String?,
    unlimited: String,
): String = if (untilDay.isNullOrBlank()) unlimited else "$fromDay – $untilDay"
