package org.example.project.core.utils

/**
 * Converts a balance from `GET /api/v1/kassenbuch/balance` into the locale-neutral decimal string
 * the money layer parses.
 *
 * The endpoint now answers a canonical decimal (`"1234.56"`) like every other money field. Older
 * servers answered the German **display text** instead — `formatGerman(balance)`, i.e.
 * `"1.234,56"`: dot for grouping, comma for the decimal point, always two fraction digits — which
 * `Decimal.parse`, a strict ASCII `[+-]?digits[.digits]` parser, rejects (admin16 NOTE-5). A client
 * may still meet such a server, so it normalizes both shapes here.
 *
 * The two shapes cannot be confused: the German form always carries a comma, the canonical one
 * never does. A string without a comma is returned unchanged (trimmed).
 */
fun normalizeServerFormattedAmount(raw: String): String {
    val trimmed = raw.trim()
    if (!trimmed.contains(',')) return trimmed
    return trimmed.replace(".", "").replace(',', '.')
}
